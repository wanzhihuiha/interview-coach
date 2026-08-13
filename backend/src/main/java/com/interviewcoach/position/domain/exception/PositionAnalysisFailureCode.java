package com.interviewcoach.position.domain.exception;

/**
 * 岗位解析任务写入数据库的稳定失败分类，不包含 JD 或模型响应正文。
 */
public enum PositionAnalysisFailureCode {
    /** Worker 收到空白或不满足分析前提的 JD 输入。 */
    INPUT_INVALID,
    /** 模型调用链被识别为超时，远端实际结果可能未知。 */
    LLM_TIMEOUT,
    /** Worker 线程或其异常链表明执行已被中断。 */
    WORKER_INTERRUPTED,
    /** 调用模型服务时发生未被更具体分类覆盖的请求失败。 */
    LLM_REQUEST_FAILED,
    /** 模型服务调用返回空值或空白文本，无法生成候选画像。 */
    LLM_EMPTY_RESPONSE,
    /** 模型返回内容无法解析为岗位画像 JSON。 */
    LLM_INVALID_JSON,
    /** 模型 JSON 已解析，但缺少当前画像契约要求的结构或取值。 */
    LLM_INVALID_PROFILE,
    /** Redis 预留参与者与数据库任务归属不一致，调度器拒绝继续执行。 */
    QUEUE_RESERVATION_INVALID,
    /** 数据库已领取任务，但虚拟线程 Worker 未能提交到执行器。 */
    WORKER_SUBMISSION_FAILED,
    /** 当前进程启动恢复发现上个进程遗留的 RUNNING 任务并将其终结。 */
    APPLICATION_RESTARTED,
    /** 现有具体分类均不能表达的未预期解析或状态写回失败。 */
    UNEXPECTED_ERROR
}
