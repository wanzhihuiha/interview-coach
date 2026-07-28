import request from './request'
import { apiCall } from './request'
import type { ApiResponse, Resume, UserProfileData } from '@/types'

const mockResumes: Resume[] = [
  {
    resumeId: 1,
    fileName: '张三_Java开发_简历.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    jobCategory: '技术族',
    createdAt: '2026-07-20T10:00:00'
  },
  {
    resumeId: 2,
    fileName: '李四_前端开发_简历.txt',
    fileType: 'TXT',
    status: 'PENDING_CONFIRM',
    jobCategory: '技术族',
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

export async function getResumeProfile(resumeId: number): Promise<{ profile?: UserProfileData; experienceLevel?: string; status?: string }> {
  const res = await apiCall(
    () => request.get(`/resumes/${resumeId}/profile`) as Promise<ApiResponse<{ profile?: UserProfileData; experienceLevel?: string; status?: string }>>,
    () => null,
    null
  )
  return res.data || {}
}

export async function confirmResume(resumeId: number, profile: UserProfileData): Promise<void> {
  await apiCall(
    () => request.put(`/resumes/${resumeId}/confirm`, { profile }) as Promise<ApiResponse<void>>,
    () => undefined
  )
}

export async function reparseResume(resumeId: number): Promise<Resume> {
  const res = await apiCall(
    () => request.put(`/resumes/${resumeId}/reparse`) as Promise<ApiResponse<Resume>>,
    () => null,
    null
  )
  return res.data || { resumeId, fileName: '', status: 'PENDING' }
}

export async function deleteResume(resumeId: number): Promise<void> {
  await apiCall(
    () => request.delete(`/resumes/${resumeId}`) as Promise<ApiResponse<void>>,
    () => undefined
  )
}
