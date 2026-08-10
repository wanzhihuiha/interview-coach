# Goal 执行记录

- Goal 文档版本：14
- Goal 路径：D:/DATA/code/interview-coach/wzh_goal/goal-0001
- Goal 状态：进行中
- 更新时间：2026-07-30 18:26

## 当前位置

- 当前 Task：T-005
- 当前状态：待验证
- 对应 plan 工作包：WP-05 数据库、Redis 与后端验证
- 动作类型：验证
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 下一步：由用户决定是否继续目标 MySQL Hibernate `validate`、真实 Redis 边界验证和应用启动；执行前需按当前规则确认具体命令、目的和影响。全量 `mvn test` 与当前禁止单元测试的规则冲突，在规则未调整前不得执行。
- 恢复入口：从用户已完成的 V3 结构核对、目标环境配置、真实 Redis 验证入口和当前 AGENTS.md 验证约束恢复；T-006/T-007 已完成，不再阻塞 T-005。

## 任务索引

| Task ID | 名称 | 动作类型 | 状态 | 对应 plan 工作包 |
|---|---|---|---|---|
| T-001 | 建立 V3 与单行分析任务状态模型 | 实施 | 已完成 | WP-01 V3 与单行分析状态模型 |
| T-002 | 建立分布式准入、额度与虚拟线程基础设施 | 实施 | 已完成 | WP-02 Redisson 准入、Redis 额度、上传限制和虚拟线程 |
| T-003 | 实现两种分析模式、API 和替换流程 | 实施 | 已完成 | WP-03 两种分析模式、API 和替换流程 |
| T-004 | 完成面试读取、事实变化与启动恢复 | 实施 | 已完成 | WP-04 面试读取、事实变化与启动恢复 |
| T-005 | 执行数据库、Redis 与后端验证 | 验证 | 待验证 | WP-05 数据库、Redis 与后端验证 |
| T-006 | 审查 Java/Spring 最终后端变更 | 只读审查 | 已完成 | WP-06 Java/Spring 只读审查 |
| T-007 | 修复终态额度结算与上传补偿 | 实施 | 已完成 | WP-07 审查 findings 一致性整改 |

## 任务详情

### T-001 建立 V3 与单行分析任务状态模型

- 状态：已完成
- 动作类型：实施
- 对应 plan 工作包：WP-01 V3 与单行分析状态模型
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：用户已确认整体方案及方案 2 修正，且 V2 已执行；不得修改 V2；不得覆盖当前工作区已有修改。
- 下一步：无；WP-01 已完成，后续按独立 T-002 继续 WP-02。
- 恢复入口：如需复核 T-001，从 V3、`ResumeProfileAnalysisStateService`、分析事件/Worker 代次传递和 5 个定向测试类恢复；V3 仍只做了静态核对，目标 MySQL 执行属于后续验证步骤。

#### 实际结果

- 实际完成内容：新增只供用户后续手工执行的 V3；把成功结果字段与当前任务字段分离；首次任务允许结果 hash 为空；准备新任务只推进 `task_profile_hash + task_generation + task_mode` 并停用结果，不清空旧 `analysis_data`、结果 hash 和结果生成元数据；增加只能幂等置 true 的 `initial_model_call_started`；成功写回同时校验用户归属、任务代次、任务 hash 和当前正式事实 hash；失败与启动恢复不清空旧结果、不返还首次调用标记。事件和 Worker 仅为现有调用链补齐 generation/hash 传递，没有实现 Agent 回调、Redis、额度或新 API。
- 实际修改对象：`backend/src/main/resources/db/migration/V3__resume_ai_task_control.sql`；`Resume`、`ResumeProfileAnalysis`、`ResumeProfileAnalysisMode`；`ResumeProfileRepository`、`ResumeProfileAnalysisRepository`；`ResumeProfileAnalysisStateService`、`ResumeProfileAnalysisRequestedEvent`、`ResumeProfileAnalysisWorker`、`ResumeService` 的最小 generation 传递；`ResumeProfileAnalysisStateServiceTest`、`ResumeProfileAnalysisWorkerTest`、`ResumeProfileAnalysisEventListenerTest`、`ResumeServiceTest`。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：`mvn -o -q -DskipTests compile` 通过；标准定向 `mvn ... test` 在全量 `testCompile` 阶段被既有 `InterviewServiceTest.java:242` 调用不存在的 `Position.setPublic(boolean)` 阻断，选定测试当时未运行；随后离线生成测试 classpath，仅编译 5 个选定测试类并执行 Maven Surefire，`ResumeProfileAnalysisStateServiceTest` 8、`ResumeProfileAnalysisWorkerTest` 2、`ResumeProfileAnalysisEventListenerTest` 2、`ResumeServiceTest` 12、`ResumeTaskRecoveryServiceTest` 1，共 25 个测试，0 failure、0 error、0 skipped。`ResumeServiceTest` 使用 H2 `create-drop` 验证了新实体映射和首次登记语义，但未执行 V3。静态核对 V3/JPA 字段一致、相关文件无 feedback 字段、生产代码只有 false 到 true 的首次调用标记写入；V2 SHA-256 在实施前后均为 `A3D99DD9B275E9FB47D31C02BD3B842109AD88D1FF6C33C1003122DB7C884E75`，V1 未修改。
- 计划但未执行的验证：目标 MySQL 手工执行 V3、目标环境 Hibernate `validate`、完整 Maven 测试生命周期与全量 `mvn test`、真实 Redis/额度/许可验证、应用启动和 WP-06 只读审查。
- 审查 findings 状态：未执行代码审查；本轮只有实施内静态核对，不描述为审查通过。
- 待验证内容：V3 在目标 MySQL 的可执行性及新列与 Hibernate `validate` 的对应关系；仓库全量测试需先处理或避开既有 `InterviewServiceTest` 编译错误后再执行。

#### 阻塞与风险

- 失败或阻塞：T-001 无阻塞；标准 Maven 定向测试生命周期存在一个与 WP-01 无关的既有测试编译错误，但选定测试已通过隔离编译和 Surefire 得到实际结果。
- 需要的条件或决策：无。V3 继续由用户在后续步骤手工执行。
- 剩余风险：V3 仅静态核对且未在目标 MySQL 执行；H2 不能证明 MySQL DDL 与 Hibernate `validate` 通过；全量 `mvn test` 尚未执行；当前工作树包含大量其他窗口改动，V2 本身为既有未跟踪文件。

#### 关键记录

- 2026-07-30 10:53：依据 confirmed Grill 记录和当前代码建立 `confirmed-goal + development-ready` 版本 1；分配 T-001，但未开始业务实施。
- 2026-07-30 10:53：确认前端不属于本 Goal，V2 不修改，V3 只由后端提供且数据库由用户手工执行。
- 2026-07-30 11:25：用户采用方案 2；Goal 升为版本 2，补充“事实先独立确认、许可可得才建免费 INITIAL、真实模型调用才消耗首次资格”、Agent 调用前幂等计数、可空结果哈希、严格 REFINE 条件和上传文件失败补偿；T-001 仍未开始。
- 2026-07-30 12:11：完成 WP-01；新增并静态核对 V3，完成结果/任务字段分离、首次调用单向标记、generation/hash 迟到保护和 25 个选定测试；T-001 标记已完成，T-002 登记为未开始。

### T-002 建立分布式准入、额度与虚拟线程基础设施

- 状态：已完成
- 动作类型：实施
- 对应 plan 工作包：WP-02 Redisson 准入、Redis 额度、上传限制和虚拟线程
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：T-001 已完成；V3 目标 MySQL 执行不是开始本地 WP-02 编码的前置条件，但新代码启动前必须完成。
- 下一步：无；WP-02 已完成，后续按独立 T-003 继续 WP-03。
- 恢复入口：如需复核 T-002，从 `backend/pom.xml`、Surefire 实际运行时 classpath、Redisson/Redis 组件、上传补偿链路、两个 Listener/Worker 和 14 个定向测试类恢复；真实 Redis 行为仍属于 WP-05。

#### 实际结果

- 实际完成内容：新增 Redisson core 3.32.0 并基于 `RedisProperties` 手工装配惰性 `RedissonClient`，保留 Lettuce 与 `StringRedisTemplate`；实现用户 5、简历 1 的非阻塞可过期许可、固定获取/逆序释放、permit id 续期、租约丢失中断与成功写回抑制；实现手动 AI 额度和每日新建数的单 Key Lua token 状态机，日期固定为准入时的 `Asia/Shanghai` 自然日，失败/释放转换保持幂等；将简历 Worker 执行器改为逐任务虚拟线程，单个平台调度线程只做许可续期；上传/删除使用独立用户变更锁，上传在锁内复查最多保留 5 份，按“额度预留 -> 文件保存 -> 数据库短事务建档 -> AI 许可 -> 新建额度确认 -> 事件交接”推进，数据库创建、许可、锁所有权、额度确认或必要事件交接失败时尽力释放许可和 Redis token、删除本次数据库记录与文件；补充许可和非敏感额度事件上下文及分析任务额度恢复元数据基础入口。
- 实际修改对象：`backend/pom.xml`、`application.yml`；`ResumeAiQuotaReservation`、`ResumeAiTaskLease`、两个 RequestedEvent；`ResumeAiTaskProperties`、`ResumeRedissonConfiguration`、`ResumeAiTaskAdmissionService`、`ResumeAiTaskLeaseRunner`、`ResumeRedisScriptExecutor`、`ResumeAiQuotaService`、`ResumeDailyCreationQuotaService`、`ResumeUserMutationLock`；`ResumePersistenceService`、`ResumeUploadService`、`ResumeService`、`ResumeErrorCode`、`ResumeRepository`、`ResumeProfileAnalysisStateService`；两个 EventListener、两个 Worker、`ResumeParseExecutorConfig`；对应 14 个定向测试类。
- 是否偏离 plan：否。手动额度尚未接入 Agent 的真实模型调用前边界是 WP-03 的明确范围，本轮只提供 Lua 状态机、事件上下文和恢复元数据基础，不提前实现回调、API mode、feedback 或 Prompt。

#### 验证与审查

- 已执行验证及结果：`mvn -o -DskipTests compile` 通过，编译 217 个生产源文件；最终离线 Maven 定向测试覆盖 14 个类，59 tests、0 failure、0 error、0 skipped，且标准 `testCompile` 成功，没有被 `InterviewServiceTest` 阻断；`ResumeRedissonConfigurationTest` 静态构建并核对同源 host/port/database/username/password/client-name/connect timeout/command timeout；只读解析成功测试生成的 Surefire XML，实际 `java.class.path` 同时包含 `spring-boot-starter-data-redis-3.3.2.jar`、`lettuce-core-6.3.2.RELEASE.jar`、`spring-data-redis-3.3.2.jar` 和唯一的 `redisson-3.32.0.jar`，不存在 `redisson-spring-*` 或 Redisson starter，结合 POM 直接依赖、编译和 Spring 测试上下文证明 core 依赖与现有 Redis 装配边界；`git --no-pager diff --check` 退出码 0，仅输出既有 LF/CRLF 提示；V1/V2 的 Git 只读差异为空，V2 SHA-256 仍为 `A3D99DD9B275E9FB47D31C02BD3B842109AD88D1FF6C33C1003122DB7C884E75`；静态搜索确认没有固定 Worker 平台线程池或隐藏业务队列，也没有在 WP-02 组件中实现 feedback、Agent 调用前回调或新 API mode。
- 计划但未执行的验证：未执行字面上的 `mvn -o dependency:tree "-Dincludes=org.redisson:*"` 命令；该命令未获授权，其目标依赖边界已由实际 Surefire 运行时 classpath、POM、编译和 Spring 测试上下文覆盖，因此不再构成 T-002 验收缺口。未执行全量 `mvn test`、应用启动、数据库/V3、目标 MySQL Hibernate `validate`、真实 Redis Lua/许可/续期/并发/跨午夜验证和 WP-06 只读审查，这些均未获本轮授权或属于 WP-05/WP-06。
- 审查 findings 状态：未执行代码审查；本轮为实施内静态范围核对，不描述为审查通过。
- 待验证内容：真实 Redis 上的第 6 个用户任务、同简历第 2 个任务、租期到期/续期、Lua 5 成功/10 尝试和每日新建 5 的运行行为留到 WP-05。

#### 阻塞与风险

- 失败或阻塞：实现无阻塞。第一次定向测试在 `testCompile` 被新增配置测试调用 Redisson 受保护 API 阻断，改用只读反射取配置后通过；后续一次测试因新增锁丢失用例覆盖通用 Mockito 桩产生 `UnnecessaryStubbing`，修正测试桩后最终 59 个测试全部通过。两次中间失败均未描述为通过。
- 需要的条件或决策：无；开始 T-003 仍需用户明确继续 Goal，且新的命令授权不能从本轮历史授权继承。
- 剩余风险：当前 Lua 单元测试验证服务返回映射和脚本边界，但未在真实 Redis 执行脚本；Redisson 许可、续期和用户锁使用 Mock 验证，尚未覆盖 Redis 进程故障、长暂停和多实例竞态；编译仍有既有 protobuf POM 元数据、过时 API 和 ByteBuddy 动态 agent 警告；目标 MySQL 尚未执行 V3；当前工作树仍包含大量其他窗口修改。

#### 关键记录

- 2026-07-30 13:33：完成 WP-02 实现和授权内验证；离线编译通过，最终 14 类 59 个定向测试通过；因依赖树命令未获授权，T-002 按协议标记为待验证，未开始 WP-03。
- 2026-07-30 13:53：只读核对实际 Surefire 运行时 classpath，确认保留 Spring Data Redis/Lettuce 且只有 Redisson core 3.32.0，无 Redisson starter 或 `redisson-spring-*`；该运行时证据覆盖依赖边界验收，T-002 标记已完成，登记 T-003 但未开始 WP-03。

### T-003 实现两种分析模式、API 和替换流程

- 状态：已完成
- 动作类型：实施
- 对应 plan 工作包：WP-03 两种分析模式、API 和替换流程
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：T-001、T-002 已完成；继续保留 V2、前端和当前工作树已有修改。
- 下一步：无；WP-03 已完成，后续按独立 T-004 继续 WP-04。
- 恢复入口：如需复核 T-003，从模式请求 DTO、`ResumeAiTaskSubmissionService`、分析状态服务、两个 Agent/Worker、两个事件 Listener 及 9 个最终定向测试类恢复；真实 Redis 和全量测试仍属于 WP-05。

#### 实际结果

- 实际完成内容：实现公开 `REGENERATE/REFINE` 请求与内部 `INITIAL` 映射；正式事实确认独立提交，可选 INITIAL 无许可或调度失败不回滚事实；手动任务按“校验 -> 许可 -> 必要额度 -> 锁内复查/登记 -> 事件交接”提交，被拒绝请求无副作用；REFINE 严格校验同版本可解析成功结果和无执行中任务，REGENERATE 丢弃旧分析及 feedback；feedback 仅保留于 DTO、内存事件和 Agent 调用栈，序列化、旧结果及 feedback 均先脱敏；两个 Agent 在全部本地输入准备完成后、`LlmService.chat` 前只执行一次幂等回调；INITIAL 单向消耗首次资格，手动任务在模型调用前转 attempt，数据库成功后转 success；Worker 对迟到任务、租约丢失、模型前失败、模型后失败和数据库成功后 Redis 转换失败分别补偿，且不恢复旧结果可用性。
- 实际修改对象：`ResumeController`、`ResumeProfileAnalysisRetryRequest`、`ResumeProfileResponse`、`ResumeErrorCode`、`ResumeService`、`ResumeAiTaskSubmissionService`、`ResumeProfileAnalysisStateService`、`ResumeParseStateService`、分析/解析事件、两个 Agent/Worker、两个 EventListener、脱敏调用链及对应测试；新增 `ResumeControllerTest`。未修改 frontend、gateway、V1 或 V2。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：首次 `mvn -o -DskipTests compile` 因 `ResumeAiTaskSubmissionService` lambda 捕获可变 `lease` 失败，修复后同命令成功并编译 219 个生产源文件；首次 WP-03 定向测试在测试适配完成前为 40 tests、19 errors，第二次为 63 tests、2 failures、4 errors，均如实用于修复测试桩；最终离线 Maven 定向测试覆盖 9 个类，66 tests、0 failure、0 error、0 skipped，生产和 34 个测试源文件编译成功；`git --no-pager diff --check` 退出码 0，仅有既有 LF/CRLF 提示；V1/V2 Git 文本差异为空，V2 SHA-256 保持 `A3D99DD9B275E9FB47D31C02BD3B842109AD88D1FF6C33C1003122DB7C884E75`；静态搜索确认 Entity/Repository/Redis 无 feedback，日志不输出 feedback 正文。2026-07-30 16:33 按持久化 T-003 目标恢复复验：`mvn -o -DskipTests compile` 再次 BUILD SUCCESS；当前 9 个 WP-03 定向测试类因后续测试扩充共运行 59 tests，0 failure、0 error、0 skipped，BUILD SUCCESS；再次静态核对公开 `REGENERATE/REFINE`、内部 `INITIAL`、调用前回调先于 `LlmService.chat`，且 Entity/Repository/Redis 无 feedback 字段，V1/V2 Git 文本差异为空、V2 SHA-256 未变。
- 计划但未执行的验证：未执行全量 `mvn test`、应用启动、数据库/V3、目标 MySQL Hibernate `validate` 和真实 Redis 并发/额度验证；这些未获授权或属于 WP-05。
- 审查 findings 状态：未审查。
- 待验证内容：WP-03 本地完成线已满足；真实 Redis、目标 MySQL、全量回归和最终审查留到 WP-05/WP-06。

#### 阻塞与风险

- 失败或阻塞：最终无阻塞；两轮中间验证失败均已在当前工作包内修复并由最终测试覆盖。
- 需要的条件或决策：无。
- 剩余风险：旧前端若仍发送无 body 重试请求会收到业务参数错误；前端适配不在本 Goal。H2/Mock 测试不能替代真实 Redis/MySQL 和全量回归。

#### 关键记录

- 2026-07-30 13:53：T-002 完成后登记 T-003；本轮未实施 WP-03。
- 2026-07-30 15:27：完成 WP-03 实现、静态边界核对和最终 9 类 66 个定向测试；T-003 标记已完成，登记 T-004 为未开始。
- 2026-07-30 16:34：恢复时发现持久化目标仍引用版本 5/T-003 未开始，与当前三文件版本 9 和代码事实不一致；未倒退进度。使用当前代码重新完成离线编译及 9 类 59 个 WP-03 定向测试，均成功，T-003 保持已完成，当前总进度仍为 T-006。

### T-004 完成面试读取、事实变化与启动恢复

- 状态：已完成
- 动作类型：实施
- 对应 plan 工作包：WP-04 面试读取、事实变化与启动恢复
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：T-001～T-003 已完成；继续保留当前工作树已有修改。
- 下一步：无；WP-04 已完成，后续按独立 T-005 继续 WP-05。
- 恢复入口：如需复核 T-004，从 `ResumeProfileAnalysisStateService.loadCurrent`、画像响应、`InterviewService` 创建与继续面试路径、Recovery Service/Runner 及 6 个最终定向测试类恢复；目标 MySQL 和真实 Redis 仍属于 WP-05。

#### 实际结果

- 实际完成内容：页面查询继续返回 inactive 的保留成功结果，并由 `AnalysisView` 显式计算 `usableForInterview` 与 `refineAllowed`；结果 hash 不匹配或分析 JSON 损坏时不能进入新面试或 `REFINE`，事实解析和分析任务执行中均禁止 `REFINE`。画像响应增加可用于面试、可调整、任务代次和任务模式字段。新面试只读取当前可用且同事实版本的分析，已创建面试继续读取创建时保存的事实和分析快照。启动恢复只把遗留任务标记失败，不重放模型；事务提交后幂等释放可确定的 Redis 额度预留，释放成功后按 userId、generation/hash/token 清除恢复元数据，释放失败则保留元数据供下次启动重试，且不修改 `initial_model_call_started`。
- 实际修改对象：`ResumeProfileAnalysisStateService`、`ResumeProfileResponse`、`ResumeService`、`InterviewService`、`ResumeTaskRecoveryService`、`ResumeTaskRecoveryRunner`、`ResumePersistenceService`、`ResumeRepository`、`ResumeProfileAnalysisRepository`；`ResumeProfileAnalysisStateServiceTest`、`ResumeServiceTest`、`ResumePersistenceServiceTest`、`InterviewServiceTest`、`ResumeTaskRecoveryServiceTest`、`ResumeTaskRecoveryRunnerTest`。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：离线 Maven 定向测试 `mvn -o -DskipTests=false "-Dtest=ResumeProfileAnalysisStateServiceTest,ResumeServiceTest,ResumePersistenceServiceTest,InterviewServiceTest,ResumeTaskRecoveryServiceTest,ResumeTaskRecoveryRunnerTest" test` 通过；6 个测试类共 39 tests、0 failure、0 error、0 skipped，36 个测试源文件编译成功。`git --no-pager diff --check` 退出码 0，仅有既有 LF/CRLF 提示；V1/V2 无 Git 文本差异，V2 SHA-256 保持 `A3D99DD9B275E9FB47D31C02BD3B842109AD88D1FF6C33C1003122DB7C884E75`。静态搜索确认 Recovery 路径不发布任务事件、不调用 `LlmService`，也不把首次模型调用标记恢复为 false。
- 计划但未执行的验证：目标 MySQL 手工执行 V3、目标环境 Hibernate `validate`、真实 Redis 额度释放/故障重试、全量 `mvn test`、应用启动和 WP-06 只读审查。
- 审查 findings 状态：未审查；实施内静态核对不描述为审查通过。
- 待验证内容：WP-04 本地完成线已满足；数据库、真实 Redis、全量回归和最终审查留到 WP-05/WP-06。

#### 阻塞与风险

- 失败或阻塞：最终无阻塞；定向测试全部通过。
- 需要的条件或决策：无。
- 剩余风险：H2/Mock 测试不能替代目标 MySQL V3、Hibernate `validate` 和真实 Redis 恢复行为；应用未启动，全量回归未执行。

#### 关键记录

- 2026-07-30 15:27：T-003 完成后登记 T-004；尚未实施 WP-04。
- 2026-07-30 15:52：完成 WP-04 实现、静态边界核对和最终 6 类 39 个定向测试；T-004 标记已完成，登记 T-005 为未开始。

### T-005 执行数据库、Redis 与后端验证

- 状态：待验证
- 动作类型：验证
- 对应 plan 工作包：WP-05 数据库、Redis 与后端验证
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：T-001～T-004 已完成；当前只授权 backend 内 Maven 离线编译和相关定向单元测试，不授权数据库、应用启动、真实 Redis、全量 `mvn test`、联网下载或扩大范围命令。
- 下一步：T-006/T-007 已完成；按当前 AGENTS.md 的确认边界，由用户决定是否继续目标 MySQL Hibernate `validate`、真实 Redis 边界验证和应用启动。全量 `mvn test` 与当前“后端改动不允许单元测试”规则冲突，在规则未调整前不得执行。
- 恢复入口：从用户已完成的 V3 结构核对结果、目标环境配置、真实 Redis 验证入口和当前 AGENTS.md 验证约束恢复；既有离线证据位于 `backend/target/surefire-reports`。

#### 实际结果

- 实际完成内容：完成授权内生产代码离线编译、覆盖 WP-01～WP-04 的综合定向单元测试和迁移/隐私/恢复静态边界核对；2026-07-30 用户已在目标 MySQL 手工执行 V3，并提供 `information_schema.COLUMNS` 查询截图完成结构核对。未执行真实 Redis、Hibernate `validate`、应用启动、全量测试或联网操作。
- 实际修改对象：未修改业务代码、配置、V1、V2、frontend 或 gateway；Maven 仅更新 `backend/target` 下本地构建产物和 Surefire 报告。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：`mvn -o -DskipTests compile` 成功；随后离线执行 27 个明确相关测试类，覆盖分析状态、事实解析、准入/租约、Redis Lua 服务、上传补偿、Agent、Worker、事件监听、Controller、面试快照和启动恢复，共 117 tests、0 failure、0 error、0 skipped，BUILD SUCCESS。标准 `testCompile` 完成且当前类均为最新，Maven compiler 输入清单包含 36 个测试源文件。`git --no-pager diff --check` 退出码 0，仅输出既有 LF/CRLF 提示；V1/V2 无 Git 文本差异，V2 SHA-256 为 `A3D99DD9B275E9FB47D31C02BD3B842109AD88D1FF6C33C1003122DB7C884E75`；V3 静态核对包含可空结果 hash、独立 task hash/generation、首次调用标记、可用性及 quota 元数据；持久化/Repository/Redis 范围无 feedback，未发现 feedback 日志；Recovery 路径无事件重放、`LlmService` 或首次资格复位。用户随后提供目标 MySQL 查询截图，10 个 V3 字段均存在：`resume.parse_quota_date/parse_quota_token` 为可空，`resume_profile_analysis.source_profile_hash/task_profile_hash/task_mode/task_quota_date/task_quota_token` 为可空，`task_generation` 为 `BIGINT NOT NULL DEFAULT 0`，`initial_model_call_started/usable_for_interview` 为 `BIT(1) NOT NULL DEFAULT b'0'`，与 V3 预期一致。
- 计划但未执行的验证：目标环境 Hibernate `validate`、真实 Redis 用户 5/简历 1/额度/跨日/租约/故障恢复验证、应用启动、全量 `mvn test` 和联网依赖验证。全量测试当前受最新 AGENTS.md 禁止单元测试的规则约束，不能视为已授权或可执行。
- 审查 findings 状态：未审查。
- 待验证内容：目标 MySQL 字段结构已核对；仍缺 Hibernate 实体校验、真实 Redis、应用装配和全量回归，因此 T-005 不能标记已完成。

#### 阻塞与风险

- 失败或阻塞：授权内验证无失败。Maven 仍报告既有 protobuf POM 元数据、Commons Logging 和 ByteBuddy 动态 agent 警告；失败路径测试会按预期输出异常日志，但不构成测试失败。
- 需要的条件或决策：执行应用启动、Hibernate `validate` 或真实 Redis 验证前需要用户明确确认具体命令、目的和影响；全量 `mvn test` 还需要先解决其与当前 AGENTS.md 的规则冲突。
- 剩余风险：字段查询证明 V3 目标列结构符合预期，但尚不能替代 Hibernate `validate`；真实 Redis 多实例竞态、租约到期、跨午夜额度与应用装配仍无运行证据；全量回归尚无证据。

#### 关键记录

- 2026-07-30 15:52：T-004 完成后登记 T-005；尚未执行 WP-05。
- 2026-07-30 16:00：完成当前授权内离线编译、27 类 117 个综合定向测试和静态边界核对；因目标 MySQL、真实 Redis、应用启动和全量测试未执行，T-005 标记待验证；登记可基于现有实际证据独立推进的 T-006。
- 2026-07-30 16:51：用户确认已手工执行 V3，并提供目标 MySQL `information_schema.COLUMNS` 查询截图；10 个目标字段的类型、可空性和默认值与 V3 一致。T-005 继续待验证，缺口缩小为 Hibernate `validate`、真实 Redis、应用启动和全量回归。

### T-006 审查 Java/Spring 最终后端变更

- 状态：已完成
- 动作类型：只读审查
- 对应 plan 工作包：WP-06 Java/Spring 只读审查
- 对应 Skill：code-review-skill + wzh-java-backend
- 依赖：T-005 已产生授权内实际验证结果；T-005 未执行的外部环境验证不阻止只读审查，但仍阻止 Goal 关闭。
- 下一步：无；两轮整改后复审均已完成，剩余运行环境验证回到 T-005。
- 恢复入口：如需复核，从 `ResumeAiTaskOutcome`、`ResumeAiQuotaSettlement`、两个 Worker/状态服务、Submission/Listener/Recovery 结算链和上传补偿链恢复。

#### 实际结果

- 实际完成内容：先按风险优先完成本 Goal Java/Spring 后端变更的第一轮只读审查，发现 1 个 P1、2 个 P2 和 1 个非阻断 P3；T-007 整改后完成复审，确认原 1 个 P1 和 2 个 P2 已消除。复审期间新增发现“Redis Key 过期后数据库 token 可能永久阻塞”的 1 个 P2；T-007 补充关闭自然日清理规则后再次复审，最终未发现剩余确定性生产代码 finding。
- 实际修改对象：审查阶段未修改业务代码、配置、SQL或测试；仅由 Goal Mode 更新 `wzh_goal_input.md`、`wzh_goal_plan.md`、`wzh_goal_tasks.md` 的共享版本和实际进度。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：完成整改前审查、整改后复审及关闭自然日补丁后的再次复审；覆盖数据库终态确认、Redis 结算顺序、quota 恢复、Spring 事务代理与 Listener 装配、上传补偿顺序。最终未发现剩余确定性生产代码 finding。该结论仅为静态审查，不是测试通过；离线编译结果记录在 T-007。
- 计划但未执行的验证：当前规则禁止新增、修改或运行单元测试；未执行目标 MySQL Hibernate `validate`、真实 Redis、应用启动和全量 `mvn test`，继续由 T-005 记录。
- 审查 findings 状态：无阻断；第一轮 1 个 P1、2 个 P2 与复审新增 1 个 P2 均已解决，1 个 P3 保留为非阻断维护项。
- 第一轮正式 findings（历史记录，均已解决）：
  1. **P1：数据库提交结果不确定时可能把已经成功落库的任务按失败释放成功预留。** `ResumeParseWorker` 与 `ResumeProfileAnalysisWorker` 只有在 `stateService.complete(...)` 正常返回后才设置本地 `resultPersisted=true`；如果事务提交已在数据库生效、但连接在提交确认阶段抛错，后续 `fail(...)` 会因状态已经是 `SUCCEEDED/PENDING_CONFIRM` 而不改库，Worker 仍调用 `markFailed`。这样成功结果已经可用但 Redis 成功预留被释放，用户可以继续提交并突破每天 5 次成功的硬上限，违反“无法确认时 fail-closed”。修复需要让失败状态推进明确返回是否确认失败，并且只有在数据库能够确认当前任务未成功时才释放额度；无法确认时保留预留。
  2. **P2：运行、调度或提交失败会先清除持久化 quota 元数据，Redis 释放失败后无法由启动恢复重试。** `ResumeParseStateService.fail` 和 `ResumeProfileAnalysisStateService.fail` 无条件清空 quota date/token；Worker 和 `ResumeAiTaskSubmissionService` 随后才尝试 `markFailed`，两个 EventListener 虽先尝试释放，但即使释放抛错也继续调用清元数据的失败方法。Redis 暂时不可用时 token 留在 Redis、数据库恢复入口却已丢失，用户可能在当日余下时间持续被占用成功/尝试预留。修复需要把“确认 Redis 已释放后清元数据”和“释放失败保留元数据”做成一致的幂等顺序。
  3. **P2：上传失败补偿在确认数据库记录删除前就返还每日新建额度。** `ResumeUploadService.compensateFailedUpload` 先调用 `creationQuotaService.release`，随后才调用 `deleteCreatedForCompensation`。若数据库删除失败，简历行仍然存在但当日新建计数已经返还；之后删除残留行并重试可超过每天新建 5 份的语义。修复需要仅在数据库记录不存在或确认删除成功后返还已提交的新建额度；无法确认时保持 fail-closed。
- 复审新增 finding（历史记录，已解决）：**P2：Redis Key 过期后，明确终态的旧 quota token 可能因 Redis 返回 false 而永久保留在数据库，阻止新 generation。** 当前实现仅在数据库终态为 `SUCCESS_CONFIRMED/FAILURE_CONFIRMED` 且 quota 日期早于当前上海自然日时直接清理旧凭据；`UNKNOWN`、当前日和未来日仍保持 fail-closed。
- 非阻断维护项：`ResumeProfileAnalysisStateService.complete` 会更新 schema、prompt 和生成时间，但不更新或清理 `modelName`；当前没有生产写入点和消费者，按 P3 保留。
- 待验证内容：现有相关测试仍是整改前语义，不能作为当前实现证据；真实 Redis、多实例竞态、锁异常恢复、Hibernate `validate` 和应用装配仍缺运行证据。

#### 阻塞与风险

- 失败或阻塞：无；阻断 findings 已在当前确认方案内修复并完成静态复审。
- 需要的条件或决策：T-006 无新增决策；运行环境验证由 T-005 按授权边界继续。
- 剩余风险：人工改库或数据损坏导致 quota 日期/token 仅一项非空时会失败关闭且无法自动恢复；目标 MySQL Hibernate `validate`、真实 Redis、多实例竞态、锁异常恢复、应用装配和全量回归仍无运行证据。

#### 关键记录

- 2026-07-30 16:00：T-005 产生本地验证结果后登记 T-006；尚未开始 WP-06。
- 2026-07-30 16:28：完成 WP-06 第一轮只读审查，确认 1 个 P1、2 个 P2 和 1 个 P3；未修改业务代码。T-006 因高优先级 finding 保持进行中，等待独立实施轮次修复后复审。
- 2026-07-30 17:33：用户授权按已确认方案实施三个 finding；登记 T-007，T-006 保持进行中并等待整改后复审。
- 2026-07-30 18:26：整改后复审确认原 findings 已消除；复审新增的旧日期 Redis Key 过期 P2 也已在 T-007 修复并再次复审。最终无剩余确定性生产代码 finding，T-006 标记已完成。

### T-007 修复终态额度结算与上传补偿

- 状态：已完成
- 动作类型：实施
- 对应 plan 工作包：WP-07 审查 findings 一致性整改
- 对应 Skill：wzh-dev-collab + wzh-java-backend
- 依赖：T-006 已提供三个正式 finding 及修复边界；用户已授权实施；不需要修改 V3。
- 下一步：无；WP-07 已完成，剩余运行验证回到 T-005。
- 恢复入口：如需复核，从 `ResumeAiTaskOutcome`、`ResumeAiQuotaSettlement`、两个 Worker/状态服务、`ResumeAiTaskSubmissionService`、两个 EventListener、Recovery Service/Runner、`ResumeAiQuotaService` 和上传补偿链恢复。

#### 实际结果

- 实际完成内容：新增数据库任务终态三态结果和统一 quota 结算 helper；解析/分析 Worker、Submission、Listener 和启动 Recovery 均先确认数据库为成功、失败或未知，再决定 Redis 转换，未知结果不结算。Redis 返回 false 或抛错时保留数据库 quota 凭据，转换成功后才用 user、generation、hash、date、token 守卫清理；已关闭的上海自然日仅在数据库终态明确时清理旧 token，避免 Key 过期后永久阻塞。`complete/fail` 不再提前清凭据，未结算 token 禁止被新 generation 覆盖。上传补偿改为尽力释放 lease、确认数据库删除或不存在、返还新建额度、最后删除文件；建档提交确认异常后仍利用已分配 ID 做补偿确认。
- 实际修改对象：新增 `ResumeAiTaskOutcome.java`、`ResumeAiQuotaSettlement.java`；修改 `ResumeParseStateService.java`、`ResumeParseWorker.java`、`ResumeParseEventListener.java`、`ResumeProfileAnalysisStateService.java`、`ResumeProfileAnalysisWorker.java`、`ResumeProfileAnalysisEventListener.java`、`ResumeAiTaskSubmissionService.java`、`ResumeTaskRecoveryService.java`、`ResumeTaskRecoveryRunner.java`、`ResumeAiQuotaService.java`、`ResumeUploadService.java`、`ResumePersistenceService.java`。未修改 V1、V2、V3、测试、frontend 或 gateway。
- 是否偏离 plan：否。

#### 验证与审查

- 已执行验证及结果：整改后完成 Java/Spring 静态复审，原 1 个 P1、2 个 P2 及复审新增的旧日期 token P2 均已消除，最终无剩余确定性生产代码 finding；该结果不是测试通过。在 `backend/` 执行 `mvn -o -DskipTests compile`，编译 221 个主代码 source files，结果 `BUILD SUCCESS`；未编译、未运行测试，未联网、未启动应用、未访问数据库或 Redis。Maven 警告为既有 protobuf POM 元数据、注解处理提示和 `ChatClientFactory` 过时 API 提示。
- 计划但未执行的验证：当前规则禁止新增、修改或运行单元测试，因此未执行定向测试、`testCompile` 或全量 `mvn test`；未执行 Hibernate `validate`、真实 Redis、多实例竞态、应用启动或联网验证。
- 审查 findings 状态：无阻断。
- 待验证内容：现有 Worker/Listener/StateService/Upload 测试仍断言整改前语义或 Mock 旧重载，不能作为当前实现证据；真实 Redis、Hibernate `validate`、应用装配和全量回归继续由 T-005 记录。

#### 阻塞与风险

- 失败或阻塞：无；授权内离线主代码编译成功。
- 需要的条件或决策：T-007 无新增决策；后续运行验证由 T-005 取得对应命令授权。
- 剩余风险：提交确认未知和当前日 Redis 暂时不可用时会保留预留并 fail-closed；人工造成 quota 日期/token 部分缺失时需人工修复；现有测试与新语义失配，运行环境证据仍待 T-005。

#### 关键记录

- 2026-07-30 17:33：用户明确要求按已确认修复方案开始修改；T-007 进入进行中。
- 2026-07-30 18:26：完成终态结算、恢复和上传补偿整改，静态复审最终无阻断 finding；离线主代码编译 `BUILD SUCCESS`，未编译或运行测试。T-007 标记已完成，当前 Task 切回 T-005 待验证。

## Goal 关闭与重开

- 当前关闭状态：未关闭
- 关闭时间：无
- 完成依据：尚未满足 plan 完成标准 C-001～C-015。
- 未完成或取消项：V3 已在目标 MySQL 执行并完成字段结构核对；T-001～T-004、T-006、T-007 已完成。T-005 因 Hibernate `validate`、真实 Redis、应用启动和全量测试未执行而待验证。
- 用户主观验收：不需要；数据库手工执行属于用户操作证据，不是界面主观验收。
- 剩余风险：见 plan“风险与回退”、T-005 未验证项、现有测试与整改后语义失配，以及真实环境运行风险。
- 重开记录：无。
