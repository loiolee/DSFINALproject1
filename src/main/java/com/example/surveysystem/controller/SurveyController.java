package com.example.surveysystem.controller;

import com.example.surveysystem.config.AuthInterceptor;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.ApiResponse;
import com.example.surveysystem.dto.CreateSurveyRequest;
import com.example.surveysystem.dto.UpdateDeadlineRequest;
import com.example.surveysystem.dto.UpdateSurveyRequest;
import com.example.surveysystem.service.SurveyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
public class SurveyController {
    private final SurveyService surveyService;

    @PostMapping
    public ApiResponse<Survey> create(@RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
                                      @Valid @RequestBody CreateSurveyRequest request) {
        return ApiResponse.success("问卷草稿创建成功", surveyService.create(userId, request));
    }

    @GetMapping("/mine")
    public ApiResponse<List<Survey>> listMine(@RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(surveyService.listMine(userId));
    }

    @GetMapping("/{surveyId}")
    public ApiResponse<Survey> get(@PathVariable String surveyId,
                                   @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(surveyService.getOwned(surveyId, userId));
    }

    @PutMapping("/{surveyId}")
    public ApiResponse<Survey> updateDraft(@PathVariable String surveyId,
                                           @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
                                           @Valid @RequestBody UpdateSurveyRequest request) {
        return ApiResponse.success("草稿更新成功", surveyService.updateDraft(surveyId, userId, request));
    }

    @DeleteMapping("/{surveyId}")
    public ApiResponse<Void> deleteDraft(@PathVariable String surveyId,
                                         @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        surveyService.deleteDraft(surveyId, userId);
        return ApiResponse.success("草稿删除成功", null);
    }

    @PostMapping("/{surveyId}/publish")
    public ApiResponse<Survey> publish(@PathVariable String surveyId,
                                       @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success("问卷发布成功", surveyService.publish(surveyId, userId));
    }

    @PostMapping("/{surveyId}/close")
    public ApiResponse<Survey> close(@PathVariable String surveyId,
                                     @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success("问卷已关闭", surveyService.close(surveyId, userId));
    }

    @PutMapping("/{surveyId}/deadline")
    public ApiResponse<Survey> updateDeadline(@PathVariable String surveyId,
                                              @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
                                              @RequestBody UpdateDeadlineRequest request) {
        return ApiResponse.success("截止时间已更新", surveyService.updateDeadline(surveyId, userId, request.getDeadline()));
    }
}
