---
name: resume-discussion
description: 简历探讨环节——深入探讨候选人简历中的项目经历，验证真实性与技术深度。
triggerPhase: RESUME_DISCUSSION
userInvocable: false
---

# 简历探讨环节 Skill

## 目标
- 深入探讨简历中的项目经历，验证真实性与技术深度。
- 了解候选人在项目中的角色、贡献、技术挑战与成果。
- 将项目经历与岗位要求进行匹配评估。

## 触发条件
- 当前面试环节: `RESUME_DISCUSSION`
- 候选人简历 `userProfile.projectExperience` 已加载

## 可用工具

| 工具 | 用途 |
|---|---|
| `generateResumeQuestion` | 基于当前项目生成深入探讨问题 |
| `evaluateAnswer` | 评估回答的真实性与技术深度 |
| `decideNextAction` | 决定继续追问当前项目或切换项目/环节 |

## 执行流程

### 1. 初始化项目索引 🔴
- 将 `currentProjectIndex` 置为 0，从第一个项目开始探讨。
- 将 `currentPhaseQuestionCount` 置为 0。

### 2. 生成问题 🔴
- 调用 `generateResumeQuestion(context)`。
- 问题应围绕当前项目的：背景、架构、角色、挑战、成果、技术选型。

### 3. 评估回答 🔴
- 每道题都调用 `evaluateAnswer`。
- 重点评估 `practicalExperience` 和 `technicalDepth`。

### 4. 决策 🔴
调用 `decideNextAction`：
- 若当前项目问题数 < `maxResumeQuestionsPerProject`（默认 3），继续追问当前项目。
- 若当前项目问题数达到上限且存在下一个项目，切换项目（`SWITCH_TOPIC`）。
- 若当前项目问题数达到上限且不存在下一个项目，切换至 `BEHAVIORAL` 或 `ENDING`。

### 5. 切换项目 🔴
- `currentProjectIndex + 1`。
- `currentPhaseQuestionCount` 重置为 0。
- 调用 `generateResumeQuestion` 生成新项目问题。

## 主题与问题策略
- 主题来源: 候选人简历 `projectExperience`
- 每个项目问题数上限: 3（可通过 `InterviewContext.maxResumeQuestionsPerProject` 配置）
- 追问方向:
  1. 项目背景与业务目标
  2. 技术架构与选型理由
  3. 候选人具体职责
  4. 遇到的技术挑战与解决方案
  5. 成果量化（性能、稳定性、效率等）

## 输出格式
```json
{"question":"..."}
```

## 示例问题
- "请介绍一下你简历中提到的 XX 项目的背景和你的主要职责。"
- "在这个项目中，你们为什么选择微服务架构？遇到过哪些拆分后的挑战？"
- "你提到性能提升了 30%，能具体说说优化前后是怎么测量的吗？"

## 底线规则
- 必须基于候选人真实简历项目提问，不得虚构项目。
- 问题要具体，避免泛泛而谈。
- 对回答中的关键数字和成果必须进行追问验证。
- 项目耗尽后必须切换环节。

## 不做的事
- 不问简历中没有的项目或技术栈。
- 不做道德审判或质疑候选人诚实性（只验证技术深度与一致性）。
- 不泛泛提问如“介绍一下你的项目”而不聚焦具体职责与挑战。
- 项目问完后不继续纠缠同一项目。
