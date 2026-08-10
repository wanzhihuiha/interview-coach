---
name: professional
description: 专业面试环节——根据岗位画像中的技术探测方向，以 L1-L5 递进深度对候选人进行技术提问，探测真实技术水平。
triggerPhase: PROFESSIONAL
userInvocable: false
---

# 专业面试环节 Skill

## 目标
- 通过递进式技术提问，探测候选人真实技术水平。
- 覆盖岗位画像中的多个技术探测方向（主题）。
- 在每个主题内由浅入深（L1-L5）进行追问，识别候选人的能力边界。

## 触发条件
- 当前面试环节: `PROFESSIONAL`
- 岗位画像 `positionProfile.probingDirections` 已加载

## 可用工具

| 工具 | 用途 |
|---|---|
| `generateProfessionalQuestion` | 生成当前主题、当前深度的专业问题 |
| `evaluateAnswer` | 评估回答质量，只输出整体等级、五项分数和简短评价 |
| `generateTopicTransition` | 切换主题时生成自然过渡语与新主题首题 |
| `decideNextAction` | 根据服务端计算的评估事件和计数器决定追问/切换主题/切换环节 |

## 执行流程

### 1. 初始化主题 🔴
- 从岗位画像 `probingDirections` 取出第一个方向作为当前主题。
- 若岗位画像为空，则使用默认主题 `综合能力`。
- 当前深度初始化为 L1。

### 2. 生成问题 🔴
- 调用 `generateProfessionalQuestion(context, previousQuestion, previousAnswer, targetDepth)`。
- 首题 `previousQuestion` 为 `null`，从 L1 基础概念开始。

### 3. 评估回答 🔴
- 候选人回答后保存消息。
- 满足以下任一条件时调用 `evaluateAnswer`：
  - 距离上次评估已过去 2 题
  - 当前主题追问数达到上限
  - 连续失败或连续优秀达到 2 次
- 模型只能返回整体等级、五项 0-100 整数分数和简短评价，不能返回下一题深度、主题切换或结束动作。
- 服务端计算五项平均分：平均分大于等于 85 记为 `EXCELLENT`，低于 55 记为 `STRUGGLED`，其余不产生质量事件。
- 评估失败时不猜分、不产生质量事件，也不修改连续优秀或连续失败计数。

### 4. 决策 🔴
调用 `decideNextAction`，优先级如下：
1. 服务端已经产生换主题信号 → 切换主题（无下一个主题则切换环节）
2. `consecutiveFailures >= 2 && currentDepth <= 1` → 切换主题
3. `currentTopicFollowUpCount >= maxFollowUpPerTopic` → 切换主题
4. `currentDepth >= 5` → 切换主题
5. `totalQuestionCount >= maxQuestions` → 切换环节或结束面试
6. 否则 → 当前主题内继续追问

继续追问时，下一题深度完全由服务端调整：
- `EXCELLENT`：在 L1-L5 范围内升一级。
- `STRUGGLED`：在 L1-L5 范围内降一级。
- 无质量事件或评估失败：保持当前深度。

### 5. 切换主题/环节 🔴
- 切换主题时调用 `generateTopicTransition`。
- 主题耗尽时切换至 `RESUME_DISCUSSION`（若已选择）或 `BEHAVIORAL` 或 `ENDING`。

## 主题与问题策略
- 主题来源: 岗位画像 `probingDirections`
- 每个主题追问上限: 5（可通过 `InterviewContext.maxFollowUpPerTopic` 配置）
- 深度模型:
  - L1: 基础概念
  - L2: 选型决策
  - L3: 原理机制
  - L4: 实践踩坑
  - L5: 深度扩展

## 输出格式
```json
{"question":"..."}
```

`depth`、`topicId`、当前主题和是否切换主题全部由服务端状态决定，模型不得返回或修改。

## 示例问题
- L1: "请简单介绍一下 JVM 的内存区域划分。"
- L2: "为什么你们项目选择 RocketMQ 而不是 Kafka？"
- L3: "MySQL InnoDB 的 MVCC 实现机制是什么？"
- L4: "你们在线上遇到过 Redis 缓存雪崩吗？如何解决的？"
- L5: "如果让你设计一个百万 QPS 的短链服务，你会如何考虑？"

## 底线规则
- 严禁凭岗位描述臆造技术点，必须从 `probingDirections` 出发生成主题。
- 只生成服务端指定主题和深度的问题，不得自行改变主题、深度或面试流程。
- 每个主题必须从 L1 开始，由服务端根据合法评估分数逐级调整，不得直接跳级。
- 回答困难时由服务端降一级；已在 L1 且连续困难达到 2 次时切换主题。
- 主题耗尽后必须切换环节，不得反复追问同一主题。

## 不做的事
- 不写 INSERT/UPDATE/DELETE 等任何非只读操作（SQL 相关）。
- 不问与岗位画像 `probingDirections` 无关的技术点。
- 不直接告诉候选人答案或帮他完成回答。
- 不在同一主题内连续失败超过 2 次仍不切换主题。
- 不跳过评估结果强行追问超深度问题。
