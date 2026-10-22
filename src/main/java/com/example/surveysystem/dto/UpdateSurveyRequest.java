package com.example.surveysystem.dto;

/**
 * 更新草稿问卷时使用的 DTO。字段约束、题目结构及嵌套输入类型与创建问卷保持一致，
 * 但独立命名可以清晰地区分创建和更新接口；已发布问卷仍由 Service 层禁止编辑。
 */
public class UpdateSurveyRequest extends CreateSurveyRequest {
}
