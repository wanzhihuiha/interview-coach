package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.util.Objects;

/**
 * 按数据库确认的任务终态结算 Redis quota，并只在转换成功后清理数据库恢复凭据。
 */
public final class ResumeAiQuotaSettlement {

    private ResumeAiQuotaSettlement() {
    }

    /**
     * 未知数据库结果保持 fail-closed；Redis 返回 false 或抛错时不会执行凭据清理。
     */
    public static boolean settle(
            ResumeAiQuotaService quotaService,
            Long userId,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome,
            Runnable clearPersistedReservation) {
        Objects.requireNonNull(quotaService, "quotaService must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(clearPersistedReservation, "clearPersistedReservation must not be null");
        if (reservation == null) {
            return true;
        }
        if (outcome == ResumeAiTaskOutcome.UNKNOWN) {
            return false;
        }
        if (quotaService.isQuotaDateClosed(reservation)) {
            // 旧自然日已结束，残留 token 不再参与任何当前额度计算，避免 Redis Key 过期后永久阻塞。
            clearPersistedReservation.run();
            return true;
        }
        boolean transitioned = outcome == ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                ? quotaService.markSucceeded(userId, reservation)
                : quotaService.markFailed(userId, reservation);
        if (!transitioned) {
            return false;
        }
        clearPersistedReservation.run();
        return true;
    }
}
