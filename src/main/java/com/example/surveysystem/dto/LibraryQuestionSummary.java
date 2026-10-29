package com.example.surveysystem.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** 题库列表的一行。不含版本内容与引用计数，避免列表页产生逐条回查。 */
@Data
@Builder
public class LibraryQuestionSummary {
    private String id;
    private String title;
    private String type;
    private Integer currentVersionNo;
    private String visibility;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
