package com.example.surveysystem.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 题目共享授权（对应集合 {@code question_shares}）：题目创建者把某道题的「使用」权限授予另一个用户。
 *
 * <p>为什么单独成集合而不在 {@link LibraryQuestion} 里放一个 {@code sharedWith} 数组：</p>
 * <ul>
 *   <li>被授权方需要「别人共享给我的题目」列表，这是一个按 {@code granteeId} 的反向查询，
 *       数组内嵌无法单独建索引支撑，只能全集合扫描。</li>
 *   <li>授权与撤销是独立的高频操作，内嵌数组会让每次授权都重写整条题目文档。</li>
 *   <li>共享关系可以带上授权时间、权限级别等元数据，未来扩展不需要改题目文档结构。</li>
 * </ul>
 *
 * <p>共享的是「题目身份」而不是某个版本：被授权方选中该题时仍然要指定具体版本，
 * 这样创建者发布新版本后，被授权方可以选择用新版还是继续用旧版。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "question_shares")
public class QuestionShare {
    /** 只能读取并选入自己的问卷，不能修改题库内容。 */
    public static final String PERMISSION_USE = "use";
    /** 在 use 的基础上允许创建新版本。默认不授予，避免多人协作时互相覆盖。 */
    public static final String PERMISSION_EDIT = "edit";

    @Id
    private String id;

    /** 被共享的题目身份 {@link LibraryQuestion#getId()}。 */
    private String questionId;

    /** 授权者，冗余保存 {@link LibraryQuestion#getOwnerId()}，支撑「我共享出去的题目」列表。 */
    private String ownerId;

    /** 被授权者 users._id。 */
    private String granteeId;

    @Builder.Default
    private String permission = PERMISSION_USE;

    private Instant createdAt;

    /** 是否允许在被授权方一侧创建新版本。 */
    public boolean allowsEdit() {
        return PERMISSION_EDIT.equals(permission);
    }
}
