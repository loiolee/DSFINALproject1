package com.example.surveysystem.service;

import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.ConstraintsInput;
import com.example.surveysystem.dto.OptionInput;
import com.example.surveysystem.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目内容的转换：请求 DTO → 领域结构，以及版本内容 → 问卷快照的复制。
 *
 * <p>抽成组件的理由与 {@link QuestionContentValidator} 相同：问卷编辑与题库编辑必须产出
 * 结构完全一致的内容，否则「从题库选题」复制快照时会缺字段。</p>
 */
@Component
@RequiredArgsConstructor
public class QuestionContentMapper {

    private final IdGenerator idGenerator;

    /** 请求体选项 → 领域选项。未给 ID 时由服务端生成，未给顺序时按数组下标补。 */
    public List<Survey.Option> toOptions(List<OptionInput> inputs) {
        List<Survey.Option> options = new ArrayList<>();
        if (inputs == null) return options;
        for (int i = 0; i < inputs.size(); i++) {
            OptionInput input = inputs.get(i);
            String id = input.getId() == null || input.getId().isBlank()
                    ? idGenerator.shortId()
                    : input.getId().trim();
            options.add(Survey.Option.builder()
                    .id(id)
                    .label(input.getLabel() == null ? "" : input.getLabel().trim())
                    .order(input.getOrder() == null ? i + 1 : input.getOrder())
                    .build());
        }
        return options;
    }

    /** 请求体约束 → 领域约束。未配置的项保持 null，表示不限制。 */
    public Survey.Constraints toConstraints(ConstraintsInput input) {
        if (input == null) return null;
        return Survey.Constraints.builder()
                .minSelect(input.getMinSelect()).maxSelect(input.getMaxSelect())
                .minLength(input.getMinLength()).maxLength(input.getMaxLength())
                .minValue(input.getMinValue()).maxValue(input.getMaxValue())
                .isInteger(Boolean.TRUE.equals(input.getIsInteger())).build();
    }

    /** 逐字段新建选项对象，避免问卷快照与题库版本共用同一份引用。 */
    public List<Survey.Option> copyOptions(List<Survey.Option> source) {
        List<Survey.Option> copy = new ArrayList<>();
        if (source == null) return copy;
        for (Survey.Option option : source) {
            copy.add(Survey.Option.builder()
                    .id(option.getId()).label(option.getLabel()).order(option.getOrder()).build());
        }
        return copy;
    }

    public Survey.Constraints copyConstraints(Survey.Constraints source) {
        if (source == null) return null;
        return Survey.Constraints.builder()
                .minSelect(source.getMinSelect()).maxSelect(source.getMaxSelect())
                .minLength(source.getMinLength()).maxLength(source.getMaxLength())
                .minValue(source.getMinValue()).maxValue(source.getMaxValue())
                .isInteger(source.isInteger()).build();
    }
}
