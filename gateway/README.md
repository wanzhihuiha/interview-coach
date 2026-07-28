# gateway 模块（占位）

## 当前状态

MVP 阶段**不引入独立网关服务**。本目录仅作为未来独立网关模块的物理占位。

当前所有网关能力由 `backend` 模块自包含实现：

- 统一入口：`/api/v1/**`
- JWT 认证与鉴权：`Spring Security + JwtAuthenticationFilter`
- CORS：统一跨域配置
- 基础限流：应用内拦截器 / 过滤器
- 请求日志：统一日志记录

## 拆分触发条件

当以下条件满足时，再从 `backend` 中剥离网关能力到本模块：

1. 后端拆分为多个独立服务（user-service / resume-service / interview-service 等）
2. 多端统一接入需求强烈（PC / 小程序 / App / 开放 API）
3. 需要在入口层集中做限流、熔断、灰度、日志治理
4. 团队运维能力足以支撑额外部署节点

## 技术选型

- **网关框架**：Spring Cloud Gateway（响应式、与 Spring 生态无缝集成）
- **限流**：Redis + Lua 滑动窗口
- **熔断降级**：Resilience4j
- **配置管理**：Nacos / 本地配置

## 未来目录结构

```
gateway/
├── src/
│   └── main/
│       ├── java/com/interviewcoach/gateway/
│       │   ├── GatewayApplication.java
│       │   ├── config/
│       │   │   └── GatewayConfig.java
│       │   ├── filter/
│       │   │   ├── JwtAuthFilter.java
│       │   │   ├── RateLimitFilter.java
│       │   │   └── LogFilter.java
│       │   └── handler/
│       │       └── FallbackHandler.java
│       └── resources/
│           └── application.yml
└── pom.xml
```

## 拆分前 `backend` 需要保持的约定

1. 接口统一前缀：`/api/v1/{module}/**`
2. JWT 无状态认证，不依赖服务端 session
3. 业务模块通过标准请求头获取用户 ID（如 `X-User-Id`）
4. 模块包隔离清晰：user / resume / position / interview / growth
5. 限流键统一按 `userId` / `ip` / `api` 设计
