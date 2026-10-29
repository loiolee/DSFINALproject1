package com.example.surveysystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单道题在所有问卷上的统计结果（需求「查看单个题目的统计结果」）。
 *
 * <p><b>为什么按版本分组而不是把所有回答混在一起算比例</b>：同一道题的不同版本可能增删选项。
 * 若把 v1 的「三个选项」和 v2 的「四个选项」的回答合并算百分比，分母里混了两套不同的选项集合，
 * 每个选项的比例都会偏低，看起来像「所有人都没选」。按版本分组后，每个版本内部的分母是一致的。</p>
 *
 * <p>顶层只给「跨版本合计的回答数」与「引用该题的问卷数」这两个不涉及比例的量，
 * 它们不受选项集合变化影响。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossSurveyQuestionStats {
    private String questionId;
    private String title;
    private String type;

    /** 有多少份问卷引用了这道题（含草稿与已关闭）。 */
    private int surveyCount;
    /** 这道题一共有多少个版本。 */
    private int versionCount;
    /** 所有版本合计的回答条数。 */
    private long totalAnswered;
    /** 是否有版本没有任何回答。为 true 时前端应提示「统计口径仅覆盖部分版本」。 */
    private boolean hasEmptyVersion;

    @Builder.Default
    private List<VersionStats> versions = new ArrayList<>();

    /** 单个版本内的统计。选项题用 {@code options}，数字题用 {@code numberStats}，文本题用 {@code textSampleTotal}。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionStats {
        private String versionId;
        private Integer versionNo;
        private long answeredCount;

        /** 选项题：每个选项被选中的次数与占比（占比以本版本的 answeredCount 为分母）。 */
        @Builder.Default
        private List<OptionStat> options = new ArrayList<>();

        /** 数字题：平均值、最小值、最大值。 */
        private NumberStats numberStats;

        /** 文本题：回答总条数（不受明细截断影响）。 */
        private long textSampleTotal;
        /** 文本题：回答明细，最多返回 {@code MAX_TEXT_SAMPLES} 条。 */
        @Builder.Default
        private List<String> textSamples = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionStat {
        private String optionId;
        private String label;
        private long count;
        /** 0~1 的小数，前端自行格式化成百分比。 */
        private double ratio;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NumberStats {
        private long count;
        private Double average;
        private Double min;
        private Double max;
    }
}
