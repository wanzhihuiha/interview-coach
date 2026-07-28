package com.interviewcoach.resume.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历上传响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeUploadResponse {

    private Long resumeId;
    private String fileName;
    private String status;
    private Integer parseProgress;
}
