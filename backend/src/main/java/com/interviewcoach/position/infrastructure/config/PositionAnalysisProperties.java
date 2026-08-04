package com.interviewcoach.position.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 岗位解析提交限制和事务后入队投影参数。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "position.analysis")
public class PositionAnalysisProperties {

    @Min(1)
    private int personalActiveLimit = 5;

    @Min(1)
    private int personalWaitingLimit = 5;

    @Min(1)
    private int publicWaitingLimit = 20;

    @Min(1)
    private int maxConcurrency = 2;

    private Duration submissionInterval = Duration.ofMinutes(5);

    private Duration dispatchRetryDelay = Duration.ofSeconds(1);

    @Min(1)
    private int enqueueThreads = 1;

    @Min(1)
    private int enqueueQueueCapacity = 100;

    @Min(1)
    private int enqueueMaxAttempts = 3;

    private Duration enqueueRetryDelay = Duration.ofMillis(200);

    @AssertTrue(message = "position.analysis 时间参数必须满足提交/重试边界")
    public boolean isDurationConfigurationValid() {
        return isNonNegative(submissionInterval)
                && isPositive(dispatchRetryDelay)
                && isNonNegative(enqueueRetryDelay);
    }

    private boolean isNonNegative(Duration duration) {
        return duration != null && !duration.isNegative();
    }

    private boolean isPositive(Duration duration) {
        return isNonNegative(duration) && !duration.isZero();
    }
}
