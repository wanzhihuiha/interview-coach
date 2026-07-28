import request from './request'
import { apiCall } from './request'
import type { ApiResponse, Position, PositionProfileData } from '@/types'

const mockPositions: Position[] = [
  {
    positionId: 1,
    positionName: 'Java开发',
    companyName: '字节跳动',
    jobCategory: '技术族',
    parseStatus: 'CONFIRMED',
    auditStatus: 'APPROVED'
  },
  {
    positionId: 2,
    positionName: '前端开发',
    companyName: '腾讯',
    jobCategory: '技术族',
    parseStatus: 'CONFIRMED',
    auditStatus: 'APPROVED'
  },
  {
    positionId: 3,
    positionName: '产品经理',
    companyName: '阿里巴巴',
    jobCategory: '产品族',
    parseStatus: 'CONFIRMED',
    auditStatus: 'APPROVED'
  }
]

export async function getPositionList(params?: {
  page?: number
  size?: number
  parseStatus?: string
  auditStatus?: string
}): Promise<Position[]> {
  const res = await apiCall(
    () => request.get('/positions', { params }) as Promise<ApiResponse<{ content: Position[] }>>,
    () => ({ content: mockPositions }),
    { content: [] }
  )
  return res.data?.content || []
}

export async function createPosition(data: Partial<Position>): Promise<Position> {
  const payload = {
    positionName: data.positionName,
    companyName: data.companyName,
    jobCategory: data.jobCategory,
    level: data.level,
    location: data.location,
    salaryRange: data.salaryRange,
    jdContent: data.jdContent
  }
  const res = await apiCall(
    () => request.post('/positions', payload) as Promise<ApiResponse<Position>>,
    () => ({ ...data, positionId: Date.now(), parseStatus: 'PENDING', auditStatus: 'PENDING' } as Position)
  )
  return res.data
}

export async function uploadPosition(
  file: File,
  positionName: string
): Promise<Position> {
  const formData = new FormData()
  formData.append('file', file)
  const ext = file.name.split('.').pop()?.toUpperCase() || 'TXT'
  formData.append('fileType', ext)
  formData.append('positionName', positionName)
  const res = await apiCall(
    () => request.post('/positions/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as Promise<ApiResponse<Position>>,
    () => ({
      positionId: Date.now(),
      positionName,
      parseStatus: 'PENDING',
      auditStatus: 'PENDING'
    } as Position)
  )
  return res.data
}

export async function getPositionDetail(positionId: number): Promise<Position> {
  const res = await apiCall(
    () => request.get(`/positions/${positionId}`) as Promise<ApiResponse<Position>>,
    () => null,
    null
  )
  return res.data || { positionId, positionName: '', jobCategory: '', parseStatus: 'PENDING', auditStatus: 'PENDING' }
}

export async function getPositionProfile(positionId: number): Promise<{
  profile?: PositionProfileData
  parseStatus?: string
}> {
  const res = await apiCall(
    () => request.get(`/positions/${positionId}/profile`) as Promise<ApiResponse<{ profile?: PositionProfileData; parseStatus?: string }>>,
    () => null,
    null
  )
  return res.data || {}
}

export async function confirmPosition(positionId: number, profile: PositionProfileData): Promise<void> {
  await apiCall(
    () => request.put(`/positions/${positionId}/confirm`, { profile }) as Promise<ApiResponse<void>>,
    () => undefined
  )
}

export async function reparsePosition(positionId: number): Promise<Position> {
  const res = await apiCall(
    () => request.put(`/positions/${positionId}/reparse`) as Promise<ApiResponse<Position>>,
    () => null,
    null
  )
  return res.data || { positionId, positionName: '', jobCategory: '', parseStatus: 'PENDING', auditStatus: 'PENDING' }
}

export async function deletePosition(positionId: number): Promise<void> {
  await apiCall(
    () => request.delete(`/positions/${positionId}`) as Promise<ApiResponse<void>>,
    () => undefined
  )
}
