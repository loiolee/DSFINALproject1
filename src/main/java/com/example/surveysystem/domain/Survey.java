package com.example.surveysystem.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 问卷主文档。
 *
 * <p>第二阶段起，{@code questions} 数组里的元素承担两个角色：</p>
 * <ul>
 *   <li><b>题库引用</b>：{@code questionId} + {@code questionVersionId} 指向题库中的某个版本；</li>
 *   <li><b>内容快照</b>：{@code type / title / options / constraints} 是引用那一刻从版本复制过来的副本。</li>
 * </ul>
 *
 * <p>保留快照而不是每次读取都回查版本集合，是刻意的取舍：答题链路（逐题下发、逐题校验、跳转判定）
 * 和统计链路都只读本问卷文档即可完成，不需要额外查询；同时「已发布问卷内容永不改变」由快照直接保证，
 * 不依赖版本集合的不可变约定。代价是题库改题后草稿不会自动跟随，需要显式重新绑定版本。</p>
 *
 * <p>跳转规则仍然只存在于问卷内（{@code questions[].jumps}），不进入题库版本：
 * 跳转目标指向本问卷内的另一道题，同一道题被不同问卷复用时跳转目标必然不同。</p>
 */
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

    /** 题目来源：直接在问卷内创建，内容只属于这一份问卷，不出现在题库里。 */
    public static final String QUESTION_SOURCE_LOCAL = "local";
    /** 题目来源：引用题库中的某个版本，内容以版本快照为准，问卷内不允许修改内容。 */
    public static final String QUESTION_SOURCE_LIBRARY = "library";

    /**
     * 题型归一化：去空格并转小写。
     *
     * <p>放在领域类而不是各服务里，是因为题库与问卷两条链路都必须用同一套归一化规则，
     * 否则「题库存了 Single、问卷发布时按 single 校验失败」这类问题会随机出现。</p>
     */
    public static String normalizeType(String type) {
        return type == null ? null : type.trim().toLowerCase(java.util.Locale.ROOT);
    }

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
        /**
         * 问卷内题目实例 ID。
         * 语义与第一阶段完全一致：跳转规则、答卷的 answers[].questionId、visitedQuestionIds
         * 都通过它定位题目，因此第二阶段不允许改变它的含义，否则第一阶段的历史答卷会全部失联。
         */
        private String id;
        private int order;
        /** 本问卷内是否必答。引用题库题目时未显式指定，则取版本里的默认值。 */
        @Builder.Default
        private boolean required = true;

        /** 题库题目身份 {@link LibraryQuestion#getId()}；本地题目为 null。 */
        private String questionId;
        /** 绑定的题库版本 {@link QuestionVersion#getId()}；本地题目为 null。 */
        private String questionVersionId;
        /** 绑定的版本号，冗余保存供「被哪些问卷使用」直接展示。 */
        private Integer versionNo;
        /** local / library，见 {@link Survey#QUESTION_SOURCE_LOCAL}。 */
        @Builder.Default
        private String sourceType = QUESTION_SOURCE_LOCAL;

        /** 以下四个字段是内容快照：引用题库题目时由版本复制，本地题目时由请求体提供。发布后冻结。 */
        private String type;
        private String title;
        @Builder.Default
        private List<Option> options = new ArrayList<>();
        private Constraints constraints;

        /** 跳转规则属于本问卷内的实例配置，不进入题库版本。 */
        @Builder.Default
        private List<JumpRule> jumps = new ArrayList<>();

        /**
         * 题库中该题的最新版本号，<b>不持久化</b>。
         *
         * <p>只在读取问卷时由服务层回填，用于提示「这道题在题库里已有更新版本」。
         * 不落库的理由：它是题库当前状态，随时会变，存进问卷就会变成一份需要同步的副本。</p>
         */
        @Transient
        private Integer latestVersionNo;

        /**
         * 题库已有比当前绑定更新的版本，<b>不持久化</b>。
         *
         * <p>只用于提示，<b>不触发任何自动升级</b>：需求明确要求「修改题目时不要影响已经做好的问卷」，
         * 因此换不换版本由用户自己决定，见 {@code PUT /api/surveys/{id}/questions/{qid}/version}。</p>
         */
        @Transient
        private Boolean newerVersionAvailable;

        /** 是否引用了题库版本。 */
        public boolean isFromLibrary() {
            return QUESTION_SOURCE_LIBRARY.equals(sourceType);
        }
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
        /**
         * 是否只允许整数。
         *
         * <p>字段名是 {@code isInteger}，而 {@code boolean} 的 JavaBean 读取方法恰好也叫 {@code isInteger()}，
         * Jackson 会把它当成属性 {@code integer} 的读取方法。只给字段加 {@code @JsonProperty} 不够：
         * 字段被改名成 {@code isInteger} 的同时，那个由读取方法推导出来的 {@code integer} 属性仍然存在，
         * 于是响应里会同时出现 {@code "integer": true} 与 {@code "isInteger": true} 两个键——
         * 一个概念两个名字，客户端不知道该信哪个，请求里同时传两个还会互相覆盖。</p>
         *
         * <p>因此读取方法也显式标注同一个 JSON 名，让两条路径合并成同一个属性。显式声明后
         * Lombok 不再生成同签名方法，请求、响应、数据库字段名三者保持一致。</p>
         */
        @Builder.Default
        @JsonProperty("isInteger")
        private boolean isInteger = false;

        @JsonProperty("isInteger")
        public boolean isInteger() {
            return isInteger;
        }

        @JsonProperty("isInteger")
        public void setInteger(boolean isInteger) {
            this.isInteger = isInteger;
        }
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
