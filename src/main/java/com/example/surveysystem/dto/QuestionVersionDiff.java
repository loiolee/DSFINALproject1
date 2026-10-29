package com.example.surveysystem.dto;

import com.example.surveysystem.domain.Survey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 两个题目版本之间的差异（需求「记录题目的修改历史」的可读化）。
 *
 * <p>对比只在<b>版本内容</b>范围内进行，因此不包含 {@code jumps} 与 {@code order}：
 * 这两项属于问卷内实例配置，本来就不在版本里（见 schema.md §10.3）。</p>
 *
 * <p>{@code summary} 是给人看的一句话列表，前端可以直接逐条渲染；
 * 下面各字段是结构化的差异，供前端做更细的展示（例如把改动项高亮）。两者由同一份比对结果生成，
 * 不会出现「摘要说改了题干、结构化数据说没改」这种不一致。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionVersionDiff {
    private String questionId;
    private int fromVersionNo;
    private int toVersionNo;

    /** 两个版本内容完全一致时为 true，此时 summary 只有一条「两个版本内容完全相同」。 */
    private boolean identical;

    /** 人类可读的差异描述，按题型 → 题干 → 必答 → 选项 → 约束的顺序排列。 */
    @Builder.Default
    private List<String> summary = new ArrayList<>();

    private FieldChange type;
    private FieldChange title;
    private FieldChange required;
    private OptionDiff options;

    /** 约束字段的逐项差异；未变化的字段不出现。 */
    @Builder.Default
    private List<FieldChange> constraints = new ArrayList<>();

    /**
     * 单字段差异。{@code from} / {@code to} 用 Object 承载，
     * 这样题干（String）、必答（Boolean）、数值约束（Double / Integer）可以共用同一个结构。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldChange {
        /** 字段名，与领域对象一致，便于前端按字段定位。 */
        private String field;
        private Object from;
        private Object to;
        private boolean changed;
    }

    /** 选项集合的差异。选项以 ID 为身份，因此「文案改了」与「换了一个选项」能被区分开。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionDiff {
        @Builder.Default
        private List<Survey.Option> added = new ArrayList<>();
        @Builder.Default
        private List<Survey.Option> removed = new ArrayList<>();
        @Builder.Default
        private List<OptionChange> modified = new ArrayList<>();
        /** 共同选项的相对顺序发生变化。 */
        private boolean reordered;
        private boolean changed;
    }

    /** 同一选项 ID 的文案变化。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionChange {
        private String id;
        private String fromLabel;
        private String toLabel;
    }
}
