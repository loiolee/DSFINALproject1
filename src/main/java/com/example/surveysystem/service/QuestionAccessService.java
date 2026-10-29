package com.example.surveysystem.service;

import com.example.surveysystem.domain.LibraryQuestion;
import com.example.surveysystem.domain.QuestionShare;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.LibraryQuestionRepository;
import com.example.surveysystem.repository.QuestionShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 题目的访问控制：判断某个用户对某道题能做什么。
 *
 * <p>第二阶段把这段判定单独抽出来，原因是它同时被三个服务需要：题库服务（读题目与版本）、
 * 问卷服务（引用别人的题目、换绑版本）、共享服务（只有创建者能改授权）。
 * 如果留在共享服务里，题库和问卷服务就得依赖「共享」这个和它们无关的概念，
 * 而且同一套 owner / grantee 判定会被复制成好几份，改一处漏一处。</p>
 *
 * <p>四种角色：创建者、被授权者（use）、被授权者（edit）、无关用户。判定顺序固定为
 * 「先看是不是创建者，再看有没有授权记录」，避免多查一次共享集合。</p>
 *
 * <p>题目不存在与无权限返回<b>不同</b>的错误：前者 404、后者 403。
 * 这样被授权者能区分「这道题被删了」和「我没被授权」，而不是一律看到「不存在」。
 * 注意 404/403 的区分本身不泄露信息——调用方已经知道题目 ID 才能发起请求。</p>
 */
@Service
@RequiredArgsConstructor
public class QuestionAccessService {

    /** 创建者：读写、归档、授权、看统计全都可以。 */
    public static final String LEVEL_OWNER = "owner";
    /** 被授予 edit：能读、能追加新版本，但不能归档、不能转授、不能看跨问卷统计。 */
    public static final String LEVEL_EDIT = "edit";
    /** 被授予 use：能读、能选进自己的问卷，不能改内容。 */
    public static final String LEVEL_USE = "use";

    private final LibraryQuestionRepository questionRepository;
    private final QuestionShareRepository shareRepository;

    /** 能否读取：创建者，或被授权者（不论 use 还是 edit）。 */
    public boolean canRead(String questionId, String userId) {
        if (questionId == null || userId == null) {
            return false;
        }
        LibraryQuestion question = questionRepository.findById(questionId).orElse(null);
        return question != null && hasReadAccess(question, userId);
    }

    /** 校验读权限并返回题目。 */
    public LibraryQuestion requireReadable(String questionId, String userId) {
        return requireReadable(questionId, userId, "无权访问该题目");
    }

    /**
     * 校验读权限并返回题目，拒绝消息由调用方给出。
     *
     * <p>引用题目与查看题目是两种不同的动作：前者是「无权使用」，后者是「无权访问」。
     * 用同一句提示会让用户分不清是自己没被授权，还是这道题不属于自己。</p>
     */
    public LibraryQuestion requireReadable(String questionId, String userId, String forbiddenMessage) {
        LibraryQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> BusinessException.notFound("题目不存在"));
        if (!hasReadAccess(question, userId)) {
            throw BusinessException.forbidden(forbiddenMessage);
        }
        return question;
    }

    /** 校验编辑权限并返回题目：创建者，或持有 edit 权限的被授权者。 */
    public LibraryQuestion requireEditable(String questionId, String userId) {
        LibraryQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> BusinessException.notFound("题目不存在"));
        if (!hasEditAccess(question, userId)) {
            throw BusinessException.forbidden("无权修改该题目");
        }
        return question;
    }

    /**
     * 校验创建者身份并返回题目。
     *
     * <p>{@code forbiddenMessage} 由调用方给出，因为不同操作的拒绝理由不同：
     * 归档是「只有创建者可以归档」，看使用情况是「只有创建者可以查看使用情况」。
     * 统一成一句「无权访问」会让用户不知道到底缺什么权限。</p>
     */
    public LibraryQuestion requireOwned(String questionId, String userId, String forbiddenMessage) {
        LibraryQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> BusinessException.notFound("题目不存在"));
        if (!Objects.equals(question.getOwnerId(), userId)) {
            throw BusinessException.forbidden(forbiddenMessage);
        }
        return question;
    }

    /** 读权限判定：创建者直接放行，否则查一次共享记录。 */
    private boolean hasReadAccess(LibraryQuestion question, String userId) {
        if (Objects.equals(question.getOwnerId(), userId)) {
            return true;
        }
        return shareRepository.existsByQuestionIdAndGranteeId(question.getId(), userId);
    }

    /**
     * 当前用户对这道题的权限级别：{@code owner} / {@code edit} / {@code use}；无关用户返回 {@code null}。
     *
     * <p>页面需要据此决定「修改」「共享授权」这些按钮出不出现。这个结论必须由服务端给出：
     * 前端并不知道登录用户的 ID（JWT 里只有用户名），也没有共享记录的访问权，
     * 让页面自己推断权限，只会把授权规则复制出第二份实现，且一定与后端不同步。</p>
     */
    public String accessLevel(LibraryQuestion question, String userId) {
        if (question == null || userId == null) {
            return null;
        }
        if (Objects.equals(question.getOwnerId(), userId)) {
            return LEVEL_OWNER;
        }
        return shareRepository.findByQuestionIdAndGranteeId(question.getId(), userId)
                .map(share -> share.allowsEdit() ? LEVEL_EDIT : LEVEL_USE)
                .orElse(null);
    }

    /** 编辑权限判定：创建者直接放行，否则要求共享记录带 edit 级别。 */
    private boolean hasEditAccess(LibraryQuestion question, String userId) {
        if (Objects.equals(question.getOwnerId(), userId)) {
            return true;
        }
        return shareRepository.findByQuestionIdAndGranteeId(question.getId(), userId)
                .map(QuestionShare::allowsEdit)
                .orElse(false);
    }
}
