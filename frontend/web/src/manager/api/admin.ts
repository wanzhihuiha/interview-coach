import request from '@/api/request'
import type { ApiResponse } from '@/types'
import type {
  AdminDashboardStats,
  AuditLogListResponse,
  PositionListResponse,
  QuestionBankItem,
  UserListResponse
} from '@/manager/types'

/**
 * 后台管理 API 封装。
 */

export function getAdminDashboardStats(): Promise<ApiResponse<AdminDashboardStats>> {
  return request.get('/admin/dashboard/stats')
}

export function getAdminPositions(
  page = 0,
  size = 10,
  auditStatus?: string
): Promise<ApiResponse<PositionListResponse>> {
  return request.get('/admin/positions', {
    params: { page, size, auditStatus }
  })
}

export function getAdminPositionDetail(id: number): Promise<ApiResponse<unknown>> {
  return request.get(`/admin/positions/${id}`)
}

export function auditPosition(id: number, status: string, remark?: string): Promise<ApiResponse<void>> {
  return request.put(`/positions/${id}/audit`, { status, remark })
}

export interface QuestionBankListResponse {
  content: QuestionBankItem[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export function getPendingQuestions(
  jobCategory?: string,
  phase?: string
): Promise<ApiResponse<QuestionBankItem[]>> {
  return request.get('/admin/question-bank/pending', {
    params: { jobCategory, phase }
  })
}

export function getPermanentQuestions(
  params: {
    jobCategory?: string
    phase?: string
    keyword?: string
    page?: number
    size?: number
  }
): Promise<ApiResponse<QuestionBankListResponse>> {
  return request.get('/admin/question-bank/permanent', { params })
}

export function promoteQuestion(id: number): Promise<ApiResponse<number>> {
  return request.post(`/admin/question-bank/${id}/promote`)
}

export function rejectQuestion(id: number): Promise<ApiResponse<void>> {
  return request.post(`/admin/question-bank/${id}/reject`)
}

export function updateQuestion(
  id: number,
  item: Partial<QuestionBankItem>
): Promise<ApiResponse<void>> {
  return request.put(`/admin/question-bank/${id}`, item)
}

export function getAdminUsers(page = 0, size = 20): Promise<ApiResponse<UserListResponse>> {
  return request.get('/admin/users', {
    params: { page, size }
  })
}

export function updateUserStatus(id: number, status: string): Promise<ApiResponse<void>> {
  return request.put(`/admin/users/${id}/status`, { status })
}

export function getAuditLogs(
  page = 0,
  size = 20,
  params?: Record<string, unknown>
): Promise<ApiResponse<AuditLogListResponse>> {
  return request.get('/admin/audit-logs', {
    params: { page, size, ...params }
  })
}
