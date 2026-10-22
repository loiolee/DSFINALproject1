package com.example.surveysystem.service;

import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.QuestionStats;
import com.example.surveysystem.dto.SurveyStatsResponse;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.ResponseRepository;
import com.example.surveysystem.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.*;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

/** 仅对已提交答卷进行聚合统计；每题分母按照实际存在该题答案的答卷计算。 */
@Service
@RequiredArgsConstructor
public class StatsService {
    /** 文本回答明细的最大返回条数。 */
    private static final int MAX_TEXT_ANSWERS = 500;

    private final SurveyRepository surveyRepository;
    private final ResponseRepository responseRepository;
    private final MongoTemplate mongoTemplate;

    public SurveyStatsResponse getStats(String surveyId, String ownerId) {
        Survey survey = requireOwnedSurvey(surveyId, ownerId);

        SurveyStatsResponse result = SurveyStatsResponse.builder()
                .surveyId(survey.getId())
                .surveyTitle(survey.getTitle())
                .submittedCount(responseRepository.countBySurveyIdAndStatus(surveyId, "submitted"))
                .questions(new ArrayList<>())
                .build();
        List<Survey.Question> questions = new ArrayList<>(
                survey.getQuestions() == null ? List.of() : survey.getQuestions());
        questions.sort(Comparator.comparingInt(Survey.Question::getOrder));
        for (Survey.Question question : questions) {
            result.getQuestions().add(statForQuestion(surveyId, question));
        }
        return result;
    }

    /**
     * 单题统计：只聚合指定题目，供「查看某一道题的统计」使用。
     * 题目必须属于该问卷，且调用者必须是问卷创建者。
     */
    public QuestionStats getQuestionStats(String surveyId, String questionId, String ownerId) {
        Survey survey = requireOwnedSurvey(surveyId, ownerId);
        Survey.Question question = (survey.getQuestions() == null ? List.<Survey.Question>of() : survey.getQuestions())
                .stream()
                .filter(item -> Objects.equals(item.getId(), questionId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("该问卷中不存在这道题目"));
        return statForQuestion(surveyId, question);
    }

    private Survey requireOwnedSurvey(String surveyId, String ownerId) {
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> BusinessException.notFound("问卷不存在"));
        if (!Objects.equals(survey.getOwnerId(), ownerId)) {
            throw BusinessException.forbidden("无权查看该问卷统计");
        }
        return survey;
    }

    private QuestionStats statForQuestion(String surveyId, Survey.Question question) {
        long answeredCount = countAnswered(surveyId, question.getId());
        QuestionStats.QuestionStatsBuilder builder = QuestionStats.builder()
                .questionId(question.getId())
                .title(question.getTitle())
                .type(question.getType())
                .answeredCount(answeredCount)
                .options(new ArrayList<>())
                .textAnswers(new ArrayList<>());

        if ("single".equals(question.getType()) || "multiple".equals(question.getType())) {
            Map<String, Long> counts = optionCounts(surveyId, question.getId());
            List<QuestionStats.OptionCount> options = new ArrayList<>();
            if (question.getOptions() != null) {
                question.getOptions().stream().sorted(Comparator.comparingInt(Survey.Option::getOrder))
                        .forEach(option -> options.add(QuestionStats.OptionCount.builder()
                                .optionId(option.getId())
                                .label(option.getLabel())
                                .count(counts.getOrDefault(option.getId(), 0L))
                                .build()));
            }
            builder.options(options);
        } else if ("number".equals(question.getType())) {
            builder.averageNumber(averageNumber(surveyId, question.getId()).orElse(null));
        } else if ("text".equals(question.getType())) {
            builder.textAnswerCount(countTextAnswers(surveyId, question.getId()));
            builder.textAnswers(textAnswers(surveyId, question.getId()));
        }
        return builder.build();
    }

    /**
     * Aggregation 管道先按 surveyId 和 submitted 过滤，再 unwind answers 展开内嵌答案，
     * 最后只匹配当前 questionId 并计数。因此被跳转逻辑跳过的题目不会进入该题分母。
     */
    private long countAnswered(String surveyId, String questionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("surveyId").is(surveyId).and("status").is("submitted")),
                unwind("answers"),
                match(Criteria.where("answers.questionId").is(questionId)),
                group("answers.questionId").count().as("answeredCount")
        );
        Document row = mongoTemplate.aggregate(aggregation, "responses", Document.class).getUniqueMappedResult();
        return row == null ? 0L : numberAsLong(row.get("answeredCount"));
    }

    /** 多选题展开 answers.optionIds 后按选项 ID 分组，每次选择计一次。 */
    private Map<String, Long> optionCounts(String surveyId, String questionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("surveyId").is(surveyId).and("status").is("submitted")),
                unwind("answers"),
                match(Criteria.where("answers.questionId").is(questionId)),
                unwind("answers.optionIds"),
                group("answers.optionIds").count().as("count")
        );
        Map<String, Long> counts = new HashMap<>();
        for (Document row : mongoTemplate.aggregate(aggregation, "responses", Document.class).getMappedResults()) {
            Object optionId = row.get("_id");
            if (optionId != null) counts.put(optionId.toString(), numberAsLong(row.get("count")));
        }
        return counts;
    }

    private Optional<Double> averageNumber(String surveyId, String questionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("surveyId").is(surveyId).and("status").is("submitted")),
                unwind("answers"),
                match(Criteria.where("answers.questionId").is(questionId)
                        .and("answers.numberValue").exists(true)),
                group("answers.questionId").avg("answers.numberValue").as("average")
        );
        Document row = mongoTemplate.aggregate(aggregation, "responses", Document.class).getUniqueMappedResult();
        if (row == null) return Optional.empty();
        Object average = row.get("average");
        // BigDecimal 在 MongoDB 中可能以 Decimal128 返回。
        if (average instanceof Number value) return Optional.of(value.doubleValue());
        if (average instanceof Decimal128 decimal128) return Optional.of(decimal128.bigDecimalValue().doubleValue());
        return Optional.empty();
    }

    /** 文本回答明细，最多返回 {@value #MAX_TEXT_ANSWERS} 条，防止答卷很多时响应体无限膨胀。 */
    private List<String> textAnswers(String surveyId, String questionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("surveyId").is(surveyId).and("status").is("submitted")),
                unwind("answers"),
                match(Criteria.where("answers.questionId").is(questionId)
                        .and("answers.textValue").exists(true)),
                project().and("answers.textValue").as("textValue"),
                limit(MAX_TEXT_ANSWERS)
        );
        return mongoTemplate.aggregate(aggregation, "responses", Document.class).getMappedResults().stream()
                .map(row -> row.getString("textValue"))
                .filter(Objects::nonNull)
                .toList();
    }

    /** 文本回答总条数，与明细分开统计，保证「共 N 条」不受明细截断影响。 */
    private long countTextAnswers(String surveyId, String questionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("surveyId").is(surveyId).and("status").is("submitted")),
                unwind("answers"),
                match(Criteria.where("answers.questionId").is(questionId)
                        .and("answers.textValue").exists(true)),
                count().as("total")
        );
        Document row = mongoTemplate.aggregate(aggregation, "responses", Document.class).getUniqueMappedResult();
        return row == null ? 0L : numberAsLong(row.get("total"));
    }

    private long numberAsLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
