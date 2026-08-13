package com.interviewcoach.resume.infrastructure.cache;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 {@code resume.parse-cache} 读取的事实解析结果缓存配置，供解析 Worker 决定是否使用 Redis 内容缓存。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resume.parse-cache")
public class ResumeParseCacheProperties {

    /** 是否启用按用户、文件摘要、Prompt 与 Schema 隔离的结果缓存；默认关闭，关闭时 Worker 直接调用模型。 */
    private boolean enabled = false;
    /** 缓存结果的 Spring Duration 生存时间；默认 7 天且依据缺失，调大延长复用窗口，调小会增加模型调用。 */
    private Duration ttl = Duration.ofDays(7);
}
