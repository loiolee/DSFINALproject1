package com.example.surveysystem.controller;

import com.example.surveysystem.config.AuthInterceptor;
import com.example.surveysystem.dto.ApiResponse;
import com.example.surveysystem.dto.StartResponseResult;
import com.example.surveysystem.dto.SubmitAnswerRequest;
import com.example.surveysystem.service.AnswerEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ResponseController {
    /** 公开短链接前缀，符合需求中 /survey/xxxxxx 的链接形式。 */
    private static final String SHORT_LINK_PATTERN = "^[A-Za-z0-9]{1,32}$";

    private final AnswerEngineService answerEngineService;

    /**
     * 把 /survey/{publicId} 重定向到前端填写页。
     * 该路径不在 /api/** 拦截范围内，因此不需要令牌即可打开页面；
     * 是否允许填写仍由后续调用答题接口时的登录校验决定。
     */
    @GetMapping("/survey/{publicId}")
    public ResponseEntity<Void> shortLink(@PathVariable String publicId) {
        if (!publicId.matches(SHORT_LINK_PATTERN)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/index.html#/fill/" + publicId))
                .build();
    }

    @PostMapping("/api/surveys/{publicId}/start")
    public ApiResponse<StartResponseResult> start(@PathVariable String publicId,
                                                   @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success("答题会话已创建", answerEngineService.start(publicId, userId));
    }

    @PostMapping("/api/responses/{responseId}/answer")
    public ApiResponse<StartResponseResult> answer(@PathVariable String responseId,
                                                @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
                                                @Valid @RequestBody SubmitAnswerRequest request) {
        return ApiResponse.success("当前题目处理完成", answerEngineService.answer(responseId, userId, request));
    }

    @PostMapping("/api/responses/{responseId}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable String responseId,
                                                   @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success("答卷提交成功", answerEngineService.submit(responseId, userId));
    }
}
