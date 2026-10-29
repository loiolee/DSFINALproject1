package com.example.surveysystem.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 题目详情：身份信息 + 当前版本内容 + 两个计数。
 *
 * <p>{@code usageCount} 是「有多少份问卷引用了这道题」，供修改或归档前判断影响面。
 * 已发布问卷引用的是版本快照，因此这个数字变化不会影响它们的可见内容。</p>
 */
@Data
@Builder
public class LibraryQuestionDetail {
    private String id;
    private String ownerId;
    private String title;
    private String type;
    private Integer currentVersionNo;
    private String visibility;
    private String status;
    /**
     * 请求者本人对这道题的权限级别：{@code owner} / {@code edit} / {@code use}。
     *
     * <p>页面据此决定「修改」「归档」「共享授权」等按钮是否出现。不放进前端计算的原因：
     * 前端拿不到自己的用户 ID（JWT 只带了用户名），也没有共享记录的读取权，
     * 若由页面比对 ownerId，等于把授权规则复制了第二份。</p>
     */
    private String accessLevel;
    /** 该题目一共产生过多少个版本。 */
    private long versionCount;
    /** 有多少份问卷引用了这道题。 */
    private long usageCount;
    private Instant createdAt;
    private Instant updatedAt;
    /** 当前版本的完整内容。 */
    private QuestionVersionView currentVersion;
}
