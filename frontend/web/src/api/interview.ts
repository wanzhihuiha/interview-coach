import request from './request'
import { apiCall } from './request'
import { getToken } from '@/utils/storage'
import type {
  ApiResponse,
  InterviewSession,
  InterviewPhase,
  InterviewReport,
  GrowthPlan,
  CreateInterviewResponse,
  InterviewDetail,
  InterviewMessage,
  InterviewReportDto
} from '@/types'

const PHASE_META: Record<string, { key: string; description: string; questionCount: number }> = {
  SELF_INTRO: { key: 'intro', description: '热身放松，了解基本信息', questionCount: 1 },
  PROFESSIONAL: { key: 'professional', description: '深度提问，考察专业技能', questionCount: 8 },
  RESUME_DISCUSSION: { key: 'resume', description: '项目追问，深入了解项目经历', questionCount: 3 },
  BEHAVIORAL: { key: 'behavior', description: 'STAR问题，考察软技能', questionCount: 4 },
  ENDING: { key: 'ending', description: '面试结束，生成报告', questionCount: 0 }
}

function mapStatus(status: string): InterviewSession['status'] {
  switch (status) {
    case 'ENDED': return 'completed'
    case 'INTERRUPTED': return 'interrupted'
    default: return 'ongoing'
  }
}

function buildPhases(
  selectedPhases: string[],
  currentPhase: string,
  phaseLabels: Record<string, string> = {}
): InterviewPhase[] {
  return selectedPhases.map((phaseName) => {
    const meta = PHASE_META[phaseName] || { key: phaseName.toLowerCase(), description: '', questionCount: 0 }
    return {
      key: meta.key,
      name: phaseLabels[phaseName] || '未知环节',
      description: meta.description,
      questionCount: meta.questionCount,
      completed: false,
      current: phaseName === currentPhase
    }
  })
}

function markCompleted(phases: InterviewPhase[], currentPhaseKey: string): InterviewPhase[] {
  let reachedCurrent = false
  return phases.map((p) => {
    const isCurrent = p.key === currentPhaseKey
    if (isCurrent) reachedCurrent = true
    return {
      ...p,
      current: isCurrent,
      completed: !reachedCurrent && !isCurrent
    }
  })
}

function mapDetailToSession(detail: InterviewDetail): InterviewSession {
  const currentPhaseKey = normalizePhaseKey(detail.currentPhase)
  const phases = markCompleted(
    buildPhases(detail.selectedPhases, detail.currentPhase, detail.phaseLabels),
    currentPhaseKey
  )
  return {
    id: detail.interviewId,
    positionTitle: detail.positionTitle,
    company: detail.companyName || '',
    status: mapStatus(detail.status),
    statusLabel: detail.statusLabel,
    score: detail.overallScore,
    level: detail.grade,
    startTime: detail.startedAt ? new Date(detail.startedAt).toLocaleString('zh-CN') : '',
    phases,
    currentPhase: detail.currentPhase,
    currentPhaseLabel: detail.currentPhaseLabel
  }
}

function normalizePhaseKey(phase: string): string {
  switch (phase) {
    case 'SELF_INTRO': return 'intro'
    case 'PROFESSIONAL': return 'professional'
    case 'RESUME_DISCUSSION': return 'resume'
    case 'BEHAVIORAL': return 'behavior'
    case 'ENDING': return 'ending'
    default: return phase.toLowerCase()
  }
}

const mockDetail: InterviewDetail = {
  interviewId: 1001,
  resumeId: 1,
  positionId: 1,
  status: 'IN_PROGRESS',
  statusLabel: '进行中',
  currentPhase: 'PROFESSIONAL',
  currentPhaseLabel: '专业面试',
  selectedPhases: ['SELF_INTRO', 'PROFESSIONAL', 'RESUME_DISCUSSION', 'BEHAVIORAL', 'ENDING'],
  phaseLabels: {
    SELF_INTRO: '自我介绍',
    PROFESSIONAL: '专业面试',
    RESUME_DISCUSSION: '简历探讨',
    BEHAVIORAL: '行为面试',
    ENDING: '结束'
  },
  positionTitle: 'Java开发',
  companyName: '字节跳动',
  startedAt: '2024-01-20T10:00:00'
}

const mockReport: InterviewReportDto = {
  interviewId: 1001,
  overallScore: 78,
  grade: '良好',
  phases: {
    SELF_INTRO: { phaseLabel: '自我介绍', completed: true, questionCount: 1 },
    PROFESSIONAL: { phaseLabel: '专业面试', completed: true, questionCount: 8 },
    RESUME_DISCUSSION: { phaseLabel: '简历探讨', completed: true, questionCount: 3 },
    BEHAVIORAL: { phaseLabel: '行为面试', completed: true, questionCount: 4 },
    ENDING: { phaseLabel: '结束', completed: true, questionCount: 0 }
  },
  dimensions: {
    technicalDepth: 80,
    technicalBreadth: 75,
    practicalExperience: 82,
    expression: 75,
    learningAbility: 74
  },
  weaknesses: [
    'Redis 缓存一致性问题 - 不够深入',
    'Docker 容器网络配置 - 实践经验较少',
    '分布式事务解决方案 - 了解不全面'
  ],
  strengths: [
    'Java 并发编程 - 原理理解深入，有源码阅读经验',
    'MySQL 索引原理 - 分析到位，能讲清楚底层结构'
  ],
  conclusion: '本次面试已完成，候选人整体表现良好。',
  mdContent: '# 面试评估报告\n\n## 一、综合评分\n\n- 综合评分：78\n- 等级：良好\n\n## 二、维度得分\n\n| 维度 | 得分 |\n|---|---|\n| 技术深度 | 80 |\n| 技术广度 | 75 |\n| 实践经验 | 82 |\n| 表达能力 | 75 |\n| 学习能力 | 74 |\n\n## 三、环节完成情况\n\n| 环节 | 题目数 | 状态 |\n|---|---|---|\n| 自我介绍 | 2 | 已完成 |\n| 专业面试 | 8 | 已完成 |\n| 简历探讨 | 3 | 已完成 |\n| 行为面试 | 4 | 已完成 |\n| 结束 | 0 | 已完成 |\n\n## 四、优势知识点\n\n- Java 并发编程 - 原理理解深入，有源码阅读经验\n- MySQL 索引原理 - 分析到位，能讲清楚底层结构\n\n## 五、薄弱知识点\n\n- Redis 缓存一致性问题 - 不够深入\n- Docker 容器网络配置 - 实践经验较少\n- 分布式事务解决方案 - 了解不全面\n\n## 六、综合评价\n\n本次面试已完成，候选人整体表现良好。建议继续加强 Redis、Docker 和分布式事务相关知识的实践。'
}

const mockGrowthPlan: GrowthPlan = {
  id: 1001,
  positionTitle: 'Java开发',
  learningPath: [
    { phase: '第一周：Redis 基础与缓存策略', duration: '1 周', goal: '掌握 Redis 核心数据结构与缓存策略', tasks: ['学习 Redis 数据结构和命令', '理解缓存读写策略（Cache-Aside/Write-Behind）'], resources: [] },
    { phase: '第二周：缓存一致性深入', duration: '1-2 周', goal: '能够设计并落地缓存一致性方案', tasks: ['学习分布式锁实现', '实践双写一致性方案'], resources: [] }
  ],
  knowledgeGaps: [
    { topic: 'Redis 缓存一致性问题', description: '面试中针对 Redis 缓存一致性的回答未能体现系统性理解，建议从核心概念、典型方案、实践踩坑三个层面补全。', importance: '高', keywords: ['核心概念', '常见方案', '实践案例', '面试题'] },
    { topic: 'Docker 容器网络配置', description: '面试中针对 Docker 网络配置的回答未能体现系统性理解，建议从核心概念、典型方案、实践踩坑三个层面补全。', importance: '中', keywords: ['核心概念', '常见方案', '实践案例', '面试题'] }
  ],
  exercises: [
    { title: '练习 1：设计缓存一致性方案', content: '请设计一个满足高并发场景下的缓存一致性方案...' }
  ],
  mdContent: '# 成长方案\n\n## 一、学习路径\n\n### 第一周：Redis 基础与缓存策略\n...'
}

export async function startInterview(config: {
  resumeId: number
  positionId: number
  phases: string[]
}): Promise<InterviewSession> {
  const phaseKeyMap: Record<string, string> = {
    intro: 'SELF_INTRO',
    professional: 'PROFESSIONAL',
    resume: 'RESUME_DISCUSSION',
    behavior: 'BEHAVIORAL'
  }
  const payload = {
    resumeId: config.resumeId,
    positionId: config.positionId,
    selectedPhases: config.phases.map((p) => phaseKeyMap[p] || p.toUpperCase())
  }
  const res = await apiCall(
    () => request.post('/interviews', payload) as Promise<ApiResponse<CreateInterviewResponse>>,
    () => ({
      interviewId: Date.now(),
      status: 'IN_PROGRESS',
      statusLabel: '进行中',
      currentPhase: 'SELF_INTRO',
      currentPhaseLabel: '自我介绍',
      selectedPhases: ['SELF_INTRO', 'PROFESSIONAL', 'RESUME_DISCUSSION', 'BEHAVIORAL', 'ENDING'],
      firstQuestion: '请先做一个简单的自我介绍。',
      phaseOrder: ['SELF_INTRO', 'PROFESSIONAL', 'RESUME_DISCUSSION', 'BEHAVIORAL', 'ENDING'],
      phaseLabels: {
        SELF_INTRO: '自我介绍',
        PROFESSIONAL: '专业面试',
        RESUME_DISCUSSION: '简历探讨',
        BEHAVIORAL: '行为面试',
        ENDING: '结束'
      }
    })
  )
  const data = res.data
  return {
    id: data.interviewId,
    positionTitle: '',
    company: '',
    status: mapStatus(data.status),
    statusLabel: data.statusLabel,
    startTime: '',
    phases: buildPhases(data.phaseOrder, data.currentPhase, data.phaseLabels),
    currentPhase: data.currentPhase,
    currentPhaseLabel: data.currentPhaseLabel
  }
}

export async function getInterview(id: number): Promise<InterviewSession | null> {
  const res = await apiCall(
    () => request.get(`/interviews/${id}`) as Promise<ApiResponse<InterviewDetail>>,
    () => mockDetail,
    null
  )
  return res.data ? mapDetailToSession(res.data) : null
}

export async function getInterviewMessages(id: number): Promise<InterviewMessage[]> {
  const res = await apiCall(
    () => request.get(`/interviews/${id}/messages`) as Promise<ApiResponse<InterviewMessage[]>>,
    () => [],
    []
  )
  return res.data || []
}

export interface AnswerEvent {
  type: 'thinking' | 'phaseChange' | 'question' | 'interviewEnd' | 'done' | 'error'
  content?: string
  phase?: string
  phaseLabel?: string
  depth?: number
  topicId?: string
  topicName?: string
  previousPhase?: string
  previousPhaseLabel?: string
  currentPhase?: string
  currentPhaseLabel?: string
  code?: string
  message?: string
  fallback?: boolean
}

export function submitAnswer(
  id: number,
  answer: string,
  onEvent: (event: AnswerEvent) => void
): { abort: () => void } {
  const controller = new AbortController()
  fetch(`/api/v1/interviews/${id}/answer`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken() || ''}`
    },
    body: JSON.stringify({ answer }),
    signal: controller.signal
  }).then(async (response) => {
    if (!response.ok) {
      const text = await response.text().catch(() => '请求失败')
      onEvent({ type: 'error', message: text })
      return
    }
    const reader = response.body?.getReader()
    if (!reader) return
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue
        const jsonStr = trimmed.slice(5).trim()
        if (!jsonStr) continue
        try {
          const event = JSON.parse(jsonStr) as AnswerEvent
          onEvent(event)
        } catch (e) {
          console.warn('[SSE] 解析事件失败', jsonStr)
        }
      }
    }
  }).catch((err) => {
    if (err.name !== 'AbortError') {
      onEvent({ type: 'error', message: err.message || '网络错误' })
    }
  })
  return { abort: () => controller.abort() }
}

export async function endInterview(id: number): Promise<InterviewSession | null> {
  const res = await apiCall(
    () => request.post(`/interviews/${id}/end`) as Promise<ApiResponse<InterviewDetail>>,
    () => null,
    null
  )
  return res.data ? mapDetailToSession(res.data) : null
}

export async function getInterviewReport(id: number): Promise<InterviewReport | null> {
  const res = await apiCall(
    () => request.get(`/interviews/${id}/report`) as Promise<ApiResponse<InterviewReportDto>>,
    () => mockReport,
    null
  )
  const dto = res.data
  if (!dto) return null
  return {
    id: dto.interviewId,
    positionTitle: '',
    totalScore: dto.overallScore,
    level: dto.grade,
    scores: [
      { name: '技术深度', score: dto.dimensions.technicalDepth },
      { name: '技术广度', score: dto.dimensions.technicalBreadth },
      { name: '实践经验', score: dto.dimensions.practicalExperience },
      { name: '表达能力', score: dto.dimensions.expression },
      { name: '学习能力', score: dto.dimensions.learningAbility }
    ],
    phaseSummary: Object.entries(dto.phases).map(([, summary]) => {
      return `${summary.phaseLabel || '未知环节'} (${summary.questionCount}题) ${summary.completed ? '· 已完成' : ''}`
    }),
    weakPoints: dto.weaknesses.length ? dto.weaknesses : ['部分问题可进一步深入'],
    strongPoints: dto.strengths.length ? dto.strengths : ['回答态度积极'],
    mdContent: dto.mdContent || ''
  }
}

export async function getGrowthPlan(id: number): Promise<GrowthPlan | null> {
  const res = await apiCall(
    () => request.get(`/interviews/${id}/growth-plan`, { timeout: 90000 }) as Promise<ApiResponse<GrowthPlan>>,
    () => mockGrowthPlan,
    null
  )
  return res.data
}

export async function getInterviewHistory(): Promise<InterviewSession[]> {
  const res = await apiCall(
    () => request.get('/interviews') as Promise<ApiResponse<InterviewDetail[]>>,
    () => [
      { ...mockDetail, status: 'ENDED', statusLabel: '已结束', overallScore: 78, grade: '良好' },
      { ...mockDetail, interviewId: 1002, positionTitle: '前端开发', companyName: '腾讯', status: 'ENDED', statusLabel: '已结束', overallScore: 85, grade: '优秀' },
      { ...mockDetail, interviewId: 1003, positionTitle: '产品经理', companyName: '阿里巴巴', status: 'INTERRUPTED', statusLabel: '已中断' }
    ],
    []
  )
  return (res.data || []).map(mapDetailToSession)
}
