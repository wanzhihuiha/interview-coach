package com.interviewcoach.resume.domain.entity;

/**
 * 辅助分析任务的内部生成模式；INITIAL 仅用于首次免费分析。
 */
public enum ResumeProfileAnalysisMode {
    /** 首次真正调用辅助分析模型的免费内部模式，不接受 API 客户端直接提交。 */
    INITIAL,
    /** 基于当前正式事实完全重新生成，不携带旧结果或用户反馈。 */
    REGENERATE,
    /** 基于与当前事实哈希匹配的旧成功结果和敏感反馈继续调整。 */
    REFINE
}
