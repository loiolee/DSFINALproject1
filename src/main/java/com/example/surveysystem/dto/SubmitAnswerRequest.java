package com.example.surveysystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import com.fasterxml.jackson.databind.JsonNode;

/** answer 的 JSON 形态由题型决定：single=字符串，multiple=字符串数组，text=字符串，number=数字。 */
@Data
public class SubmitAnswerRequest {
    @NotBlank(message = "questionId 不能为空")
    private String questionId;
    // 可为 JSON null，以便非必答题跳过；是否允许跳过由服务端根据 required 判定。
    private JsonNode answer;
}
