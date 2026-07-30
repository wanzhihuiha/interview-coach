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

    private final String userPermitId;
    private final String resumePermitId;
    private final AtomicBoolean valid = new AtomicBoolean(true);

    public ResumeAiTaskLease(String userPermitId, String resumePermitId) {
        this.userPermitId = requirePermitId(userPermitId, "userPermitId");
        this.resumePermitId = requirePermitId(resumePermitId, "resumePermitId");
    }

    public String userPermitId() {
        return userPermitId;
    }

    public String resumePermitId() {
        return resumePermitId;
    }

    public boolean isValid() {
        return valid.get();
    }

    public synchronized boolean markLost() {
        return valid.compareAndSet(true, false);
    }

    /**
     * 在本地租约有效检查和短数据库写回之间建立互斥，避免续期线程已判定丢失后继续提交结果。
     */
    public synchronized boolean executeIfValid(BooleanSupplier action) {
        Objects.requireNonNull(action, "action must not be null");
        return valid.get() && action.getAsBoolean();
    }

    private static String requirePermitId(String permitId, String fieldName) {
        if (permitId == null || permitId.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return permitId;
    }
}
