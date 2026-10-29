package com.example.surveysystem.dto;

import lombok.Data;

/**
 * 题目约束的输入结构。
 *
 * <p>与 {@link OptionInput} 一样，第二阶段从 {@link CreateSurveyRequest} 的内嵌类提升为顶层 DTO，
 * 供问卷编辑与题库编辑共用。字段全部可空，未配置的约束视为不限制。</p>
 */
@Data
public class ConstraintsInput {
    private Integer minSelect;
    private Integer maxSelect;
    private Integer minLength;
    private Integer maxLength;
    private Double minValue;
    private Double maxValue;
    private Boolean isInteger = false;
}
