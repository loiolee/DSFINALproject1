package com.example.surveysystem.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class UpdateDeadlineRequest {
    /** 传 null 表示取消截止时间；非 null 时必须晚于当前时间。 */
    private Instant deadline;
}
