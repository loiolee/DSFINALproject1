package com.example.surveysystem.service;

import com.example.surveysystem.domain.LibraryQuestion;
import com.example.surveysystem.domain.QuestionVersion;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.domain.User;
import com.example.surveysystem.dto.LibraryQuestionDetail;
import com.example.surveysystem.dto.LibraryQuestionSummary;
import com.example.surveysystem.dto.QuestionContentRequest;
import com.example.surveysystem.dto.QuestionUsageView;
import com.example.surveysystem.dto.QuestionVersionDiff;
import com.example.surveysystem.dto.QuestionVersionView;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.LibraryQuestionRepository;
import com.example.surveysystem.repository.QuestionVersionRepository;
import com.example.surveysystem.repository.SurveyRepository;
import com.example.surveysystem.repository.UserRepository;
import com.example.surveysystem.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库：题目身份与版本的读写。
 *
 * <p>核心规则只有一条——<b>版本只增不改</b>。任何内容修改都新增一条版本记录并把题目的
 * {@code currentVersionId} 指向新版本，旧版本永久保留。因此「修改题目不影响已发布问卷」
 * 不需要任何同步逻辑：旧问卷引用的是旧版本，内容天然不变。</p>
 *
 * <p>与问卷链路的边界：本服务只读写 {@code questions} / {@code question_versions}，
 * 不改动任何 {@code surveys} 文档。把题目选进问卷、换绑版本属于问卷侧的操作。</p>
 */
@Service
@RequiredArgsConstructor
public class QuestionLibraryService {
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final Set<String> LIST_STATUS_VALUES = Set.of("active", "archived", "all");

    private final LibraryQuestionRepository questionRepository;
    private final QuestionVersionRepository versionRepository;
    private final SurveyRepository surveyRepository;
    private final UserRepository userRepository;
    private final QuestionContentValidator contentValidator;
    private final QuestionContentMapper contentMapper;
    private final QuestionVersionComparator versionComparator;
    private final QuestionAccessService accessService;
    private final IdGenerator idGenerator;

    /** 新建题目：同时写入 v1，并把题目身份的当前版本指针指向它。 */
    public LibraryQuestionDetail create(String ownerId, QuestionContentRequest request) {
        QuestionContent content = readContent(request, true);
        LibraryQuestion question = createQuestion(ownerId, content, request.getChangeNote(), null, null);
        return toDetail(question, ownerId);
    }

    /**
     * 我的题库列表。
     *
     * @param status 留空默认只看 active；传 all 看全部；传 archived 只看归档
     */
    public List<LibraryQuestionSummary> listMine(String ownerId, String status, String keyword) {
        String normalized = normalizeStatus(status);
        List<LibraryQuestion> questions = "all".equals(normalized)
                ? questionRepository.findByOwnerIdOrderByUpdatedAtDesc(ownerId)
                : questionRepository.findByOwnerIdAndStatusOrderByUpdatedAtDesc(ownerId, normalized);

        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (kw.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException("关键词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        // 只在本人题库内过滤：数据量是人工规模，内存过滤比在 Mongo 里拼正则简单，
        // 也省掉了关键词中正则元字符的转义问题。
        return questions.stream()
                .filter(item -> kw.isEmpty()
                        || (item.getTitle() != null && item.getTitle().toLowerCase(Locale.ROOT).contains(kw)))
                .map(this::toSummary)
                .toList();
    }

    /** 题目详情。创建者与被授权者都能看：被授权者需要看到版本历史才能选题。 */
    public LibraryQuestionDetail detail(String questionId, String ownerId) {
        return toDetail(accessService.requireReadable(questionId, ownerId), ownerId);
    }

    /** 修改题目：内容不改写旧版本，而是追加一个新版本。 */
    public LibraryQuestionDetail update(String questionId, String operatorId, QuestionContentRequest request) {
        // 创建者或持有 edit 权限的被授权者都可以改；改动只是追加版本，不会影响任何已绑定的问卷。
        LibraryQuestion question = accessService.requireEditable(questionId, operatorId);
        if (!question.isSelectable()) {
            throw BusinessException.conflict("已归档的题目不能再修改，请先解除归档");
        }
        QuestionVersion current = requireCurrentVersion(question);
        // required 留空时沿用当前版本，避免客户端漏传把必答性静默改掉。
        QuestionContent content = readContent(request, current.isRequired());
        appendVersion(question, content, request.getChangeNote(), operatorId, null);
        return toDetail(question, operatorId);
    }

    /**
     * 归档。不做物理删除：历史问卷引用的是快照，已经发出的共享也仍然有效，
     * 物理删除会让「这道题被哪些问卷用过」这类追溯彻底断掉。
     */
    public void archive(String questionId, String ownerId) {
        // 归档是题目的生命周期操作，只归创建者：被授权者归档会让创建者自己的题库少一道题。
        LibraryQuestion question = accessService.requireOwned(questionId, ownerId, "只有题目创建者可以归档题目");
        if (LibraryQuestion.STATUS_ARCHIVED.equals(question.getStatus())) return;
        question.setStatus(LibraryQuestion.STATUS_ARCHIVED);
        question.setUpdatedAt(Instant.now());
        questionRepository.save(question);
    }

    /** 版本历史，最新在前。 */
    public List<QuestionVersionView> versions(String questionId, String ownerId) {
        accessService.requireReadable(questionId, ownerId);
        return versionRepository.findByQuestionIdOrderByVersionNoDesc(questionId).stream()
                .map(this::toVersionView)
                .toList();
    }

    public QuestionVersionView version(String questionId, int versionNo, String ownerId) {
        accessService.requireReadable(questionId, ownerId);
        return toVersionView(versionRepository.findByQuestionIdAndVersionNo(questionId, versionNo)
                .orElseThrow(() -> BusinessException.notFound("指定版本不存在: v" + versionNo)));
    }

    /**
     * 对比两个版本的内容差异（需求「记录题目的修改历史」）。
     *
     * <p>两个版本都必须属于该题目：只校验题目归属还不够，否则传两个不同题目的版本号
     * 会得到一份「题型、题干、选项全变了」的差异，看起来像一次大改版，实际是拿错题在比。</p>
     */
    public QuestionVersionDiff compare(String questionId, int fromVersionNo, int toVersionNo, String ownerId) {
        accessService.requireReadable(questionId, ownerId);
        QuestionVersion from = versionRepository.findByQuestionIdAndVersionNo(questionId, fromVersionNo)
                .orElseThrow(() -> BusinessException.notFound("指定版本不存在: v" + fromVersionNo));
        QuestionVersion to = versionRepository.findByQuestionIdAndVersionNo(questionId, toVersionNo)
                .orElseThrow(() -> BusinessException.notFound("指定版本不存在: v" + toVersionNo));
        return versionComparator.compare(from, to);
    }

    /**
     * 这道题被哪些问卷使用（需求「查看某个题被哪些问卷使用」）。
     *
     * <p>只有题目创建者能看。问卷可能属于别人，被授权者不应该知道「我的题被谁拿去用了」，
     * 这与跨问卷统计的权限边界一致（见 schema.md §10.4）。</p>
     *
     * <p>按问卷去重：同一份问卷里重复引用这道题时只出现一行，重复次数由
     * {@code repeatCount} 说明。若同一份问卷里的多个实例绑了不同版本，
     * {@code mixedVersions} 为 true，此时 {@code boundVersionNo} 取最小值。</p>
     */
    public List<QuestionUsageView> usages(String questionId, String ownerId) {
        LibraryQuestion question = accessService.requireOwned(questionId, ownerId, "只有题目创建者可以查看使用情况");
        List<Survey> surveys = surveyRepository.findByLibraryQuestionId(questionId);
        if (surveys.isEmpty()) {
            return List.of();
        }
        Set<String> ownerIds = surveys.stream()
                .map(Survey::getOwnerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> usernames = new HashMap<>();
        for (User user : userRepository.findAllById(ownerIds)) {
            usernames.put(user.getId(), user.getUsername());
        }
        Integer latest = question.getCurrentVersionNo();
        return surveys.stream()
                .map(survey -> toUsage(survey, questionId, latest, usernames))
                .sorted(Comparator.comparing(QuestionUsageView::getSurveyUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private QuestionUsageView toUsage(Survey survey, String questionId, Integer latest,
                                      Map<String, String> usernames) {
        List<Survey.Question> matched = survey.getQuestions() == null ? List.of()
                : survey.getQuestions().stream()
                        .filter(item -> Objects.equals(item.getQuestionId(), questionId))
                        .toList();
        Set<Integer> versions = matched.stream()
                .map(Survey.Question::getVersionNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Integer bound = versions.isEmpty() ? null : Collections.min(versions);
        return QuestionUsageView.builder()
                .surveyId(survey.getId())
                .surveyTitle(survey.getTitle())
                .surveyStatus(survey.getStatus())
                .surveyOwnerId(survey.getOwnerId())
                .surveyOwnerUsername(usernames.get(survey.getOwnerId()))
                .questionInstanceIds(matched.stream().map(Survey.Question::getId).toList())
                .repeatCount(matched.size())
                .boundVersionNo(bound)
                .mixedVersions(versions.size() > 1)
                .latestVersion(latest)
                .outdated(bound != null && latest != null && bound < latest)
                .surveyUpdatedAt(survey.getUpdatedAt())
                .build();
    }

    /**
     * 恢复旧版本。
     *
     * <p>不是把指针拨回去，而是以旧版本内容追加一个新版本：版本号只增不减，
     * 历史保持线性可读，也不会出现「v3 被删掉」这类无法解释的状态。
     * 新版本的 {@code restoredFromVersionId} 记录被恢复的来源。</p>
     */
    public LibraryQuestionDetail restore(String questionId, int versionNo, String operatorId, String changeNote) {
        LibraryQuestion question = accessService.requireEditable(questionId, operatorId);
        if (!question.isSelectable()) {
            throw BusinessException.conflict("已归档的题目不能再恢复版本，请先解除归档");
        }
        QuestionVersion target = versionRepository.findByQuestionIdAndVersionNo(questionId, versionNo)
                .orElseThrow(() -> BusinessException.notFound("指定版本不存在: v" + versionNo));
        QuestionContent content = new QuestionContent(
                target.getType(), target.getTitle(), target.isRequired(),
                contentMapper.copyOptions(target.getOptions()),
                contentMapper.copyConstraints(target.getConstraints()));
        String note = trimToNull(changeNote) == null ? "恢复自 v" + versionNo : changeNote.trim();
        appendVersion(question, content, note, operatorId, target.getId());
        return toDetail(question, operatorId);
    }

    /**
     * 把某份问卷里的一道本地题目存入题库（需求「可以把题加入题库」）。
     *
     * <p>刻意<b>不修改原问卷</b>：导入的语义是「把内容存档到题库」，不是「在原问卷上建立引用」。
     * 已发布的问卷本来就不允许改，若草稿问卷在导入后被自动换绑，用户会在没有预期的情况下
     * 发现自己的题目变成了「引用题库」的状态。需要换绑时应当由用户在问卷编辑里显式操作。</p>
     *
     * <p>因此导入后原问卷的答卷不会进入跨问卷统计——它们对应的仍是本地题目，没有库归属。
     * 这是「不修改已发布问卷」这条硬约束带来的必然结果。</p>
     */
    public LibraryQuestionDetail promoteFromSurvey(String surveyId, String questionInstanceId,
                                                   String ownerId, String changeNote) {
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> BusinessException.notFound("问卷不存在"));
        if (!Objects.equals(survey.getOwnerId(), ownerId)) {
            throw BusinessException.forbidden("无权操作该问卷");
        }
        List<Survey.Question> questions = survey.getQuestions() == null ? List.of() : survey.getQuestions();
        Survey.Question source = questions.stream()
                .filter(item -> Objects.equals(item.getId(), questionInstanceId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("该问卷中不存在这道题目"));
        if (source.isFromLibrary()) {
            throw BusinessException.conflict("该题目已经来自题库，不需要重复导入");
        }
        // 幂等：同一份问卷里的同一道题只允许沉淀一次。
        // 这里不改动原问卷（加入题库不应产生对问卷的副作用），因此「是否已导入」只能靠题目身份上的来源字段判断。
        if (questionRepository
                .findByOwnerIdAndSourceSurveyIdAndSourceQuestionInstanceId(ownerId, surveyId, questionInstanceId)
                .isPresent()) {
            throw BusinessException.conflict("该题目已加入过题库，无需重复导入");
        }
        QuestionContent content = new QuestionContent(
                source.getType(), source.getTitle(), source.isRequired(),
                contentMapper.copyOptions(source.getOptions()),
                contentMapper.copyConstraints(source.getConstraints()));
        // 第一阶段的题目没有经过题库侧的校验，导入时用同一套规则再验一次，
        // 避免把「单选题只有 1 个选项」这类历史脏数据带进题库。
        contentValidator.validate(content.type(), content.title(), content.options(), content.constraints());

        String note = trimToNull(changeNote) == null ? "从问卷导入" : changeNote.trim();
        try {
            return toDetail(createQuestion(ownerId, content, note, surveyId, questionInstanceId), ownerId);
        } catch (DuplicateKeyException e) {
            // 两次点击几乎同时到达时，上面的查重都会通过，最终由 uniq_question_source 唯一索引拦住。
            throw BusinessException.conflict("该题目已加入过题库，无需重复导入");
        }
    }

    /* ==================== 内部实现 ==================== */

    /** 题目内容的中间表示，避免在多个方法间传四个参数。 */
    private record QuestionContent(String type, String title, boolean required,
                                   List<Survey.Option> options, Survey.Constraints constraints) {
    }

    private QuestionContent readContent(QuestionContentRequest request, boolean fallbackRequired) {
        String type = Survey.normalizeType(request.getType());
        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        List<Survey.Option> options = contentMapper.toOptions(request.getOptions());
        Survey.Constraints constraints = contentMapper.toConstraints(request.getConstraints());
        contentValidator.validate(type, title, options, constraints);
        boolean required = request.getRequired() == null ? fallbackRequired : request.getRequired();
        return new QuestionContent(type, title, required, options, constraints);
    }

    private LibraryQuestion createQuestion(String ownerId, QuestionContent content, String changeNote,
                                           String sourceSurveyId, String sourceQuestionInstanceId) {
        Instant now = Instant.now();
        // 两个 ID 都先算好，才能做到「题目身份先落库、版本后落库」。
        String questionId = idGenerator.shortId();
        String versionId = idGenerator.shortId();

        // 先写题目身份：uniq_question_source 唯一索引在这里拦截重复导入。
        // 顺序很关键——如果先写版本，重复导入时版本已经落库、题目却因撞索引失败，
        // 就会留下一条没有任何题目指向的孤儿版本，而题库列表里看不到它，谁也清理不掉。
        LibraryQuestion question = LibraryQuestion.builder()
                .id(questionId)
                .ownerId(ownerId)
                .title(content.title())
                .type(content.type())
                .currentVersionId(versionId)
                .currentVersionNo(1)
                .visibility(LibraryQuestion.VISIBILITY_PRIVATE)
                .status(LibraryQuestion.STATUS_ACTIVE)
                .sourceSurveyId(sourceSurveyId)
                .sourceQuestionInstanceId(sourceQuestionInstanceId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        questionRepository.save(question);

        QuestionVersion version = QuestionVersion.builder()
                .id(versionId)
                .questionId(questionId)
                .versionNo(1)
                .type(content.type())
                .title(content.title())
                .required(content.required())
                .options(content.options())
                .constraints(content.constraints())
                .changeNote(trimToNull(changeNote))
                .createdBy(ownerId)
                .createdAt(now)
                .build();
        try {
            versionRepository.save(version);
        } catch (RuntimeException e) {
            // 补偿：版本写不进去就把题目身份撤回，否则题目会指向一个不存在的版本，
            // 之后每次读详情都在 requireCurrentVersion 里 404，用户只看到「题目坏了」。
            questionRepository.delete(question);
            throw e;
        }
        return question;
    }

    private void appendVersion(LibraryQuestion question, QuestionContent content, String changeNote,
                               String createdBy, String restoredFromVersionId) {
        int nextNo = versionRepository.findFirstByQuestionIdOrderByVersionNoDesc(question.getId())
                .map(latest -> latest.getVersionNo() + 1)
                .orElse(1);
        QuestionVersion version = QuestionVersion.builder()
                .id(idGenerator.shortId())
                .questionId(question.getId())
                .versionNo(nextNo)
                .parentVersionId(question.getCurrentVersionId())
                .restoredFromVersionId(restoredFromVersionId)
                .type(content.type())
                .title(content.title())
                .required(content.required())
                .options(content.options())
                .constraints(content.constraints())
                .changeNote(trimToNull(changeNote))
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();
        try {
            versionRepository.save(version);
        } catch (DuplicateKeyException ex) {
            // 两个并发修改算出同一个下一个版本号时，由 (questionId, versionNo) 唯一索引拦下。
            throw BusinessException.conflict("题目正在被修改，请刷新后重试");
        }

        question.setTitle(version.getTitle());
        question.setType(version.getType());
        question.setCurrentVersionId(version.getId());
        question.setCurrentVersionNo(version.getVersionNo());
        question.setUpdatedAt(Instant.now());
        questionRepository.save(question);
    }

    private QuestionVersion requireCurrentVersion(LibraryQuestion question) {
        return versionRepository.findById(question.getCurrentVersionId())
                .orElseThrow(() -> BusinessException.notFound("题目当前版本不存在"));
    }

    private String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? LibraryQuestion.STATUS_ACTIVE
                : status.trim().toLowerCase(Locale.ROOT);
        if (!LIST_STATUS_VALUES.contains(normalized)) {
            throw new BusinessException("status 只能是 active、archived 或 all");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LibraryQuestionDetail toDetail(LibraryQuestion question, String operatorId) {
        return LibraryQuestionDetail.builder()
                .id(question.getId())
                .ownerId(question.getOwnerId())
                .title(question.getTitle())
                .type(question.getType())
                .currentVersionNo(question.getCurrentVersionNo())
                .visibility(question.getVisibility())
                .status(question.getStatus())
                .accessLevel(accessService.accessLevel(question, operatorId))
                // 版本号从 1 连续递增、版本从不删除，因此「当前版本号」就是版本总数，
                // 不必再对 question_versions 做一次 count。
                .versionCount(question.getCurrentVersionNo() == null ? 0 : question.getCurrentVersionNo())
                .usageCount(surveyRepository.countByLibraryQuestionId(question.getId()))
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .currentVersion(toVersionView(requireCurrentVersion(question)))
                .build();
    }

    private LibraryQuestionSummary toSummary(LibraryQuestion question) {
        return LibraryQuestionSummary.builder()
                .id(question.getId())
                .title(question.getTitle())
                .type(question.getType())
                .currentVersionNo(question.getCurrentVersionNo())
                .visibility(question.getVisibility())
                .status(question.getStatus())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private QuestionVersionView toVersionView(QuestionVersion version) {
        return QuestionVersionView.builder()
                .id(version.getId())
                .questionId(version.getQuestionId())
                .versionNo(version.getVersionNo())
                .parentVersionId(version.getParentVersionId())
                .restoredFromVersionId(version.getRestoredFromVersionId())
                .type(version.getType())
                .title(version.getTitle())
                .required(version.isRequired())
                .options(version.getOptions())
                .constraints(version.getConstraints())
                .changeNote(version.getChangeNote())
                .createdBy(version.getCreatedBy())
                .createdAt(version.getCreatedAt())
                .build();
    }
}
