package com.example.surveysystem.service;

import com.example.surveysystem.domain.Response;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.StartResponseResult;
import com.example.surveysystem.dto.SubmitAnswerRequest;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.ResponseRepository;
import com.example.surveysystem.repository.SurveyRepository;
import com.example.surveysystem.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnswerEngineService {
    private final ResponseRepository responseRepository;
    private final SurveyRepository surveyRepository;
    private final MongoTemplate mongoTemplate;
    private final HashUtils hashUtils;

    public StartResponseResult start(String publicId, String userId) {
        Survey survey = surveyRepository.findByPublicId(publicId)
                .orElseThrow(() -> BusinessException.notFound("问卷不存在或公开链接无效"));
        ensureAcceptingAnswers(survey);
        List<Survey.Question> questions = orderedQuestions(survey);
        if (questions.isEmpty()) throw BusinessException.conflict("问卷没有可答题目");

        String respondentHash = survey.isAnonymous() ? hashUtils.respondentHash(survey.getId(), userId) : null;
        String submissionKey = null;
        if (!survey.isAllowMultiple()) {
            submissionKey = survey.isAnonymous() ? "anon:" + respondentHash : "user:" + userId;
            // 快速路径；并发下稍后保存时仍由 MongoDB 唯一索引作最终保障。
            Optional<Response> existing = responseRepository.findBySurveyIdAndSubmissionKey(survey.getId(), submissionKey);
            if (existing.isPresent()) return resumeOrReject(existing.get(), survey);
        }

        Instant now = Instant.now();
        Response response = Response.builder()
                .surveyId(survey.getId())
                .userId(survey.isAnonymous() ? null : userId)
                .respondentKeyHash(respondentHash)
                .anonymous(survey.isAnonymous())
                .status(Response.STATUS_IN_PROGRESS)
                .currentQuestionId(questions.getFirst().getId())
                .submissionKey(submissionKey)
                .startedAt(now)
                .visitedQuestionIds(new ArrayList<>(List.of(questions.getFirst().getId())))
                .answers(new ArrayList<>())
                .build();
        try {
            response = responseRepository.save(response);
        } catch (DuplicateKeyException ex) {
            // 两个并发 start 可能同时通过上面的查重；唯一索引保证最终只创建一份。
            if (submissionKey != null) {
                Response existing = responseRepository.findBySurveyIdAndSubmissionKey(survey.getId(), submissionKey)
                        .orElseThrow(() -> BusinessException.conflict("检测到重复提交，请重试"));
                return resumeOrReject(existing, survey);
            }
            throw BusinessException.conflict("创建答卷时发生并发冲突，请重试");
        }
        return StartResponseResult.builder()
                .responseId(response.getId())
                .surveyTitle(survey.getTitle())
                .question(StartResponseResult.QuestionView.from(questions.getFirst()))
                .completed(false)
                .message("答题会话已创建")
                .build();
    }

    public StartResponseResult answer(String responseId, String userId, SubmitAnswerRequest request) {
        Response response = getResponse(responseId);
        Survey survey = getSurveyForResponse(response);
        assertResponseOwner(response, survey, userId);
        ensureAcceptingAnswers(survey);
        if (!Response.STATUS_IN_PROGRESS.equals(response.getStatus())) {
            throw BusinessException.conflict("该答卷已提交，不能继续答题");
        }
        if (Survey.END.equals(response.getCurrentQuestionId())) {
            throw BusinessException.conflict("所有题目已完成，请直接提交答卷");
        }
        if (!Objects.equals(response.getCurrentQuestionId(), request.getQuestionId())) {
            throw BusinessException.conflict("答题顺序已变化，请按服务端返回的当前题目作答");
        }

        Survey.Question question = findQuestion(survey, response.getCurrentQuestionId());
        Response.Answer answer = parseAndValidateAnswer(question, request.getAnswer());
        String nextQuestionId = evaluate(question, answer, survey);

        // 原子条件更新：只有 currentQuestionId 仍等于本次题目时才写入，防止并发请求覆盖/重复答题。
        Query query = Query.query(Criteria.where("_id").is(responseId)
                .and("status").is(Response.STATUS_IN_PROGRESS)
                .and("currentQuestionId").is(question.getId()));
        Update update = new Update()
                .set("currentQuestionId", nextQuestionId)
                .set("updatedAt", Instant.now());
        if (answer != null) update.push("answers").value(answer);
        if (!Survey.END.equals(nextQuestionId)) update.addToSet("visitedQuestionIds").value(nextQuestionId);
        UpdateResult updateResult = mongoTemplate.updateFirst(query, update, Response.class);
        if (updateResult.getModifiedCount() != 1) {
            throw BusinessException.conflict("该题已被提交或答题状态已变化，请刷新后继续");
        }

        boolean completed = Survey.END.equals(nextQuestionId);
        Survey.Question nextQuestion = completed ? null : findQuestion(survey, nextQuestionId);
        return StartResponseResult.builder()
                .responseId(responseId)
                .completed(completed)
                .nextQuestion(nextQuestion == null ? null : StartResponseResult.QuestionView.from(nextQuestion))
                .surveyTitle(survey.getTitle())
                .message(completed ? "答题路径已结束，请提交答卷" : "答案已保存")
                .build();
    }

    public Map<String, Object> submit(String responseId, String userId) {
        Response response = getResponse(responseId);
        Survey survey = getSurveyForResponse(response);
        assertResponseOwner(response, survey, userId);
        ensureAcceptingAnswers(survey);
        if (Response.STATUS_SUBMITTED.equals(response.getStatus())) {
            throw BusinessException.conflict("该答卷已经提交，请勿重复提交");
        }
        if (!Survey.END.equals(response.getCurrentQuestionId())) {
            throw BusinessException.conflict("还有题目尚未完成，不能提交答卷");
        }

        // 仅校验实际访问过的题目；跳转跳过的必答题不应阻止交卷。
        Set<String> visited = new HashSet<>(response.getVisitedQuestionIds() == null
                ? List.of() : response.getVisitedQuestionIds());
        Set<String> answered = new HashSet<>();
        if (response.getAnswers() != null) {
            response.getAnswers().forEach(a -> answered.add(a.getQuestionId()));
        }
        for (Survey.Question question : survey.getQuestions()) {
            if (visited.contains(question.getId()) && question.isRequired() && !answered.contains(question.getId())) {
                throw new BusinessException("必答题尚未作答: " + question.getTitle());
            }
        }

        Instant submittedAt = Instant.now();
        Query query = Query.query(Criteria.where("_id").is(responseId)
                .and("status").is(Response.STATUS_IN_PROGRESS)
                .and("currentQuestionId").is(Survey.END));
        Update update = new Update().set("status", Response.STATUS_SUBMITTED).set("submittedAt", submittedAt);
        UpdateResult result = mongoTemplate.updateFirst(query, update, Response.class);
        if (result.getModifiedCount() != 1) {
            throw BusinessException.conflict("答卷状态已变化，无法重复提交");
        }
        return Map.of("responseId", responseId, "status", Response.STATUS_SUBMITTED, "submittedAt", submittedAt);
    }

    private StartResponseResult resumeOrReject(Response existing, Survey survey) {
        if (Response.STATUS_SUBMITTED.equals(existing.getStatus())) {
            throw BusinessException.conflict("你已经填写并提交过这份问卷");
        }
        if (Survey.END.equals(existing.getCurrentQuestionId())) {
            return StartResponseResult.builder().responseId(existing.getId()).surveyTitle(survey.getTitle())
                    .question(null).completed(true).message("答题路径已结束，请提交答卷").build();
        }
        Survey.Question question = findQuestion(survey, existing.getCurrentQuestionId());
        return StartResponseResult.builder().responseId(existing.getId()).surveyTitle(survey.getTitle())
                .question(StartResponseResult.QuestionView.from(question)).completed(false).message("已恢复答题会话").build();
    }

    private Response getResponse(String responseId) {
        return responseRepository.findById(responseId)
                .orElseThrow(() -> BusinessException.notFound("答卷会话不存在"));
    }

    private Survey getSurveyForResponse(Response response) {
        return surveyRepository.findById(response.getSurveyId())
                .orElseThrow(() -> BusinessException.notFound("对应问卷不存在"));
    }

    private void assertResponseOwner(Response response, Survey survey, String userId) {
        if (survey.isAnonymous()) {
            // 匿名答卷不存 userId；通过当前登录用户重算 HMAC 来证明会话所有权。
            String candidate = hashUtils.respondentHash(survey.getId(), userId);
            if (response.getRespondentKeyHash() == null || !java.security.MessageDigest.isEqual(
                    candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    response.getRespondentKeyHash().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw BusinessException.forbidden("无权访问该答卷会话");
            }
        } else if (!Objects.equals(response.getUserId(), userId)) {
            throw BusinessException.forbidden("无权访问该答卷会话");
        }
    }

    private void ensureAcceptingAnswers(Survey survey) {
        if (!Survey.STATUS_PUBLISHED.equals(survey.getStatus())) {
            throw BusinessException.conflict("问卷当前不可填写");
        }
        if (survey.getDeadline() != null && !survey.getDeadline().isAfter(Instant.now())) {
            throw BusinessException.conflict("问卷已超过截止时间");
        }
    }

    private List<Survey.Question> orderedQuestions(Survey survey) {
        List<Survey.Question> questions = new ArrayList<>(survey.getQuestions() == null ? List.of() : survey.getQuestions());
        questions.sort(Comparator.comparingInt(Survey.Question::getOrder));
        return questions;
    }

    private Survey.Question findQuestion(Survey survey, String questionId) {
        return survey.getQuestions().stream().filter(q -> Objects.equals(q.getId(), questionId)).findFirst()
                .orElseThrow(() -> BusinessException.conflict("当前题目不存在，问卷结构可能已发生异常"));
    }

    /** 将前端的 JSON answer 转换为规范化的内嵌 Answer，并在写库前完成题型和约束校验。 */
    private Response.Answer parseAndValidateAnswer(Survey.Question question, JsonNode node) {
        boolean emptyNode = node == null || node.isNull() || node.isMissingNode();
        Survey.Constraints constraints = question.getConstraints();
        List<String> optionIds = new ArrayList<>();
        String textValue = null;
        Double numberValue = null;
        boolean emptyAnswer = emptyNode;

        switch (question.getType()) {
            case "single" -> {
                if (!emptyNode) {
                    if (!node.isTextual()) throw new BusinessException("单选题 answer 必须是选项 ID 字符串");
                    String selected = node.asText().trim();
                    emptyAnswer = selected.isEmpty();
                    if (!emptyAnswer) {
                        ensureOptionExists(question, selected);
                        optionIds.add(selected);
                    }
                }
                if (!emptyAnswer) validateSelectCount(question, optionIds.size(), constraints, 1);
            }
            case "multiple" -> {
                if (!emptyNode) {
                    if (!node.isArray()) throw new BusinessException("多选题 answer 必须是选项 ID 字符串数组");
                    Set<String> unique = new LinkedHashSet<>();
                    for (JsonNode item : node) {
                        if (!item.isTextual() || item.asText().isBlank()) throw new BusinessException("多选题中的每个选项 ID 都必须是非空字符串");
                        if (!unique.add(item.asText())) throw new BusinessException("多选题不能重复选择同一个选项");
                    }
                    optionIds.addAll(unique);
                    emptyAnswer = optionIds.isEmpty();
                    for (String optionId : optionIds) ensureOptionExists(question, optionId);
                    if (!emptyAnswer) validateSelectCount(question, optionIds.size(), constraints, null);
                }
            }
            case "text" -> {
                if (!emptyNode) {
                    if (!node.isTextual()) throw new BusinessException("文本题 answer 必须是字符串");
                    textValue = node.asText();
                    emptyAnswer = textValue.isBlank();
                    if (!emptyAnswer && constraints != null) {
                        int length = textValue.length();
                        if (constraints.getMinLength() != null && length < constraints.getMinLength()) throw new BusinessException("文本长度未达到最小限制");
                        if (constraints.getMaxLength() != null && length > constraints.getMaxLength()) throw new BusinessException("文本长度超过最大限制");
                    }
                }
            }
            case "number" -> {
                if (!emptyNode) {
                    if (!node.isNumber()) throw new BusinessException("数字题 answer 必须是数字，不能用字符串代替");
                    numberValue = node.doubleValue();
                    emptyAnswer = false;
                    if (constraints != null) {
                        if (constraints.isInteger() && numberValue != Math.floor(numberValue)) throw new BusinessException("该题只允许整数");
                        if (constraints.getMinValue() != null && numberValue < constraints.getMinValue()) throw new BusinessException("数值小于最小限制");
                        if (constraints.getMaxValue() != null && numberValue > constraints.getMaxValue()) throw new BusinessException("数值大于最大限制");
                    }
                }
            }
            default -> throw new BusinessException("不支持的题型: " + question.getType());
        }

        if (emptyAnswer) {
            if (question.isRequired()) throw new BusinessException("该题为必答题，不能跳过");
            return null;
        }
        return Response.Answer.builder()
                .questionId(question.getId())
                // 题库归属与版本在答题时一并落库：跨问卷统计需要按题目身份归并，
                // 单题统计需要按版本区分口径（v1 与 v2 的选项可能不同）。
                // 本地题目这两个字段为 null，历史答卷同样为 null，聚合时自然被排除。
                .libraryQuestionId(question.getQuestionId())
                .questionVersionId(question.getQuestionVersionId())
                .type(question.getType())
                .optionIds(optionIds)
                .textValue(textValue)
                .numberValue(numberValue)
                .answeredAt(Instant.now())
                .build();
    }

    private void ensureOptionExists(Survey.Question question, String selected) {
        boolean exists = question.getOptions() != null && question.getOptions().stream()
                .anyMatch(option -> selected.equals(option.getId()));
        if (!exists) throw new BusinessException("所选 optionId 不属于当前题目");
    }

    private void validateSelectCount(Survey.Question question, int count, Survey.Constraints constraints, Integer singleLimit) {
        if (singleLimit != null && count > singleLimit) throw new BusinessException("单选题只能选择一个选项");
        if (constraints != null) {
            if (constraints.getMinSelect() != null && count < constraints.getMinSelect()) throw new BusinessException("选择数量少于 minSelect 限制");
            if (constraints.getMaxSelect() != null && count > constraints.getMaxSelect()) throw new BusinessException("选择数量超过 maxSelect 限制");
        }
    }

    /**
     * 服务端跳转引擎：按 jumps 数组优先级逐条判断，第一条命中即返回目标；
     * 没有命中时返回按 order 排列的下一题，题目走完则返回 END。绝不读取前端传来的目标题目 ID。
     */
    private String evaluate(Survey.Question current, Response.Answer answer, Survey survey) {
        if (answer != null && current.getJumps() != null) {
            for (Survey.JumpRule rule : current.getJumps()) {
                if (matches(rule, answer)) {
                    return Survey.END.equals(rule.getTargetQuestionId()) ? Survey.END : rule.getTargetQuestionId();
                }
            }
        }
        return orderedQuestions(survey).stream()
                .filter(question -> question.getOrder() > current.getOrder())
                .min(Comparator.comparingInt(Survey.Question::getOrder))
                .map(Survey.Question::getId)
                .orElse(Survey.END);
    }

    /**
     * 判断单条跳转规则是否命中。
     *
     * <p>这里用 switch 穷举 {@link Survey#SUPPORTED_JUMP_OPS} 里的操作符，是刻意的设计取舍：
     * 「操作符」是有限的比较语义词汇（等于 / 包含 / 大于…），每个分支对应不同的类型处理方式，
     * 属于系统能力边界，而不是某份问卷的业务规则。真正的业务规则（哪道题选哪个选项跳到哪里）
     * 存在 {@code surveys.questions[].jumps} 文档里，运行时解释执行，加规则不需要改代码。
     * 发布前的校验层与这里共用同一份操作符白名单，因此 default 分支只用于防御脏数据。</p>
     */
    private boolean matches(Survey.JumpRule rule, Response.Answer answer) {
        String op = rule.getOp();
        String value = rule.getValue();
        if (answer.getOptionIds() != null && !answer.getOptionIds().isEmpty()) {
            boolean selected = rule.getOptionId() != null && answer.getOptionIds().contains(rule.getOptionId());
            return switch (op) {
                case "eq", "contains" -> selected;
                case "neq" -> rule.getOptionId() != null && !selected;
                default -> false;
            };
        }
        if (answer.getTextValue() != null) {
            return switch (op) {
                case "eq" -> Objects.equals(answer.getTextValue(), value);
                case "neq" -> !Objects.equals(answer.getTextValue(), value);
                case "contains" -> value != null && answer.getTextValue().contains(value);
                default -> false;
            };
        }
        if (answer.getNumberValue() != null && value != null) {
            try {
                int comparison = Double.compare(answer.getNumberValue(), Double.parseDouble(value));
                return switch (op) {
                    case "eq" -> comparison == 0;
                    case "neq" -> comparison != 0;
                    case "gt" -> comparison > 0;
                    case "gte" -> comparison >= 0;
                    case "lt" -> comparison < 0;
                    case "lte" -> comparison <= 0;
                    default -> false;
                };
            } catch (NumberFormatException ignored) {
                return false; // 发布校验正常情况下已保证 value 合法；此处仍作防御处理。
            }
        }
        return false;
    }
}
