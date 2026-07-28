package com.interviewcoach.resume.infrastructure.ai;

/**
 * LLM 模型层级，用于按任务复杂度路由到不同模型。
 */
public enum ModelTier {
    /**
     * 轻量模型：格式化、简单分类等低复杂度任务。
     */
    L1,
    /**
     * 标准模型：简历/JD 解析、画像生成等中等复杂度任务。
     */
    L2,
    /**
     * 高性能模型：深度推理、复杂评估等高复杂度任务。
     */
    L3
}
