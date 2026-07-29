/**
 * 后台管理模块类型定义。
 */

export interface PositionListItem {
  positionId: number
  positionName: string
  companyName?: string
  jobCategory: string
  jobCategoryLabel?: string
  level?: string
  levelLabel?: string
  location?: string
  parseStatus: string
  parseStatusLabel?: string
  auditStatus: string
  auditStatusLabel?: string
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

export interface AdminDashboardStats {
  pendingPositions: number
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
