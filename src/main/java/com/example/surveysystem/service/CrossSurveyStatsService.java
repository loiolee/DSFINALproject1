package com.example.surveysystem.service;

import com.example.surveysystem.domain.LibraryQuestion;
import com.example.surveysystem.domain.QuestionVersion;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.CrossSurveyQuestionStats;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.LibraryQuestionRepository;
import com.example.surveysystem.repository.QuestionVersionRepository;
import com.example.surveysystem.repository.SurveyRepository;
import com.example.surveysystem.utils.AggregationUtils;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.count;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

/**
 * 跨问卷单题统计（需求「查看单个题目的统计结果」）。
 *
 * <p>与 {@link StatsService} 的区别：StatsService 是「一份问卷内某道题」的统计，
 * 以 {@code surveyId} 为边界；本服务是「同一道题库题目在所有问卷上」的统计，
 * 以 {@code questionVersionId} 为边界。两者的分母完全不同，不能合并实现。</p>
 *
 * <p><b>权限边界</b>：只有题目创建者能看。问卷可能属于别人，把「我出的题在别人问卷里的回答分布」
 * 开放给被授权者，等于泄露了别人问卷的数据（见 schema.md §10.4）。因此本服务只返回聚合结果，
 * 不返回任何答卷 ID 或填写者信息。</p>
 *
 * <p><b>按版本分组</b>：不同版本的选项集合可能不同，混在一起算比例会让每个选项的比例都偏低。
 * 详见 {@link CrossSurveyQuestionStats} 的类注释。</p>
 */
@Service
@RequiredArgsConstructor
public class CrossSurveyStatsService {
    /** 文本回答明细的返回上限，防止答卷很多时响应体无限膨胀。与 StatsService 保持一致。 */
    private static final int MAX_TEXT_SAMPLES = 500;
    private static final String SUBMITTED = "submitted";

    private final LibraryQuestionRepository questionRepository;
    private final QuestionVersionRepository versionRepository;
    private final SurveyRepository surveyRepository;
    private final MongoTemplate mongoTemplate;

    public CrossSurveyQuestionStats getStats(String questionId, String ownerId) {
        LibraryQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> BusinessException.notFound("题目不存在"));
        if (!Objects.equals(question.getOwnerId(), ownerId)) {
            throw BusinessException.forbidden("只有题目创建者可以查看跨问卷统计");
        }

        List<QuestionVersion> versions = versionRepository.findByQuestionIdOrderByVersionNoDesc(questionId);
        List<String> versionIds = versions.stream().map(QuestionVersion::getId).toList();
        int surveyCount = (int) surveyRepository.countByLibraryQuestionId(questionId);

        if (versionIds.isEmpty()) {
            return CrossSurveyQuestionStats.builder()
                    .questionId(questionId)
                    .title(question.getTitle())
                    .type(question.getType())
                    .surveyCount(surveyCount)
                    .versionCount(0)
                    .build();
        }

        // 用 questionVersionId 而不是 libraryQuestionId 过滤：前者有复合索引
        // idx_answer_version_status，后者没有。而且版本 ID 是已知集合，等价且更快。
        Map<String, Long> answeredByVersion = countAnsweredByVersion(versionIds);
        Map<String, Map<String, Long>> optionCounts = optionCountsByVersion(versionIds);

        List<CrossSurveyQuestionStats.VersionStats> versionStats = new ArrayList<>();
        long totalAnswered = 0;
        boolean hasEmptyVersion = false;
        for (QuestionVersion version : versions) {
            long answered = answeredByVersion.getOrDefault(version.getId(), 0L);
            totalAnswered += answered;
            if (answered == 0) {
                hasEmptyVersion = true;
            }
            CrossSurveyQuestionStats.VersionStats.VersionStatsBuilder builder =
                    CrossSurveyQuestionStats.VersionStats.builder()
                            .versionId(version.getId())
                            .versionNo(version.getVersionNo())
                            .answeredCount(answered);

            switch (String.valueOf(version.getType())) {
                case "single", "multiple" -> builder.options(
                        buildOptionStats(version, optionCounts.getOrDefault(version.getId(), Map.of()), answered));
                case "number" -> builder.numberStats(numberStats(version.getId()));
                case "text" -> {
                    TextStats text = textStats(version.getId());
                    builder.textSampleTotal(text.total());
                    builder.textSamples(text.samples());
                }
                default -> {
                    // 题型白名单外的历史数据：只给回答数，不做进一步统计。
                }
            }
            versionStats.add(builder.build());
        }

        return CrossSurveyQuestionStats.builder()
                .questionId(questionId)
                .title(question.getTitle())
                .type(question.getType())
                .surveyCount(surveyCount)
                .versionCount(versions.size())
                .totalAnswered(totalAnswered)
                .hasEmptyVersion(hasEmptyVersion)
                .versions(versionStats)
                .build();
    }

    /** 每个版本的答题条数。被跳转逻辑跳过的答卷不会出现在该版本里，因此不会进入分母。 */
    private Map<String, Long> countAnsweredByVersion(List<String> versionIds) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("status").is(SUBMITTED)
                        .and("answers.questionVersionId").in(versionIds)),
                unwind("answers"),
                match(Criteria.where("answers.questionVersionId").in(versionIds)),
                // 先投影成无点号的字段名再分组：分组键里带点号会生成形如
                // {_id: {"answers.questionVersionId": ...}} 的文档，读起来既脆弱又难排查。
                project().and("answers.questionVersionId").as("versionId"),
                group("versionId").count().as("answeredCount")
        );
        Map<String, Long> result = new HashMap<>();
        for (Document row : mongoTemplate.aggregate(aggregation, "responses", Document.class).getMappedResults()) {
            // group 之后分组键落在 _id 上，顶层只剩聚合出来的字段。
            Object id = row.get("_id");
            if (id != null) {
                result.put(id.toString(), AggregationUtils.asLong(row.get("answeredCount")));
            }
        }
        return result;
    }

    /** 每个版本里每个选项被选中的次数。多选题展开 optionIds 后，每次选择计一次。 */
    private Map<String, Map<String, Long>> optionCountsByVersion(List<String> versionIds) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("status").is(SUBMITTED)
                        .and("answers.questionVersionId").in(versionIds)),
                unwind("answers"),
                match(Criteria.where("answers.questionVersionId").in(versionIds)),
                unwind("answers.optionIds"),
                project().and("answers.questionVersionId").as("versionId")
                        .and("answers.optionIds").as("optionId"),
                group("versionId", "optionId").count().as("count")
        );
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Document row : mongoTemplate.aggregate(aggregation, "responses", Document.class).getMappedResults()) {
            // 两个分组字段时 _id 是子文档 {versionId: ..., optionId: ...}。
            if (!(row.get("_id") instanceof Document key)) {
                continue;
            }
            String versionId = key.getString("versionId");
            String optionId = key.getString("optionId");
            if (versionId == null || optionId == null) {
                continue;
            }
            result.computeIfAbsent(versionId, ignored -> new HashMap<>())
                    .put(optionId, AggregationUtils.asLong(row.get("count")));
        }
        return result;
    }

    /**
     * 组装选项统计。
     *
     * <p>以版本里定义的选项为准逐个输出，而不是以聚合结果为准：没人选的选项也要出现（count=0），
     * 否则前端会以为这个选项不存在，而不是「这个选项没人选」。</p>
     */
    private List<CrossSurveyQuestionStats.OptionStat> buildOptionStats(QuestionVersion version,
                                                                      Map<String, Long> counts,
                                                                      long answered) {
        List<Survey.Option> options = version.getOptions() == null ? List.of() : version.getOptions();
        List<CrossSurveyQuestionStats.OptionStat> stats = new ArrayList<>();
        for (Survey.Option option : options) {
            long count = counts.getOrDefault(option.getId(), 0L);
            stats.add(CrossSurveyQuestionStats.OptionStat.builder()
                    .optionId(option.getId())
                    .label(option.getLabel())
                    .count(count)
                    .ratio(answered == 0 ? 0d : (double) count / answered)
                    .build());
        }
        return stats;
    }

    private CrossSurveyQuestionStats.NumberStats numberStats(String versionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("status").is(SUBMITTED)
                        .and("answers.questionVersionId").is(versionId)),
                unwind("answers"),
                match(Criteria.where("answers.questionVersionId").is(versionId)
                        .and("answers.numberValue").exists(true)),
                group("answers.questionVersionId")
                        .count().as("count")
                        .avg("answers.numberValue").as("average")
                        .min("answers.numberValue").as("min")
                        .max("answers.numberValue").as("max")
        );
        Document row = mongoTemplate.aggregate(aggregation, "responses", Document.class).getUniqueMappedResult();
        if (row == null) {
            return CrossSurveyQuestionStats.NumberStats.builder().count(0).build();
        }
        return CrossSurveyQuestionStats.NumberStats.builder()
                .count(AggregationUtils.asLong(row.get("count")))
                .average(AggregationUtils.asDouble(row.get("average")))
                .min(AggregationUtils.asDouble(row.get("min")))
                .max(AggregationUtils.asDouble(row.get("max")))
                .build();
    }

    /**
     * 文本题的样本与总数。
     *
     * <p>只发一次聚合：样本未取满时，取回的行数就是总数；取满（说明还有更多）时才补一次 count。
     * 原实现对每个版本固定发两次聚合（count + 样本），版本一多就是 2N 次；
     * 绝大多数版本的回答数远小于 {@link #MAX_TEXT_SAMPLES}，因此常规路径只查一次。</p>
     */
    private TextStats textStats(String versionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("status").is(SUBMITTED)
                        .and("answers.questionVersionId").is(versionId)),
                unwind("answers"),
                match(Criteria.where("answers.questionVersionId").is(versionId)
                        .and("answers.textValue").exists(true)),
                project().and("answers.textValue").as("textValue"),
                limit(MAX_TEXT_SAMPLES)
        );
        List<Document> rows = mongoTemplate.aggregate(aggregation, "responses", Document.class).getMappedResults();
        List<String> samples = rows.stream()
                .map(row -> row.getString("textValue"))
                .filter(Objects::nonNull)
                .toList();
        // 行数而非 samples 的大小：textValue 显式为 null 的行也会被 exists 匹配到，
        // 它们计入总数但不作为样本返回，用 samples.size() 判断会少算。
        long total = rows.size() < MAX_TEXT_SAMPLES ? rows.size() : countTextAnswers(versionId);
        return new TextStats(total, samples);
    }

    /** 文本题统计结果：总数（可能大于样本数）与样本明细。 */
    private record TextStats(long total, List<String> samples) {
    }

    private long countTextAnswers(String versionId) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("status").is(SUBMITTED)
                        .and("answers.questionVersionId").is(versionId)),
                unwind("answers"),
                match(Criteria.where("answers.questionVersionId").is(versionId)
                        .and("answers.textValue").exists(true)),
                count().as("total")
        );
        Document row = mongoTemplate.aggregate(aggregation, "responses", Document.class).getUniqueMappedResult();
        return row == null ? 0L : AggregationUtils.asLong(row.get("total"));
    }

}