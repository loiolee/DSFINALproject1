package com.example.surveysystem.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 单道题目统计。answeredCount 是实际回答该题的已提交答卷数，而非问卷总提交数。 */
@Data
@Builder
public class QuestionStats {
    private String questionId;
    private String title;
    private String type;
    private long answeredCount;
    private Double averageNumber;
    @Builder.Default
    private List<OptionCount> options = new ArrayList<>();
    @Builder.Default
    private List<String> textAnswers = new ArrayList<>();
    /** 文本回答总条数；textAnswers 只返回前若干条，避免答卷很多时响应体过大。 */
    private long textAnswerCount;

    @Data
    @Builder
    public static class OptionCount {
        private String optionId;
        private String label;
        private long count;
    }
}
