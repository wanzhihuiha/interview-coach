package com.interviewcoach.resume.application.event;

import java.time.LocalDate;

/**
 * 手动 AI 任务的非敏感额度上下文；日期在准入时固定，不随 Worker 跨日变化。
 *
 * @param quotaDate 任务通过额度准入时按注入时钟计算的日期，跨日结算仍使用该日期
 * @param quotaToken Redis 额度状态机中标识本次尝试的非空凭据
 */
public record ResumeAiQuotaReservation(LocalDate quotaDate, String quotaToken) {

    /** 校验额度日期和凭据，禁止创建无法结算的上下文。 */
    public ResumeAiQuotaReservation {
        if (quotaDate == null) {
            throw new IllegalArgumentException("quotaDate must not be null");
        }
        if (quotaToken == null || quotaToken.isBlank()) {
            throw new IllegalArgumentException("quotaToken must not be blank");
        }
    }
}
