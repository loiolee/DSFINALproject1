package com.example.surveysystem.service;

import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 题目内容校验：题型、题干、选项、约束。
 *
 * <p>第二阶段从 {@code SurveyService.validateForPublish} 里抽出来，供问卷发布校验与题库编辑共用。
 * 抽出的理由是这两处必须用同一套规则：如果题库允许保存一个「单选题只有 1 个选项」的版本，
 * 而问卷发布时又拒绝它，用户就会遇到「题库能存、选进问卷却发布不了」的矛盾。</p>
 *
 * <p>不包含跳转规则校验：跳转规则只存在于问卷内，题库版本里没有这项。</p>
 */
@Component
public class QuestionContentValidator {

    /**
     * 校验一份题目内容。错误消息与第一阶段完全一致，避免既有测试用例的断言失效。
     */
    public void validate(String type, String title, List<Survey.Option> options, Survey.Constraints constraints) {
        if (!Survey.SUPPORTED_QUESTION_TYPES.contains(type)) {
            throw new BusinessException("不支持的题型: " + type);
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("题目标题不能为空");
        }

        List<Survey.Option> items = options == null ? List.of() : options;
        if (("single".equals(type) || "multiple".equals(type)) && items.size() < 2) {
            throw new BusinessException("单选题或多选题至少需要两个选项: " + title);
        }
        if (("text".equals(type) || "number".equals(type)) && !items.isEmpty()) {
            throw new BusinessException("文本题和数字题不能包含选项: " + title);
        }

        Set<String> optionIds = new HashSet<>();
        Set<Integer> optionOrders = new HashSet<>();
        for (Survey.Option option : items) {
            if (option.getId() == null || option.getId().isBlank() || !optionIds.add(option.getId())) {
                throw new BusinessException("选项 ID 缺失或重复: " + title);
            }
            if (option.getLabel() == null || option.getLabel().isBlank()) {
                throw new BusinessException("选项文本不能为空");
            }
            if (option.getOrder() < 1 || !optionOrders.add(option.getOrder())) {
                throw new BusinessException("选项顺序必须是唯一正整数");
            }
        }
        validateConstraints(type, constraints);
    }

    /** 约束只要求「自洽」：上下界不倒置、单选不超过 1 项。未配置的项视为不限制。 */
    public void validateConstraints(String type, Survey.Constraints constraints) {
        if (constraints == null) return;
        if (constraints.getMinSelect() != null && constraints.getMinSelect() < 0) {
            throw new BusinessException("minSelect 不能小于 0");
        }
        if (constraints.getMaxSelect() != null && constraints.getMaxSelect() < 1) {
            throw new BusinessException("maxSelect 必须大于 0");
        }
        if (constraints.getMinSelect() != null && constraints.getMaxSelect() != null
                && constraints.getMinSelect() > constraints.getMaxSelect()) {
            throw new BusinessException("minSelect 不能大于 maxSelect");
        }
        if (constraints.getMinLength() != null && constraints.getMinLength() < 0) {
            throw new BusinessException("minLength 不能小于 0");
        }
        if (constraints.getMaxLength() != null && constraints.getMaxLength() < 0) {
            throw new BusinessException("maxLength 不能小于 0");
        }
        if (constraints.getMinLength() != null && constraints.getMaxLength() != null
                && constraints.getMinLength() > constraints.getMaxLength()) {
            throw new BusinessException("minLength 不能大于 maxLength");
        }
        if (constraints.getMinValue() != null && constraints.getMaxValue() != null
                && Double.compare(constraints.getMinValue(), constraints.getMaxValue()) > 0) {
            throw new BusinessException("minValue 不能大于 maxValue");
        }
        if ("single".equals(type)
                && ((constraints.getMinSelect() != null && constraints.getMinSelect() > 1)
                || (constraints.getMaxSelect() != null && constraints.getMaxSelect() > 1))) {
            throw new BusinessException("单选题的选择数量约束不能超过 1");
        }
    }
}
