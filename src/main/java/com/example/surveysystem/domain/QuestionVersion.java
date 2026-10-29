package com.example.surveysystem.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 题目版本（对应集合 {@code question_versions}）：一次内容冻结的完整快照，写入后不再修改。
 *
 * <p>为什么必须不可变：需求「修改题目时不要影响已经做好的问卷」要求旧问卷看到的内容永远不变。
 * 如果直接改版本内容，所有引用该版本的问卷会一起变；因此「改题」这个动作被定义为
 * <b>新增一条版本记录</b>，而不是原地更新。问卷侧引用的是具体版本 ID，天然锁定内容。</p>
 *
 * <p>两个字段刻意不放在这里：</p>
 * <ul>
 *   <li>{@code jumps}（跳转规则）：跳转目标指向「同一份问卷内的另一道题」，同一道题在 A 问卷
 *       和 B 问卷里的跳转目标必然不同，属于问卷内实例配置，放在 {@link Survey.Question#getJumps()}。</li>
 *   <li>{@code order}（展示顺序）：同样随问卷而变，也放在 {@link Survey.Question}。</li>
 * </ul>
 *
 * <p>选项与约束直接复用 {@link Survey.Option} / {@link Survey.Constraints}：两者的数据结构必须
 * 与问卷侧快照完全一致，复用同一份定义可以让「版本 → 问卷快照」的复制不需要任何字段转换，
 * 避免两套结构随时间漂移导致复制丢字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "question_versions")
public class QuestionVersion {

    /** 版本主键，即问卷快照里记录的 questionVersionId。 */
    @Id
    private String id;

    /** 所属题目身份 {@link LibraryQuestion#getId()}。 */
    private String questionId;

    /** 版本号，从 1 开始逐次递增，与 questionId 组成唯一键。 */
    private Integer versionNo;

    /** 上一版本 ID；v1 为 null。用于还原「这个题是从哪个版本改过来的」。 */
    private String parentVersionId;

    /** 仅「恢复旧版本」产生的版本才有值，指向被恢复的那个版本。 */
    private String restoredFromVersionId;

    /** single / multiple / text / number。 */
    private String type;

    private String title;

    /** 版本级默认必答性；问卷内可以按需覆盖。 */
    @Builder.Default
    private boolean required = true;

    /** 选项 ID 在版本内固定不变，跳转规则与答卷都依赖它，因此版本一旦写入不可再改。 */
    @Builder.Default
    private List<Survey.Option> options = new ArrayList<>();

    private Survey.Constraints constraints;

    /** 改动说明，便于在版本历史里区分每次修改的目的。 */
    private String changeNote;

    /** 创建该版本的用户 users._id。 */
    private String createdBy;

    private Instant createdAt;

    /** 是否为题目身份下的第一个版本。 */
    public boolean isFirstVersion() {
        return versionNo != null && versionNo == 1;
    }

    /** 由旧版本派生出来的新版本，用于区分「首次创建」与「修改」。 */
    public boolean isDerived() {
        return parentVersionId != null;
    }
}
