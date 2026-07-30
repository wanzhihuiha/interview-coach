package com.interviewcoach.resume.infrastructure.redis;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.ZoneId;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 简历 AI 任务的分布式准入、每日额度和上传数量参数。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "resume.ai-task")
public class ResumeAiTaskProperties {

    @Min(1)
    private int userPermits = 5;

    @Min(1)
    private int resumePermits = 1;

    private Duration acquireWait = Duration.ZERO;

    private Duration lease = Duration.ofMinutes(5);

    private Duration renewInterval = Duration.ofMinutes(1);

    @Min(1)
    private int successLimit = 5;

    @Min(1)
    private int attemptLimit = 10;

    @Min(1)
    private int retainedResumes = 5;

    @Min(1)
    private int dailyCreates = 5;

    @NotBlank
    private String zone = "Asia/Shanghai";

    @NotBlank
    private String keyPrefix = "interview-coach:resume-ai:v1";

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    @AssertTrue(message = "resume.ai-task 时间参数必须满足 acquire-wait >= 0 且 lease > renew-interval > 0")
    public boolean isLeaseConfigurationValid() {
        return acquireWait != null
                && !acquireWait.isNegative()
                && lease != null
                && renewInterval != null
                && !renewInterval.isZero()
                && !renewInterval.isNegative()
                && lease.compareTo(renewInterval) > 0;
    }
}
