package com.interviewcoach.growth.domain.entity;

/**
 * 持久化在成长方案记录中的内部生成状态，由应用服务控制缓存命中、重试和结果写回。
 */
public enum GrowthPlanStatus {
    /** 记录已创建或失败记录正在重试，方案内容尚不能作为完成结果返回。 */
    GENERATING,

    /** 结构化 JSON 与 Markdown 已组装并尝试保存，后续请求可直接读取缓存。 */
    COMPLETED,

    /** 本次生成路径抛出异常；后续请求会把该记录重新置为生成中并再次尝试。 */
    FAILED
}
