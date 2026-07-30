package com.interviewcoach.resume.infrastructure.cache;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 简历事实解析缓存配置；开发和测试默认关闭。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resume.parse-cache")
public class ResumeParseCacheProperties {

    private boolean enabled = false;
    private Duration ttl = Duration.ofDays(7);
}
