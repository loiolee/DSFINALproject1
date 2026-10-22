package com.example.surveysystem.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 问卷主文档。题目、选项和跳转规则嵌入问卷，读取问卷时无需额外关联查询。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "surveys")
public class Survey {
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_CLOSED = "closed";
    public static final String END = "END";

    /**
     * 支持的题型。题型是「系统能力边界」，不是某份问卷的业务规则，因此以白名单形式固化在代码中；
     * 而「哪道题跳到哪里」属于业务规则，存放在 {@code questions[].jumps} 文档里，不在代码中。
     */
    public static final Set<String> SUPPORTED_QUESTION_TYPES = Set.of("single", "multiple", "text", "number");

    /**
     * 支持的跳转比较操作符。它们是一组有限的比较语义词汇（等于、包含、大于…），
     * 校验层与执行层共用同一份定义，避免两处白名单不一致导致「能发布但答不通」。
     */
    public static final Set<String> SUPPORTED_JUMP_OPS = Set.of("eq", "neq", "contains", "gt", "gte", "lt", "lte");

    @Id
    private String id;
    private String ownerId;

    /** 只有发布时才生成 publicId；未发布文档不参与此部分唯一索引。 */
    @Indexed(unique = true, name = "uniq_survey_public_id", partialFilter = "{ 'publicId': { '$type': 'string' } }")
    private String publicId;

    private String title;
    private String description;
    @Builder.Default
    private boolean anonymous = false;
    @Builder.Default
    private boolean allowMultiple = false;
    @Builder.Default
    private String status = STATUS_DRAFT;
    private Instant deadline;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<Question> questions = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String id;
        private int order;
        /** single、multiple、text、number */
        private String type;
        private String title;
        @Builder.Default
        private boolean required = true;
        @Builder.Default
        private List<Option> options = new ArrayList<>();
        private Constraints constraints;
        @Builder.Default
        private List<JumpRule> jumps = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option {
        private String id;
        private String label;
        private int order;
    }

    /** 不同题型只使用对应字段；未配置的限制视为不限制。数值用 Double 保证以原生数字类型存储。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Constraints {
        private Integer minSelect;
        private Integer maxSelect;
        private Integer minLength;
        private Integer maxLength;
        private Double minValue;
        private Double maxValue;
        @Builder.Default
        private boolean isInteger = false;
    }

    /** jumps 按数组顺序匹配，第一条命中的规则优先；目标只允许后续题目或 END。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JumpRule {
        /** eq、neq、contains、gt、gte、lt、lte */
        private String op;
        private String optionId;
        /** 可用于文本比较或数值比较的字面值。 */
        private String value;
        private String targetQuestionId;
    }
}
