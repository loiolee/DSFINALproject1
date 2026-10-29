package com.example.surveysystem.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 创建或更新草稿的请求 DTO；不直接把请求 JSON 绑定到 MongoDB 实体。 */
@Data
public class CreateSurveyRequest {
    @NotBlank(message = "问卷标题不能为空")
    @Size(max = 200, message = "问卷标题不能超过 200 个字符")
    private String title;

    @Size(max = 5000, message = "问卷描述不能超过 5000 个字符")
    private String description;

    private Boolean anonymous = false;
    private Boolean allowMultiple = false;
    private Instant deadline;

    @Valid
    @NotEmpty(message = "问卷至少需要一道题目")
    private List<QuestionInput> questions = new ArrayList<>();

    @Data
    public static class QuestionInput {
        /** 可选：问卷内题目实例 ID。如跳转规则引用该题，建议显式提供稳定 ID。 */
        private String id;
        private Integer order;
        /**
         * 题型。本地题目必填；引用题库版本时可不传，内容以版本快照为准。
         *
         * <p>这里刻意不加 {@code @NotBlank}：Bean Validation 无法表达「本地题目必填、题库引用可空」
         * 这种条件约束，加了注解就等于强迫客户端在引用题库题目时把题干和选项原样回传一遍，
         * 与「引用即只读」的语义自相矛盾。必填性由 {@code SurveyService.mapQuestions}
         * 在本地题目分支里校验，错误消息与注解版本一致。</p>
         */
        private String type;
        /** 题干。本地题目必填；引用题库版本时可不传。理由同上。 */
        private String title;
        /**
         * 本问卷内是否必答。留空时：本地题目按必答处理，题库题目取版本里的默认值。
         * 第二阶段把默认值从 {@code true} 改为 {@code null}，是为了让「沿用版本默认值」这件事可以表达；
         * 对本地题目的行为与第一阶段完全相同（null 视为必答）。
         */
        private Boolean required;

        /**
         * 可选：题库题目身份。与 questionVersionId 一起使用时用于校验一致性；
         * 两者都为空表示这是一道本地题目。
         */
        private String questionId;
        /**
         * 可选：引用题库中的某个版本。提供该字段时，题目内容以版本快照为准，
         * 请求体里的 type / title / options / constraints 只用于一致性校验，不会被写入。
         */
        private String questionVersionId;
        /** 可选：版本号。仅作展示与校验用，实际以 questionVersionId 对应的版本为准。 */
        private Integer versionNo;

        @Valid
        private List<OptionInput> options = new ArrayList<>();
        private ConstraintsInput constraints;
        @Valid
        private List<JumpInput> jumps = new ArrayList<>();
    }

    @Data
    public static class JumpInput {
        @NotBlank(message = "跳转操作符不能为空")
        private String op;
        private String optionId;
        private String value;
        @NotBlank(message = "跳转目标不能为空")
        private String targetQuestionId;
    }
}
