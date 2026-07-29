package com.interviewcoach.resume.application.event;

/**
 * 简历解析请求事件，在简历状态事务提交后交给后台线程执行。
 *
 * @param resumeId    简历 ID
 * @param userId      简历所属用户 ID
 * @param forceRefresh 是否绕过已有解析缓存
 */
public record ResumeParseRequestedEvent(Long resumeId, Long userId, boolean forceRefresh) {
}
