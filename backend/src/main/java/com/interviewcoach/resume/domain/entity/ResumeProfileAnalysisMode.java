package com.interviewcoach.resume.domain.entity;

/**
 * 辅助分析任务的内部生成模式；INITIAL 仅用于首次免费分析。
 */
public enum ResumeProfileAnalysisMode {
    INITIAL,
    REGENERATE,
    REFINE
}
