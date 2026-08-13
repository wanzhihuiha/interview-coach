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
 * 岗位解析提交、当前 JVM 调度以及事务后 Redis 入队投影的配置载体。
 * Spring 从 {@code position.analysis} 绑定配置并在启动时校验；各默认值的精确产品或容量依据未在当前可靠资料中记录。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "position.analysis")
public class PositionAnalysisProperties {

    /**
     * 单个用户可同时保留的未归档个人岗位上限，默认 5 个；调大将增加可占用岗位量，调小可能更早拒绝新建，精确默认值依据缺失。
     */
    @Min(1)
    private int personalActiveLimit = 5;

    /**
     * 单个用户在 MySQL 中可同时处于 WAITING 的个人任务上限，默认 5 个；调大增加排队压力，调小更早拒绝提交，精确默认值依据缺失。
     */
    @Min(1)
    private int personalWaitingLimit = 5;

    /**
     * PUBLIC 队列参与者在 MySQL 中可同时处于 WAITING 的任务上限，默认 20 个；调大增加公共积压，调小更早拒绝管理员提交，精确默认值依据缺失。
     */
    @Min(1)
    private int publicWaitingLimit = 20;

    /**
     * 当前 JVM 可同时占用的岗位解析许可数，默认 2；调大增加本进程模型调用并发，调小降低吞吐，精确默认值依据缺失。
     */
    @Min(1)
    private int maxConcurrency = 2;

    /**
     * 同一用户两次成功登记个人解析任务之间的最短间隔，默认 5 分钟；增大或减小会直接改变提交频控，精确默认值依据缺失。
     */
    private Duration submissionInterval = Duration.ofMinutes(5);

    /**
     * 当前 JVM 调度循环遇到暂时失败后的再次唤醒延迟，默认 1 秒；过短会增加轮询压力，过长会延后可用任务领取，精确默认值依据缺失。
     */
    private Duration dispatchRetryDelay = Duration.ofSeconds(1);

    /**
     * 事务后 Redis 投影执行器的平台线程数，默认 1；调大允许并发投影，调小限制处理吞吐，精确默认值依据缺失。
     */
    @Min(1)
    private int enqueueThreads = 1;

    /**
     * 事务后 Redis 投影执行器的本地等待队列容量，默认 100 个任务；队列满时提交会被拒绝并保留 MySQL 事实，精确默认值依据缺失。
     */
    @Min(1)
    private int enqueueQueueCapacity = 100;

    /**
     * 单个 Redis 投影动作在当前事件处理中的最大尝试次数，默认 3 次；耗尽后告警并等待后续恢复，精确默认值依据缺失。
     */
    @Min(1)
    private int enqueueMaxAttempts = 3;

    /**
     * Redis 投影相邻尝试之间的等待时间，默认 200 毫秒；增大延后恢复，减小会在故障时更密集重试，精确默认值依据缺失。
     */
    private Duration enqueueRetryDelay = Duration.ofMillis(200);

    /**
     * 在配置绑定阶段验证提交间隔可为零，而调度重试必须为正、入队重试间隔可为零。
     *
     * @return 所有 Duration 非空且满足各自边界时返回 {@code true}
     */
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
