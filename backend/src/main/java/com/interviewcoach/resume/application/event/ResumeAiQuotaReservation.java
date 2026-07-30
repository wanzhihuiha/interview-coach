package com.interviewcoach.resume.application.event;

import java.time.LocalDate;

/**
 * 手动 AI 任务的非敏感额度上下文；日期在准入时固定，不随 Worker 跨日变化。
 */
public record ResumeAiQuotaReservation(LocalDate quotaDate, String quotaToken) {

    public ResumeAiQuotaReservation {
        if (quotaDate == null) {
            throw new IllegalArgumentException("quotaDate must not be null");
        }
        if (quotaToken == null || quotaToken.isBlank()) {
            throw new IllegalArgumentException("quotaToken must not be blank");
        }
    }
}
