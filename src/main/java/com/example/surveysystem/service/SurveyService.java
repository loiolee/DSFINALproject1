package com.example.surveysystem.service;

import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.CreateSurveyRequest;
import com.example.surveysystem.dto.UpdateSurveyRequest;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.SurveyRepository;
import com.example.surveysystem.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SurveyService {
    private static final Set<String> QUESTION_TYPES = Survey.SUPPORTED_QUESTION_TYPES;
    private static final Set<String> JUMP_OPS = Survey.SUPPORTED_JUMP_OPS;

    private final SurveyRepository surveyRepository;
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
                .questions(mapQuestions(request.getQuestions()))
                .build();
        return surveyRepository.save(survey);
    }

    public List<Survey> listMine(String ownerId) {
        return surveyRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public Survey getOwned(String surveyId, String ownerId) {
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> BusinessException.notFound("问卷不存在"));
        ensureOwner(survey, ownerId);
        return survey;
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
        survey.setQuestions(mapQuestions(request.getQuestions()));
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
     * 将请求 DTO 转成内嵌文档。先分配所有题目 ID，再转换跳转目标，以支持同一请求内互相引用。
     * 显式给出 ID 的题目/选项会保留该 ID；未提供时由服务端生成短 ID。
     */
    private List<Survey.Question> mapQuestions(List<CreateSurveyRequest.QuestionInput> inputs) {
        if (inputs == null) throw new BusinessException("问卷至少需要一道题目");
        Map<String, String> questionIdMap = new HashMap<>();
        List<String> assignedIds = new ArrayList<>();
        for (CreateSurveyRequest.QuestionInput input : inputs) {
            String oldId = normalizeId(input.getId());
            String assigned = oldId == null ? idGenerator.shortId() : oldId;
            if (assignedIds.contains(assigned)) throw new BusinessException("题目 ID 不能重复: " + assigned);
            assignedIds.add(assigned);
            if (oldId != null) questionIdMap.put(oldId, assigned);
        }

        List<Survey.Question> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            CreateSurveyRequest.QuestionInput input = inputs.get(i);
            String questionId = assignedIds.get(i);
            List<CreateSurveyRequest.OptionInput> optionInputs = input.getOptions() == null
                    ? List.of() : input.getOptions();
            Map<String, String> optionIdMap = new HashMap<>();
            List<Survey.Option> options = new ArrayList<>();
            Set<String> usedOptionIds = new HashSet<>();
            for (int j = 0; j < optionInputs.size(); j++) {
                CreateSurveyRequest.OptionInput optionInput = optionInputs.get(j);
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

            CreateSurveyRequest.ConstraintsInput c = input.getConstraints();
            Survey.Constraints constraints = c == null ? null : Survey.Constraints.builder()
                    .minSelect(c.getMinSelect()).maxSelect(c.getMaxSelect())
                    .minLength(c.getMinLength()).maxLength(c.getMaxLength())
                    .minValue(c.getMinValue()).maxValue(c.getMaxValue())
                    .isInteger(Boolean.TRUE.equals(c.getIsInteger())).build();

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
            result.add(Survey.Question.builder()
                    .id(questionId)
                    .order(input.getOrder() == null ? i + 1 : input.getOrder())
                    .type(input.getType() == null ? null : input.getType().trim().toLowerCase(Locale.ROOT))
                    .title(input.getTitle() == null ? "" : input.getTitle().trim())
                    .required(input.getRequired() == null || input.getRequired())
                    .options(options).constraints(constraints).jumps(jumps).build());
        }
        return result;
    }

    private String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
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
            if (!QUESTION_TYPES.contains(q.getType())) throw new BusinessException("不支持的题型: " + q.getType());
            if (q.getTitle() == null || q.getTitle().isBlank()) throw new BusinessException("题目标题不能为空");
            questionById.put(q.getId(), q);

            List<Survey.Option> options = q.getOptions() == null ? List.of() : q.getOptions();
            if (("single".equals(q.getType()) || "multiple".equals(q.getType())) && options.size() < 2) {
                throw new BusinessException("单选题或多选题至少需要两个选项: " + q.getTitle());
            }
            if (("text".equals(q.getType()) || "number".equals(q.getType())) && !options.isEmpty()) {
                throw new BusinessException("文本题和数字题不能包含选项: " + q.getTitle());
            }
            Set<String> optionIds = new HashSet<>();
            Set<Integer> optionOrders = new HashSet<>();
            for (Survey.Option option : options) {
                if (option.getId() == null || option.getId().isBlank() || !optionIds.add(option.getId())) {
                    throw new BusinessException("选项 ID 缺失或重复: " + q.getTitle());
                }
                if (option.getLabel() == null || option.getLabel().isBlank()) throw new BusinessException("选项文本不能为空");
                if (option.getOrder() < 1 || !optionOrders.add(option.getOrder())) throw new BusinessException("选项顺序必须是唯一正整数");
            }
            validateConstraints(q);
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
                if (Set.of("gt", "gte", "lt", "lte").contains(jump.getOp())) {
                    if (!"number".equals(q.getType())) throw new BusinessException("gt/gte/lt/lte 只允许用于数字题");
                    parseNumericValue(jump.getValue());
                }
                if (Set.of("eq", "neq").contains(jump.getOp()) && ("number".equals(q.getType()) || "text".equals(q.getType()))) {
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
                } else if (Set.of("eq", "neq").contains(jump.getOp())
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

    private void validateConstraints(Survey.Question q) {
        Survey.Constraints c = q.getConstraints();
        if (c == null) return;
        if (c.getMinSelect() != null && c.getMinSelect() < 0) throw new BusinessException("minSelect 不能小于 0");
        if (c.getMaxSelect() != null && c.getMaxSelect() < 1) throw new BusinessException("maxSelect 必须大于 0");
        if (c.getMinSelect() != null && c.getMaxSelect() != null && c.getMinSelect() > c.getMaxSelect()) throw new BusinessException("minSelect 不能大于 maxSelect");
        if (c.getMinLength() != null && c.getMinLength() < 0) throw new BusinessException("minLength 不能小于 0");
        if (c.getMaxLength() != null && c.getMaxLength() < 0) throw new BusinessException("maxLength 不能小于 0");
        if (c.getMinLength() != null && c.getMaxLength() != null && c.getMinLength() > c.getMaxLength()) throw new BusinessException("minLength 不能大于 maxLength");
        if (c.getMinValue() != null && c.getMaxValue() != null && Double.compare(c.getMinValue(), c.getMaxValue()) > 0) throw new BusinessException("minValue 不能大于 maxValue");
        if ("single".equals(q.getType()) && ((c.getMinSelect() != null && c.getMinSelect() > 1) || (c.getMaxSelect() != null && c.getMaxSelect() > 1))) {
            throw new BusinessException("单选题的选择数量约束不能超过 1");
        }
    }
}
