package com.example.surveysystem.service;

import com.example.surveysystem.domain.QuestionVersion;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.QuestionVersionDiff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目版本对比。
 *
 * <p>只比内容：题型、题干、必答默认值、选项、约束。{@code jumps} 与 {@code order} 不在版本里，
 * 因此无从比较，也不应该比较——它们属于问卷内实例配置。</p>
 *
 * <p>选项以 ID 为身份做匹配。这样「选项文案改了」与「删掉一个选项又加了一个新选项」是两件不同的事：
 * 前者不影响已有答卷，后者的新选项在历史答卷里没有答案，统计时必须区分对待。</p>
 */
@Component
public class QuestionVersionComparator {

    /** 对比两个版本，返回结构化差异与可读摘要。 */
    public QuestionVersionDiff compare(QuestionVersion from, QuestionVersion to) {
        List<String> summary = new ArrayList<>();

        QuestionVersionDiff.FieldChange type = fieldChange("type", from.getType(), to.getType(), summary,
                "题型由 " + from.getType() + " 改为 " + to.getType());
        QuestionVersionDiff.FieldChange title = fieldChange("title", from.getTitle(), to.getTitle(), summary,
                "题干已修改");
        QuestionVersionDiff.FieldChange required = fieldChange("required", from.isRequired(), to.isRequired(), summary,
                "必答设置由「" + requiredText(from.isRequired()) + "」改为「" + requiredText(to.isRequired()) + "」");

        QuestionVersionDiff.OptionDiff options = compareOptions(from.getOptions(), to.getOptions(), summary);
        List<QuestionVersionDiff.FieldChange> constraints = compareConstraints(
                from.getConstraints(), to.getConstraints(), summary);

        return QuestionVersionDiff.builder()
                .questionId(to.getQuestionId())
                .fromVersionNo(from.getVersionNo())
                .toVersionNo(to.getVersionNo())
                .identical(summary.isEmpty())
                .summary(summary.isEmpty() ? List.of("两个版本内容完全相同") : summary)
                .type(type)
                .title(title)
                .required(required)
                .options(options)
                .constraints(constraints)
                .build();
    }

    private QuestionVersionDiff.FieldChange fieldChange(String field, Object from, Object to,
                                                        List<String> summary, String description) {
        boolean changed = !Objects.equals(from, to);
        if (changed) {
            summary.add(description);
        }
        return QuestionVersionDiff.FieldChange.builder()
                .field(field).from(from).to(to).changed(changed).build();
    }

    private QuestionVersionDiff.OptionDiff compareOptions(List<Survey.Option> fromOptions,
                                                          List<Survey.Option> toOptions,
                                                          List<String> summary) {
        List<Survey.Option> source = fromOptions == null ? List.of() : fromOptions;
        List<Survey.Option> target = toOptions == null ? List.of() : toOptions;

        // 用 LinkedHashMap 保持版本内的原始顺序，后续判断「顺序是否变化」依赖它。
        Map<String, Survey.Option> sourceById = indexById(source);
        Map<String, Survey.Option> targetById = indexById(target);

        List<Survey.Option> added = target.stream()
                .filter(option -> !sourceById.containsKey(option.getId()))
                .toList();
        List<Survey.Option> removed = source.stream()
                .filter(option -> !targetById.containsKey(option.getId()))
                .toList();

        List<QuestionVersionDiff.OptionChange> modified = new ArrayList<>();
        for (Survey.Option before : source) {
            Survey.Option after = targetById.get(before.getId());
            if (after != null && !Objects.equals(labelOf(before), labelOf(after))) {
                modified.add(QuestionVersionDiff.OptionChange.builder()
                        .id(before.getId())
                        .fromLabel(before.getLabel())
                        .toLabel(after.getLabel())
                        .build());
            }
        }

        Set<String> commonIds = sourceById.keySet().stream()
                .filter(targetById::containsKey)
                .collect(Collectors.toSet());
        List<String> sourceOrder = source.stream().map(Survey.Option::getId).filter(commonIds::contains).toList();
        List<String> targetOrder = target.stream().map(Survey.Option::getId).filter(commonIds::contains).toList();
        boolean reordered = !sourceOrder.equals(targetOrder);

        for (Survey.Option option : added) {
            summary.add("新增选项「" + labelOf(option) + "」");
        }
        for (Survey.Option option : removed) {
            summary.add("删除选项「" + labelOf(option) + "」");
        }
        for (QuestionVersionDiff.OptionChange change : modified) {
            summary.add("选项「" + labelOf(change.getFromLabel()) + "」文案改为「" + labelOf(change.getToLabel()) + "」");
        }
        if (reordered) {
            summary.add("选项顺序已调整");
        }

        return QuestionVersionDiff.OptionDiff.builder()
                .added(added)
                .removed(removed)
                .modified(modified)
                .reordered(reordered)
                .changed(!added.isEmpty() || !removed.isEmpty() || !modified.isEmpty() || reordered)
                .build();
    }

    private List<QuestionVersionDiff.FieldChange> compareConstraints(Survey.Constraints from,
                                                                    Survey.Constraints to,
                                                                    List<String> summary) {
        List<QuestionVersionDiff.FieldChange> changes = new ArrayList<>();
        // 一侧没有约束时按「全部为空」处理，避免到处判空。
        Survey.Constraints a = from == null ? new Survey.Constraints() : from;
        Survey.Constraints b = to == null ? new Survey.Constraints() : to;

        addConstraint(changes, summary, "minSelect", a.getMinSelect(), b.getMinSelect());
        addConstraint(changes, summary, "maxSelect", a.getMaxSelect(), b.getMaxSelect());
        addConstraint(changes, summary, "minLength", a.getMinLength(), b.getMinLength());
        addConstraint(changes, summary, "maxLength", a.getMaxLength(), b.getMaxLength());
        addConstraint(changes, summary, "minValue", a.getMinValue(), b.getMinValue());
        addConstraint(changes, summary, "maxValue", a.getMaxValue(), b.getMaxValue());
        addConstraint(changes, summary, "isInteger", a.isInteger(), b.isInteger());
        return changes;
    }

    private void addConstraint(List<QuestionVersionDiff.FieldChange> changes, List<String> summary,
                               String field, Object from, Object to) {
        if (Objects.equals(from, to)) {
            return;
        }
        changes.add(QuestionVersionDiff.FieldChange.builder()
                .field(field).from(from).to(to).changed(true).build());
        if (to == null) {
            summary.add("取消约束 " + field);
        } else if (from == null) {
            summary.add("新增约束 " + field + " = " + to);
        } else {
            summary.add("约束 " + field + " 由 " + from + " 改为 " + to);
        }
    }

    private Map<String, Survey.Option> indexById(List<Survey.Option> options) {
        Map<String, Survey.Option> map = new LinkedHashMap<>();
        for (Survey.Option option : options) {
            // 选项 ID 是匹配依据；没有 ID 的历史脏数据无法定位，直接跳过而不是让整个对比失败。
            if (option.getId() != null) {
                map.put(option.getId(), option);
            }
        }
        return map;
    }

    private String requiredText(boolean required) {
        return required ? "必答" : "选答";
    }

    private String labelOf(Survey.Option option) {
        return labelOf(option.getLabel());
    }

    private String labelOf(String label) {
        return label == null ? "" : label;
    }
}
