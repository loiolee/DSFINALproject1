package com.example.surveysystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 一条共享授权的展示视图。
 *
 * <p>「我共享出去的」与「别人共享给我的」两个列表共用这个结构：
 * 前者关心 {@code granteeUsername}，后者关心 {@code ownerUsername}，
 * 用同一个视图可以让前端只写一套渲染逻辑。</p>
 *
 * <p>{@code questionStatus} 一并返回，是为了让被授权方看到「这道题已被创建者归档」。
 * 归档题目不能选进新问卷，如果列表里直接隐藏，用户会以为共享被取消了。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionShareView {
    private String id;
    private String questionId;
    private String questionTitle;
    /** active / archived，见 {@code LibraryQuestion.STATUS_*}。 */
    private String questionStatus;
    private String ownerId;
    private String ownerUsername;
    private String granteeId;
    private String granteeUsername;
    /** use / edit。 */
    private String permission;
    private Instant createdAt;
}
