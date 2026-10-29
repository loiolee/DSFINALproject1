package com.example.surveysystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 「这道题被哪份问卷使用」的一行记录（需求「查看某个题被哪些问卷使用」）。
 *
 * <p>粒度是<b>问卷</b>而不是题目实例：用户想知道的是「哪几份问卷用了我这道题」，
 * 同一份问卷里重复引用两次不应该出现两行。重复次数放在 {@code repeatCount} 里说明。</p>
 *
 * <p>{@code boundVersionNo} 与 {@code latestVersion} 一起回答「这份问卷用的是哪一版、
 * 是不是已经落后了」，这正是「改题不影响已做好问卷」这条规则在数据上的体现。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionUsageView {
    private String surveyId;
    private String surveyTitle;
    /** draft / published / closed。 */
    private String surveyStatus;
    private String surveyOwnerId;
    private String surveyOwnerUsername;
    /** 该问卷内引用这道题的题目实例 ID；重复引用时给出全部实例。 */
    private List<String> questionInstanceIds;
    /** 同一份问卷内引用这道题的次数。 */
    private int repeatCount;
    /** 该问卷绑定的版本号；同一问卷内多个实例绑不同版本时取最小值，并置 {@code mixedVersions}。 */
    private Integer boundVersionNo;
    /** 该问卷内的实例绑定了不同版本，无法用单一版本号概括。 */
    private boolean mixedVersions;
    /** 题目当前最新版本号，用于判断该问卷是否落后。 */
    private Integer latestVersion;
    /** 绑定的版本落后于最新版本。 */
    private boolean outdated;
    private Instant surveyUpdatedAt;
}
