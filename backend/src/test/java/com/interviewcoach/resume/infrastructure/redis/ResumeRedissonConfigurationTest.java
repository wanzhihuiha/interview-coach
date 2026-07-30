package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.test.util.ReflectionTestUtils;

class ResumeRedissonConfigurationTest {

    @Test
    void shouldBuildSingleServerConfigFromSpringRedisProperties() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("redis.internal");
        properties.setPort(6380);
        properties.setDatabase(3);
        properties.setUsername("resume-user");
        properties.setPassword("test-password");
        properties.setClientName("interview-coach-test");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setTimeout(Duration.ofSeconds(3));

        Config config = new ResumeRedissonConfiguration().buildConfig(properties);
        SingleServerConfig server = (SingleServerConfig)
                ReflectionTestUtils.getField(config, "singleServerConfig");

        assertThat(server).isNotNull();
        assertThat(server.getAddress()).isEqualTo("redis://redis.internal:6380");
        assertThat(server.getDatabase()).isEqualTo(3);
        assertThat(server.getUsername()).isEqualTo("resume-user");
        assertThat(server.getPassword()).isEqualTo("test-password");
        assertThat(server.getClientName()).isEqualTo("interview-coach-test");
        assertThat(server.getConnectTimeout()).isEqualTo(2000);
        assertThat(server.getTimeout()).isEqualTo(3000);
    }
}
