/**
 * 后台管理模块类型定义。
 */

import type { Position } from '@/types'

export type PositionListItem = Position

export interface PositionListResponse {
  content: PositionListItem[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export interface AdminDashboardStats {
  pendingPublicPositions: number
  pendingQuestions: number
  totalUsers: number
  todayAudits: number
}

export interface AuditLogItem {
  id: number
  caller: string
  callerLabel?: string
  operation: string
  methodKey: string
  status: string
  statusLabel?: string
  durationMs: number
  argsSummary?: string
  resultSummary?: string
  errorMessage?: string
  createdAt: string
}

export interface AuditLogListResponse {
  content: AuditLogItem[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export interface UserListItem {
  userId: number
  username: string
  phone?: string
  email?: string
  status: string
  statusLabel?: string
  roles: string[]
  roleLabels?: string[]
  createTime?: string
  updateTime?: string
}

export interface UserListResponse {
  content: UserListItem[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export interface QuestionBankItem {
  id: number
  jobCategory: string
  jobCategoryLabel?: string
  phase: string
  phaseLabel?: string
  topicId?: string
  topicName?: string
  content: string
  expectedAnswer?: string
  usageCount?: number
  difficultyLevel?: number
}
