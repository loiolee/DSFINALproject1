package com.example.surveysystem.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库题目内容的请求体，创建题目与修改题目共用。
 *
 * <p>只包含「题目问什么」这一层信息：题型、题干、选项、约束。
 * 不包含跳转规则与展示顺序——这两者属于问卷内的实例配置，进不了题库版本。</p>
 */
@Data
public class QuestionContentRequest {
    @NotBlank(message = "题目标题不能为空")
    @Size(max = 1000, message = "题目标题不能超过 1000 个字符")
    private String title;

    @NotBlank(message = "题型不能为空")
    private String type;

    /**
     * 版本级默认必答性。创建时留空按必答处理；
     * 修改时留空表示沿用当前版本的值，避免客户端漏传导致必答性被静默改掉。
     */
    private Boolean required;

    @Valid
    private List<OptionInput> options = new ArrayList<>();

    private ConstraintsInput constraints;

    /** 改动说明，写入版本历史，便于区分每次修改的目的。 */
    @Size(max = 1000, message = "改动说明不能超过 1000 个字符")
    private String changeNote;
}
