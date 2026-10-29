package com.example.surveysystem.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 一份 Response 同时作为答题会话和最终答卷，answers 在文档内嵌入。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "responses")
@CompoundIndexes({
        @CompoundIndex(
                name = "uniq_survey_submission_key",
                def = "{ 'surveyId': 1, 'submissionKey': 1 }",
                unique = true,
                partialFilter = "{ 'submissionKey': { '$type': 'string' } }"
        ),
        @CompoundIndex(name = "idx_survey_status", def = "{ 'surveyId': 1, 'status': 1 }")
})
public class Response {
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_SUBMITTED = "submitted";

    @Id
    private String id;
    private String surveyId;
    /** 非匿名答卷保存 userId；匿名答卷必须保持该字段为空。 */
    @Field(write = Field.Write.NON_NULL)
    private String userId;
    private String respondentKeyHash;
    private boolean anonymous;
    private String status;
    private String currentQuestionId;
    /** allowMultiple=false 时才设置，用部分唯一索引保证并发下最多一份答卷。 */
    private String submissionKey;
    private Instant startedAt;
    private Instant submittedAt;
    private Instant updatedAt;
    @Builder.Default
    private List<String> visitedQuestionIds = new ArrayList<>();
    @Builder.Default
    private List<Answer> answers = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Answer {
        /** 问卷内题目实例 ID，语义与第一阶段一致，历史答卷靠它关联。 */
        private String questionId;
        /**
         * 题库题目身份；本地题目为 null。
         * 跨问卷统计按它归并「同一道题在不同问卷里的回答」。
         */
        private String libraryQuestionId;
        /**
         * 绑定的题库版本；本地题目为 null。
         * 单题统计按它区分口径：v1 与 v2 的选项可能不同，不能混在一起算比例。
         */
        private String questionVersionId;
        private String type;
        @Builder.Default
        private List<String> optionIds = new ArrayList<>();
        private String textValue;
        /** 使用 Double 以保证写入 MongoDB 时为原生数值类型，聚合管道 $avg 才能计算。 */
        private Double numberValue;
        private Instant answeredAt;
    }
}
