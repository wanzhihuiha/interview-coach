import request from '@/api/request'
import type { PositionCreatePayload } from '@/api/position'
import type {
  ApiResponse,
  Position,
  PositionAnalysisStatus,
  PositionCreateResult,
  PositionProfileData,
  PositionProfileResponse
} from '@/types'
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
  archived = false
): Promise<ApiResponse<PositionListResponse>> {
  return request.get('/admin/positions', {
    params: { page, size, archived }
  })
}

export function createAdminPosition(
  data: PositionCreatePayload
): Promise<ApiResponse<PositionCreateResult>> {
  return request.post('/admin/positions', data)
}

export function uploadAdminPosition(
  file: File,
  positionName: string
): Promise<ApiResponse<PositionCreateResult>> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileType', file.name.split('.').pop()?.toUpperCase() || '')
  formData.append('positionName', positionName)
  return request.post('/admin/positions/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function getAdminPositionDetail(id: number): Promise<ApiResponse<Position>> {
  return request.get(`/admin/positions/${id}`)
}

export function getAdminPositionProfile(id: number): Promise<ApiResponse<PositionProfileResponse>> {
  return request.get(`/admin/positions/${id}/profile`)
}

export function getAdminPositionAnalysisStatus(
  id: number,
  signal?: AbortSignal
): Promise<ApiResponse<PositionAnalysisStatus>> {
  return request.get(`/admin/positions/${id}/analysis-status`, { signal })
}

export function confirmAdminPosition(
  id: number,
  taskId: number,
  profile: PositionProfileData
): Promise<ApiResponse<void>> {
  return request.put(`/admin/positions/${id}/confirm`, { taskId, profile })
}

export function reparseAdminPosition(id: number): Promise<ApiResponse<PositionCreateResult>> {
  return request.put(`/admin/positions/${id}/reparse`)
}

export function archiveAdminPosition(id: number): Promise<ApiResponse<void>> {
  return request.put(`/admin/positions/${id}/archive`)
}

export function deleteAdminPosition(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/admin/positions/${id}`)
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
