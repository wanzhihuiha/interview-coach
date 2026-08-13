package com.interviewcoach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Interview Coach 的 Spring Boot 启动入口。
 *
 * <p>JVM 调用 {@link #main(String[])} 后，由 Spring Boot 创建应用上下文并装配本项目组件；
 * 后续 HTTP、任务和持久化能力均由该上下文中的 Bean 提供。</p>
 */
@SpringBootApplication
public class InterviewCoachApplication {

    /**
     * 将 JVM 传入的启动参数交给 Spring Boot；上下文装配失败时异常向外传播并终止启动。
     *
     * @param args JVM 传入的应用启动参数
     */
    public static void main(String[] args) {
        // 由 JVM 入口触发 Spring 组件扫描和上下文装配；返回的上下文由 Spring 持续管理，main 不另存引用。
        SpringApplication.run(InterviewCoachApplication.class, args);
    }
}
