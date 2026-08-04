package com.interviewcoach.position.application.dto;

import lombok.Data;

/**
 * 岗位当前解析任务的轻量轮询响应。
 */
@Data
public class PositionAnalysisStatusResponse {

    private Long positionId;
    private Long taskId;
    private String latestTaskStatus;
    private String latestTaskStatusLabel;
    private Long queueAhead;
    private String analysisErrorCode;
    private String analysisErrorMessage;
    private Boolean profileUsable;
    private Boolean canConfirm;
    private Boolean canRetry;
    private Boolean archived;
}
