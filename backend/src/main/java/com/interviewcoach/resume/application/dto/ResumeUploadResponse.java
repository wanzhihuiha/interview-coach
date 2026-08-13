package com.interviewcoach.resume.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历文件落盘并登记解析任务后的响应，前端据此进入状态轮询。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeUploadResponse {

    /** 新建简历记录的数据库主键。 */
    private Long resumeId;
    /** 上传时保留的原始文件名。 */
    private String fileName;
    /** 已登记解析任务的当前状态稳定编码。 */
    private String status;
    /** 当前解析状态的中文展示名。 */
    private String statusLabel;
    /** 根据当前状态映射的展示进度；精确固定值依据缺失。 */
    private Integer parseProgress;
}
