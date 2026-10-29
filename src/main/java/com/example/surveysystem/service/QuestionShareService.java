package com.example.surveysystem.service;

import com.example.surveysystem.domain.LibraryQuestion;
import com.example.surveysystem.domain.QuestionShare;
import com.example.surveysystem.domain.User;
import com.example.surveysystem.dto.QuestionShareView;
import com.example.surveysystem.dto.ShareQuestionRequest;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.LibraryQuestionRepository;
import com.example.surveysystem.repository.QuestionShareRepository;
import com.example.surveysystem.repository.UserRepository;
import com.example.surveysystem.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 题目共享授权（需求「可以把题分享给别人使用」）。
 *
 * <p>共享的粒度是<b>题目身份</b>而不是某个版本：被授权方选中该题时仍然要指定具体版本。
 * 这样创建者发布新版本后，被授权方可以选择用新版还是继续用旧版，
 * 与「修改题目不影响已经做好的问卷」这条规则保持一致。</p>
 *
 * <p>权限模型只有两级：</p>
 * <ul>
 *   <li>{@code use}：能读取题目、能把任意版本选进自己的问卷，不能改题库内容。</li>
 *   <li>{@code edit}：在 use 基础上可以创建新版本（即调用「修改题目」）。</li>
 * </ul>
 *
 * <p>本服务只负责授权记录的读写，<b>不做访问控制判定</b>——那部分在
 * {@link QuestionAccessService}，因为题库服务与问卷服务同样需要它。</p>
 *
 * <p>无论哪一级，<b>都不能再授权给第三人、不能归档、不能看跨问卷统计</b>。
 * 这三项是创建者独有的：如果允许被授权方转授，权限链会失控；统计权限边界见 schema.md §10.4。</p>
 */
@Service
@RequiredArgsConstructor
public class QuestionShareService {
    private static final Set<String> SUPPORTED_PERMISSIONS =
            Set.of(QuestionShare.PERMISSION_USE, QuestionShare.PERMISSION_EDIT);

    private final QuestionShareRepository shareRepository;
    private final LibraryQuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final QuestionAccessService accessService;
    private final IdGenerator idGenerator;

    /** 把题目共享给指定用户；对同一人重复共享视为更新权限，不新增记录。 */
    public QuestionShareView share(String questionId, String ownerId, ShareQuestionRequest request) {
        LibraryQuestion question = accessService.requireOwned(questionId, ownerId, "只有题目创建者可以管理共享授权");

        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        User grantee = userRepository.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
        if (Objects.equals(grantee.getId(), ownerId)) {
            throw new BusinessException("不能把题目共享给自己");
        }
        String permission = normalizePermission(request.getPermission());

        QuestionShare share = shareRepository.findByQuestionIdAndGranteeId(questionId, grantee.getId())
                .orElseGet(() -> QuestionShare.builder()
                        .id(idGenerator.shortId())
                        .questionId(questionId)
                        .ownerId(ownerId)
                        .granteeId(grantee.getId())
                        .createdAt(Instant.now())
                        .build());
        // 重复共享时保留原 createdAt：授权时间是「第一次授权」的时间，不该被改权限的动作覆盖。
        share.setPermission(permission);
        try {
            shareRepository.save(share);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("授权正在被修改，请刷新后重试");
        }

        markShared(question, true);
        // 用 HashMap 而不是 Map.of：Map.of 不接受 null 值，而用户名在极端情况下可能查不到。
        Map<String, String> usernames = new HashMap<>();
        usernames.put(grantee.getId(), grantee.getUsername());
        usernames.put(ownerId, ownerUsername(ownerId));
        return toView(share, usernames, question);
    }

    /** 撤销授权。撤销后若该题已无任何授权，可见性回退为 private。 */
    public void revoke(String questionId, String ownerId, String granteeId) {
        LibraryQuestion question = accessService.requireOwned(questionId, ownerId, "只有题目创建者可以管理共享授权");
        QuestionShare share = shareRepository.findByQuestionIdAndGranteeId(questionId, granteeId)
                .orElseThrow(() -> BusinessException.notFound("该用户没有被授权使用这道题目"));
        shareRepository.delete(share);
        if (shareRepository.countByQuestionId(questionId) == 0) {
            markShared(question, false);
        }
    }

    /** 我共享出去的授权记录（题目创建者视角）。 */
    public List<QuestionShareView> listShares(String questionId, String ownerId) {
        accessService.requireOwned(questionId, ownerId, "只有题目创建者可以管理共享授权");
        return toViews(shareRepository.findByQuestionIdOrderByCreatedAtDesc(questionId));
    }

    /** 别人共享给我的题目（被授权方视角）。 */
    public List<QuestionShareView> listSharedWithMe(String granteeId) {
        return toViews(shareRepository.findByGranteeIdOrderByCreatedAtDesc(granteeId));
    }

    // ------------------------------------------------------------------ 内部工具

    /** 维护题目上的 {@code visibility} 标志，供题库列表直接展示，不必回查共享集合。 */
    private void markShared(LibraryQuestion question, boolean shared) {
        String target = shared ? LibraryQuestion.VISIBILITY_SHARED : LibraryQuestion.VISIBILITY_PRIVATE;
        if (target.equals(question.getVisibility())) {
            return;
        }
        question.setVisibility(target);
        question.setUpdatedAt(Instant.now());
        questionRepository.save(question);
    }

    private String normalizePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return QuestionShare.PERMISSION_USE;
        }
        String normalized = permission.trim().toLowerCase();
        if (!SUPPORTED_PERMISSIONS.contains(normalized)) {
            throw new BusinessException("不支持的授权级别: " + permission);
        }
        return normalized;
    }

    private String ownerUsername(String ownerId) {
        return userRepository.findById(ownerId).map(User::getUsername).orElse(null);
    }

    /** 批量转视图：用户名与题干各查一次，避免逐条回查（N+1）。 */
    private List<QuestionShareView> toViews(List<QuestionShare> shares) {
        if (shares.isEmpty()) {
            return List.of();
        }
        Set<String> userIds = new HashSet<>();
        Set<String> questionIds = new HashSet<>();
        for (QuestionShare share : shares) {
            userIds.add(share.getOwnerId());
            userIds.add(share.getGranteeId());
            questionIds.add(share.getQuestionId());
        }
        Map<String, String> usernames = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            usernames.put(user.getId(), user.getUsername());
        }
        Map<String, LibraryQuestion> questions = new HashMap<>();
        for (LibraryQuestion question : questionRepository.findAllById(questionIds)) {
            questions.put(question.getId(), question);
        }
        return shares.stream()
                .map(share -> toView(share, usernames, questions.get(share.getQuestionId())))
                .toList();
    }

    private QuestionShareView toView(QuestionShare share, Map<String, String> usernames, LibraryQuestion question) {
        return QuestionShareView.builder()
                .id(share.getId())
                .questionId(share.getQuestionId())
                .questionTitle(question == null ? null : question.getTitle())
                .questionStatus(question == null ? null : question.getStatus())
                .ownerId(share.getOwnerId())
                .ownerUsername(usernames.get(share.getOwnerId()))
                .granteeId(share.getGranteeId())
                .granteeUsername(usernames.get(share.getGranteeId()))
                .permission(share.getPermission())
                .createdAt(share.getCreatedAt())
                .build();
    }
}
