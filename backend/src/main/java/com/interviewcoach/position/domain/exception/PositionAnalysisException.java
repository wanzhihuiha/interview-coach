package com.interviewcoach.position.domain.exception;

/**
 * 岗位模型解析的可归类失败；message 必须是可以安全写入任务记录的脱敏说明。
 */
public class PositionAnalysisException extends RuntimeException {

    /** Worker 用于写入任务错误码的稳定失败分类，不包含 JD 或模型响应正文。 */
    private final PositionAnalysisFailureCode failureCode;

    /**
     * 创建不带底层原因的可归类解析异常。
     *
     * @param failureCode 供 Worker 分类并持久化的稳定失败代码
     * @param message 可以安全写入任务记录并返回给调用方的脱敏说明
     */
    public PositionAnalysisException(
            PositionAnalysisFailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    /**
     * 创建保留底层异常链的可归类解析异常；底层原因只用于进程内定位，不直接持久化或返回。
     *
     * @param failureCode 供 Worker 分类并持久化的稳定失败代码
     * @param message 可以安全写入任务记录并返回给调用方的脱敏说明
     * @param cause 模型调用或 JSON 处理等底层原因
     */
    public PositionAnalysisException(
            PositionAnalysisFailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    /** 返回 Worker 写入当前任务失败状态时使用的稳定分类。 */
    public PositionAnalysisFailureCode getFailureCode() {
        return failureCode;
    }
}
