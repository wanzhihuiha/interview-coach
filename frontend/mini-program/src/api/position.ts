import request, { withMockFallback } from './request';
import type { ApiResponse } from './request';
import { mockPositions } from '@/data/mock';

export interface Position {
  positionId: number;
  positionName: string;
  companyName?: string;
  jobCategory: string;
  level?: string;
  location?: string;
  salaryRange?: string;
  jdContent?: string;
  parseStatus: string;
  auditStatus: string;
  createdAt?: string;
  updatedAt?: string;
}

export async function getAccessiblePositionList(params?: { page?: number; size?: number }): Promise<Position[]> {
  return withMockFallback(async () => {
    const res = await request.get<{ content: Position[] }>('/positions/accessible', { data: params });
    return res.data?.content || [];
  }, mockPositions.slice(0, params?.size || 10), 'getAccessiblePositionList');
}

export async function getPositionDetail(positionId: number): Promise<Position | null> {
  return withMockFallback(async () => {
    const res = await request.get<Position>(`/positions/${positionId}`);
    return res.data || null;
  }, mockPositions.find((p) => p.positionId === positionId) || mockPositions[0], 'getPositionDetail');
}
