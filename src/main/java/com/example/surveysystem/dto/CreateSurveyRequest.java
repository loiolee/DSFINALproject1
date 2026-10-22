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
        /** 可选：如跳转规则引用该题，建议显式提供稳定 ID。 */
        private String id;
        private Integer order;
        @NotBlank(message = "题型不能为空")
        private String type;
        @NotBlank(message = "题目标题不能为空")
        @Size(max = 1000, message = "题目标题不能超过 1000 个字符")
        private String title;
        private Boolean required = true;
        @Valid
        private List<OptionInput> options = new ArrayList<>();
        private ConstraintsInput constraints;
        @Valid
        private List<JumpInput> jumps = new ArrayList<>();
    }

    @Data
    public static class OptionInput {
        private String id;
        @NotBlank(message = "选项文本不能为空")
        @Size(max = 500, message = "选项文本不能超过 500 个字符")
        private String label;
        private Integer order;
    }

    @Data
    public static class ConstraintsInput {
        private Integer minSelect;
        private Integer maxSelect;
        private Integer minLength;
        private Integer maxLength;
        private Double minValue;
        private Double maxValue;
        private Boolean isInteger = false;
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
