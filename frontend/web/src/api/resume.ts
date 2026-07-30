import request from './request'
import { apiCall } from './request'
import type {
  ApiResponse,
  Resume,
  ResumeParseStatus,
  ResumeProfileAnalysisData,
  UserProfileData
} from '@/types'

const mockResumes: Resume[] = [
  {
    resumeId: 1,
    fileName: '张三_Java开发_简历.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    statusLabel: '已确认',
    hasConfirmedProfile: true,
    jobCategory: 'TECH',
    jobCategoryLabel: '技术类',
    createdAt: '2026-07-20T10:00:00'
  },
  {
    resumeId: 2,
    fileName: '李四_前端开发_简历.txt',
    fileType: 'TXT',
    status: 'PENDING_CONFIRM',
    statusLabel: '待确认',
    hasConfirmedProfile: false,
    jobCategory: 'TECH',
    jobCategoryLabel: '技术类',
    createdAt: '2026-07-22T14:30:00'
  }
]

export async function getResumeList(): Promise<Resume[]> {
  const res = await apiCall(
    () => request.get('/resumes') as Promise<ApiResponse<{ content: Resume[] }>>,
    () => ({ content: mockResumes }),
    { content: [] }
  )
  return res.data?.content || []
}

export async function uploadResume(file: File): Promise<Resume> {
  const formData = new FormData()
  formData.append('file', file)
  const ext = file.name.split('.').pop()?.toUpperCase() || 'TXT'
  formData.append('fileType', ext)
  const res = await apiCall(
    () => request.post('/resumes/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as Promise<ApiResponse<Resume>>,
    () => ({
      resumeId: Date.now(),
      fileName: file.name,
      status: 'PENDING',
      statusLabel: '待解析',
      createdAt: new Date().toISOString()
    })
  )
  return res.data
}

export async function getResumeDetail(resumeId: number): Promise<Resume> {
  const res = await apiCall(
    () => request.get(`/resumes/${resumeId}`) as Promise<ApiResponse<Resume>>,
    () => null,
    null
  )
  return res.data || { resumeId, fileName: '', status: 'PENDING' }
}

export async function getResumeProfile(resumeId: number): Promise<{
  profile?: UserProfileData
  confirmedProfile?: UserProfileData
  draftProfile?: UserProfileData
  analysis?: ResumeProfileAnalysisData
  hasConfirmedProfile?: boolean
  parseGeneration?: number
  experienceLevel?: string
  experienceLevelLabel?: string
  status?: string
  statusLabel?: string
  analysisStatus?: string
  analysisErrorMessage?: string
}> {
  const res = await apiCall(
    () => request.get(`/resumes/${resumeId}/profile`) as Promise<ApiResponse<{
      profile?: UserProfileData
      confirmedProfile?: UserProfileData
      draftProfile?: UserProfileData
      analysis?: ResumeProfileAnalysisData
      hasConfirmedProfile?: boolean
      parseGeneration?: number
      experienceLevel?: string
      experienceLevelLabel?: string
      status?: string
      statusLabel?: string
      analysisStatus?: string
      analysisErrorMessage?: string
    }>>,
    () => null,
    null
  )
  return res.data || {}
}

export async function getResumeParseStatus(resumeId: number): Promise<ResumeParseStatus> {
  const res = await apiCall(
    () => request.get(`/resumes/${resumeId}/parse-status`) as Promise<ApiResponse<ResumeParseStatus>>,
    () => ({ resumeId, status: 'PENDING', statusLabel: '待解析', parseProgress: 10 })
  )
  return res.data
}

export async function confirmResume(
  resumeId: number,
  profile: UserProfileData,
  parseGeneration: number
): Promise<void> {
  await apiCall(
    () => request.put(`/resumes/${resumeId}/confirm`, {
      parseGeneration,
      profile
    }) as Promise<ApiResponse<void>>,
    () => undefined
  )
}

export async function reparseResume(resumeId: number): Promise<Resume> {
  const res = await apiCall(
    () => request.put(`/resumes/${resumeId}/reparse`) as Promise<ApiResponse<Resume>>,
    () => ({ resumeId, fileName: '', status: 'PENDING', statusLabel: '待解析', parseProgress: 10 })
  )
  return res.data
}

export async function deleteResume(resumeId: number): Promise<void> {
  await apiCall(
    () => request.delete(`/resumes/${resumeId}`) as Promise<ApiResponse<void>>,
    () => undefined
  )
}
