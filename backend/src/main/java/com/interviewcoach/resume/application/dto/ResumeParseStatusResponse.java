package com.interviewcoach.resume.application.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端轮询简历解析及辅助分析任务时使用的状态响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseStatusResponse {

    /** 被轮询的简历数据库主键。 */
    private Long resumeId;
    /** 当前解析状态的稳定英文编码。 */
    private String status;
    /** 当前解析状态的中文展示名。 */
    private String statusLabel;
    /** 根据解析状态映射的展示进度；当前使用 0、10、50、100，精确取值依据缺失。 */
    private Integer parseProgress;
    /** 当前解析任务代次，用于客户端提交草稿时防止覆盖新结果。 */
    private Long parseGeneration;
    /** 是否已存在可供后续面试使用的正式事实画像。 */
    private Boolean hasConfirmedProfile;
    /** 最近一次解析错误的稳定编码；当前无错误时为空。 */
    private String errorCode;
    /** 最近一次解析错误的用户可见说明；当前无错误时为空。 */
    private String errorMessage;
    /** 当前辅助分析任务状态的英文枚举名；尚无分析记录时为空。 */
    private String analysisStatus;
    /** 简历记录最后更新时间，供轮询端判断状态刷新时间。 */
    private LocalDateTime updatedAt;
}
