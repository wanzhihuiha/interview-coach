import Taro from '@tarojs/taro';
import request, { withMockFallback } from './request';
import { getToken } from '@/utils/storage';
import type { ApiResponse } from './request';
import { isMockEnabled } from '@/constants/env';
import {
  mockInterviews,
  mockInterviewDetail,
  mockInterviewMessages,
  mockInterviewReport,
  mockGrowthPlan,
} from '@/data/mock';

export interface InterviewPhase {
  key: string;
  name: string;
  description: string;
  questionCount: number;
  completed: boolean;
  current: boolean;
}

export interface InterviewSession {
  id: number;
  positionTitle: string;
  company: string;
  status: 'ongoing' | 'completed' | 'interrupted';
  score?: number;
  level?: string;
  startTime: string;
  phases: InterviewPhase[];
  currentPhase?: string;
}

export interface InterviewMessage {
  messageId: number;
  phase: string;
  role: 'interviewer' | 'candidate';
  content: string;
  topic?: string;
  depth?: number;
  seqNo: number;
  createdAt: string;
}

export interface InterviewReport {
  id: number;
  positionTitle: string;
  totalScore: number;
  level: string;
  scores: { name: string; score: number }[];
  phaseSummary: string[];
  weakPoints: string[];
  strongPoints: string[];
  mdContent: string;
}

export interface GrowthPlan {
  id: number;
  positionTitle: string;
  learningPath: {
    phase: string;
    duration?: string;
    goal?: string;
    tasks: string[];
    resources?: { name: string; type: string; url: string }[];
  }[];
  exercises: { title: string; content: string }[];
  knowledgeGaps: {
    topic: string;
    description: string;
    importance: string;
    keywords: string[];
  }[];
  mdContent: string;
}

interface CreateInterviewResponse {
  interviewId: number;
  status: string;
  currentPhase: string;
  selectedPhases: string[];
  firstQuestion: string;
  phaseOrder: string[];
}

interface InterviewDetail {
  interviewId: number;
  resumeId: number;
  positionId: number;
  status: string;
  currentPhase: string;
  currentTopic?: string;
  currentDepth?: number;
  totalQuestionCount?: number;
  selectedPhases: string[];
  pendingQuestion?: string;
  positionTitle: string;
  companyName: string;
  overallScore?: number;
  grade?: string;
  startedAt?: string;
  endedAt?: string;
}

interface ReportDto {
  interviewId: number;
  overallScore: number;
  grade: string;
  phases: Record<string, { completed: boolean; questionCount: number }>;
  dimensions: {
    technicalDepth: number;
    technicalBreadth: number;
    practicalExperience: number;
    expression: number;
    learningAbility: number;
  };
  strengths: string[];
  weaknesses: string[];
  conclusion: string;
  mdContent: string;
}

const PHASE_META: Record<string, { key: string; name: string; description: string; questionCount: number }> = {
  SELF_INTRO: { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1 },
  PROFESSIONAL: { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8 },
  RESUME_DISCUSSION: { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3 },
  BEHAVIORAL: { key: 'behavior', name: '行为面试', description: 'STAR问题，考察软技能', questionCount: 4 },
  ENDING: { key: 'ending', name: '结束', description: '面试结束，生成报告', questionCount: 0 },
};

const PHASE_NAMES: Record<string, string> = {
  SELF_INTRO: '自我介绍',
  PROFESSIONAL: '专业面试',
  RESUME_DISCUSSION: '简历探讨',
  BEHAVIORAL: '行为面试',
  ENDING: '结束',
};

function normalizePhaseKey(phase: string): string {
  switch (phase) {
    case 'SELF_INTRO': return 'intro';
    case 'PROFESSIONAL': return 'professional';
    case 'RESUME_DISCUSSION': return 'resume';
    case 'BEHAVIORAL': return 'behavior';
    case 'ENDING': return 'ending';
    default: return phase.toLowerCase();
  }
}

function buildPhases(selectedPhases: string[], currentPhase: string): InterviewPhase[] {
  return selectedPhases.map((phaseName) => {
    const meta = PHASE_META[phaseName] || { key: phaseName.toLowerCase(), name: phaseName, description: '', questionCount: 0 };
    return {
      key: meta.key,
      name: meta.name,
      description: meta.description,
      questionCount: meta.questionCount,
      completed: false,
      current: phaseName === currentPhase,
    };
  });
}

function markCompleted(phases: InterviewPhase[], currentPhaseKey: string): InterviewPhase[] {
  let reachedCurrent = false;
  return phases.map((p) => {
    const isCurrent = p.key === currentPhaseKey;
    if (isCurrent) reachedCurrent = true;
    return { ...p, current: isCurrent, completed: !reachedCurrent && !isCurrent };
  });
}

function mapDetailToSession(detail: InterviewDetail): InterviewSession {
  const currentPhaseKey = normalizePhaseKey(detail.currentPhase);
  const phases = markCompleted(buildPhases(detail.selectedPhases, detail.currentPhase), currentPhaseKey);
  return {
    id: detail.interviewId,
    positionTitle: detail.positionTitle,
    company: detail.companyName || '',
    status: detail.status === 'ENDED' ? 'completed' : detail.status === 'INTERRUPTED' ? 'interrupted' : 'ongoing',
    score: detail.overallScore,
    level: detail.grade,
    startTime: detail.startedAt ? new Date(detail.startedAt).toLocaleString('zh-CN') : '',
    phases,
    currentPhase: detail.currentPhase,
  };
}

export async function startInterview(config: { resumeId: number; positionId: number; phases: string[] }): Promise<InterviewSession> {
  return withMockFallback(async () => {
    const phaseKeyMap: Record<string, string> = {
      intro: 'SELF_INTRO',
      professional: 'PROFESSIONAL',
      resume: 'RESUME_DISCUSSION',
      behavior: 'BEHAVIORAL',
    };
    const payload = {
      resumeId: config.resumeId,
      positionId: config.positionId,
      selectedPhases: config.phases.map((p) => phaseKeyMap[p] || p.toUpperCase()),
    };
    const res = await request.post<CreateInterviewResponse>('/interviews', { data: payload });
    const data = res.data;
    return {
      id: data.interviewId,
      positionTitle: '',
      company: '',
      status: data.status === 'ENDED' ? 'completed' : 'ongoing',
      startTime: '',
      phases: buildPhases(data.phaseOrder, data.currentPhase),
      currentPhase: data.currentPhase,
    };
  }, mockInterviews[0], 'startInterview');
}

export async function getInterview(id: number): Promise<InterviewSession | null> {
  return withMockFallback(async () => {
    const res = await request.get<InterviewDetail>(`/interviews/${id}`);
    return res.data ? mapDetailToSession(res.data) : null;
  }, mapDetailToSession(mockInterviewDetail), 'getInterview');
}

export async function getInterviewMessages(id: number): Promise<InterviewMessage[]> {
  return withMockFallback(async () => {
    const res = await request.get<InterviewMessage[]>(`/interviews/${id}/messages`);
    return res.data || [];
  }, mockInterviewMessages, 'getInterviewMessages');
}

export async function endInterview(id: number): Promise<InterviewSession | null> {
  return withMockFallback(async () => {
    const res = await request.post<InterviewDetail>(`/interviews/${id}/end`);
    return res.data ? mapDetailToSession(res.data) : null;
  }, mapDetailToSession({ ...mockInterviewDetail, status: 'ENDED' }), 'endInterview');
}

export async function getInterviewReport(id: number): Promise<InterviewReport | null> {
  return withMockFallback(async () => {
    const res = await request.get<ReportDto>(`/interviews/${id}/report`);
    const dto = res.data;
    if (!dto) return null;
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
        { name: '学习能力', score: dto.dimensions.learningAbility },
      ],
      phaseSummary: Object.entries(dto.phases).map(([phase, summary]) => {
        const meta = PHASE_META[phase];
        return `${meta ? meta.name : phase} (${summary.questionCount}题) ${summary.completed ? '· 已完成' : ''}`;
      }),
      weakPoints: dto.weaknesses.length ? dto.weaknesses : ['部分问题可进一步深入'],
      strongPoints: dto.strengths.length ? dto.strengths : ['回答态度积极'],
      mdContent: dto.mdContent || '',
    };
  }, mockInterviewReport, 'getInterviewReport');
}

export async function getGrowthPlan(id: number): Promise<GrowthPlan | null> {
  return withMockFallback(async () => {
    const res = await request.get<GrowthPlan>(`/interviews/${id}/growth-plan`);
    return res.data || null;
  }, mockGrowthPlan, 'getGrowthPlan');
}

export async function getInterviewHistory(): Promise<InterviewSession[]> {
  return withMockFallback(async () => {
    const res = await request.get<InterviewDetail[]>('/interviews');
    return (res.data || []).map(mapDetailToSession);
  }, mockInterviews, 'getInterviewHistory');
}

export interface AnswerEvent {
  type: 'thinking' | 'phaseChange' | 'question' | 'interviewEnd' | 'done' | 'error';
  content?: string;
  phase?: string;
  depth?: number;
  topicId?: string;
  topicName?: string;
  previousPhase?: string;
  currentPhase?: string;
  code?: string;
  message?: string;
  fallback?: boolean;
}

function submitAnswerMock(onEvent: (event: AnswerEvent) => void): { abort: () => void } {
  let aborted = false;
  const events: AnswerEvent[] = [
    { type: 'thinking' },
    { type: 'question', content: '这是一个演示回复。在实际后端可用时，这里会返回 AI 面试官的实时流式反馈。', phase: 'PROFESSIONAL', topicName: '模拟面试', depth: 1 },
    { type: 'done' },
  ];
  events.forEach((event, index) => {
    setTimeout(() => {
      if (!aborted) {
        onEvent(event);
      }
    }, (index + 1) * 600);
  });
  return {
    abort: () => {
      aborted = true;
    },
  };
}

export function submitAnswer(
  id: number,
  answer: string,
  onEvent: (event: AnswerEvent) => void
): { abort: () => void } {
  if (isMockEnabled()) {
    return submitAnswerMock(onEvent);
  }
  const isH5 = process.env.TARO_ENV === 'h5';
  const token = getToken() || '';

  if (isH5) {
    return submitAnswerH5(id, answer, token, onEvent);
  }
  return submitAnswerMini(id, answer, token, onEvent);
}

function parseSSE(buffer: string, onEvent: (event: AnswerEvent) => void): string {
  const lines = buffer.split('\n');
  const leftover = lines.pop() || '';
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('data:')) continue;
    const jsonStr = trimmed.slice(5).trim();
    if (!jsonStr) continue;
    try {
      const event = JSON.parse(jsonStr) as AnswerEvent;
      onEvent(event);
    } catch (e) {
      console.warn('[SSE] parse failed', jsonStr);
    }
  }
  return leftover;
}

function submitAnswerH5(
  id: number,
  answer: string,
  token: string,
  onEvent: (event: AnswerEvent) => void
): { abort: () => void } {
  const controller = new AbortController();
  fetch(`/api/v1/interviews/${id}/answer`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Client-Type': 'mini-program',
    },
    body: JSON.stringify({ answer }),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const text = await response.text().catch(() => '请求失败');
        onEvent({ type: 'error', message: text });
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSSE(buffer, onEvent);
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onEvent({ type: 'error', message: err.message || '网络错误' });
      }
    });
  return { abort: () => controller.abort() };
}

function submitAnswerMini(
  id: number,
  answer: string,
  token: string,
  onEvent: (event: AnswerEvent) => void
): { abort: () => void } {
  let buffer = '';
  const task = Taro.request({
    url: `/api/v1/interviews/${id}/answer`,
    method: 'POST',
    header: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Client-Type': 'mini-program',
    },
    data: { answer },
    enableChunked: true,
    success: () => {
      // 流结束时处理剩余 buffer
      parseSSE(buffer + '\n', onEvent);
    },
    fail: (err) => {
      onEvent({ type: 'error', message: err.errMsg || '网络错误' });
    },
  });

  // Taro 类型声明可能不完整，使用 any 绕过
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const requestTask = task as any;
  if (requestTask.onChunkReceived) {
    requestTask.onChunkReceived((res: { data: ArrayBuffer }) => {
      const chunk = new TextDecoder().decode(res.data);
      buffer += chunk;
      buffer = parseSSE(buffer, onEvent);
    });
  }

  return {
    abort: () => {
      try {
        requestTask.abort?.();
      } catch (e) {
        console.warn('[SSE] abort failed', e);
      }
    },
  };
}

export function phaseKeyToName(phase?: string): string {
  return phase ? PHASE_NAMES[phase] || phase : '';
}
