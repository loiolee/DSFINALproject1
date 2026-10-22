package com.example.surveysystem.controller;

import com.example.surveysystem.config.AuthInterceptor;
import com.example.surveysystem.dto.ApiResponse;
import com.example.surveysystem.dto.QuestionStats;
import com.example.surveysystem.dto.SurveyStatsResponse;
import com.example.surveysystem.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
public class StatsController {
    private final StatsService statsService;

    @GetMapping("/{surveyId}/stats")
    public ApiResponse<SurveyStatsResponse> stats(@PathVariable String surveyId,
                                          @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(statsService.getStats(surveyId, userId));
    }

    /** 单题统计：满足「查看某一道题的统计」的需求，只聚合这一道题。 */
    @GetMapping("/{surveyId}/stats/{questionId}")
    public ApiResponse<QuestionStats> questionStats(@PathVariable String surveyId,
                                                    @PathVariable String questionId,
                                                    @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(statsService.getQuestionStats(surveyId, questionId, userId));
    }
}
