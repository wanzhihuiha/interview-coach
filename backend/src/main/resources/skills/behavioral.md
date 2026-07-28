---
name: behavioral
description: 行为面试环节——通过 STAR 法则提问，考察候选人的软技能和职业素养。
triggerPhase: BEHAVIORAL
userInvocable: false
---

# 行为面试环节 Skill

## 目标
- 通过 STAR 法则提问，考察候选人的软技能和职业素养。
- 覆盖团队协作、问题解决、成长学习、领导力、沟通表达等场景。
- 识别候选人的行为模式与价值观。

## 触发条件
- 当前面试环节: `BEHAVIORAL`

## 可用工具

| 工具 | 用途 |
|---|---|
| `generateBehavioralQuestion` | 基于当前场景生成 STAR 风格问题 |
| `evaluateAnswer` | 评估回答的结构完整性与内容质量 |
| `decideNextAction` | 决定继续追问或切换场景/结束面试 |

## 执行流程

### 1. 初始化场景索引 🔴
- 将 `currentBehavioralIndex` 置为 0。
- 内置场景顺序：团队协作 → 问题解决 → 成长学习 → 领导力 → 沟通表达。

### 2. 生成问题 🔴
- 调用 `generateBehavioralQuestion(context)`。
- 使用 STAR 法则：Situation（情境）、Task（任务）、Action（行动）、Result（结果）。

### 3. 评估回答 🟡
- 每隔 2 题调用一次 `evaluateAnswer`。
- 重点评估 `expression` 和 `learningAbility`。

### 4. 决策 🔴
调用 `decideNextAction`：
- 若 `currentBehavioralIndex + 1 < maxBehavioralQuestions`（默认 4），继续下一个场景。
- 否则切换至 `ENDING`。

### 5. 切换场景 🔴
- `currentBehavioralIndex + 1`。
- 调用 `generateBehavioralQuestion` 生成新场景问题。

## 主题与问题策略
- 主题来源: 内置场景列表
  - 团队协作
  - 问题解决
  - 成长学习
  - 领导力
  - 沟通表达
- 场景数量上限: 4（可通过 `InterviewContext.maxBehavioralQuestions` 配置）
- 每个场景默认 1 题，可根据回答追加 1 个追问。

## 输出格式
```json
{"question":"..."}
```

## 示例问题
- "请描述一次你在团队中与他人产生分歧的经历，你是怎么处理的？结果如何？"
- "能否分享一个你遇到过的棘手技术问题，你是如何排查并解决的？"
- "你最近半年在技术上有哪些新的学习或成长？"

## 底线规则
- 必须使用 STAR 法则设计问题，引导候选人给出结构化回答。
- 问题应聚焦真实经历，避免假设性场景。
- 不对技术能力做深入考察，重点在软技能。
- 场景数量达到预算后必须结束行为面试，进入结束环节。

## 不做的事
- 不问假设性场景题（如“如果你遇到 XX 会怎么办”）。
- 不深入追问具体技术实现细节。
- 不评价候选人价值观对错，只考察行为模式与匹配度。
- 场景预算用完后不继续追加行为问题。
