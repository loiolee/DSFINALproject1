package com.example.surveysystem.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 题库中的题目身份（对应集合 {@code questions}）。
 *
 * <p>第二阶段引入。第一阶段题目只是 {@link Survey} 文档里的内嵌对象，ID 仅在单份问卷内有意义，
 * 因此无法「保存下来以后再用」「共享给同事」「跨问卷统计」。本类把题目提升为独立实体：
 * 这里只保存「这是哪一道题」以及指向当前版本的指针，题目内容全部放在
 * {@link QuestionVersion}，以保证历史版本不可变。</p>
 *
 * <p>题目身份与版本的分工：</p>
 * <ul>
 *   <li>身份不变：同一道题在题库里始终只有一条 {@code questions} 记录，被多少份问卷引用都只有一条。</li>
 *   <li>版本可变：每次修改内容都新增一条 {@code question_versions}，旧版本保留，旧问卷继续指向旧版本。</li>
 * </ul>
 *
 * <p>索引统一在 {@code MongoIndexConfig} 中声明，不在本类上用 {@code @Indexed} 重复声明，
 * 避免两处定义不一致导致启动时报「索引已存在但选项不同」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "questions")
public class LibraryQuestion {
    /** 仅创建者可读写；被共享者只能读取与使用。 */
    public static final String VISIBILITY_PRIVATE = "private";
    public static final String VISIBILITY_SHARED = "shared";

    /** 归档后不再出现在选题列表，但历史问卷与已共享关系仍然有效，不做物理删除。 */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    private String id;

    /** 创建者 users._id。跨问卷统计的权限判定以此字段为准：只有创建者能看到该题在所有问卷上的聚合结果。 */
    private String ownerId;

    /** 当前版本的题干，冗余保存供题库列表直接展示，避免列表页逐条回查版本集合。 */
    private String title;

    /** 当前版本的题型，冗余保存供列表筛选。 */
    private String type;

    /** 指向 question_versions._id，是「当前版本」的唯一判定依据。 */
    private String currentVersionId;

    /** 当前版本号，冗余保存供列表展示。 */
    private Integer currentVersionNo;

    @Builder.Default
    private String visibility = VISIBILITY_PRIVATE;

    @Builder.Default
    private String status = STATUS_ACTIVE;

    /**
     * 来源问卷 ID。只有「从问卷加入题库」的题目才有值，在题库里直接创建的题目为 null。
     *
     * <p>与 {@link #sourceQuestionInstanceId} 一起构成幂等键：同一份问卷里的同一道题
     * 无论用户点多少次「加入题库」，都只会生成一条题目身份，不会产生重复题目。
     * 用「记录来源」而不是「回写问卷」来实现幂等，是为了守住「加入题库不修改原问卷」这条边界。</p>
     */
    private String sourceSurveyId;

    /** 来源问卷内的题目实例 ID，与 {@link #sourceSurveyId} 成对出现。 */
    private String sourceQuestionInstanceId;

    private Instant createdAt;

    private Instant updatedAt;

    /** 是否已被共享出去。共享明细在 {@code question_shares}，这里只是列表展示用的标志。 */
    public boolean isShared() {
        return VISIBILITY_SHARED.equals(visibility);
    }

    /** 归档的题目不能再次被选入新问卷。 */
    public boolean isSelectable() {
        return STATUS_ACTIVE.equals(status);
    }
}
