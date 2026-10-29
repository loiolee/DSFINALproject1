package com.example.surveysystem.service;

import com.example.surveysystem.domain.LibraryQuestion;
import com.example.surveysystem.domain.QuestionVersion;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.ConstraintsInput;
import com.example.surveysystem.dto.CreateSurveyRequest;
import com.example.surveysystem.dto.OptionInput;
import com.example.surveysystem.dto.UpdateSurveyRequest;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.LibraryQuestionRepository;
import com.example.surveysystem.repository.QuestionVersionRepository;
import com.example.surveysystem.repository.SurveyRepository;
import com.example.surveysystem.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SurveyService {
    private static final Set<String> JUMP_OPS = Survey.SUPPORTED_JUMP_OPS;
    /** 与题库侧 {@code QuestionContentRequest.title} 的上限保持一致，两处都写 1000 会漂移。 */
    private static final int MAX_TITLE_LENGTH = 1000;
    /** 只适用于数字题的比较操作符。提取为常量，避免在跳转校验的循环里反复构造 Set。 */
    private static final Set<String> NUMERIC_JUMP_OPS = Set.of("gt", "gte", "lt", "lte");
    private static final Set<String> EQUALITY_JUMP_OPS = Set.of("eq", "neq");

    private final SurveyRepository surveyRepository;
    private final QuestionVersionRepository questionVersionRepository;
    private final LibraryQuestionRepository libraryQuestionRepository;
    private final QuestionContentValidator contentValidator;
    private final QuestionContentMapper contentMapper;
    private final QuestionAccessService accessService;
    private final IdGenerator idGenerator;

    public Survey create(String ownerId, CreateSurveyRequest request) {
        Instant now = Instant.now();
        Survey survey = Survey.builder()
                .ownerId(ownerId)
                .title(request.getTitle().trim())
                .description(request.getDescription())
                .anonymous(Boolean.TRUE.equals(request.getAnonymous()))
                .allowMultiple(Boolean.TRUE.equals(request.getAllowMultiple()))
                .status(Survey.STATUS_DRAFT)
                .deadline(request.getDeadline())
                .createdAt(now)
                .updatedAt(now)
                .questions(mapQuestions(request.getQuestions(), ownerId, Set.of()))
                .build();
        return surveyRepository.save(survey);
    }

    public List<Survey> listMine(String ownerId) {
        List<Survey> surveys = surveyRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        // 列表页也要显示「有题目可用新版本」，否则用户只能逐份点进详情才能发现。
        // 批量回填：所有问卷涉及的题库题目一次查完，不随问卷数量增加查询次数。
        annotateVersionStatus(surveys);
        return surveys;
    }

    public Survey getOwned(String surveyId, String ownerId) {
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> BusinessException.notFound("问卷不存在"));
        ensureOwner(survey, ownerId);
        return survey;
    }

    /**
     * 读取问卷详情并回填「题库是否有更新版本」的提示字段。
     *
     * <p>只在对外读取时回填，不在 {@link #getOwned} 里做：编辑、发布、关闭这些内部流程
     * 不需要这两个提示字段，多查一次题库纯属浪费。</p>
     *
     * <p>一次性按 questionId 批量取题库题目，避免每道题查一次库（N+1）。</p>
     */
    public Survey getOwnedWithVersionStatus(String surveyId, String ownerId) {
        Survey survey = getOwned(surveyId, ownerId);
        annotateVersionStatus(survey);
        return survey;
    }

    /** 回填 {@code latestVersionNo} 与 {@code newerVersionAvailable}；本地题目保持 null。 */
    private void annotateVersionStatus(Survey survey) {
        annotateVersionStatus(List.of(survey));
    }

    /** 批量版本：一次查库覆盖多份问卷，列表页与详情页共用。 */
    private void annotateVersionStatus(List<Survey> surveys) {
        Set<String> libraryQuestionIds = surveys.stream()
                .flatMap(survey -> (survey.getQuestions() == null ? List.<Survey.Question>of() : survey.getQuestions()).stream())
                .filter(Survey.Question::isFromLibrary)
                .map(Survey.Question::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (libraryQuestionIds.isEmpty()) {
            return;
        }
        Map<String, Integer> latestByQuestionId = new HashMap<>();
        for (LibraryQuestion libraryQuestion : libraryQuestionRepository.findAllById(libraryQuestionIds)) {
            latestByQuestionId.put(libraryQuestion.getId(), libraryQuestion.getCurrentVersionNo());
        }
        for (Survey survey : surveys) {
            List<Survey.Question> questions = survey.getQuestions();
            if (questions == null) {
                continue;
            }
            for (Survey.Question question : questions) {
                if (!question.isFromLibrary() || question.getQuestionId() == null) {
                    continue;
                }
                Integer latest = latestByQuestionId.get(question.getQuestionId());
                question.setLatestVersionNo(latest);
                int bound = question.getVersionNo() == null ? 0 : question.getVersionNo();
                question.setNewerVersionAvailable(latest != null && latest > bound);
            }
        }
    }

    /**
     * 把问卷内某道题手动换绑到题库的另一个版本。
     *
     * <p>这是「修改题目不影响已做好的问卷」的配套能力：默认不自动升级，用户看到
     * {@code newerVersionAvailable} 提示后自己决定要不要跟到新版本。</p>
     *
     * <p>三条限制：</p>
     * <ul>
     *   <li>只有草稿可以换绑。已发布问卷换绑会让同一份问卷的新旧答卷基于不同版本的选项，
     *       统计时无法区分，等于自己制造数据污染。</li>
     *   <li>版本必须属于该题。否则会把 A 题的内容塞进 B 题的位置。</li>
     *   <li>新版本不能缺少跳转规则引用的选项。跳转规则按 {@code optionId} 匹配，
     *       选项被删掉后规则永远不命中，用户不会收到任何报错，只会觉得「跳转失效了」。</li>
     * </ul>
     *
     * <p>问卷内的 {@code required}、{@code order}、{@code jumps} 保持不变：它们属于实例配置，
     * 不是题目内容，换版本不应该顺手改掉。</p>
     */
    public Survey rebindQuestionVersion(String surveyId, String questionInstanceId, int versionNo, String ownerId) {
        Survey survey = getOwned(surveyId, ownerId);
        if (!Survey.STATUS_DRAFT.equals(survey.getStatus())) {
            throw BusinessException.conflict("只有草稿状态的问卷可以更换题目版本");
        }
        List<Survey.Question> questions = survey.getQuestions() == null ? List.of() : survey.getQuestions();
        Survey.Question target = questions.stream()
                .filter(item -> Objects.equals(item.getId(), questionInstanceId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("该问卷中不存在这道题目"));
        if (!target.isFromLibrary()) {
            throw BusinessException.conflict("本地题目没有题库版本，无法换绑");
        }

        QuestionVersion version = questionVersionRepository
                .findByQuestionIdAndVersionNo(target.getQuestionId(), versionNo)
                .orElseThrow(() -> BusinessException.notFound("指定版本不存在: v" + versionNo));
        if (!accessService.canRead(target.getQuestionId(), ownerId)) {
            throw BusinessException.forbidden("无权使用该题目: " + target.getQuestionId());
        }

        Set<String> newOptionIds = (version.getOptions() == null ? List.<Survey.Option>of() : version.getOptions())
                .stream().map(Survey.Option::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Survey.JumpRule> jumps = target.getJumps() == null ? List.of() : target.getJumps();
        boolean brokenJump = jumps.stream()
                .map(Survey.JumpRule::getOptionId)
                .filter(Objects::nonNull)
                .anyMatch(optionId -> !newOptionIds.contains(optionId));
        if (brokenJump) {
            throw BusinessException.conflict("v" + versionNo + " 中不存在该题跳转规则引用的选项，请先调整跳转规则再换绑");
        }

        target.setQuestionVersionId(version.getId());
        target.setVersionNo(version.getVersionNo());
        target.setType(version.getType());
        target.setTitle(version.getTitle());
        target.setOptions(contentMapper.copyOptions(version.getOptions()));
        target.setConstraints(contentMapper.copyConstraints(version.getConstraints()));
        survey.setUpdatedAt(Instant.now());
        return surveyRepository.save(survey);
    }

    public Survey updateDraft(String surveyId, String ownerId, UpdateSurveyRequest request) {
        Survey survey = getOwned(surveyId, ownerId);
        if (!Survey.STATUS_DRAFT.equals(survey.getStatus())) {
            throw BusinessException.conflict("问卷发布后题目、选项和跳转规则均被冻结，不能再编辑");
        }
        survey.setTitle(request.getTitle().trim());
        survey.setDescription(request.getDescription());
        survey.setAnonymous(Boolean.TRUE.equals(request.getAnonymous()));
        survey.setAllowMultiple(Boolean.TRUE.equals(request.getAllowMultiple()));
        survey.setDeadline(request.getDeadline());
        // 保存前记下原本已引用的题库题目：允许它们继续保持引用，即使其中有的已被归档。
        Set<String> alreadyReferenced = (survey.getQuestions() == null ? List.<Survey.Question>of() : survey.getQuestions())
                .stream()
                .map(Survey.Question::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        survey.setQuestions(mapQuestions(request.getQuestions(), ownerId, alreadyReferenced));
        survey.setUpdatedAt(Instant.now());
        return surveyRepository.save(survey);
    }

    public Survey publish(String surveyId, String ownerId) {
        Survey survey = getOwned(surveyId, ownerId);
        if (!Survey.STATUS_DRAFT.equals(survey.getStatus())) {
            throw BusinessException.conflict("只有 draft 状态的问卷可以发布");
        }
        validateForPublish(survey);
        if (survey.getDeadline() != null && !survey.getDeadline().isAfter(Instant.now())) {
            throw new BusinessException("截止时间必须晚于当前时间");
        }

        // 随机短码提高可读性，唯一索引处理多实例部署时极低概率的碰撞。
        for (int attempt = 0; attempt < 5; attempt++) {
            String publicId = idGenerator.shortId();
            if (surveyRepository.findByPublicId(publicId).isPresent()) continue;
            survey.setPublicId(publicId);
            survey.setStatus(Survey.STATUS_PUBLISHED);
            survey.setUpdatedAt(Instant.now());
            try {
                return surveyRepository.save(survey);
            } catch (DuplicateKeyException ex) {
                survey.setStatus(Survey.STATUS_DRAFT);
                survey.setPublicId(null);
            }
        }
        throw BusinessException.conflict("生成问卷公开编号失败，请重试");
    }

    public Survey updateDeadline(String surveyId, String ownerId, Instant deadline) {
        Survey survey = getOwned(surveyId, ownerId);
        if (Survey.STATUS_CLOSED.equals(survey.getStatus())) {
            throw BusinessException.conflict("已关闭的问卷不能再修改截止时间");
        }
        if (deadline != null && !deadline.isAfter(Instant.now())) {
            throw new BusinessException("截止时间必须晚于当前时间");
        }
        survey.setDeadline(deadline);
        survey.setUpdatedAt(Instant.now());
        return surveyRepository.save(survey);
    }

    public void deleteDraft(String surveyId, String ownerId) {
        Survey survey = getOwned(surveyId, ownerId);
        if (!Survey.STATUS_DRAFT.equals(survey.getStatus())) {
            throw BusinessException.conflict("只有草稿问卷可以删除");
        }
        surveyRepository.delete(survey);
    }

    public Survey close(String surveyId, String ownerId) {
        Survey survey = getOwned(surveyId, ownerId);
        if (!Survey.STATUS_PUBLISHED.equals(survey.getStatus())) {
            throw BusinessException.conflict("只有已发布问卷可以关闭");
        }
        survey.setStatus(Survey.STATUS_CLOSED);
        survey.setUpdatedAt(Instant.now());
        return surveyRepository.save(survey);
    }

    private void ensureOwner(Survey survey, String ownerId) {
        if (!Objects.equals(survey.getOwnerId(), ownerId)) {
            throw BusinessException.forbidden("无权操作该问卷");
        }
    }

    /**
     * 将请求 DTO 转成内嵌文档。先分配所有题目实例 ID，再转换跳转目标，以支持同一请求内互相引用。
     * 显式给出 ID 的题目/选项会保留该 ID；未提供时由服务端生成短 ID。
     *
     * <p>第二阶段增加了一条分支：</p>
     * <ul>
     *   <li>{@code questionVersionId} 为空 —— 本地题目，内容完全来自请求体，与第一阶段行为一致；</li>
     *   <li>{@code questionVersionId} 有值 —— 引用题库版本，内容从版本快照复制，请求体里的内容字段
     *       只做一致性校验、不参与写入。这就是「引用即只读」的落点：想改内容必须去题库创建新版本。</li>
     * </ul>
     */
    private List<Survey.Question> mapQuestions(List<CreateSurveyRequest.QuestionInput> inputs, String operatorId,
                                              Set<String> alreadyReferenced) {
        if (inputs == null) throw new BusinessException("问卷至少需要一道题目");
        Map<String, String> questionIdMap = new HashMap<>();
        List<String> assignedIds = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (CreateSurveyRequest.QuestionInput input : inputs) {
            String oldId = normalizeId(input.getId());
            String assigned = oldId == null ? idGenerator.shortId() : oldId;
            if (!usedIds.add(assigned)) throw new BusinessException("题目 ID 不能重复: " + assigned);
            assignedIds.add(assigned);
            if (oldId != null) questionIdMap.put(oldId, assigned);
        }

        // 预取所有被引用的版本：一次 findAllById 代替逐题 findById。
        // 问卷有 N 道引用题时，原写法要查 N 次库；这里固定 1 次。
        Set<String> referencedVersionIds = inputs.stream()
                .map(q -> normalizeId(q.getQuestionVersionId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, QuestionVersion> versionCache = referencedVersionIds.isEmpty()
                ? Map.of()
                : questionVersionRepository.findAllById(referencedVersionIds).stream()
                        .collect(Collectors.toMap(QuestionVersion::getId, version -> version));

        List<Survey.Question> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            CreateSurveyRequest.QuestionInput input = inputs.get(i);
            String instanceId = assignedIds.get(i);
            String versionId = normalizeId(input.getQuestionVersionId());

            String type;
            String title;
            List<Survey.Option> options;
            Survey.Constraints constraints;
            // 本地题目的选项 ID 可能由客户端临时指定，需要重映射；题库题目的选项 ID 随版本冻结，直接用。
            Map<String, String> optionIdMap = new HashMap<>();
            String libraryQuestionId = null;
            Integer versionNo = null;
            String sourceType = Survey.QUESTION_SOURCE_LOCAL;
            boolean requiredDefault = true;

            if (versionId == null) {
                List<OptionInput> optionInputs = input.getOptions() == null
                        ? List.of() : input.getOptions();
                options = new ArrayList<>();
                Set<String> usedOptionIds = new HashSet<>();
                for (int j = 0; j < optionInputs.size(); j++) {
                    OptionInput optionInput = optionInputs.get(j);
                    String oldOptionId = normalizeId(optionInput.getId());
                    String assignedOptionId = oldOptionId == null ? idGenerator.shortId() : oldOptionId;
                    if (!usedOptionIds.add(assignedOptionId)) {
                        throw new BusinessException("同一道题内的选项 ID 不能重复: " + assignedOptionId);
                    }
                    if (oldOptionId != null) optionIdMap.put(oldOptionId, assignedOptionId);
                    options.add(Survey.Option.builder()
                            .id(assignedOptionId)
                            .label(optionInput.getLabel() == null ? "" : optionInput.getLabel().trim())
                            .order(optionInput.getOrder() == null ? j + 1 : optionInput.getOrder())
                            .build());
                }
                type = Survey.normalizeType(input.getType());
                title = input.getTitle() == null ? "" : input.getTitle().trim();
                // 本地题目的题型与题干必填。校验放在这里而不是 DTO 注解上，
                // 因为引用题库版本的题目不需要传这两项（内容以版本快照为准）。
                if (type == null || type.isBlank()) {
                    throw new BusinessException("题型不能为空");
                }
                if (title.isBlank()) {
                    throw new BusinessException("题目标题不能为空");
                }
                if (title.length() > MAX_TITLE_LENGTH) {
                    throw new BusinessException("题目标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
                }
                constraints = contentMapper.toConstraints(input.getConstraints());
            } else {
                QuestionVersion version = versionCache.get(versionId);
                if (version == null) {
                    throw new BusinessException("题目版本不存在: " + versionId);
                }
                // 引用别人的题目必须先被授权：否则知道题目 ID 就能白拿内容，共享机制形同虚设。
                // 这里取回题目身份而不是只判 canRead，是为了同时拿到归档状态。
                LibraryQuestion libraryQuestion = accessService.requireReadable(
                        version.getQuestionId(), operatorId, "无权使用该题目: " + version.getQuestionId());
                // 归档的题目不能再被选入新问卷（LibraryQuestion.isSelectable 的语义）。
                // 例外：本问卷原本就引用了这道题，允许原样保存，否则题目一旦归档，
                // 所有引用它的旧草稿都会因为「保存」这个动作直接报错，用户被迫删题。
                if (!libraryQuestion.isSelectable() && !alreadyReferenced.contains(version.getQuestionId())) {
                    throw new BusinessException("题目已归档，不能选入新问卷: " + libraryQuestion.getTitle());
                }
                String declaredQuestionId = normalizeId(input.getQuestionId());
                if (declaredQuestionId != null && !declaredQuestionId.equals(version.getQuestionId())) {
                    throw new BusinessException("题目版本与题目身份不匹配: " + versionId);
                }
                if (input.getVersionNo() != null && !input.getVersionNo().equals(version.getVersionNo())) {
                    throw new BusinessException("题目版本号与版本不一致: " + versionId);
                }
                rejectLocalContentOverride(input, version);
                type = version.getType();
                title = version.getTitle();
                options = contentMapper.copyOptions(version.getOptions());
                constraints = contentMapper.copyConstraints(version.getConstraints());
                requiredDefault = version.isRequired();
                libraryQuestionId = version.getQuestionId();
                versionNo = version.getVersionNo();
                sourceType = Survey.QUESTION_SOURCE_LIBRARY;
            }

            List<Survey.JumpRule> jumps = new ArrayList<>();
            if (input.getJumps() != null) {
                for (CreateSurveyRequest.JumpInput jump : input.getJumps()) {
                    String target = Survey.END.equalsIgnoreCase(jump.getTargetQuestionId())
                            ? Survey.END
                            : questionIdMap.getOrDefault(jump.getTargetQuestionId(), jump.getTargetQuestionId());
                    String optionId = jump.getOptionId() == null ? null
                            : optionIdMap.getOrDefault(jump.getOptionId(), jump.getOptionId());
                    jumps.add(Survey.JumpRule.builder()
                            .op(jump.getOp() == null ? null : jump.getOp().trim().toLowerCase(Locale.ROOT))
                            .optionId(optionId).value(jump.getValue()).targetQuestionId(target).build());
                }
            }

            boolean required = input.getRequired() == null ? requiredDefault : input.getRequired();
            result.add(Survey.Question.builder()
                    .id(instanceId)
                    .order(input.getOrder() == null ? i + 1 : input.getOrder())
                    .required(required)
                    .questionId(libraryQuestionId)
                    .questionVersionId(versionId)
                    .versionNo(versionNo)
                    .sourceType(sourceType)
                    .type(type)
                    .title(title)
                    .options(options).constraints(constraints).jumps(jumps).build());
        }
        return result;
    }

    private String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /**
     * 「引用即只读」的校验：请求体里如果带了内容字段，必须与版本快照完全一致。
     *
     * <p>为什么不是直接忽略而是报错：静默改写会让用户在问卷编辑页看到自己的修改"消失"，
     * 无法判断是保存失败还是被覆盖。报错能明确指向正确做法——去题库创建新版本。</p>
     *
     * <p>选项列表为空时视为「未提供」：DTO 里 options 默认是空数组而不是 null，
     * 无法用 null 判断是否传过，而引用题库题目时客户端通常不需要重复提交选项。</p>
     */
    private void rejectLocalContentOverride(CreateSurveyRequest.QuestionInput input, QuestionVersion version) {
        if (input.getType() != null && !input.getType().isBlank()
                && !Survey.normalizeType(input.getType()).equals(version.getType())) {
            throw new BusinessException("引用的题库题目不能在问卷内修改题型，请在题库中创建新版本");
        }
        if (input.getTitle() != null && !input.getTitle().isBlank()
                && !input.getTitle().trim().equals(version.getTitle())) {
            throw new BusinessException("引用的题库题目不能在问卷内修改题干，请在题库中创建新版本");
        }
        if (input.getOptions() != null && !input.getOptions().isEmpty()
                && !sameOptions(input.getOptions(), version.getOptions())) {
            throw new BusinessException("引用的题库题目不能在问卷内修改选项，请在题库中创建新版本");
        }
        if (input.getConstraints() != null && !sameConstraints(input.getConstraints(), version.getConstraints())) {
            throw new BusinessException("引用的题库题目不能在问卷内修改约束，请在题库中创建新版本");
        }
    }

    private boolean sameOptions(List<OptionInput> inputs, List<Survey.Option> options) {
        List<Survey.Option> current = options == null ? List.of() : options;
        if (inputs.size() != current.size()) return false;
        Map<String, Survey.Option> byId = new HashMap<>();
        for (Survey.Option option : current) byId.put(option.getId(), option);
        for (OptionInput input : inputs) {
            Survey.Option option = byId.get(normalizeId(input.getId()));
            if (option == null) return false;
            String label = input.getLabel() == null ? "" : input.getLabel().trim();
            if (!label.equals(option.getLabel())) return false;
            if (input.getOrder() != null && input.getOrder() != option.getOrder()) return false;
        }
        return true;
    }

    private boolean sameConstraints(ConstraintsInput input, Survey.Constraints current) {
        boolean inputInteger = Boolean.TRUE.equals(input.getIsInteger());
        boolean currentInteger = current != null && current.isInteger();
        if (inputInteger != currentInteger) return false;
        Survey.Constraints now = current == null ? new Survey.Constraints() : current;
        return Objects.equals(input.getMinSelect(), now.getMinSelect())
                && Objects.equals(input.getMaxSelect(), now.getMaxSelect())
                && Objects.equals(input.getMinLength(), now.getMinLength())
                && Objects.equals(input.getMaxLength(), now.getMaxLength())
                && Objects.equals(input.getMinValue(), now.getMinValue())
                && Objects.equals(input.getMaxValue(), now.getMaxValue());
    }

    /** 发布前集中验证题目结构及跳转规则，确保运行时不会遇到逆向跳转或非法目标。 */
    private void validateForPublish(Survey survey) {
        List<Survey.Question> questions = survey.getQuestions();
        if (questions == null || questions.isEmpty()) throw new BusinessException("问卷至少需要一道题目");
        Set<String> questionIds = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        Map<String, Survey.Question> questionById = new HashMap<>();
        for (Survey.Question q : questions) {
            if (q.getId() == null || q.getId().isBlank() || !questionIds.add(q.getId())) {
                throw new BusinessException("题目 ID 缺失或重复");
            }
            if (q.getOrder() < 1 || !orders.add(q.getOrder())) {
                throw new BusinessException("题目顺序必须为大于 0 的唯一整数");
            }
            questionById.put(q.getId(), q);
            // 题型、题干、选项、约束的校验与题库编辑共用同一套规则，避免「题库能存、问卷发不出去」。
            contentValidator.validate(q.getType(), q.getTitle(), q.getOptions(), q.getConstraints());
        }

        for (Survey.Question q : questions) {
            List<Survey.JumpRule> jumps = q.getJumps() == null ? List.of() : q.getJumps();
            for (Survey.JumpRule jump : jumps) {
                if (jump.getOp() == null || !JUMP_OPS.contains(jump.getOp())) {
                    throw new BusinessException("不支持的跳转操作符: " + jump.getOp());
                }
                if (jump.getTargetQuestionId() == null) throw new BusinessException("跳转目标不能为空");
                if (!Survey.END.equals(jump.getTargetQuestionId())) {
                    Survey.Question target = questionById.get(jump.getTargetQuestionId());
                    if (target == null) throw new BusinessException("跳转目标题目不存在: " + jump.getTargetQuestionId());
                    if (target.getOrder() <= q.getOrder()) throw new BusinessException("只允许向后跳转或跳转到 END: " + q.getTitle());
                }
                if (jump.getOptionId() != null) {
                    boolean optionFound = q.getOptions() != null && q.getOptions().stream()
                            .anyMatch(option -> jump.getOptionId().equals(option.getId()));
                    if (!optionFound) throw new BusinessException("跳转规则引用了当前题目中不存在的 optionId");
                }
                if (NUMERIC_JUMP_OPS.contains(jump.getOp())) {
                    if (!"number".equals(q.getType())) throw new BusinessException("gt/gte/lt/lte 只允许用于数字题");
                    parseNumericValue(jump.getValue());
                }
                if (EQUALITY_JUMP_OPS.contains(jump.getOp())
                        && ("number".equals(q.getType()) || "text".equals(q.getType()))) {
                    if (jump.getValue() == null) throw new BusinessException("文本题或数字题的 eq/neq 规则必须提供 value");
                    if ("number".equals(q.getType())) {
                        parseNumericValue(jump.getValue());
                    }
                }
                if ("contains".equals(jump.getOp()) && "text".equals(q.getType()) && jump.getValue() == null) {
                    throw new BusinessException("文本题的 contains 规则必须提供 value");
                }
                if ("contains".equals(jump.getOp()) && "number".equals(q.getType())) {
                    throw new BusinessException("数字题不支持 contains 操作符");
                }
                if ("contains".equals(jump.getOp()) && ("single".equals(q.getType()) || "multiple".equals(q.getType()))) {
                    if (jump.getOptionId() == null) throw new BusinessException("选项题的 contains 规则必须提供 optionId");
                } else if (EQUALITY_JUMP_OPS.contains(jump.getOp())
                        && ("single".equals(q.getType()) || "multiple".equals(q.getType()))
                        && jump.getOptionId() == null) {
                    throw new BusinessException("选项题的 eq/neq 规则必须提供 optionId");
                }
            }
        }
    }

    /** 数值跳转规则的 value 必须是有限数字。 */
    private void parseNumericValue(String value) {
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException("数值跳转规则的 value 必须是合法数字");
        }
        if (!Double.isFinite(parsed)) throw new BusinessException("数值跳转规则的 value 必须是有限数字");
    }
}
