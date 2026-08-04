package com.interviewcoach.position.domain.exception;

/**
 * 岗位模型解析的可归类失败；message 必须是可以安全写入任务记录的脱敏说明。
 */
public class PositionAnalysisException extends RuntimeException {

    private final PositionAnalysisFailureCode failureCode;

    public PositionAnalysisException(
            PositionAnalysisFailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public PositionAnalysisException(
            PositionAnalysisFailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public PositionAnalysisFailureCode getFailureCode() {
        return failureCode;
    }
}
