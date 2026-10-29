package com.example.surveysystem.controller;

import com.example.surveysystem.config.AuthInterceptor;
import com.example.surveysystem.domain.Survey;
import com.example.surveysystem.dto.ApiResponse;
import com.example.surveysystem.dto.CreateSurveyRequest;
import com.example.surveysystem.dto.LibraryQuestionDetail;
import com.example.surveysystem.dto.UpdateDeadlineRequest;
import com.example.surveysystem.dto.UpdateSurveyRequest;
import com.example.surveysystem.service.QuestionLibraryService;
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
    private final QuestionLibraryService questionLibraryService;

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
        return ApiResponse.success(surveyService.getOwnedWithVersionStatus(surveyId, userId));
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

    /**
     * 把本问卷里的一道本地题目存入题库（需求「可以把题加入题库」）。
     *
     * <p>只复制内容，不改动本问卷：原问卷的这道题仍然是本地题目，不会被自动换绑成题库引用。
     * 换绑需要用户在问卷编辑里显式操作。</p>
     */
    @PostMapping("/{surveyId}/questions/{questionId}/promote")
    public ApiResponse<LibraryQuestionDetail> promoteToLibrary(
            @PathVariable String surveyId,
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @RequestParam(required = false) String changeNote) {
        return ApiResponse.success("题目已存入题库",
                questionLibraryService.promoteFromSurvey(surveyId, questionId, userId, changeNote));
    }

    /**
     * 手动把某道题换绑到题库的另一个版本。
     *
     * <p>配合问卷详情里的 {@code newerVersionAvailable} 提示使用：系统不自动升级，
     * 由用户看到提示后决定是否跟到新版本。只有草稿可以换绑。</p>
     */
    @PutMapping("/{surveyId}/questions/{questionId}/version")
    public ApiResponse<Survey> rebindQuestionVersion(
            @PathVariable String surveyId,
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @RequestParam int versionNo) {
        return ApiResponse.success("题目版本已更换",
                surveyService.rebindQuestionVersion(surveyId, questionId, versionNo, userId));
    }
}
