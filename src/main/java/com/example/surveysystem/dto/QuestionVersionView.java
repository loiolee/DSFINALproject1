package com.example.surveysystem.dto;

import com.example.surveysystem.domain.Survey;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 一个题目版本的对外视图。
 *
 * <p>刻意不包含跳转规则与展示顺序：它们属于问卷内的实例配置，版本里没有这两项。
 * 客户端把某个版本选入问卷时，选项 ID 必须原样保留，否则跳转规则与统计口径都会断掉。</p>
 */
@Data
@Builder
public class QuestionVersionView {
    private String id;
    private String questionId;
    private Integer versionNo;
    /** 上一版本；v1 为 null。 */
    private String parentVersionId;
    /** 仅「恢复旧版本」产生的版本才有值。 */
    private String restoredFromVersionId;
    private String type;
    private String title;
    private boolean required;
    private List<Survey.Option> options;
    private Survey.Constraints constraints;
    private String changeNote;
    private String createdBy;
    private Instant createdAt;
}
