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
 * 岗位 JD 输入校验、临时文件和提取资源的 Spring 配置载体。
 * Spring 从 {@code position.upload} 绑定配置并在启动时校验；默认数值的精确产品或容量依据未在当前可靠资料中记录。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "position.upload")
public class PositionUploadProperties {

    /**
     * 单个上传文件允许实际复制的最大字节数，默认 10 MiB；调大增加磁盘和解析资源占用，调小会拒绝更多文件，精确默认值依据缺失。
     */
    @Min(1)
    private long maxSize = 10 * 1024 * 1024L;

    /**
     * 规范化 JD 文本允许的最大 Unicode code point 数，默认 2000；超出即拒绝，调大会增加模型输入，精确默认值依据缺失。
     */
    @Min(1)
    private int maxCodePoints = 2000;

    /**
     * PDF 允许的最大页数，默认 20 页；超过即拒绝，调大会增加提取耗时和内存占用，精确默认值依据缺失。
     */
    @Min(1)
    private int maxPdfPages = 20;

    /**
     * 请求线程等待后台文本提取完成的最长时间，默认 15 秒；超时只停止等待，提取 Worker 仍负责最终清理，精确默认值依据缺失。
     */
    private Duration extractionTimeout = Duration.ofSeconds(15);

    /**
     * 当前 JVM 文件提取平台线程数，默认 2；调大提高本机并发同时增加资源占用，精确默认值依据缺失。
     */
    @Min(1)
    private int extractionThreads = 2;

    /**
     * 当前 JVM 文件提取等待队列容量，默认 8 个任务；满载后新上传会收到繁忙错误，精确默认值依据缺失。
     */
    @Min(1)
    private int extractionQueueCapacity = 8;

    /**
     * 岗位上传临时文件目录，默认位于当前进程的 JVM 临时目录下；目录是否由多个实例共享取决于部署，当前证据不足。
     */
    @NotBlank
    private String tempDirectory = System.getProperty("java.io.tmpdir")
            + "/interview-coach/position-jd";

    /**
     * 启动清理可删除同前缀孤儿临时文件的最小年龄，默认 24 小时；调小可能更早清理，调大延长磁盘占用，精确默认值依据缺失。
     */
    private Duration orphanMaxAge = Duration.ofHours(24);

    /**
     * 在配置绑定阶段确认提取超时和孤儿文件年龄均为正值。
     *
     * @return 两项 Duration 非空且大于零时返回 {@code true}
     */
    @AssertTrue(message = "position.upload 时间参数必须大于 0")
    public boolean isDurationConfigurationValid() {
        return isPositive(extractionTimeout) && isPositive(orphanMaxAge);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
