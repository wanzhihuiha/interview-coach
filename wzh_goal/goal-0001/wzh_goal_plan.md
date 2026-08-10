# Goal 方案基线

- Goal 文档版本：14
- Goal 路径：D:/DATA/code/interview-coach/wzh_goal/goal-0001
- Goal 类型：confirmed-goal
- 文档等级：development-ready
- 需求基线：wzh_goal_input.md（版本 14）
- 更新时间：2026-07-30 18:26

## 方案目标与边界

- 方案目标：在现有模块化单体和简历域分层内，把“事实画像”和“辅助分析”明确分开，并补齐可重做、可并行但受控、可失败恢复且不会污染面试快照的后端任务闭环。
- 当前范围：`backend/` 内简历上传/重解析、辅助分析、Redis 准入与额度、虚拟线程执行、状态持久化、面试快照读取、V3 迁移、配置和后端测试。
- 明确保留：现有 API 前缀、认证用户资源归属、每简历单行辅助分析、正式事实画像、面试创建时快照、解析缓存隔离规则、主备模型路由、启动后手动重试策略。
- 明确不做：前端、历史分析版本、意见持久化、永久去重、全局用户总并发、通用 Redis 降级（正式事实已提交后的可选 `INITIAL` 跳过除外）、持久化任务队列、网关或微服务拆分。
- 关键约束：V2 已执行且只读；V3 只创建不代用户执行；Redis 和模型调用不得放进长数据库事务；外部发送内容必须先脱敏；当前工作树已有修改必须原样保留。

## 当前方案

### 1. 数据语义与单行状态

- `resume_profile.profile_data` 继续只保存用户可核对的事实。辅助分析继续保存于 `resume_profile_analysis.analysis_data`，其内容只作为优势、待验证点和推断技能水平线索。
- `resume_profile_analysis` 继续保持 `resume_id` 唯一，不创建历史表。现有 `source_profile_hash` 只表示“当前保留的成功结果来自哪一版正式事实”，没有成功结果时允许为空；新增 `task_profile_hash + task_generation` 只表示“当前任务正在分析哪一版事实”，两套字段不得混用。
- 单行分析状态增加持久化的 `initial_model_call_started`，默认 false；没有分析行也视为 false。它只表示该简历的免费辅助分析是否已经真正跨过模型调用边界，不因任务登记、调度或调用前失败而变为 true。
- 新任务登记时保留旧 `analysis_data`、旧结果哈希和旧结果生成元数据，只把“可参与新面试”设为 false，并推进当前任务状态、任务模式和任务代次。
- 新任务成功时，在同一个数据库短事务中校验用户、任务代次、任务事实哈希和当前正式事实哈希，随后覆盖旧结果、写入新的 `source_profile_hash` 和结果生成元数据，并重新设为可参与面试。
- 新任务失败、调度失败、租约丢失、应用重启或迟到写回时，旧数据可以继续展示和作为满足严格哈希条件的 `REFINE` 参考，但保持不可参与新面试，不自动恢复。

### 2. 分析模式与事实变化

- 对外只接受 `REGENERATE` 和 `REFINE`；内部首次免费分析使用 `INITIAL`，其生成语义等同不带旧结果和意见的 `REGENERATE`。没有可用旧分析时公开请求只允许 `REGENERATE`。
- `REGENERATE` 的模型输入仅包含当前正式事实；即使存在旧分析和反馈也不得携带。
- `REFINE` 的模型输入包含当前正式事实、可解析的上一次成功分析和本次意见。准备任务和 Worker 启动时都校验旧结果哈希与当前正式事实哈希严格相等，并要求同一简历没有 `PENDING/RUNNING` 的事实解析或辅助分析任务。
- 意见先通过现有 `ResumeDesensitizer` 尽力脱敏，只存在于请求 DTO、内存任务命令和当前 Worker 调用栈；不进入实体、数据库、Redis Value、日志、错误消息或响应。
- 辅助分析 Prompt 明确把旧分析和意见定义为“调整角度的参考，不是事实”，拒绝新增事实或按意见直接指定技能等级。
- 正式事实确认使用独立数据库短事务先提交，不能与可选辅助分析登记绑成一个成败结果。提交成功后再非阻塞获取 AI 许可：许可可得才登记并调度免费 `INITIAL`；许可不可得、许可服务异常或任务登记失败时，不排队、不创建可执行任务并释放已经取得的资源，确认接口仍成功返回，正式事实可直接用于面试。
- 用户之后手动选择 `REGENERATE` 时，如果 `initial_model_call_started=false` 或尚无分析行，则内部仍登记为免费 `INITIAL`，不创建每日额度 token；只有第一次模型调用边界被幂等标记后，之后的手动分析才进入每日额度。首次调用开始后即使模型失败也不恢复免费资格；调用前失败仍保留资格。
- 后续重新解析并确认时：
  - 新事实哈希不变：保留当前辅助分析结果和任务语义；已经真正进入过模型调用或同简历已有执行中任务时不重复分析，首次免费资格仍未使用且无执行中任务时，确认提交后可以按非阻塞规则尝试第一次 `INITIAL`。
  - 新事实哈希变化：立即把旧结果设为不可参与面试；首次免费资格尚未使用时，确认提交后仍按非阻塞规则尝试自动 `INITIAL`，资格已使用时不自动分析，由用户手动 `REGENERATE` 并进入每日额度。

### 3. 分布式准入与虚拟线程

- Spring Data Redis/Lettuce 和现有 `StringRedisTemplate` 保持不变。新增 Redisson core 客户端并手工装配 `RedissonClient`，复用同一套 `spring.data.redis` 连接信息，避免 Redisson starter 替换或重复装配现有 Redis Bean。
- 每个 AI 任务同步获取两个非阻塞许可：
  - 用户级 `RPermitExpirableSemaphore`，许可数 5。
  - 简历级 `RPermitExpirableSemaphore`，许可数 1；新上传首次解析在简历记录产生前由状态机阻止同简历并行，记录产生后任务上下文绑定该简历。
- 获取顺序固定为用户级后简历级，失败按逆序释放。手动请求任一许可不可得时立即返回明确业务错误，不提交后台执行器、不预留额度、不改变当前分析任务状态；事实确认后的可选 `INITIAL` 许可不可得时静默跳过任务登记，但事实哈希变化造成的旧结果停用仍然生效。
- 可过期许可使用唯一 permit id。默认配置从“租期 5 分钟、每 1 分钟续期、等待时间 0”起步，并要求租期大于当前模型主备调用和文件提取的合理上界；参数可配置，实施后以真实耗时验证。
- 续期失败使任务失去写回资格并尽力中断 Worker；数据库代次和哈希条件仍是最终写入防线。正常结束在 `finally` 中停止续期并按 permit id 主动释放；进程异常由租期释放。
- `ResumeParseExecutorConfig` 改为 Java 21 虚拟线程 `ExecutorService`。执行器不承担业务排队或并发限制；被准入的任务从文本提取到写回都在同一个虚拟线程完成，实际并发只由分布式许可控制。
- 外部 Redis、文件和模型调用在数据库事务外执行。短事务服务负责重新校验资源归属、源状态、任务代次和哈希并完成状态写入，避免 Redis/LLM 等待期间持有数据库锁。

### 4. 每日手动额度

- 使用 `StringRedisTemplate` 执行经过测试的 Lua 脚本，按用户和准入时确定的 `Asia/Shanghai` 日期维护一个带 TTL 的原子状态；Key 由服务端用户 ID 和日期构造，不包含简历正文、反馈或联系方式。
- 每个需要每日额度的手动任务使用唯一 quota token。Redis 状态至少区分成功数、成功预留数、尝试数、尝试预留数和 token 当前阶段，支持下列幂等转换：
  1. 准入：同时检查 `success + successReserved < 5`、`attempt + attemptReserved < 10`，预留一次成功和一次可开始尝试的资格。
  2. 真正调用 `LlmService.chat` 前：Agent 在完成本地输入校验、事实序列化和所有脱敏后执行一次幂等“模型调用即将开始”回调，把该 token 的尝试预留转换为一次已发生尝试。回调失败时不得调用模型；主模型、备用模型和 `LlmService` 内部重试均位于回调之后，不再重复计数。
  3. 数据库写回成功后：把成功预留转换为一次成功；如果模型/JSON/代次/哈希/数据库写回任一失败，只释放成功预留。
  4. 未进入模型调用即失败：同时释放尝试预留和成功预留，不计尝试也不计成功。
- Redis token 转换必须幂等，避免异常处理、重复回调或迟到任务重复增减。Redis 在数据库成功后暂时不可用时不释放成功预留，保持 fail-closed，避免出现可继续超额成功的窗口。
- 手动解析任务和手动分析任务保存非敏感的 quota token/日期任务元数据，供应用重启时释放明确失败任务的预留；用户意见仍不保存。无法确认的崩溃窗口宁可临时占住当日名额，也不能放大额度。
- 新简历首次事实解析和免费 `INITIAL` 不创建手动 quota token，但仍占用用户/简历并发许可。免费 `INITIAL` 使用同一个调用前回调幂等写入 `initial_model_call_started=true`；回调执行后无论模型成功或失败都不返还免费资格，回调之前的序列化、脱敏、调度或状态失败则继续保留资格。

### 5. 简历保留数与每日新建数

- 当前删除为物理删除，因此“未删除简历数”就是当前用户 `resume` 行数。上传前在服务端查询 `countByUserId`，达到 5 立即拒绝；删除成功后自然释放保留名额。
- 为避免并行上传同时通过“当前 4 份”的检查，上传创建和删除对同一用户使用短时、非 AI 任务的 Redisson 用户级变更锁；锁内重新检查数据库数量。
- 每日新建使用独立 Redis 原子 token/counter，按 `Asia/Shanghai` 自然日最多成功创建 5 份。正常创建失败时补偿预留；进程在 Redis 预留后、数据库创建前崩溃时允许 fail-closed 到当日结束，但不能导致超额创建。
- 删除只影响数据库保留数，不递减当天新建计数。删除后重传仍是新简历并可获得首次免费流程，这是已接受风险。
- `FileStorageService.store()` 的文件写入不受 Spring 数据库事务控制。上传编排在文件保存后若数据库创建简历、登记必需的首次解析任务或提交前其他必要步骤失败，必须在异常路径尽力调用文件删除，并同时释放已经取得的许可和 Redis 预留；删除补偿失败记录安全日志，不能把孤儿文件伪装成已回滚。

### 6. 面试和恢复

- 新面试只读取“保留结果可解析、结果事实哈希等于当前正式事实、且 `usableForInterview=true`”的辅助分析；否则只使用正式事实创建面试。
- 面试创建后继续从 `Interview.userProfileSnapshot` 和 `userProfileAnalysisSnapshot` 恢复上下文，不回查新的分析结果。
- `analysisRefineAllowed` 只在“保留成功结果可解析、`source_profile_hash` 等于当前正式事实哈希、同一简历没有执行中任务”三项同时满足时为 true；它只是页面能力提示，提交接口仍必须重新校验简历级许可、用户级许可和每日额度。
- 应用启动不自动重放任何模型任务。现有恢复流程改为按当前任务代次标记遗留 `PENDING/RUNNING` 失败，保留旧分析并保持停用，释放可确定的额度预留；Redisson 许可由正常释放或租期到期回收。
- 因 `REFINE` 意见只在内存中存在，重启后的失败任务只能由用户重新输入意见并手动发起。

## 关键决策

| 决策点 | 结论 | 理由 | 影响 |
|---|---|---|---|
| 事实与推断 | 两套数据、两套语义 | 未经证实的优势和技能水平不能成为面试事实 | Agent Prompt、实体、响应和面试读取必须分开 |
| 重分析模式 | `REGENERATE` / `REFINE` 显式选择 | 同时支持完全重做和按意见调整 | 新 DTO、模式校验、两套 Prompt 输入 |
| 事实确认与首次分析 | 正式事实先独立提交；许可可得才自动登记免费 `INITIAL` | 可选推断不能阻断可核对事实用于面试 | 确认事务、许可准入、任务登记和失败语义必须拆开 |
| 首次免费边界 | 持久化 `initial_model_call_started`，只在 Agent 即将调用 `LlmService.chat` 时置 true | 任务登记或调用前失败不应消耗资格，真实调用失败也不能反复免费 | V3、Agent 回调、恢复和额度路由 |
| 旧结果生命周期 | 单行保留、立即停用、成功覆盖、失败不恢复 | 不需要历史版本，但需要过渡参考 | V3 状态字段、状态服务和响应语义变化 |
| 迟到任务隔离 | 事实哈希 + 独立任务代数双守卫 | 同一事实哈希上的旧任务也可能迟到 | 事件、Worker、实体和最终写入条件均携带 generation |
| 并发 | Redisson 可过期信号量：用户 5、简历 1 | 允许用户并行并支持未来多实例 | 新依赖、装配、许可续期和错误码 |
| 执行器 | 虚拟线程 per task，不设业务队列 | 当前主要耗时是外部 I/O，准入已在执行前完成 | 替换固定 2 线程/20 队列 |
| 每日额度 | Redis Lua token 状态转换，调用 `LlmService.chat` 前由 Agent 幂等触发 attempt | 并行下保证 5 成功/10 尝试，调用前失败不计、调用后失败只计尝试 | 新额度服务、Agent 回调、任务元数据和恢复逻辑 |
| 简历数量 | 数据库当前行数 5 + Redis 每日新建 5 | 轻量限制资源且明确接受有限重传绕过 | 上传/删除串行边界和新业务错误 |
| 文件与数据库一致性 | 文件先写后数据库失败时尽力删除本次文件 | 文件系统不参与数据库事务 | 上传编排增加补偿和安全日志 |
| Redis 故障 | 上传新建和手动任务 fail-closed；事实已提交后的可选 `INITIAL` 失败只跳过登记 | Redis 是准入与额度必需依赖，但可选推断不能反向破坏正式事实 | 失败必须可观测，跳过时保留首次免费资格 |
| 数据迁移 | 追加 V3，用户手工执行 | V2 已执行且项目禁止改旧迁移 | 新代码启动验证依赖 V3 |

## 重大未决问题

无。Redisson 具体兼容版本、许可租期和续期间隔需要在实施与真实耗时验证中确定，但不改变已确认架构、接口或业务语义，不作为新的产品决策。

## P2 判断

- 结论：不需要
- 理由：当前后端能力可以在一个 Goal 内按依赖顺序闭环；V3 手工迁移和运行验证属于同一 Goal 的切换步骤。前端适配已经明确由其他任务处理，不并入 P2。

## 完成标准

| 编号 | 必须达到的结果 | 证据类型 |
|---|---|---|
| C-001 | 正式画像只含可核对事实，辅助分析单独保存且不会反写事实 | 自动验证 + 只读审查 |
| C-002 | `REGENERATE` 只用当前事实；`REFINE` 只在保留成功结果可解析、哈希严格匹配且同简历无执行中任务时使用事实、旧结果和本次意见；无成功结果只能 `REGENERATE` | 自动验证 |
| C-003 | 用户意见经过脱敏，只存在于当前内存任务，不进入数据库、Redis Value、日志、错误或响应 | 自动验证 + 只读审查 |
| C-004 | 正式事实确认不受可选辅助分析许可或登记失败影响；被接受的新任务立即停用旧结果，执行中仍能返回旧内容，成功覆盖并启用，失败、重启和调度失败均保留但不恢复 | 自动验证 |
| C-005 | 分析任务代数和事实哈希共同阻止旧事件、迟到结果和旧失败覆盖当前状态 | 自动验证 |
| C-006 | 新面试只读取当前可用分析；已经创建的面试始终使用创建时快照 | 自动验证 |
| C-007 | 用户第 6 个 AI 任务和同简历第 2 个任务立即拒绝；不同用户许可隔离；正常、异常和租期到期路径不会永久占用许可 | 自动验证 + 真实 Redis 验证 |
| C-008 | 两类 Worker 使用虚拟线程执行，文件提取、脱敏、模型调用和写回位于同一任务线程，固定 2 线程/20 队列不再承担调度 | 自动验证 + 只读审查 |
| C-009 | 需计费的三类手动操作共享上海自然日 5 成功/10 尝试；本地校验、文件提取、序列化或脱敏失败不计尝试；进入 `LlmService.chat` 后只计一次，主备模型不重复计，失败正确释放成功预留 | 自动验证 + 真实 Redis 验证 |
| C-010 | 每份新简历首次事实解析和首次真正进入模型调用的辅助分析免费；无许可时事实仍确认成功且不创建任务；调用前失败保留免费资格，首次调用失败后下次手动分析进入每日额度；事实不变不重复分析，变化后停用旧结果 | 自动验证 |
| C-011 | 每用户最多保留 5 份简历、每天最多新建 5 份；并行上传不能越界；删除释放保留数但不返还日计数 | 自动验证 + 真实 Redis 验证 |
| C-012 | API 请求、响应和业务错误语义明确，`analysisRefineAllowed` 按成功结果、事实哈希和同简历任务状态计算，所有提交路径继续最终校验认证用户、资源归属、许可和额度，旧分析内容不泄漏给其他用户 | 自动验证 + 只读审查 |
| C-013 | V3 独立新增 `initial_model_call_started`、任务哈希/代次等字段并允许无成功结果时 `source_profile_hash` 为空，且不修改 V2；用户在目标 MySQL 手工执行后，实体映射通过 Hibernate `validate`，失败有明确恢复入口 | 用户操作 + 启动验证 |
| C-014 | 受影响定向测试与 `mvn test` 通过；Java/Spring 只读审查无未处理的高优先级 finding；范围内隐藏状态、事务、缓存、异步和降级行为已有准确注释 | 自动验证 + 只读审查 |
| C-015 | 上传文件保存后，数据库创建或必要任务登记失败会尽力删除本次文件并释放已取得的许可/预留；补偿失败可定位且不泄漏文件正文或敏感路径 | 自动验证 + 只读审查 |

## 代码事实

静态读取只证明当前代码事实，不代表实现正确、测试通过或任务完成。

| 文件或对象 | 已确认事实 | 证据位置 | 对方案的影响 |
|---|---|---|---|
| Maven 基线 | Java 21、Spring Boot 3.3.2；已有 Spring Data Redis，没有 Redisson | `backend/pom.xml` | 新增 Redisson core 需显式选择兼容版本并验证依赖与装配 |
| 重试 HTTP 接口 | `POST /api/v1/resumes/{id}/analysis/retry` 当前无请求体 | `ResumeController.retryProfileAnalysis` | 需要新增强类型请求 DTO 和校验 |
| 上传与重试编排 | 上传、确认、重解析、分析重试都集中在 `ResumeService`；`confirmResume()` 当前在同一事务内确认事实、`prepare()` 分析并发布事件；上传当前无保留数或日新建限制 | `ResumeService.uploadResume/confirmResume/retryProfileAnalysis` | 需要拆出短事务状态推进和外部准入边界；事实确认先提交，辅助分析许可与登记后置 |
| 上传文件事务边界 | `uploadResume()` 在数据库事务内先调用 `FileStorageService.store()` 再保存 `Resume`；存储服务已明确文件写入不参与数据库事务，当前异常路径没有删除补偿 | `ResumeService.uploadResume`、`FileStorageService.store/delete` | 拆分事务或数据库保存失败时必须显式尽力删除本次文件 |
| 辅助分析准备 | `prepare()` 会把当前事实哈希写入 `source_profile_hash`，并清空 `analysis_data` 和结果生成元数据 | `ResumeProfileAnalysisStateService.prepare` | 必须让结果哈希可空且只在成功时写入；准备任务只推进 task hash/generation 并保留旧结果 |
| 辅助分析迟到保护 | 当前只用 `sourceProfileHash` 和状态保护，没有独立任务代数 | `ResumeProfileAnalysisStateService.start/complete/fail` | V3 和所有事件/写回需要 task generation |
| 辅助分析存储 | V2 对 `resume_id` 唯一，只有一个 hash、status 和 analysis_data | `ResumeProfileAnalysis`、`V2__resume_profile_draft_analysis.sql` | 保持单行并新增结果/任务分离字段 |
| 后台事件 | 分析事件只携带 resumeId、userId、sourceProfileHash；事实事件已有 parse generation 和 forceRefresh | 两个 `RequestedEvent` | 事件需要携带任务代数、模式和非持久化任务上下文 |
| 执行器 | 两类 Worker 共用固定 2 线程、20 队列、AbortPolicy | `ResumeParseExecutorConfig`、两个 EventListener | 替换为虚拟线程，准入失败在提交前完成 |
| 事实缓存 | 默认关闭；Key 已隔离用户、Prompt、Schema、文本 SHA-256；强制重解析绕过读取 | `ResumeParseWorker`、`ResumeParseCacheProperties` | 保持规则，不为额度复用跨用户缓存 |
| Agent 调用前处理 | `ResumeAnalysisAgent` 在 `LlmService.chat` 前先校验文本并脱敏；`ResumeProfileAnalysisAgent` 在调用前先序列化正式事实并再次脱敏 | 两个 Agent 的 `analyze()` | 尝试与首次免费标记不能包在整个 Agent 外层，必须放在这些本地步骤之后、`LlmService.chat` 之前 |
| 主备模型 | `SpringAiLlmService` 在同一次 `chat` 内先主模型再备用模型 | `SpringAiLlmService.doChat` | Agent 的幂等调用前回调只执行一次；不得按主模型、备用模型或内部重试重复计数 |
| 脱敏 | 已覆盖电话、邮箱、证件、姓名行、生日、地址、社交账号和护照等规则，命中后继续 | `ResumeDesensitizer` | 复用并扩展反馈路径；保留“尽力脱敏”风险说明 |
| 面试快照 | 创建时保存事实和辅助分析 JSON；后续答题从 Interview 快照恢复 | `InterviewService`、`Interview` | 只需收紧创建时读取条件，不改进行中面试 |
| 画像响应 | 当前只返回分析内容、最新状态和错误，没有 usable、refineAllowed、任务代次或模式字段 | `ResumeProfileResponse`、`ResumeService.getResumeProfile` | `analysisRefineAllowed` 需按保留成功结果、事实哈希和同简历执行状态计算，提交接口仍重新校验 |
| 启动恢复 | 当前把遗留 PENDING/RUNNING 批量标失败，不自动重放 | `ResumeTaskRecoveryService` | 需保留旧分析并处理额度 token；反馈仍无法恢复 |
| 简历删除 | 当前为物理删除并删除分析、草稿、事实和文件；Repository 没有数量查询 | `ResumeService.deleteResume`、`ResumeRepository` | 当前行数可作为保留数，需并行创建保护 |
| 测试基线 | 已有 Service、State、Worker、EventListener、Agent、脱敏、恢复和面试快照测试；test profile 用 H2 create-drop | `backend/src/test/java`、`application-test.yml` | 在既有测试风格上扩展；MySQL V3 和真实 Redis 另行验证 |

### 代码事实缺口

- 未通过构建或依赖解析确认具体 Redisson 版本与 Spring Boot 3.3.2 的组合；实施时必须先做依赖与 Bean 装配验证。
- 尚未用真实 Redis 验证可过期许可续期和 Lua 并行边界，也未用真实模型最长耗时校准租期。
- 当前主配置中的 Hibernate 行为不能代替目标环境显式 `ddl-auto=validate` 验证；V3 已由用户在目标 MySQL 手工执行并完成字段结构核对，Hibernate 实体映射验证仍待执行。

## 改动对象

| 对象 | 当前状态 | 目标动作 | 所属工作包 | 风险 |
|---|---|---|---|---|
| `backend/pom.xml` | 无 Redisson | 增加单一 Redisson core 依赖及版本属性，不引入 starter 替换 Lettuce | WP-02 | 版本或传递依赖冲突 |
| Redis/任务配置 | 只有 Spring Data Redis 连接 | 新增手工 `RedissonClient` Bean、任务许可/租期/时区/额度配置并复用现有连接来源 | WP-02 | Bean 重复、测试上下文连外部 Redis |
| `V3__*.sql` | 不存在，V2 的 `source_profile_hash` 非空 | 为 resume 和 resume_profile_analysis 追加任务代数、任务哈希、首次模型调用标记、可用性和 quota 恢复元数据，并允许无成功结果时结果哈希为空 | WP-01 | V3 未执行时实体校验失败，结果/任务字段误用 |
| 分析 Entity/Enum/Repository | 结果和当前任务共用字段，`sourceProfileHash` 非空 | 增加内部模式、任务代数、任务哈希、`initialModelCallStarted`、可用性、quota 元数据及带归属/代次的查询写入；结果哈希改为可空且只在成功写回 | WP-01 | 单行语义混淆、免费资格误判、迟到写覆盖 |
| Resume Entity/Repository | 有 parse generation，无 quota 元数据和 count | 增加手动任务 quota 元数据、用户数量查询和恢复所需查询 | WP-01/WP-02 | 并行上传越界、恢复遗漏 |
| 分布式准入组件 | 不存在 | 增加用户/简历可过期许可、续期、释放和短时上传变更锁 | WP-02 | 租约丢失、许可泄漏 |
| Redis 额度组件 | 不存在 | 增加幂等 Lua token 状态转换和上海自然日 Key/TTL | WP-02 | 重复扣减、跨日和崩溃窗口 |
| 事件与任务上下文 | 字段不足 | 携带任务 generation、mode、quota token/date、permit id 和仅内存 feedback | WP-02/WP-03 | 意见被误持久化或日志输出 |
| `ResumeParseExecutorConfig` | 固定线程池和队列 | 改为虚拟线程 ExecutorService，并为续期提供受管生命周期组件 | WP-02 | 关闭/中断和 ThreadLocal 处理 |
| `ResumeService` 与状态服务 | 外部准入、事务、状态混在现有方法；确认事实与分析登记同事务 | 编排“事实确认独立提交 -> 可选 INITIAL 准入/登记”，以及手动任务的“校验 -> 准入 -> 短事务重校验/登记 -> 提交”；上传失败显式补偿文件和已取得资源 | WP-02/WP-03 | 事务外部状态不一致、事实被可选分析阻断、孤儿文件 |
| 分析 Agent/Worker | 只有完全生成，Worker 只看 hash；Agent 在本地校验/序列化和脱敏后才调用模型 | 增加两种 Prompt 输入、反馈脱敏、`LlmService.chat` 前幂等回调、免费资格/attempt 转换、generation/hash 写回和 finally 释放 | WP-03 | 反馈污染事实、调用前错误误计、主备重复计数 |
| Controller/DTO/ErrorCode/Response | 重试无 body，状态字段不足 | 新增模式请求、校验、可区分错误和分析可用/`REFINE` 字段；无成功结果和同简历执行中均禁止 `REFINE` | WP-03/WP-04 | 前端旧调用不兼容、能力提示与提交校验漂移 |
| `InterviewService` | `loadCurrent().data` 即可进面试 | 只加载明确 usable 且匹配当前事实的结果，继续保存快照 | WP-04 | 停用结果误入新面试 |
| 恢复 Runner/Service | 只批量标失败 | 按任务元数据失败当前代次、释放 quota 预留，不恢复旧分析 | WP-04 | 重启后名额占用或错误恢复 |
| 后端测试 | 覆盖现有 V2 行为 | 扩展接口、状态、并发、额度、上传限制、虚拟线程、恢复和面试快照测试 | WP-01～WP-05 | H2/Mock 不能替代真实 Redis/MySQL |

## 稳定开发规格

- 分层：Controller 只负责请求校验和响应；应用服务编排用例、资源归属、状态和短事务；基础设施层承载 Redisson、Redis Lua、执行器、文件和模型集成。不得把许可、额度或模式主流程堆进 Controller。
- 权限：用户 ID 只来自 `@AuthenticationPrincipal`；所有数据库查询和最终写入继续带 `resumeId + userId`，Redis Key 只由服务端可信 ID 构造。不存在、越权和状态冲突沿用统一业务异常，不泄漏他人资源是否存在。
- 事务：模型、文件提取、Redis 等待和许可续期不放在数据库事务内；正式事实确认先用独立短事务提交，再尝试可选分析准入；最终状态写入必须带资源归属、源状态、task generation 和事实哈希条件，并检查结果。文件系统写入不能依赖数据库回滚，异常路径显式补偿。
- 幂等：许可只限制并发，不替代数据库 generation/hash；Redis token 的 reserve/start/success/fail/recover 转换和 Agent 的“模型调用即将开始”回调必须幂等；重复回调或释放不得重复计数、重复消耗首次资格、增加许可或返还额度。
- 日志：只记录 user/resume/task 标识、模式、阶段、状态、耗时和错误类型；不记录简历正文、事实 JSON、旧分析正文、用户意见、完整 Redis Value、permit id 或敏感 Key 全文。
- 异步：任务上下文不依赖 SecurityContext、MDC 或请求 ThreadLocal 自动传播；Worker 使用事件中的可信 userId/resumeId 并在数据库再次校验。AgentContext 必须在虚拟线程内按现有 `runAs` 方式建立并清理。
- 注释：实施每个工作包时，对范围内全部新增/修改方法、构造器、生命周期方法和框架回调逐一分类；对许可租约、quota token 状态、事务边界、旧结果停用、迟到写丢弃、重启恢复和降级语义补充“为什么”，不写逐行翻译或虚假保证。
- 兼容：保持既有路由和外层 `ApiResponse`。重试接口改为必须提交 mode，是已知契约变化；前端适配不在本 Goal，但后端 DTO/字段/错误名必须稳定可交接。

## 可验证工作包

### WP-01 V3 与单行分析状态模型

- 动作类型：实施
- 预期结果：V3、Entity、Enum 和 Repository 能表达“保留结果”和“当前任务”分离、可空结果哈希、`initial_model_call_started`、task generation/hash 双守卫以及重启 quota 恢复元数据。
- 影响对象：V3 迁移、Resume/ResumeProfileAnalysis 实体、状态枚举/模式枚举、两个 Repository、状态服务基础方法及对应测试。
- 依赖与前置条件：V2 已执行且不得修改；当前开发数据无需复杂回填。
- 实施或检查要点：字段命名统一；`source_profile_hash` 允许 null 且只在成功结果写回时更新；`task_profile_hash + task_generation` 独立推进；`initial_model_call_started` 默认 false、只能幂等从 false 进入 true；新任务字段不存反馈；最终写入带 userId、generation、task hash 和正式事实 hash；H2 映射与 MySQL DDL 对齐。
- 计划验证：实体/状态服务定向测试，覆盖无成功结果 hash 为空、准备任务保留旧结果、首次调用标记幂等和 generation/hash 迟到保护；检查 V2 无 diff；静态核对 V3 字段与 JPA 映射。
- 失败信号：准备任务仍清空旧 data 或提前写结果 hash；无成功结果不能持久化；首次调用标记可被失败恢复为 false；同 hash 旧 generation 可覆盖；V3 修改 V2 或任务元数据包含反馈。
- 风险与恢复：V3 未执行前不启动目标 MySQL 新代码；代码可回退到旧版本并保留新增列，不执行破坏性 drop。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

### WP-02 Redisson 准入、Redis 额度、上传限制和虚拟线程

- 动作类型：实施
- 预期结果：所有简历 AI 任务在入后台前取得用户/简历许可；手动额度和新建数原子受限；后台使用虚拟线程；失败路径释放许可和 quota 预留。
- 影响对象：Maven、Redisson/Redis 配置与组件、Lua 脚本封装、上传/删除数量边界、事件上下文、Executor、Listener、恢复元数据写入和单元测试。
- 依赖与前置条件：WP-01 提供任务代数和 quota 元数据；目标运行环境 Redis 必需可用。
- 实施或检查要点：复用 Spring Redis 配置但不替换 StringRedisTemplate；非阻塞获取；固定获取/释放顺序；permit id 校验续期/释放；Lua token 幂等；quota day 固定为准入时上海日期；上传并行锁不冒充 AI 5 许可；外部操作不占数据库长事务。
- 计划验证：依赖树/编译装配、许可与额度服务定向测试、Executor 虚拟线程测试、上传 5 份和并行拒绝测试、正常/异常释放测试，以及文件保存后数据库创建/必要任务登记失败的删除补偿测试。
- 失败信号：第 6 个任务进入 Executor；同简历存在两个 Worker；Redis 多命令非原子；失败占成功次数；数据库创建失败遗留本次文件；Starter 覆盖现有 Redis Bean；使用平台线程固定池或隐藏队列。
- 风险与恢复：新依赖/装配失败时回退本工作包代码；Redis Key 有 TTL；代码回退不删除业务数据。真实许可过期和续期需 WP-05 验证。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

### WP-03 两种分析模式、API 和替换流程

- 动作类型：实施
- 预期结果：正式事实确认先独立成功，许可可得时才登记免费 `INITIAL`；API 严格接收模式；`REFINE` 只在当前事实可参考旧成功结果且同简历无执行中任务时运行；反馈脱敏且不持久化；旧结果按确认语义停用、覆盖或保留失败。
- 影响对象：Controller、请求/响应 DTO、ErrorCode、ResumeService、分析状态服务、事件、Agent、Worker、Desensitizer 调用和相关测试。
- 依赖与前置条件：WP-01 状态模型、WP-02 准入/额度上下文可用。
- 实施或检查要点：事实确认事务与可选分析准入分离；无许可不登记任务且确认仍成功；被拒绝的手动请求不改状态/不占额度；状态登记后旧结果立即 inactive；公开 `REGENERATE` 在首次调用标记为 false 时内部映射 `INITIAL`；REGENERATE 不携带旧结果或反馈；REFINE 二次校验成功结果、hash 和同简历任务；两个 Agent 都在本地校验/序列化/脱敏完成后、调用 `LlmService.chat` 前执行一次幂等回调；complete 成功后转换 success；任何日志都不输出 feedback。
- 计划验证：Controller/DTO 校验、无许可时事实确认成功且无 AI 任务、Service 资源归属和拒绝无副作用、Agent Prompt 输入、文件提取/序列化/脱敏调用前失败不计数、免费模型调用失败后下次手动进入额度、Worker 成败/迟到/主备一次计数、旧数据损坏和无成功结果不能 REFINE 测试。
- 失败信号：可选分析失败回滚正式事实；无许可仍创建任务或排队；feedback 出现在实体/Redis/log；REFINE 能跨 hash、无结果或执行中启动；调用前失败消耗 attempt/免费资格；主备重复计数；失败恢复 usable；REGENERATE 携带旧 data；状态登记前即清空旧结果。
- 风险与恢复：API 新 body 与旧前端不兼容，前端任务必须同步；回退代码时 V3 新列可保留。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

### WP-04 面试读取、事实变化与启动恢复

- 动作类型：实施
- 预期结果：新面试只使用当前可用分析，旧面试保持快照；事实相同/变化按确认规则处理；重启失败任务但不恢复旧分析或意见。
- 影响对象：ResumeService 确认流程、ResumeProfileAnalysisStateService 查询视图、ResumeProfileResponse、InterviewService、RecoveryService/Runner 和测试。
- 依赖与前置条件：WP-01～WP-03 已完成。
- 实施或检查要点：页面仍可看到 inactive 的保留结果；`analysisStatus` 表示最新任务，新增字段明确 usable/refineAllowed/taskGeneration/mode；refineAllowed 必须同时检查成功结果可解析、结果 hash 匹配和同简历无执行中任务；面试加载必须同时验证 usable、结果 hash 和 JSON；恢复按当前代次处理并释放 quota token，但不得把 `initial_model_call_started=true` 恢复为 false。
- 计划验证：事实 hash 不变/变化测试，分析执行中/失败/成功时 refineAllowed 与新面试快照测试，既有面试继续读取旧快照测试，重启恢复与手动重试测试。
- 失败信号：inactive 结果进入新面试；无成功结果或执行中仍允许 REFINE；进行中面试回查新结果；重启自动重放或返还已经进入模型调用的免费资格；失败后 usable 变 true。
- 风险与恢复：分析是可选线索，损坏或不可用时降级为只用正式事实；不阻断面试资格。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

### WP-05 数据库、Redis 与后端验证

- 动作类型：验证
- 预期结果：定向测试和全量后端测试通过；用户执行 V3 后 MySQL 映射校验通过；真实 Redis 验证并发、额度、释放和跨日边界。
- 影响对象：WP-01～WP-04 全部完成标准对应链路。
- 依赖与前置条件：代码实施完成；执行命令需用户在继续 Goal 时按 wzh-dev-collab 当前授权确认；V3 由用户手工执行。
- 实施或检查要点：先定向后全量；Mock/H2 结果和真实 Redis/MySQL 结果分开记录；主备模型计数可用 Mock 路由验证，不把真实简历或意见写入日志。
- 计划验证：受影响测试类、`mvn test`、用户目标库 V3 + `ddl-auto=validate` 启动、最多 5/第 6 拒绝、同简历冲突、租期/释放、5 成功/10 尝试、首次免费资格边界、每日新建 5，以及上传数据库失败后的文件补偿。
- 失败信号：任一必需测试失败；V3/Entity 不一致；并行越界；Redis 故障静默放行；未执行项被描述为通过。
- 风险与恢复：验证失败保持任务进行中或待验证，记录精确失败和恢复入口；不自动修改/回滚用户数据库。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

### WP-06 Java/Spring 只读审查

- 动作类型：只读审查
- 预期结果：从权限、状态机、事务、Redis 原子性、许可租约、异步上下文、隐私日志、迁移和测试缺口审查最终后端 diff。
- 影响对象：本 Goal 实际修改的 Java、配置、SQL 和测试。
- 依赖与前置条件：WP-05 已有实际验证结果；审查不自动修改文件。
- 实施或检查要点：逐条还原完成标准；核对所有资源归属和最终写条件；核对范围内方法注释分类；区分 finding 和已接受风险。
- 计划验证：code-review-skill 输出 findings；高优先级 finding 必须回到新的实施轮次处理并重新验证。
- 失败信号：存在未处理的 P0/P1、事实/分析混写、跨用户访问、额度可并发突破、反馈泄漏或迁移不可恢复风险。
- 风险与恢复：审查本身只读；finding 不构成自动修复授权。
- 对应 Skill：code-review-skill + wzh-java-backend

### WP-07 审查 findings 一致性整改

- 动作类型：实施
- 预期结果：数据库终态先被明确确认并保留 quota 恢复凭据，Redis 转换明确成功后才按任务守卫清理凭据；提交确认未知时保持 fail-closed；上传补偿只在数据库记录确认删除或不存在后返还每日新建额度。
- 影响对象：事实解析与辅助分析 Worker、两个状态服务、手动任务提交服务、两个异步 Listener、恢复 Service/Runner、上传与持久化补偿服务，以及用于表达数据库确认结果的内部类型。
- 依赖与前置条件：T-006 已确认 1 个 P1 和 2 个 P2；用户已明确授权按确认方案整改；V3 不需要变化。
- 实施或检查要点：成功、失败和未知三态不得由本地布尔值推断；quota token 未成功结算前不得被新代次覆盖；所有运行、提交、调度和恢复入口统一遵循“数据库事实 -> Redis 转换 -> 守卫清 token”；上传数据库删除提交结果未知时不返还创建额度。
- 计划验证：先由 code-review-skill + wzh-java-backend 静态复审整改调用链，再执行离线 `mvn -o -DskipTests compile`；当前项目规则禁止新增、修改或运行单元测试，既有及原计划定向测试继续明确列为未执行。
- 失败信号：成功落库后仍可能执行 `markFailed`；Redis 返回 false/抛错后 quota 元数据被清；有未结算 token 时可登记新代次；数据库删除未确认即返还每日创建额度；离线编译失败或复审仍有 P0/P1。
- 风险与恢复：无法确认数据库或 Redis 结果时保留预留，可能暂时占用当日额度但不会放大硬上限；不修改 V3 或用户数据库。代码恢复需保留当前脏工作树并以本任务实际改动为边界。
- 对应 Skill：wzh-dev-collab + wzh-java-backend

## API 契约

### 手动辅助分析

- 路由保持：`POST /api/v1/resumes/{id}/analysis/retry`
- 请求改为必填 DTO：

```json
{"mode":"REGENERATE"}
```

或：

```json
{"mode":"REFINE","feedback":"希望更关注项目中的工程能力证据"}
```

- `mode` 只接受两个公开枚举值。`REFINE` 要求非空、有限长度 feedback；`REGENERATE` 不使用 feedback。
- 当不存在可用旧分析时只接受公开模式 `REGENERATE`；若该简历 `initial_model_call_started=false`，后端把这次请求内部登记为免费 `INITIAL`，不要求前端暴露第三种模式。
- 响应外层和成功空体保持现有 `ApiResponse<Void>`，不返回或保存 feedback。
- 无请求体的旧调用不再触发默认模式；由另一个前端任务显式适配，避免后端猜测用户意图。

### 画像响应

- 既有 `analysis` 字段改为“最近一次成功且仍保留的结果”，即使当前已停用也可返回。
- 既有 `analysisStatus`、`analysisErrorMessage` 表示最新任务状态与错误。
- 新增稳定字段：
  - `analysisUsableForInterview`：当前结果是否可进入之后创建的新面试。
  - `analysisRefineAllowed`：是否存在可解析且与当前正式事实 hash 严格匹配的保留成功结果，并且同一简历当前没有执行中的事实解析或辅助分析任务；该字段不代表用户并发或每日额度已经预留。
  - `analysisTaskGeneration`：当前任务代数，用于前端识别状态刷新，不作为授权依据。
  - `analysisMode`：最新任务模式；首次内部模式可返回 `INITIAL`。
- 不向前端返回 permit id、quota token、Redis Key、原始事实 hash 或内部租约信息。

### 业务错误

- 沿用 `BusinessException + ResumeErrorCode + ApiResponse`，在未占用的简历错误码范围增加可区分语义：
  - 模式或 feedback 参数不合法。
  - `REFINE` 当前不可用。
  - 同一用户 AI 并发已达 5。
  - 同一简历已有 AI 任务。
  - 当日成功名额已满。
  - 当日尝试名额已满。
  - 当前保留简历已达 5。
  - 当日新建简历已达 5。
  - Redis/许可/额度基础设施不可用。
- 资源不存在或不属于当前用户继续使用不泄漏资源存在性的现有语义；被拒绝请求不得修改旧分析状态或 Redis 计数。

## 数据迁移与切换

### V3 目标结构

- 新建 `backend/src/main/resources/db/migration/V3__resume_ai_task_control.sql`，不修改 V1/V2。
- 将 V2 的 `resume_profile_analysis.source_profile_hash` 调整为可空：它只保存最近一次成功结果的事实哈希；没有成功结果或只有失败任务时允许 null，不再由任务准备阶段提前写入。
- `resume_profile_analysis` 追加：
  - `task_profile_hash`：当前任务使用的正式事实 hash。
  - `task_generation`：当前辅助分析任务代数，默认 0。
  - `task_mode`：`INITIAL/REGENERATE/REFINE`。
  - `initial_model_call_started`：该简历的首次免费辅助分析是否已经真正进入模型调用边界，默认 false，只允许幂等从 false 变为 true。
  - `usable_for_interview`：保留结果能否进入新面试，默认 false。
  - `task_quota_date`、`task_quota_token`：仅保存手动任务恢复所需非敏感元数据。
- 尚无 `resume_profile_analysis` 行时等价于 `initial_model_call_started=false`；事实确认后未取得 AI 许可时不为制造状态而创建伪任务行。
- `resume` 追加 `parse_quota_date`、`parse_quota_token`，仅用于手动事实解析额度恢复；首次免费任务保持 null。
- 不增加 feedback、历史版本、已删除简历 hash 或永久去重字段；不增加额度数据库表。

### 执行顺序

1. 实施阶段只生成并静态核对 V3，不由 Codex 执行数据库。
2. 用户确认目标数据库和备份/回滚入口后，手工执行 V3，并核对新增列、默认值和索引。
3. V3 成功后再用新代码按目标配置启动，并显式执行 Hibernate `validate`。
4. 启动通过后进行 API、真实 Redis 和模型 Mock/测试数据验证。

### 兼容与回退

- V3 以新增列和放宽 `source_profile_hash` 可空约束为主，不删除或改写业务内容；当前无历史画像版本需要复杂回填。
- 旧应用通常可忽略新增列。需要代码回退时先停新任务、回退应用版本，保留 V3 列，不执行破坏性删列。
- Redis 许可和额度 Key 使用独立前缀与 TTL。回退后自然过期；如需人工清理，只能由用户针对已确认前缀执行，不允许扫描或清空整个 Redis。
- 数据库执行失败时停止部署新代码，依据数据库错误和备份恢复；不得重复修改已部分执行的 V3 后直接猜测重跑。

## 配置与运行

- Redisson 手工客户端读取与 Spring Data Redis 同源的 host、port、database、username/password、SSL 和 timeout；不得在新配置中复制明文凭据。
- 新增 `resume.ai-task` 配置组保存固定业务限额和可调运行参数：user permits=5、resume permits=1、acquire wait=0、lease、renew interval、success limit=5、attempt limit=10、zone=Asia/Shanghai、retained resumes=5、daily creates=5。
- 运行时 Redis 不可用时，上传新建、手动任务准入与额度检查失败快返并记录不含敏感值的错误；不自动绕过。正式事实已经提交后的可选 `INITIAL` 只跳过任务登记并保留免费资格，不把 Redis/许可故障反向变成事实确认失败。
- test profile 可以通过 Mock/测试 Bean 隔离外部 Redis，但这只属于测试替身，不改变真实运行时必需依赖。
- Executor 和续期组件由 Spring 管理生命周期；关闭时停止接收新任务、停止续期并尽力释放当前任务，未完成状态由下次启动恢复。

## 验证计划

计划中的命令和步骤不构成当前执行授权。

| 对应完成标准 | 验证或审查方式 | 预期结果 | 失败信号 |
|---|---|---|---|
| C-001～C-005 | State/Service/Agent/Worker 定向单元测试 | 两类数据不混写；无成功结果的 source hash 为空；正式事实在无许可时仍确认成功；模式、停用、覆盖、失败和迟到守卫符合方案 | 可选分析阻断事实确认、旧数据被清空、任务提前写结果 hash、跨 hash/无结果/执行中 REFINE、旧 generation 写回 |
| C-003/C-012 | 脱敏、日志和资源归属定向测试 + 只读审查 | feedback/身份信息不持久化、不输出，跨用户请求被拒绝 | 数据库/Redis/log 出现正文或跨用户返回 |
| C-006 | InterviewService 测试 | 新面试只用 usable 结果，旧面试保持快照 | 进行中面试切换或 inactive 进入快照 |
| C-007/C-009/C-011 | Redis 服务测试 + 真实 Redis 并发验证 | 用户 5、简历 1、成功 5、尝试 10、新建 5 均不越界；文件提取/序列化/脱敏失败不计尝试；主备模型只计一次；释放正确 | 第 6 个进入后台、调用前失败计数、主备重复计数、失败占成功、删除返还日计数 |
| C-008 | Executor/EventListener/Worker 测试 | Worker 线程为 virtual，任务无固定队列等待 | 平台固定池、隐藏队列或跨线程丢失任务上下文 |
| C-010 | ResumeService/State/Agent/Worker 测试 | 无许可时事实仍确认成功且不创建任务；未调用模型时免费资格保留；免费调用失败后资格已使用；事实相同不重复，变化后旧结果停用 | 事实确认回滚或排队、调用前失败消耗资格、模型失败返还资格、变化后旧结果仍 usable |
| C-013 | 用户手工 V3 + 显式 Hibernate validate 启动 | MySQL 列与实体完全匹配 | SQL 失败、缺列、类型/nullable 不一致 |
| C-014 | 受影响定向测试后执行 `mvn test` | 全部通过，无既有回归 | 任一失败或跳过被误报为通过 |
| C-014 | code-review-skill + wzh-java-backend 只读审查 | 无未处理 P0/P1，隐藏行为注释准确 | 高优先级 finding 或注释与真实行为不符 |
| C-015 | 上传 Service/存储补偿定向测试 + 只读审查 | 文件保存后数据库创建或必要任务登记失败会调用尽力删除并释放许可/预留 | 数据库回滚后仍遗留本次文件，或补偿路径泄漏敏感路径/正文 |

## 风险与回退

| 风险 | 影响 | 预防或回退 |
|---|---|---|
| Redisson 版本/自动装配冲突 | 编译或启动失败、现有 StringRedisTemplate 被替换 | 使用 core 手工 Bean；验证依赖树、Bean 唯一性和 test/local 启动；失败回退依赖与配置 |
| 许可续期失败或进程暂停 | 许可过期后旧 Worker 仍可能短暂运行 | 租期覆盖主备超时，周期续期，丢失后中断并禁止写回；generation/hash 最终守卫 |
| Redis 与数据库不能原子提交 | 崩溃窗口可能临时多占当日预留 | token 状态机幂等、任务元数据恢复、失败时 fail-closed；绝不在不确定时放开超额成功 |
| 事实确认与可选 INITIAL 分阶段 | 事实已成功但辅助分析未创建，页面短期只有正式事实 | 把这是正常成功语义；许可可得才建任务，未真正调用模型时保留免费资格，面试降级只用正式事实 |
| 文件写入不参与数据库事务 | 数据库创建或任务登记失败可能留下孤儿文件 | 文件路径只在本次编排内持有，异常路径尽力删除并记录安全日志；补偿失败作为剩余运维风险，不伪装成回滚成功 |
| 规则脱敏漏检 | 非标准姓名、联系方式或地址可能发送给模型 | Prompt 禁止身份信息、规则测试、正文不落日志；保留“尽力脱敏”产品边界 |
| 无全局并发上限 | 多用户同时运行可能压 CPU、连接池或供应商 | 作为已接受风险记录；本 Goal 只保证每用户/简历限制并观察资源指标 |
| 删除重传获得首次免费 | 用户可在每日 5 份内重复免费流程 | 已接受；保留每日新建上限，不新增永久指纹 |
| V3 未执行即启动新代码 | Hibernate validate 或 SQL 访问失败 | 明确先 V3 后启动；失败停止部署，不自动改表 |
| 前端未同步新 body/字段 | 旧页面重试请求失败 | 后端契约固定并交接；前端由其他任务修改，不在本 Goal 偷做兼容猜测 |
| 当前工作树非常脏 | 误覆盖用户后端或其他窗口前端修改 | 每个工作包先读实际文件和 diff，只改范围内对象，绝不回退或格式化无关文件 |
