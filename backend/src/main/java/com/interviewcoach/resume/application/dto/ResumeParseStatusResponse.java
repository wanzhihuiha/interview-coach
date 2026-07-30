package com.interviewcoach.resume.application.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历解析状态响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseStatusResponse {

    private Long resumeId;
    private String status;
    private String statusLabel;
    private Integer parseProgress;
    private Long parseGeneration;
    private Boolean hasConfirmedProfile;
    private String errorCode;
    private String errorMessage;
    private String analysisStatus;
    private LocalDateTime updatedAt;
}
