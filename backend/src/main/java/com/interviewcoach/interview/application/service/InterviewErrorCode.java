package com.interviewcoach.interview.application.service;

/**
 * 面试模块错误码。
 */
public enum InterviewErrorCode {

    /** 简历不存在；与“简历画像未就绪”复用数值 6001。 */
    RESUME_NOT_FOUND(6001),

    /** 简历正式画像未就绪；与“简历不存在”复用数值 6001。 */
    RESUME_STATUS_INVALID(6001),

    /** 岗位不存在或当前用户不可访问；与“岗位画像未就绪”复用数值 6002。 */
    POSITION_NOT_FOUND(6002),

    /** 私有岗位画像未就绪；与“岗位不存在”复用数值 6002。 */
    POSITION_STATUS_INVALID(6002),

    /** 简历仍被另一场进行中的面试锁定。 */
    RESUME_LOCKED(6003),

    /** 私有岗位仍被另一场进行中的面试锁定。 */
    POSITION_LOCKED(6004),

    /** 创建请求没有选择任何面试环节。 */
    NO_PHASE_SELECTED(6006),

    /** 创建请求包含空白或未知的环节编码。 */
    INVALID_PHASE(6107),

    /** 指定面试不存在或不属于当前登录用户。 */
    INTERVIEW_NOT_FOUND(6101),

    /** 已正常结束的面试不能继续提交回答。 */
    INTERVIEW_ENDED(6102),

    /** 已中断的面试不能继续提交回答。 */
    INTERVIEW_INTERRUPTED(6103),

    /** 回答为空白或超过当前服务允许的长度。 */
    ANSWER_INVALID(6105),

    /** 预留的无权访问错误码；当前按用户查询通常以“面试不存在”收口。 */
    NO_ACCESS(6104),

    /** 首题生成为空或面试初始化未能完成。 */
    INTERVIEW_INITIALIZATION_FAILED(6108),

    /** 创建时固化的简历或岗位画像快照缺失、损坏或无法序列化。 */
    INTERVIEW_SNAPSHOT_INVALID(6109),

    /** 面试状态、问题计数或回答 reservation 已被其他流程改变。 */
    INTERVIEW_STATE_CONFLICT(6110),

    /** Redis 并发槽位已达当前配置上限。 */
    INTERVIEW_SERVER_BUSY(6111),

    /** 面试 LLM 请求数或估算 Token 数触发 Redis 限流。 */
    LLM_RATE_LIMIT_EXCEEDED(6112);

    /** 对外业务错误码数值。 */
    private final int code;

    /** 保存枚举项对应的既有业务错误码。 */
    InterviewErrorCode(int code) {
        this.code = code;
    }

    /** 返回异常响应使用的业务错误码。 */
    public int getCode() {
        return code;
    }
}
