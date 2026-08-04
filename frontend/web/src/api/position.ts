import request from './request'
import { apiCall } from './request'
import type {
  ApiResponse,
  Position,
  PositionAnalysisStatus,
  PositionCreateResult,
  PositionListResponse,
  PositionProfileData,
  PositionProfileResponse
} from '@/types'

export interface PositionCreatePayload {
  positionName: string
  companyName?: string
  jobCategory: string
  level?: string
  location?: string
  salaryRange?: string
  jdContent: string
}

const mockProfile: PositionProfileData = {
  requiredSkills: [
    { skill: 'Java', importance: '高', depth: '深入' },
    { skill: 'Spring Boot', importance: '高', depth: '熟练' }
  ],
  preferredSkills: [],
  probingDirections: [],
  interviewFocus: ['工程实践与问题定位能力']
}

const mockPositions: Position[] = [
  {
    positionId: 1,
    positionName: 'Java开发',
    companyName: '字节跳动',
    jobCategory: 'TECH',
    jobCategoryLabel: '技术类',
    isPublic: false,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: true
  },
  {
    positionId: 2,
    positionName: '前端开发',
    companyName: '腾讯',
    jobCategory: 'TECH',
    jobCategoryLabel: '技术类',
    isPublic: true,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: false
  },
  {
    positionId: 3,
    positionName: '产品经理',
    companyName: '阿里巴巴',
    jobCategory: 'PRODUCT',
    jobCategoryLabel: '产品类',
    isPublic: true,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: false
  }
]

function mockPage(content: Position[], page = 0, size = 10): PositionListResponse {
  const start = page * size
  return {
    content: content.slice(start, start + size),
    totalElements: content.length,
    totalPages: content.length ? Math.ceil(content.length / size) : 0,
    currentPage: page
  }
}

export async function loadAllPositionPages(
  loadPage: (page: number, size: number) => Promise<PositionListResponse>
): Promise<Position[]> {
  const positions = new Map<number, Position>()
  let page = 0
  let totalPages = 1

  while (page < totalPages) {
    const response = await loadPage(page, 100)
    response.content.forEach(position => positions.set(position.positionId, position))
    totalPages = Math.max(totalPages, response.totalPages)
    page += 1
  }

  return Array.from(positions.values())
}

export async function getPositionList(params?: {
  page?: number
  size?: number
  archived?: boolean
}): Promise<PositionListResponse> {
  const archived = params?.archived ?? false
  const res = await apiCall(
    () => request.get('/positions', { params }) as Promise<ApiResponse<PositionListResponse>>,
    () => mockPage(
      mockPositions.filter(position => !position.isPublic && position.archived === archived),
      params?.page,
      params?.size
    )
  )
  return res.data
}

export async function getPublicPositionList(params?: {
  page?: number
  size?: number
}): Promise<PositionListResponse> {
  const res = await apiCall(
    () => request.get('/positions/public', { params }) as Promise<ApiResponse<PositionListResponse>>,
    () => mockPage(
      mockPositions.filter(position => position.isPublic && !position.archived),
      params?.page,
      params?.size
    )
  )
  return res.data
}

export async function getAccessiblePositionList(params?: {
  page?: number
  size?: number
}): Promise<PositionListResponse> {
  const res = await apiCall(
    () => request.get('/positions/accessible', { params }) as Promise<ApiResponse<PositionListResponse>>,
    () => mockPage(
      mockPositions.filter(position => position.profileUsable && !position.archived),
      params?.page,
      params?.size
    )
  )
  return res.data
}

export async function createPosition(data: PositionCreatePayload): Promise<PositionCreateResult> {
  const res = await apiCall(
    () => request.post('/positions', data) as Promise<ApiResponse<PositionCreateResult>>,
    () => {
      const positionId = Date.now()
      mockPositions.unshift({
        ...data,
        positionId,
        isPublic: false,
        archived: false,
        latestTaskId: positionId,
        latestTaskStatus: 'WAITING',
        latestTaskStatusLabel: '排队中',
        profileUsable: false,
        canConfirm: false,
        canRetry: false
      })
      return {
        positionId,
        positionName: data.positionName,
        taskId: positionId,
        latestTaskStatus: 'WAITING' as const
      }
    }
  )
  return res.data
}

export async function uploadPosition(file: File, positionName: string): Promise<PositionCreateResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileType', file.name.split('.').pop()?.toUpperCase() || '')
  formData.append('positionName', positionName)
  const res = await apiCall(
    () => request.post('/positions/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as Promise<ApiResponse<PositionCreateResult>>,
    () => {
      const positionId = Date.now()
      mockPositions.unshift({
        positionId,
        positionName,
        jobCategory: 'TECH',
        jobCategoryLabel: '技术类',
        isPublic: false,
        archived: false,
        latestTaskId: positionId,
        latestTaskStatus: 'WAITING',
        latestTaskStatusLabel: '排队中',
        profileUsable: false,
        canConfirm: false,
        canRetry: false
      })
      return {
        positionId,
        positionName,
        taskId: positionId,
        latestTaskStatus: 'WAITING' as const
      }
    }
  )
  return res.data
}

export async function getPositionDetail(positionId: number): Promise<Position> {
  const res = await apiCall(
    () => request.get(`/positions/${positionId}`) as Promise<ApiResponse<Position>>,
    () => mockPositions.find(position => position.positionId === positionId) || {
      positionId,
      positionName: '',
      jobCategory: '',
      archived: false,
      profileUsable: false,
      canConfirm: false,
      canRetry: false
    }
  )
  return res.data
}

export async function getPositionProfile(positionId: number): Promise<PositionProfileResponse> {
  const res = await apiCall(
    () => request.get(`/positions/${positionId}/profile`) as Promise<ApiResponse<PositionProfileResponse>>,
    () => {
      const position = mockPositions.find(item => item.positionId === positionId)
      return {
        positionId,
        profile: position?.profileUsable ? mockProfile : undefined,
        taskId: position?.latestTaskId,
        latestTaskStatus: position?.latestTaskStatus,
        latestTaskStatusLabel: position?.latestTaskStatusLabel,
        candidateProfile: position?.canConfirm ? mockProfile : undefined,
        profileUsable: position?.profileUsable ?? false,
        canConfirm: position?.canConfirm ?? false,
        canRetry: position?.canRetry ?? false,
        archived: position?.archived ?? false
      }
    }
  )
  return res.data
}

export async function getPositionAnalysisStatus(
  positionId: number,
  signal?: AbortSignal
): Promise<PositionAnalysisStatus> {
  const res = await apiCall(
    () => request.get(`/positions/${positionId}/analysis-status`, { signal }) as Promise<ApiResponse<PositionAnalysisStatus>>,
    () => {
      const position = mockPositions.find(item => item.positionId === positionId)
      return {
        positionId,
        taskId: position?.latestTaskId,
        latestTaskStatus: position?.latestTaskStatus,
        latestTaskStatusLabel: position?.latestTaskStatusLabel,
        queueAhead: position?.latestTaskStatus === 'WAITING' ? 0 : undefined,
        profileUsable: position?.profileUsable ?? false,
        canConfirm: position?.canConfirm ?? false,
        canRetry: position?.canRetry ?? false,
        archived: position?.archived ?? false
      }
    }
  )
  return res.data
}

export async function confirmPosition(
  positionId: number,
  taskId: number,
  profile: PositionProfileData
): Promise<void> {
  await apiCall(
    () => request.put(`/positions/${positionId}/confirm`, { taskId, profile }) as Promise<ApiResponse<void>>,
    () => {
      const position = mockPositions.find(item => item.positionId === positionId)
      if (position) {
        position.latestTaskId = undefined
        position.latestTaskStatus = undefined
        position.latestTaskStatusLabel = undefined
        position.profileUsable = true
        position.canConfirm = false
        position.canRetry = true
      }
    }
  )
}

export async function reparsePosition(positionId: number): Promise<PositionCreateResult> {
  const res = await apiCall(
    () => request.put(`/positions/${positionId}/reparse`) as Promise<ApiResponse<PositionCreateResult>>,
    () => {
      const taskId = Date.now()
      const position = mockPositions.find(item => item.positionId === positionId)
      if (position) {
        position.latestTaskId = taskId
        position.latestTaskStatus = 'WAITING'
        position.latestTaskStatusLabel = '排队中'
        position.canConfirm = false
        position.canRetry = false
      }
      return {
        positionId,
        positionName: position?.positionName || '',
        taskId,
        latestTaskStatus: 'WAITING' as const
      }
    }
  )
  return res.data
}

export async function archivePosition(positionId: number): Promise<void> {
  await apiCall(
    () => request.put(`/positions/${positionId}/archive`) as Promise<ApiResponse<void>>,
    () => {
      const position = mockPositions.find(item => item.positionId === positionId)
      if (position) {
        position.archived = true
        position.archivedAt = new Date().toISOString()
      }
    }
  )
}

export async function deletePosition(positionId: number): Promise<void> {
  await apiCall(
    () => request.delete(`/positions/${positionId}`) as Promise<ApiResponse<void>>,
    () => {
      const index = mockPositions.findIndex(position => position.positionId === positionId)
      if (index >= 0) mockPositions.splice(index, 1)
    }
  )
}
