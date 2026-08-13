package com.interviewcoach.resume.infrastructure.ai;

/**
 * 模型路由的三个配置槽位，由路由器转换为厂商和模型；当前通用 LLM 入口固定选择 L2。
 */
public enum ModelTier {
    /**
     * 轻量任务配置槽位；当前主源码尚无调用方。
     */
    L1,
    /**
     * 标准任务配置槽位；当前简历、岗位和面试通用模型调用使用该层级。
     */
    L2,
    /**
     * 高复杂度任务配置槽位；当前主源码尚无调用方。
     */
    L3
}
