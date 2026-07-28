# 网关模块详细设计

> 本文档记录网关模块的详细设计，包括功能定义、路由规则、安全策略、限流熔断等。

---

## 1. 模块概述

### 1.0 当前阶段说明（重要）

> 本文档描述的是**未来独立网关拆分后的目标设计**，用于指导当前 `backend` 模块的建设。
>
> **MVP 阶段**：不引入独立网关服务。所有网关能力（统一入口、JWT 认证、CORS、基础限流、日志）由 `backend` 模块自包含实现，前端 / 小程序直接请求 `backend` 的 `/api/v1/**` 接口。
>
> **拆分触发条件**：当用户量增长、需要多端统一接入、或后端拆分为多个微服务时，再将这些能力从 `backend` 中剥离，演进到独立的 `gateway` 模块（Spring Cloud Gateway）。

### 1.1 模块职责

| 职责 | 说明 |
|------|------|
| **统一入口** | 作为所有客户端请求的单一入口，隐藏后端服务结构 |
| **路由转发** | 根据请求路径将请求路由到对应的后端服务 |
| **协议转换** | 处理 HTTP/WebSocket 等不同协议的转换 |
| **认证鉴权** | JWT Token 验证、签名校验、权限校验 |
| **限流熔断** | 请求限流、并发控制、服务熔断降级 |
| **日志监控** | 请求日志、调用链追踪、性能指标采集 |
| **负载均衡** | 多实例请求分发（结合 LoadBalancer） |

### 1.2 模块位置

```
┌─────────────────────────────────────────────────────────────┐
│                    网关模块 (gateway-module)                   │
├─────────────────────────────────────────────────────────────┤
│  Gateway: Spring Cloud Gateway / Gateway Handler            │
│  Filter: AuthFilter, RateLimitFilter, LogFilter            │
│  RouteLocator: 路由配置管理                                   │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 模块依赖

| 依赖模块 | 说明 |
|---------|------|
| 基础设施模块 | Redis（限流计数）、MySQL（配置存储） |
| 用户模块 | 认证信息获取、Token 校验 |

### 1.4 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| **网关框架** | Spring Cloud Gateway | 响应式、高性能、与 Spring Boot 3 兼容 |
| **限流算法** | Redis + Lua | 分布式限流，支持滑动窗口 |
| **熔断器** | Resilience4j | 轻量级熔断降级 |
| **配置中心** | Nacos / 本地配置 | 路由规则集中管理 |

### 1.5 认证方案选型（JWT vs OIDC）

| 特性 | 当前方案：简单 JWT | 后续扩展：OIDC |
|------|------------------|---------------|
| Token 类型 | 自定义 Access Token | Access Token + ID Token |
| Claims | 自定义 (userId, roles) | 标准化 (sub, aud, iss, email, profile) |
| 签名算法 | HMAC-SHA (对称密钥) | RSA/ECDSA (非对称) 或 HMAC |
| 第三方 IdP | 不支持 | 支持微信、飞书、Google 等 |
| Discovery 协议 | 无 | /.well-known/openid-configuration |
| UserInfo 端点 | 无 | /userinfo 获取标准用户信息 |
| 实现复杂度 | 低 | 中高 |
| 适用场景 | 自建账户体系 | 需要第三方登录、与外部系统互操作 |

**当前决策**：MVP 阶段使用简单 JWT，理由：
- 项目初期用户体系简单，无需对接第三方 IdP
- JWT 实现轻量，运维成本低
- 微信登录已可通过 openid 直接对接，不依赖 OIDC

**后续扩展 OIDC 的触发条件**：
- 需要支持除微信外的第三方登录（飞书、企业微信、Google）
- 需要与外部合作伙伴系统实现 SSO
- 需要标准化的用户身份信息用于跨系统互操作

**OIDC 升级路径**：
1. 引入 `spring-boot-starter-oauth2-resource-server` + OIDC Discovery
2. 或直接引入 Keycloak（更完整，包含用户管理、权限控制）

---

## 2. 路由设计

### 2.1 路由规则

| 路由 ID | 路径规则 | 目标服务 | 优先级 |
|---------|---------|---------|--------|
| user-route | `/api/v1/user/**` | user-service | 1 |
| resume-route | `/api/v1/resume/**` | resume-service | 1 |
| position-route | `/api/v1/position/**` | position-service | 1 |
| interview-route | `/api/v1/interview/**` | interview-service | 1 |
| growth-route | `/api/v1/growth/**` | growth-service | 1 |
| ws-route | `/ws/**` | interview-service (WebSocket) | 2 |

### 2.2 路由配置示例

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-route
          uri: lb://user-service
          predicates:
            - Path=/api/v1/user/**
          filters:
            - StripPrefix=1
            - name: AuthFilter
            - name: RateLimitFilter
              args:
                capacity: 100
                refillRate: 10

        - id: interview-route
          uri: lb://interview-service
          predicates:
            - Path=/api/v1/interview/**
          filters:
            - StripPrefix=1
            - name: AuthFilter
            - name: RateLimitFilter
              args:
                capacity: 50
                refillRate: 5
```

### 2.3 路径重写规则

| 原始路径 | 重写后路径 | 说明 |
|---------|-----------|------|
| `/api/v1/user/login` | `/user/login` | 移除版本前缀 |
| `/api/v1/interview/session/start` | `/interview/session/start` | 移除版本前缀 |

---

## 3. 认证鉴权

### 3.1 认证流程

```
┌─────────┐     ┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│  Client │────>│   Gateway   │────>│ AuthService  │────>│ Target API  │
└─────────┘     └─────────────┘     └──────────────┘     └─────────────┘
                      │                    │                     │
                      │  1. 提取 Token     │                     │
                      │  2. 校验 Token     │                     │
                      │  3. 解析用户信息   │                     │
                      │  4. 注入上下文     │                     │
```

### 3.2 白名单接口

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/v1/user/register` | POST | 用户注册 |
| `/api/v1/user/login` | POST | 用户名密码登录 |
| `/api/v1/user/phone/login` | POST | 手机号登录 |
| `/api/v1/user/wechat/login` | POST | 微信登录 |
| `/api/v1/user/sms/send` | POST | 发送验证码 |
| `/health` | GET | 健康检查 |
| `/api/v1/doc/**` | GET | API 文档（可选） |

### 3.3 Token 校验规则

| 项目 | 说明 |
|------|------|
| **Token 来源** | 请求头 `Authorization: Bearer <token>` |
| **校验内容** | 签名、过期时间、用户状态 |
| **校验失败** | 返回 401 Unauthorized |
| **用户信息注入** | 解析后写入请求头 `X-User-Id`、`X-User-Name` |

### 3.4 认证与业务解耦设计

**核心原则**：网关负责认证，业务模块只从请求头取用户 ID，不直接依赖 JWT 校验。

#### 3.4.1 请求流程

```
┌────────┐    ┌──────────┐    ┌──────────────────┐    ┌────────────┐
│ Client │───>│  Gateway │───>│ Interview Service│───>│    DB      │
└────────┘    └──────────┘    └──────────────────┘    └────────────┘
                   │                    │
                   │ 1. 校验 JWT        │ 2. 从请求头获取 X-User-Id
                   │ 2. 注入用户信息     │    (已校验过，只取值)
                   │   到请求头         │
```

#### 3.4.2 网关注入用户信息

```java
@Component
public class AuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String token = extractToken(request);
        if (token != null && jwtUtil.validateToken(token)) {
            Claims claims = jwtUtil.parseToken(token);
            // 校验通过后，注入用户信息到请求头
            request.setAttribute("X-User-Id", claims.getSubject());
            request.setAttribute("X-User-Name", claims.get("username"));
        }
        // 后续服务直接取用，不再重复校验
        filterChain.doFilter(request, response);
    }
}
```

#### 3.4.3 业务模块获取用户

```java
@RestController
public class InterviewController {

    @GetMapping("/interview/session")
    public Response<?> getSession(
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        // 网关已校验，业务只管取值使用
        if (userId == null) {
            return Response.error(401, "未登录");
        }
        return interviewService.createSession(userId);
    }
}
```

#### 3.4.4 开发测试策略

| 场景 | 策略 | 说明 |
|------|------|------|
| **单元测试** | `@WithMockUser` | MockMvc 自动注入模拟用户 |
| **集成测试** | 放行测试路径 | 配置 `.requestMatchers("/test/**").permitAll()` |
| **本地开发** | 测试 Profile 禁用安全 | `spring.security.enabled=false` |
| **独立模块测试** | 只测业务逻辑 | 通过请求头传入用户 ID，不走网关 |

#### 3.4.5 优势

- **解耦**：业务模块不关心认证实现，只依赖标准请求头
- **可测试**：业务逻辑可独立于 JWT 验证进行测试
- **灵活切换**：未来升级到 OIDC 时，业务代码无需改动
- **职责清晰**：网关管安全，业务管业务

### 3.5 权限等级

| 等级 | 说明 | 适用场景 |
|------|------|---------|
| **PUBLIC** | 无需认证 | 白名单接口 |
| **USER** | 需要登录 | 普通用户接口 |
| **PREMIUM** | 会员专用 | 高级功能接口 |
| **ADMIN** | 管理员 | 管理后台接口 |

---

## 4. 限流熔断

### 4.1 限流策略

| 限流维度 | 限流值 | 说明 |
|---------|-------|------|
| **全局并发** | 1000 QPS | 所有请求总和 |
| **单用户** | 100 QPS | 按 Token 或 IP |
| **单接口** | 500 QPS | 按请求路径 |
| **登录接口** | 10 QPS | 防暴力破解 |

### 4.2 限流算法

**滑动窗口算法（Redis + Lua）**

```lua
-- 滑动窗口限流
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local capacity = tonumber(ARGV[3])

-- 移除窗口外的旧数据
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 统计当前窗口请求数
local count = redis.call('ZCARD', key)

if count >= capacity then
    return 0  -- 拒绝
end

-- 添加新请求
redis.call('ZADD', key, now, now .. '-' .. math.random())
redis.call('EXPIRE', key, window)

return 1  -- 通过
```

### 4.3 熔断降级

| 条件 | 触发动作 | 恢复条件 |
|------|---------|---------|
| **错误率 > 50%** | 熔断 30s | 熔断后放行 10% 请求 |
| **响应时间 > 5s** | 熔断 30s | 连续成功则恢复 |
| **并发量 > 80%** | 拒绝部分请求 | 并发下降后恢复 |

### 4.4 降级响应

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后再试",
  "data": null,
  "timestamp": 1721625600000
}
```

---

## 5. 日志与监控

### 5.1 日志内容

| 字段 | 说明 |
|------|------|
| traceId | 调用链追踪 ID |
| userId | 用户 ID（已登录时） |
| ip | 客户端 IP |
| method | HTTP 方法 |
| path | 请求路径 |
| status | 响应状态码 |
| duration | 响应耗时（ms） |
| userAgent | 客户端信息 |

### 5.2 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| QPS | 每秒请求数 | > 1000 |
| 响应时间 P99 | 99 分位响应时间 | > 2s |
| 错误率 | 5xx 比例 | > 5% |
| 限流拦截数 | 被限流的请求数 | > 100/min |

### 5.3 健康检查

| 检查项 | 说明 |
|--------|------|
| 网关自身 | /health 返回 UP |
| Redis 连接 | 限流依赖 |
| 后端服务 | 各服务路由可达性 |

---

## 6. 安全防护

### 6.1 防护策略

| 策略 | 说明 |
|------|------|
| **IP 黑名单** | 封禁恶意 IP |
| **IP 限速** | 单 IP 请求频率限制 |
| **CSRF 防护** | 验证 Origin/Referer 头 |
| **XSS 防护** | 请求参数转义 |
| **SQL 注入防护** | 参数化查询（后端负责） |
| **重放攻击防护** | 请求签名 + 时间戳 |

### 6.2 请求签名

| 参数 | 说明 |
|------|------|
| timestamp | 时间戳（毫秒） |
| nonce | 随机字符串 |
| signature | HMAC-SHA256(timestamp + nonce + secret) |

---

## 7. 接口设计

### 7.1 网关管理接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/admin/gateway/routes` | 查询所有路由 | ADMIN |
| POST | `/admin/gateway/routes` | 添加路由 | ADMIN |
| PUT | `/admin/gateway/routes/{id}` | 更新路由 | ADMIN |
| DELETE | `/admin/gateway/routes/{id}` | 删除路由 | ADMIN |
| GET | `/admin/gateway/blacklist` | IP 黑名单 | ADMIN |
| POST | `/admin/gateway/blacklist` | 添加黑名单 | ADMIN |
| DELETE | `/admin/gateway/blacklist/{ip}` | 移除黑名单 | ADMIN |

### 7.2 响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1721625600000
}
```

---

## 8. 部署架构

### 8.1 单体模式（MVP 当前方案）

> 当前不部署独立网关，所有网关能力内置于 `backend` 模块。

```
┌─────────────────────────────────────────────────────────────┐
│                      Nginx / 云 LB（可选）                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   backend (Spring Boot)                     │
│              内置：路由 · JWT 认证 · CORS · 限流 · 日志        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              MySQL / Redis / LLM / 对象存储                  │
└─────────────────────────────────────────────────────────────┘
```

### 8.2 微服务模式（扩展）

```
┌─────────────────────────────────────────────────────────────┐
│                      Nginx / 云 LB                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Gateway Cluster (2+ 实例)                       │
│              + Redis 分布式限流                              │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│ user-service  │     │interview-srv  │     │ other-service │
│   Cluster     │     │    Cluster    │     │    Cluster    │
└───────────────┘     └───────────────┘     └───────────────┘
```

---

## 9. 配置清单

### 9.1 核心配置

| 配置项 | 默认值 | 说明 |
|--------|-------|------|
| gateway.port | 8080 | 网关端口 |
| gateway.globalTimeout | 30s | 全局超时时间 |
| gateway.defaultRateLimit | 100 | 默认限流值 |
| gateway.redis.keyPrefix | gateway:ratelimit: | Redis 限流 Key 前缀 |

### 9.2 环境差异

| 环境 | 限流策略 | 日志级别 |
|------|---------|---------|
| dev | 关闭 | DEBUG |
| test | 宽松 | INFO |
| prod | 严格 | WARN |
