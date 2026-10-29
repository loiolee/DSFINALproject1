package com.example.surveysystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 把题目共享给另一个用户的请求。
 *
 * <p>用<b>用户名</b>而不是用户 ID 指定被授权者：用户只知道对方叫什么，
 * 让他去查内部 ID 既不合理，也容易填错成别人的 ID。</p>
 */
@Data
public class ShareQuestionRequest {
    @NotBlank(message = "被授权用户名不能为空")
    @Size(max = 50, message = "用户名不能超过 50 个字符")
    private String username;

    /** {@code use} 或 {@code edit}；留空按 {@code use} 处理。 */
    private String permission;
}
