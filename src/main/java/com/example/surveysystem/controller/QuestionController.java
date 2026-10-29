package com.example.surveysystem.controller;

import com.example.surveysystem.config.AuthInterceptor;
import com.example.surveysystem.dto.ApiResponse;
import com.example.surveysystem.dto.CrossSurveyQuestionStats;
import com.example.surveysystem.dto.LibraryQuestionDetail;
import com.example.surveysystem.dto.LibraryQuestionSummary;
import com.example.surveysystem.dto.QuestionContentRequest;
import com.example.surveysystem.dto.QuestionShareView;
import com.example.surveysystem.dto.QuestionUsageView;
import com.example.surveysystem.dto.QuestionVersionDiff;
import com.example.surveysystem.dto.QuestionVersionView;
import com.example.surveysystem.dto.ShareQuestionRequest;
import com.example.surveysystem.service.CrossSurveyStatsService;
import com.example.surveysystem.service.QuestionLibraryService;
import com.example.surveysystem.service.QuestionShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题库接口。
 *
 * <p>全部挂在 {@code /api/**} 下，因此与第一阶段其它接口一样受 {@link AuthInterceptor} 保护，
 * 不需要额外的鉴权代码。</p>
 */
@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionLibraryService questionLibraryService;
    private final QuestionShareService questionShareService;
    private final CrossSurveyStatsService crossSurveyStatsService;

    /** 新建题目，自动生成 v1。 */
    @PostMapping
    public ApiResponse<LibraryQuestionDetail> create(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody QuestionContentRequest request) {
        return ApiResponse.success("题目已保存到题库", questionLibraryService.create(userId, request));
    }

    /** 我的题库。status 留空只看 active，传 all 看全部，传 archived 只看归档。 */
    @GetMapping
    public ApiResponse<List<LibraryQuestionSummary>> list(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(questionLibraryService.listMine(userId, status, keyword));
    }

    @GetMapping("/{questionId}")
    public ApiResponse<LibraryQuestionDetail> detail(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionLibraryService.detail(questionId, userId));
    }

    /** 修改题目内容：不覆盖旧版本，而是生成新版本。 */
    @PutMapping("/{questionId}")
    public ApiResponse<LibraryQuestionDetail> update(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody QuestionContentRequest request) {
        return ApiResponse.success("已生成新版本", questionLibraryService.update(questionId, userId, request));
    }

    /** 归档。不做物理删除，历史问卷与已发出的共享不受影响。 */
    @DeleteMapping("/{questionId}")
    public ApiResponse<Void> archive(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        questionLibraryService.archive(questionId, userId);
        return ApiResponse.success("题目已归档", null);
    }

    /** 版本历史，最新在前。 */
    @GetMapping("/{questionId}/versions")
    public ApiResponse<List<QuestionVersionView>> versions(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionLibraryService.versions(questionId, userId));
    }

    @GetMapping("/{questionId}/versions/{versionNo}")
    public ApiResponse<QuestionVersionView> version(
            @PathVariable String questionId,
            @PathVariable int versionNo,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionLibraryService.version(questionId, versionNo, userId));
    }

    /**
     * 版本对比：返回 fromVersionNo → toVersionNo 的内容差异。
     *
     * <p>路径用 {@code compare} 而不是把两个版本号并排，是为了让「方向」明确：
     * 差异摘要里的「由 X 改为 Y」依赖 from/to 的先后，反过来的对比结果不是同一条。</p>
     */
    @GetMapping("/{questionId}/versions/{fromVersionNo}/compare/{toVersionNo}")
    public ApiResponse<QuestionVersionDiff> compare(
            @PathVariable String questionId,
            @PathVariable int fromVersionNo,
            @PathVariable int toVersionNo,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionLibraryService.compare(questionId, fromVersionNo, toVersionNo, userId));
    }

    /** 跨问卷单题统计（仅题目创建者可见，只返回聚合结果）。 */
    @GetMapping("/{questionId}/stats")
    public ApiResponse<CrossSurveyQuestionStats> stats(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(crossSurveyStatsService.getStats(questionId, userId));
    }

    /** 这道题被哪些问卷使用（仅题目创建者可见）。 */
    @GetMapping("/{questionId}/usages")
    public ApiResponse<List<QuestionUsageView>> usages(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionLibraryService.usages(questionId, userId));
    }

    /** 恢复旧版本：以该版本内容追加一个新版本，版本号继续递增。 */
    @PostMapping("/{questionId}/versions/{versionNo}/restore")
    public ApiResponse<LibraryQuestionDetail> restore(
            @PathVariable String questionId,
            @PathVariable int versionNo,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @RequestParam(required = false) String changeNote) {
        return ApiResponse.success("已恢复并生成新版本",
                questionLibraryService.restore(questionId, versionNo, userId, changeNote));
    }

    // ------------------------------------------------------------------ 共享授权

    /** 把题目共享给另一个用户；对同一人重复共享视为更新权限级别。 */
    @PostMapping("/{questionId}/shares")
    public ApiResponse<QuestionShareView> share(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody ShareQuestionRequest request) {
        return ApiResponse.success("共享成功", questionShareService.share(questionId, userId, request));
    }

    /** 我共享出去的授权记录。 */
    @GetMapping("/{questionId}/shares")
    public ApiResponse<List<QuestionShareView>> listShares(
            @PathVariable String questionId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionShareService.listShares(questionId, userId));
    }

    /** 撤销对某个用户的授权。 */
    @DeleteMapping("/{questionId}/shares/{granteeId}")
    public ApiResponse<Void> revokeShare(
            @PathVariable String questionId,
            @PathVariable String granteeId,
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        questionShareService.revoke(questionId, userId, granteeId);
        return ApiResponse.success("已撤销共享", null);
    }

    /** 别人共享给我的题目。路径是字面量，Spring 的路径匹配会优先于 {@code /{questionId}}。 */
    @GetMapping("/shared-with-me")
    public ApiResponse<List<QuestionShareView>> listSharedWithMe(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return ApiResponse.success(questionShareService.listSharedWithMe(userId));
    }
}
