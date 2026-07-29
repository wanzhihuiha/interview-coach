import request, { withMockFallback } from './request';
import { uploadFile } from './request';
import type { ApiResponse } from './request';
import { mockResumes } from '@/data/mock';

export interface Resume {
  resumeId: number;
  fileName: string;
  fileType?: string;
  fileSize?: number;
  status: string;
  jobCategory?: string;
  createdAt?: string;
  updatedAt?: string;
}

export async function getResumeList(): Promise<Resume[]> {
  return withMockFallback(async () => {
    const res = await request.get<{ content: Resume[] }>('/resumes');
    return res.data?.content || [];
  }, mockResumes, 'getResumeList');
}

export async function uploadResume(filePath: string, fileName: string, fileType: string): Promise<Resume> {
  return withMockFallback(async () => {
    const ext = fileType.toUpperCase();
    const res = await uploadFile<Resume>('/resumes/upload', filePath, 'file', {
      fileType: ext,
    });
    return res.data;
  }, {
    resumeId: Date.now(),
    fileName,
    fileType: fileType.toUpperCase(),
    status: 'PENDING',
    jobCategory: '待识别',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }, 'uploadResume');
}

export async function deleteResume(resumeId: number): Promise<void> {
  return withMockFallback(async () => {
    await request.delete(`/resumes/${resumeId}`);
  }, undefined, 'deleteResume');
}
