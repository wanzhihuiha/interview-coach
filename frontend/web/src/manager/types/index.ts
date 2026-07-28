/**
 * 后台管理模块类型定义。
 */

export interface PositionListItem {
  id: number
  positionName: string
  companyName?: string
  jobCategory: string
  level?: string
  location?: string
  parseStatus: string
  auditStatus: string
  isPublic?: boolean
  userId?: number
  createdAt?: string
  updatedAt?: string
}

export interface PositionListResponse {
  content: PositionListItem[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export interface AuditLogItem {
  id: number
  caller: string
  operation: string
  methodKey: string
  status: string
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
  roles: string[]
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
  phase: string
  topicId?: string
  topicName?: string
  content: string
  expectedAnswer?: string
  usageCount?: number
}
