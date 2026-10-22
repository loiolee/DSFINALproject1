package com.example.surveysystem.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 问卷统计总览。submittedCount 仅统计已经提交的答卷。 */
@Data
@Builder
public class SurveyStatsResponse {
    private String surveyId;
    private String surveyTitle;
    private long submittedCount;
    @Builder.Default
    private List<QuestionStats> questions = new ArrayList<>();
}
