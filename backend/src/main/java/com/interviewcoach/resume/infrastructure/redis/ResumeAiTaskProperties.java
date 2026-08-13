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
 * 从 {@code resume.ai-task} 读取的许可、租约、每日额度、简历数量、日期时区和 Redis Key 参数。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "resume.ai-task")
public class ResumeAiTaskProperties {

    /** 每个用户可同时持有的 AI 任务许可数；默认 5，精确容量依据缺失，调小会更早拒绝并发任务。 */
    @Min(1)
    private int userPermits = 5;

    /** 每份简历可同时持有的 AI 任务许可数；默认 1，精确容量依据缺失，当前用于互斥解析和辅助分析。 */
    @Min(1)
    private int resumePermits = 1;

    /** 获取每级许可时最多等待的时间；默认零表示立即返回，调大会让请求线程等待。 */
    private Duration acquireWait = Duration.ZERO;

    /** Redisson 可过期许可的租期；默认 5 分钟，精确时长依据缺失，过短会增加任务中途丢租约风险。 */
    private Duration lease = Duration.ofMinutes(5);

    /** Worker 运行期间的许可续期间隔；默认 1 分钟，精确频率依据缺失，必须短于租期。 */
    private Duration renewInterval = Duration.ofMinutes(1);

    /** 单用户、单额度日期允许成功结算的手动 AI 任务上限；默认 5，精确产品依据缺失。 */
    @Min(1)
    private int successLimit = 5;

    /** 单用户、单额度日期允许真正开始模型调用的手动任务上限；默认 10，精确产品依据缺失。 */
    @Min(1)
    private int attemptLimit = 10;

    /** 单用户允许保留的简历记录上限；默认 5，精确产品依据缺失，上传锁内按数据库计数。 */
    @Min(1)
    private int retainedResumes = 5;

    /** 单用户、单额度日期允许确认创建的简历上限；默认 5，精确产品依据缺失，删除不自动返还。 */
    @Min(1)
    private int dailyCreates = 5;

    /** 计算 AI 与创建额度自然日的时区 ID，默认上海时区。 */
    @NotBlank
    private String zone = "Asia/Shanghai";

    /**
     * 简历许可、锁和额度 Key 的公共版本前缀；当前 {@code v1} 的升级依据和兼容记录缺失，修改会隔离既有 Key。
     * 是否跨实例共享同一 Key 空间取决于部署配置。
     */
    @NotBlank
    private String keyPrefix = "interview-coach:resume-ai:v1";

    /** 将配置的时区文本解析为额度日期计算使用的 ZoneId；非法值会在装配或首次调用时失败。 */
    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /** 校验等待时间非负、租期为正且严格长于续期间隔。 */
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
