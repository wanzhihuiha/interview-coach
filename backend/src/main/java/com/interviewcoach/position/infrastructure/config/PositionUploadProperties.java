package com.interviewcoach.position.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 岗位 JD 输入和临时提取资源参数。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "position.upload")
public class PositionUploadProperties {

    @Min(1)
    private long maxSize = 10 * 1024 * 1024L;

    @Min(1)
    private int maxCodePoints = 2000;

    @Min(1)
    private int maxPdfPages = 20;

    private Duration extractionTimeout = Duration.ofSeconds(15);

    @Min(1)
    private int extractionThreads = 2;

    @Min(1)
    private int extractionQueueCapacity = 8;

    @NotBlank
    private String tempDirectory = System.getProperty("java.io.tmpdir")
            + "/interview-coach/position-jd";

    private Duration orphanMaxAge = Duration.ofHours(24);

    @AssertTrue(message = "position.upload 时间参数必须大于 0")
    public boolean isDurationConfigurationValid() {
        return isPositive(extractionTimeout) && isPositive(orphanMaxAge);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
