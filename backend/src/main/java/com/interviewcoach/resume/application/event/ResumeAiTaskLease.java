package com.interviewcoach.resume.application.event;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 一次 AI 任务持有的用户级和简历级许可。
 *
 * <p>permit id 只在进程内用于续期和释放。租约丢失后，Worker 通过本对象阻止成功结果写回。</p>
 */
public final class ResumeAiTaskLease {

    /** 用户级许可标识，由准入服务用于续期和释放。 */
    private final String userPermitId;
    /** 简历级许可标识，由准入服务用于续期和释放。 */
    private final String resumePermitId;
    /** 当前进程观察到的租约有效状态；续期失败后单向变为无效。 */
    private final AtomicBoolean valid = new AtomicBoolean(true);

    /** 创建同时持有用户级和简历级许可的本地租约，并拒绝空许可标识。 */
    public ResumeAiTaskLease(String userPermitId, String resumePermitId) {
        this.userPermitId = requirePermitId(userPermitId, "userPermitId");
        this.resumePermitId = requirePermitId(resumePermitId, "resumePermitId");
    }

    /** 返回用户级许可标识。 */
    public String userPermitId() {
        return userPermitId;
    }

    /** 返回简历级许可标识。 */
    public String resumePermitId() {
        return resumePermitId;
    }

    /** 返回当前进程是否仍允许 Worker 写回成功结果。 */
    public boolean isValid() {
        return valid.get();
    }

    /**
     * 把租约标记为已丢失。
     *
     * @return 本次调用是否首次把状态从有效改为无效
     */
    public synchronized boolean markLost() {
        return valid.compareAndSet(true, false);
    }

    /**
     * 在本地租约有效检查和短数据库写回之间建立互斥，避免续期线程已判定丢失后继续提交结果。
     *
     * @param action 仅在租约仍有效时执行的数据库写回动作
     * @return 租约有效且动作返回 {@code true} 时为 {@code true}；租约已丢失时不执行动作
     */
    public synchronized boolean executeIfValid(BooleanSupplier action) {
        Objects.requireNonNull(action, "action must not be null");
        return valid.get() && action.getAsBoolean();
    }

    /** 校验并返回非空许可标识。 */
    private static String requirePermitId(String permitId, String fieldName) {
        if (permitId == null || permitId.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return permitId;
    }
}
