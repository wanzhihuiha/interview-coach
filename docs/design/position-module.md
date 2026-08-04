# 岗位模块详细设计

> 本文档描述当前岗位生命周期实现。岗位解析采用“请求快速登记、MySQL 保存事实、Redis 公平排队、后台生成候选、人工确认发布”的模型。

## 1. 模块边界

### 1.1 职责

| 职责 | 当前实现 |
|---|---|
| JD 输入 | 支持粘贴文本或上传 PDF/TXT；前后端均校验，后端为最终判定方 |
| 解析任务 | 每个岗位最多保留一条当前任务，只使用 `WAITING/RUNNING/SUCCEEDED/FAILED` |
| 公平调度 | MySQL 保存任务事实；Redis 按参与者轮转，参与者内部按任务 ID 先进先出 |
| 画像发布 | Worker 只生成候选画像；个人所有者或管理员确认后才写入正式画像 |
| 生命周期 | 个人岗位与公共岗位分开管理，支持重新解析、归档和受保护的永久删除 |
| 面试隔离 | 创建面试时保存岗位名称、公司、类别和正式画像快照，历史面试不再依赖岗位记录 |

### 1.2 代码落点

| 层次 | 主要对象 | 职责 |
|---|---|---|
| HTTP | `PositionController`、`PositionAdminController` | 分离个人/公共入口和角色边界 |
| 用例编排 | `PositionService`、`PositionSubmissionService`、`PositionLifecycleService` | 输入编排、提交、读取、确认、归档和删除 |
| 状态事务 | `PositionAnalysisStateService` | 用短事务维护当前任务、候选和正式画像 |
| 调度执行 | `PositionAnalysisDispatcher`、`PositionAnalysisWorker` | 取得容量、领取任务、创建虚拟线程并在事务外调用模型 |
| Redis 投影 | `PositionAnalysisRedisQueue` | 参与者轮转、参与者内 FIFO、busy 标记和等待量估算 |
| 文件提取 | `JdFileExtractionService`、`JdTextExtractor` | 受限临时文件、真实格式检查、文本提取和清理 |
| 模型 | `JdAnalysisAgent` | 调用 `LlmService`，解析并校验候选画像；不生成成功兜底画像 |
| 启动恢复 | `PositionAnalysisRecoveryRunner`、`PositionAnalysisRecoveryService` | 收口遗留 `RUNNING`，重建 Redis 后再开放调度 |

### 1.3 不在本模块实现

- 不保存解析任务历史，不增加 `SUPERSEDED`、`CANCELLING`、`CANCELLED`、`TIMED_OUT` 或 `INTERRUPTED` 状态。
- 不使用 MQ、Outbox 或持续扫描数据库作为正常调度热路径。
- 不实现多实例调度租约、跨实例总并发、CPA 多账号/Key 路由或底层 HTTP 取消。
- 不恢复归档岗位，不把个人岗位转换为公共岗位。
- 不回填或兼容旧开发数据；V4 执行前应清理无需保留的数据或重建开发库。

## 2. 核心业务事实

### 2.1 岗位、任务和画像相互独立

页面和服务端必须同时判断两个事实：

1. `profileUsable`：`position_profile` 是否存在，决定岗位能否创建新面试。
2. `latestTaskStatus`：当前解析任务走到哪一步，决定是否继续轮询、确认或重试。

重新解析不会删除正式画像。新任务处于 `WAITING`、`RUNNING` 或 `FAILED` 时，旧正式画像仍可用于面试；只有候选被确认后才替换正式画像。

### 2.2 当前任务状态

| 状态 | 含义 | 允许的后续动作 |
|---|---|---|
| 无任务 | 没有当前候选或失败记录 | 活动且可管理时可发起重新解析 |
| `WAITING` | 已落库，等待公平调度 | 轮询；归档会删除任务 |
| `RUNNING` | 已取得本地容量并被数据库领取 | 轮询；归档保留任务到远程调用真实结束 |
| `SUCCEEDED` | 候选画像已保存，等待确认 | 携带精确 `taskId` 确认，或重新解析替换当前任务 |
| `FAILED` | 本轮失败，错误原因已脱敏保存 | 重新解析替换当前任务 |

状态流转只发生为：

```text
无任务 -> WAITING -> RUNNING -> SUCCEEDED
                         \-> FAILED
SUCCEEDED --确认--> 删除任务 + 写入/替换正式画像
SUCCEEDED/FAILED --重新解析--> 删除旧任务 -> 新 WAITING
```

归档和删除是生命周期动作，不是任务状态：

- 归档 `WAITING/SUCCEEDED/FAILED` 岗位时直接删除当前任务。
- 归档 `RUNNING` 岗位时保留任务；Worker 真正结束后丢弃结果并删除任务。
- 确认成功后删除当前任务，不保留“已确认任务”。

## 3. 数据模型

### 3.1 `position`

| 字段 | 用途 |
|---|---|
| `id` | 岗位 ID |
| `user_id` | 个人岗位所有者；公共岗位为 `NULL` |
| `position_name/company_name/location/salary_range` | 岗位基础信息 |
| `job_category/level` | 岗位类别与等级；确认候选时可同步候选中的等级 |
| `jd_content` | 规范化后的 JD 文本，不保存上传原文件路径 |
| `is_public` | `false` 为个人岗位，`true` 为管理员公共岗位 |
| `archived_at` | 非空表示已归档 |
| `lock_interview_id` | 个人岗位进行中面试的精确锁；公共岗位不使用全局面试锁 |
| `parse_status/audit_*` | V1 遗留列，仅为结构兼容保留；新 API、发布和权限逻辑不得读取 |

### 3.2 `position_analysis_task`

每个岗位最多一行，由 `uk_position_analysis_task_position(position_id)` 保证。

| 字段 | 用途 |
|---|---|
| `id` | 当前任务 ID，同时作为参与者内部 FIFO 和启动恢复的稳定排序号 |
| `position_id` | 当前任务所属岗位，唯一 |
| `request_user_id` | 发起人审计信息，不作为资源授权依据 |
| `queue_owner` | 服务端生成：个人为 `USER:<userId>`，所有公共任务统一为 `PUBLIC` |
| `status` | 四状态之一 |
| `candidate_profile_data` | 仅 `SUCCEEDED` 使用的候选画像 JSON |
| `error_code/error_message` | `FAILED` 的稳定分类和脱敏说明 |
| `started_at/finished_at` | 实际领取和终态时间 |
| `created_at/updated_at` | 创建和更新时间 |

任务表没有 generation、queueTicket、画像版本、取消标记、deadline、执行实例或任务历史字段。

### 3.3 `position_profile`

`position_id` 唯一，只保存已确认的正式画像。候选画像只在当前任务表中；公共画像的 `user_id` 为 `NULL`，不能用该字段解释管理员所有权。

### 3.4 `interview` 岗位快照

V4 新增：

- `position_name_snapshot`：岗位名称，非空。
- `company_name_snapshot`：公司名称，可空。
- `job_category_snapshot`：岗位类别，非空。
- 既有 `position_profile`：创建面试时的正式岗位画像 JSON。

面试准备事务只有在活动个人岗位属于当前用户，或公共岗位已确认且未归档时才创建快照。面试运行、详情、报告、Coordinator 和成长方案读取快照，不再回查实时岗位名称、公司、类别或画像。

## 4. 创建、上传和提交限制

### 4.1 输入校验

- 粘贴和文件文本统一去除开头 BOM、统一换行、清理首尾空白。
- 规范化后必须包含 `1..2000` 个 Unicode code point；模型输出不受此限制。
- 上传实际大小上限为 10 MiB，仅支持 PDF/TXT。
- 前端校验用于尽早提示；后端重新检查实际字节、内容特征、编码、PDF 页数和提取结果。

### 4.2 文件提取和清理

1. 服务端在专用临时目录创建带 `position-jd-` 前缀的随机文件名。
2. 请求线程流式复制并执行实际字节上限，不信任客户端文件名或 `Content-Length`。
3. 有界提取执行器接管后成为唯一清理责任人；提交执行器失败前仍由请求线程清理。
4. TXT 使用严格 UTF-8 解码，拒绝 PDF 签名和二进制控制字符。
5. PDF 检查前 1024 字节内的 `%PDF-` 签名、加密状态和最多 20 页，并使用 PDFBox 提取。
6. 请求等待超时或中断只停止等待，不能提前释放仍在执行的提取槽位；Worker 在 `finally` 删除临时文件。
7. 启动时只清理本模块目录内、带固定前缀且超过 `orphan-max-age` 的孤儿文件。

上传原文件不进入岗位持久卷，也不在岗位表保存路径。

### 4.3 个人提交

个人新建和重新解析共享频控。事务第一条数据库读取锁定 `sys_user` 行，然后检查：

- 新建时未归档个人岗位少于 5 个；重新解析不重复检查岗位数量。
- `queue_owner=USER:<userId>` 的 `WAITING` 任务少于 5 个。
- 距上次成功提交不少于 5 分钟。
- 重新解析目标属于本人、未归档，且当前任务不是 `WAITING/RUNNING`。

事务成功写入岗位/任务后才更新时间字段；输入、文件、事务失败均不消耗频控。个人提交不使用 Redis 锁。

### 4.4 公共提交

管理员入口先取得固定 Redisson `PUBLIC` 提交锁，再在短事务中检查公共 `WAITING` 数少于配置上限（默认 20）并写入公共岗位和任务。Redis 不可用或锁未取得时失败关闭；公共任务不占管理员的个人岗位数、个人等待数或 5 分钟频控。

### 4.5 事务后入队

数据库提交后发布轻量事件，有界入队执行器将 `taskId + queueOwner` 幂等投影到 Redis。入队失败会有限重试；耗尽后保留 MySQL `WAITING` 并告警，不回滚已经成功的 HTTP 提交。当前实现的兜底重建发生在下次应用启动，不承诺运行中持续扫描 MySQL 修复投影。

## 5. Redis 公平队列和调度

### 5.1 参与者

- 个人：`USER:<userId>`。
- 公共：全部公共任务共享一个 `PUBLIC` 参与者，因此每轮最多运行一个公共任务。
- 每个参与者的任务 ZSet 以 `taskId` 为 score/member，保证参与者内部 FIFO。
- 全局 ready List 保存参与者轮转顺序；ready Set 防重复；busy Hash 保证一个参与者同时最多运行一个任务。

示例：A 有 A1/A2，B 有 B1，公共有 P1/P2，则可形成 `A1 -> B1 -> P1 -> A2 -> P2`；新参与者加入当时队尾。

### 5.2 领取顺序

调度器由单个平台线程串行执行：

1. `Semaphore.tryAcquire()` 取得本机岗位解析容量；没有容量时不取队列、不访问数据库、不创建线程。
2. Redis Lua 原子弹出 ready 参与者的最小 `taskId`，并在 busy Hash 记录预留。
3. MySQL 短事务按 `Position -> PositionAnalysisTask` 锁顺序核对岗位存在、未归档、queue owner 正确且任务仍为 `WAITING`，再条件更新为 `RUNNING`。
4. DB 未领取到任务时，清理或恢复 Redis 预留并归还容量。
5. 领取成功后才向专用虚拟线程执行器提交 Worker。
6. 虚拟线程提交失败时把 `RUNNING` 收口为 `FAILED`，再完成 Redis 和容量清理。

### 5.3 Worker 和结果写回

- Worker 只携带数据库读取出的不可变 `AnalysisInput`，模型调用位于事务外。
- 岗位模块调用 `LlmService.chat`，不自行建立总 deadline 字段；最终调用上限由共享 CPA/LLM 层契约提供。
- 空响应、非法 JSON、画像必需结构缺失、模型异常或超时都写成 `FAILED`，不创建基础兜底画像冒充成功。
- 成功或失败写回再次锁定当前 `Position -> Task`，只接受同一 `taskId` 且源状态仍为 `RUNNING`。
- 岗位已归档时删除任务并丢弃迟到结果；不会覆盖正式画像。
- 只有模型调用、状态收口和必要清理真实结束后，才清除 Redis busy、让仍有等待任务的参与者回到队尾、释放本地容量并唤醒下一轮。
- 若数据库终态无法确认，保留 Redis busy，等待下次启动恢复，避免同一参与者继续领取。

`queueAhead` 由当前 busy 数、ready 位置和参与者内 rank 估算，只是页面快照，不承诺完成时间；Redis 不可用时状态接口仍返回 MySQL 状态，`queueAhead` 可为空。

## 6. 确认、重新解析、归档和删除

### 6.1 确认候选

个人确认必须命中本人活动个人岗位；公共确认必须通过管理员入口并命中活动公共岗位。短事务要求：

- 请求 `taskId` 与岗位当前任务 ID 完全一致。
- 当前任务为 `SUCCEEDED`，候选 JSON 存在。
- 确认画像可以序列化。

随后写入或替换 `position_profile`，同步岗位等级，并删除当前任务。公共岗位从正式画像写入成功起立即对普通用户公开，不存在额外审核状态。

### 6.2 重新解析

- 只允许当前任务为空、`SUCCEEDED` 或 `FAILED`。
- 事务内删除旧终态任务并 `flush()`，再创建新的 `WAITING`，以数据库唯一约束保证一岗位一任务。
- 不保存旧任务、旧候选或 generation；旧正式画像保持可用。
- 个人重新解析继续受等待数和 5 分钟限制，但不受“已有 5 个岗位”误拦；公共重新解析继续受 `PUBLIC` 提交锁和公共等待上限保护。

### 6.3 归档

- 个人归档先锁用户行再锁本人岗位；公共归档只允许管理员接口。
- 重复归档幂等成功。
- 立即写 `archived_at`，个人岗位立即释放未归档数量名额并禁止新面试。
- `WAITING/SUCCEEDED/FAILED` 任务直接删除；`WAITING` 的 Redis 投影在事务提交后异步移除。
- `RUNNING` 任务保持不变，不设置取消标记；Worker 返回后发现岗位归档，删除任务并丢弃结果，再释放容量。
- 正式画像保留，历史面试继续使用快照；本阶段不提供恢复使用。

### 6.4 永久删除

永久删除必须同时满足：

- 岗位已归档。
- 当前任务不是 `RUNNING`。
- 不存在该岗位的 `IN_PROGRESS` 面试。

事务按 `Position -> Task -> Profile` 删除当前任务、正式画像和岗位，不删除任何历史 `interview`。已结束面试不阻止删除，因为运行、详情、报告和成长方案均读取面试快照。永久删除不可恢复。

## 7. 启动恢复

启动期间调度器保持关闭：

1. 按任务 ID 升序读取所有当前任务。
2. 岗位不存在或已归档：删除任务。
3. 遗留 `RUNNING`：改为 `FAILED`，错误码 `APPLICATION_RESTARTED`，不自动重跑。
4. `WAITING`：核对 `queue_owner`；无效则改为 `FAILED`，有效则加入恢复快照。
5. 清空本 Goal 的 Redis ready、busy、owner 索引和参与者任务集合。
6. 将恢复快照与启动期间到达的本地 pending 入队事件合并，按 `taskId` 升序重建。
7. Redis 重建成功后才启动调度器。

启动恢复失败会阻止应用完成该 Runner，不允许在未知队列状态下开放岗位调度。当前没有 Redis 重连监听或周期性数据库补扫；运行中投影失败依赖告警、惰性清理和下次启动重建。

## 8. 权限和可见性

| 场景 | 规则 |
|---|---|
| 个人岗位 | 只有所有者可查看任务/候选、确认、重解析、归档和永久删除；他人资源按不存在处理 |
| 公共岗位管理 | `/api/v1/admin/positions/**` 类级要求 `ADMIN`，服务/查询再次限制 `is_public=true` |
| 普通用户公共读取 | 只返回未归档且正式画像存在的公共岗位，不暴露任务、候选、失败详情或管理员发起人 |
| 面试可选岗位 | `/positions/accessible` 只返回本人活动且正式画像可用的个人岗位，以及已发布公共岗位 |
| 授权依据 | `queue_owner`、`request_user_id` 和 `PositionProfile.userId` 都不是公共资源授权依据 |

日志只记录任务 ID、阶段、失败分类、异常类型和耗时，不记录 JD 正文、模型响应或完整候选画像。

## 9. HTTP 接口

### 9.1 个人与公共只读接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/positions` | 粘贴 JD 创建个人岗位和 `WAITING` 任务 |
| `POST` | `/api/v1/positions/upload` | 上传 PDF/TXT 创建个人岗位和任务 |
| `GET` | `/api/v1/positions?archived=false|true` | 本人个人岗位活动/归档列表 |
| `GET` | `/api/v1/positions/public` | 已发布公共岗位列表 |
| `GET` | `/api/v1/positions/accessible` | 可创建面试的个人+公共岗位 |
| `GET` | `/api/v1/positions/{id}` | 本人个人岗位，或已发布公共岗位详情 |
| `GET` | `/api/v1/positions/{id}/profile` | 本人可见正式+候选；普通公共读取只见正式画像 |
| `GET` | `/api/v1/positions/{id}/analysis-status` | 仅本人个人岗位的轻量轮询状态 |
| `PUT` | `/api/v1/positions/{id}/confirm` | 所有者携带当前 `taskId` 确认候选 |
| `PUT` | `/api/v1/positions/{id}/reparse` | 所有者重新解析 |
| `PUT` | `/api/v1/positions/{id}/archive` | 幂等归档本人岗位 |
| `DELETE` | `/api/v1/positions/{id}` | 永久删除本人已归档岗位 |

### 9.2 管理员公共岗位接口

`/api/v1/admin/positions` 提供与个人生命周期对应的创建、上传、列表、详情、画像、轮询、确认、重新解析、归档和永久删除接口。管理员入口不提供个人岗位审核或个人转公共能力。

### 9.3 关键契约

创建成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "positionId": 101,
    "positionName": "Java 后端工程师",
    "taskId": 7001,
    "latestTaskStatus": "WAITING"
  }
}
```

轮询状态：

```json
{
  "positionId": 101,
  "taskId": 7001,
  "latestTaskStatus": "WAITING",
  "latestTaskStatusLabel": "排队中",
  "queueAhead": 3,
  "profileUsable": true,
  "canConfirm": false,
  "canRetry": false,
  "archived": false
}
```

确认请求：

```json
{
  "taskId": 7001,
  "profile": {
    "basicInfo": { "title": "Java 后端工程师", "level": "高级" },
    "requiredSkills": [],
    "preferredSkills": [],
    "probingDirections": [],
    "interviewFocus": ["系统设计"],
    "confidenceLevel": 0.9
  }
}
```

公共响应中的任务相关字段为空，普通用户不能据此推断公共候选或失败状态。

## 10. 错误码

| 范围 | 代表错误 |
|---|---|
| `5001..5005` | 岗位名/JD/分页/画像输入错误 |
| `5101..5115` | 岗位不存在、归属、限额、频控、任务过期、归档、基础设施、运行任务和进行中面试守卫 |
| `5201..5209` | 文件读取、大小、类型/内容、PDF 页数/加密、提取繁忙/超时/中断 |

任务表的失败分类使用稳定字符串，例如 `LLM_TIMEOUT`、`LLM_REQUEST_FAILED`、`LLM_EMPTY_RESPONSE`、`LLM_INVALID_JSON`、`LLM_INVALID_PROFILE`、`WORKER_SUBMISSION_FAILED`、`APPLICATION_RESTARTED` 和 `UNEXPECTED_ERROR`。页面只展示脱敏后的安全说明。

## 11. 配置

```yaml
position:
  analysis:
    personal-active-limit: 5
    personal-waiting-limit: 5
    public-waiting-limit: 20
    max-concurrency: 2
    submission-interval: 5m
    dispatch-retry-delay: 1s
    enqueue-threads: 1
    enqueue-queue-capacity: 100
    enqueue-max-attempts: 3
    enqueue-retry-delay: 200ms
  upload:
    max-size: 10485760
    max-code-points: 2000
    max-pdf-pages: 20
    extraction-timeout: 15s
    extraction-threads: 2
    extraction-queue-capacity: 8
    temp-directory: ${POSITION_UPLOAD_TEMP_DIR:${java.io.tmpdir}/interview-coach/position-jd}
    orphan-max-age: 24h
```

岗位模块没有单独的 LLM 总 deadline 配置。CPA/LLM 的最终调用上限由共享模型层负责；岗位 Worker 不在远程调用真实结束前释放容量。

## 12. 前端状态与轮询

- 用户岗位页提供个人、公共和归档视图；个人/管理员按能力字段显示确认、重试、归档和永久删除按钮。
- 管理端是独立“公共岗位管理”，确认正式画像后立即公开，不再展示个人岗位审核流程。
- `WAITING/RUNNING` 每 2 秒轮询；无固定总时长上限。
- 页面隐藏时清理定时器并中止请求；恢复可见时立即继续；组件卸载时清理全部轮询。
- `SUCCEEDED/FAILED` 停止后台轮询；`SUCCEEDED` 提示确认，`FAILED` 提示重试。
- 前端 PDF 使用 `pdfjs-dist` 按需加载并检查签名、加密、页数和提取文本；后端仍重新执行权威校验。

## 13. V4 手工执行说明

V4 是真实或持久开发数据库变更，必须由用户执行，AI 不代执行。

1. 确认目标为可丢弃或已备份的开发 MySQL，停止依赖该库的应用写入。
2. 当前方案不回填旧 `interview`；若已有旧面试数据，先导出备份后清空相关开发数据，或直接重建开发库。
3. 新库按 V1、V2、V3、V4 顺序执行；已有 V1～V3 的开发库只追加执行 `V4__position_analysis_lifecycle.sql`，不得修改已执行迁移。
4. 执行后核对 `position.archived_at`、`sys_user.last_position_analysis_submitted_at`、三个 `interview.*_snapshot` 字段、`position_analysis_task` 四状态约束及唯一/查询索引。
5. 再启动应用，让 `spring.jpa.hibernate.ddl-auto=validate` 校验映射；启动和真实联调需另行确认。

建议核对 SQL：

```sql
SHOW COLUMNS FROM `position` LIKE 'archived_at';
SHOW COLUMNS FROM sys_user LIKE 'last_position_analysis_submitted_at';
SHOW COLUMNS FROM interview LIKE '%_snapshot';
SHOW CREATE TABLE position_analysis_task;
```

V4 没有自动 down migration。失败时停止继续写入，保留错误和备份；回退使用执行前备份恢复，或重建可丢弃开发库，不通过修改 V1～V3 或伪造默认快照值回退。

---

*文档版本：v1.0*
*更新时间：2026-08-03*
