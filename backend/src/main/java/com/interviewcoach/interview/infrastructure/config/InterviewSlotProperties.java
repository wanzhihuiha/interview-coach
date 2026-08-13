package com.interviewcoach.interview.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 {@code interview.slot} 的 Redis 面试并发槽位配置。
 * 创建服务使用它限制共享 Key 空间内的活跃会话数量并设置单槽标记过期时间。
 */
@Data
@Component
@ConfigurationProperties(prefix = "interview.slot")
public class InterviewSlotProperties {

    /**
     * 共享全局计数允许的最大并发面试数，默认 20；达到后新建会话不排队并直接返回繁忙。
     * 20 的容量依据缺失，调高会放大模型和数据库负载，调低会增加创建拒绝。
     */
    private int maxConcurrent = 20;

    /**
     * 单个面试槽位 Key 的过期分钟数，默认 10，精确依据缺失。
     *
     * <p>Key 自然过期只删除单槽标记，不会执行释放 Lua，也不会递减全局计数，因此当前实现
     * 不能把该 TTL 视为完整兜底释放；调小还会让仍进行中的面试标记提前消失。</p>
     */
    private int ttlMinutes = 10;
}
