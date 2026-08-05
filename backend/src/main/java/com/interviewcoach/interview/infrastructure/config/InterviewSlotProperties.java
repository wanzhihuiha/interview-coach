package com.interviewcoach.interview.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面试并发槽位配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "interview.slot")
public class InterviewSlotProperties {

    /**
     * 同时在线面试人数上限，超过后新创建面试直接提示繁忙。
     */
    private int maxConcurrent = 20;

    /**
     * 单个槽位 Redis key 的过期时间（分钟），用于兜底释放异常断开的面试槽位。
     */
    private int ttlMinutes = 10;
}
