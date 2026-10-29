package com.example.surveysystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 题目选项的输入结构。
 *
 * <p>第二阶段从 {@link CreateSurveyRequest} 的内嵌类提升为顶层 DTO：问卷编辑与题库编辑
 * 需要同一份选项结构，共用一份定义可以避免两处字段名或校验规则漂移。</p>
 */
@Data
public class OptionInput {
    /** 可选。本地题目留空时由服务端生成；从题库复制或从问卷导入时保留原 ID。 */
    private String id;

    @NotBlank(message = "选项文本不能为空")
    @Size(max = 500, message = "选项文本不能超过 500 个字符")
    private String label;

    /** 可选，留空按数组下标 + 1 处理。 */
    private Integer order;
}
