# Interview Coach

**简体中文** | [English](README.en-US.md)

Interview Coach 是一个面向求职者的 AI 模拟面试平台。系统基于用户简历和目标岗位生成个性化面试内容，在面试结束后提供评估报告与可执行的成长计划。

> 项目仍处于开发阶段。当前采用前后端分离的模块化单体架构，`gateway` 仅作为未来独立网关的占位模块。

## 当前能力

- 用户注册、登录、资料维护和 JWT 鉴权
- 简历上传、解析、确认和画像管理
- 岗位 JD 录入、解析、确认和公共岗位管理
- 个性化模拟面试、回答记录、面试报告和成长计划
- 用户、岗位、题库和审计日志的后台管理
- 多模型路由及无 API Key 时的 Mock 降级

## 技术栈

| 模块 | 主要技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.3.2、Spring Security、Spring Data JPA、Spring AI |
| 数据 | MySQL、Redis；H2 用于本地配置和测试 |
| 前端 | Vue 3、TypeScript、Vite 5、Pinia、Vue Router、Element Plus |
| 文档解析 | Apache PDFBox |

## 项目结构

```text
interview-coach/
├── backend/            # Spring Boot 后端
├── frontend/web/       # Vue Web 前端
├── gateway/            # 独立网关占位模块
├── docs/design/        # 各业务模块设计文档
└── DESIGN.md           # 项目总体设计
```

## 环境要求

- JDK 21
- Maven 3.6.3 或更高版本
- Node.js 18 或更高版本
- npm
- MySQL 8
- Redis 6 或更高版本

## 快速启动

### 1. 准备数据库

创建默认数据库：

```sql
CREATE DATABASE interview_coach
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

数据库创建完成后，先手工执行
`backend/src/main/resources/db/migration/V1__init_schema.sql`，创建当前版本所需的全部表，
再启动后端。Hibernate 仅校验实体与表结构是否一致，不会自动创建或修改数据库结构。

后端默认读取以下环境变量。请根据本机环境设置，不要将真实密码或密钥提交到仓库。

| 环境变量 | 是否必需 | 说明 |
| --- | --- | --- |
| `MYSQL_HOST` | 否 | MySQL 地址，默认 `localhost` |
| `MYSQL_PORT` | 否 | MySQL 端口，默认 `3306` |
| `MYSQL_DB` | 否 | 数据库名，默认 `interview_coach` |
| `MYSQL_USER` | 建议设置 | MySQL 用户名 |
| `MYSQL_PASSWORD` | 是 | MySQL 密码 |
| `REDIS_HOST` | 否 | Redis 地址，默认 `localhost` |
| `REDIS_PORT` | 否 | Redis 端口，默认 `6379` |
| `REDIS_PASSWORD` | 否 | Redis 密码，无密码时留空 |
| `REDIS_DB` | 否 | Redis 逻辑库编号，默认 `0` |
| `JWT_SECRET` | 建议设置 | JWT 签名密钥，应使用足够长的随机字符串 |

PowerShell 示例：

```powershell
$env:MYSQL_USER="root"
$env:MYSQL_PASSWORD="<your-password>"
$env:JWT_SECRET="<your-random-secret>"
```

### 2. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`，接口统一使用 `/api/v1` 前缀。

默认配置关闭真实大模型调用，未配置 API Key 也可以启动。需要接入真实模型时，请在 `backend/src/main/resources/application.yml` 中启用对应厂商，并通过环境变量提供 API Key：

- `DASHSCOPE_API_KEY`
- `OPENAI_API_KEY`
- `ZHIPU_API_KEY`
- `MINIMAX_API_KEY`

### 3. 启动前端

另开一个终端：

```powershell
cd frontend/web
npm install
npm run dev
```

访问 `http://localhost:5173`。开发服务器会将 `/api` 请求代理到 `http://localhost:8080`。

## 本地配置说明

`backend/src/main/resources/application-local.yml` 使用 H2 文件数据库，但当前包含开发机绝对路径，并启用了特定模型配置。使用 `local` Profile 前，请先将数据库和上传目录改为本机可写路径，并确认模型开关与 API Key 配置。

## 数据库结构管理

- MySQL 表结构由人工执行的版本化 SQL 管理，脚本位于 `backend/src/main/resources/db/migration/`。
- 首次初始化执行 `V1__init_schema.sql`；后续结构变化新增并按顺序手工执行 `V2__...sql`、`V3__...sql`。
- 已执行过的 SQL 文件不得修改，并应在部署记录中登记数据库已执行到的版本。
- 默认配置使用 `spring.jpa.hibernate.ddl-auto=validate`，启动时只校验表结构。
- `backend/src/main/resources/db/schema.sql` 是旧入口的废弃提示，不参与初始化。
- `local` 和 `test` Profile 使用 H2，分别由现有 `update` 和 `create-drop` 策略管理。

## 验证

后端测试：

```powershell
cd backend
mvn test
```

前端构建：

```powershell
cd frontend/web
npm run build
```

## 开发流程

建议从 `develop` 创建独立功能分支：

```powershell
git switch develop
git pull --ff-only
git switch -c feature/<功能名称>
```

开发完成后，将功能分支合并回 `develop`；稳定版本再由 `develop` 合并到 `master`。

## 设计文档

- [总体设计](DESIGN.md)
- [用户模块](docs/design/user-module.md)
- [简历模块](docs/design/resume-module.md)
- [岗位模块](docs/design/position-module.md)
- [面试模块](docs/design/interview-module.md)
- [成长模块](docs/design/growth-module.md)
- [数据库设计](docs/design/database-design.md)
- [前端原型](docs/design/frontend-prototype.md)
- [网关规划](gateway/README.md)
