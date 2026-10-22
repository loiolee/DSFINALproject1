package com.example.surveysystem.dto;

import com.example.surveysystem.domain.Survey;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 开始答题和提交单题后的统一响应；只暴露当前题目，不向客户端返回跳转规则。 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StartResponseResult {
    private String responseId;
    private String surveyTitle;
    private QuestionView question;
    /** 单题提交后的下一题；开始答题响应中该字段为空。 */
    private QuestionView nextQuestion;
    private boolean completed;
    private String message;

    @Data
    @Builder
    public static class QuestionView {
        private String id;
        private int order;
        private String type;
        private String title;
        private boolean required;
        private List<Survey.Option> options;
        private Survey.Constraints constraints;

        public static QuestionView from(Survey.Question question) {
            return QuestionView.builder()
                    .id(question.getId())
                    .order(question.getOrder())
                    .type(question.getType())
                    .title(question.getTitle())
                    .required(question.isRequired())
                    .options(question.getOptions())
                    .constraints(question.getConstraints())
                    .build();
        }
    }
}
