package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.util.Objects;

/**
 * 按数据库确认的任务终态结算 Redis quota，并在同日状态转换成功或准入日期已关闭后清理数据库恢复凭据。
 */
public final class ResumeAiQuotaSettlement {

    private ResumeAiQuotaSettlement() {
    }

    /**
     * 未知数据库结果保持失败关闭。同一额度日期内，Redis 返回 false 或抛错时不清理凭据；准入日期
     * 已关闭时跳过 Redis 状态转换并直接清理过期凭据，因为该 token 已不再参与当前额度计算。
     *
     * @param quotaService Redis 额度状态机服务
     * @param userId 额度所属用户
     * @param reservation 数据库保存的准入日期和 token；免费任务为空
     * @param outcome 数据库对当前任务终态的确认结果
     * @param clearPersistedReservation 清理数据库恢复凭据的动作
     * @return 已无需结算或已完成结算及凭据清理时为 {@code true}；终态未知或转换失败时为 {@code false}
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
