import request from './request'
import { apiCall } from './request'
import { isDemoUser } from '@/utils/storage'
import type { ApiResponse } from '@/types'

/**
 * 用户同意状态。
 */
export interface ConsentStatus {
  llmService: boolean
  privacyPolicy: boolean
}

/**
 * 查询当前用户的 LLM 服务与隐私政策同意状态。
 *
 * 后端接口未就绪时：demo 用户默认未同意（用于展示弹窗），真实用户默认已同意，避免阻塞。
 */
export async function getConsentStatus(): Promise<ConsentStatus> {
  const res = await apiCall(
    () => request.get('/users/consents/status') as Promise<ApiResponse<ConsentStatus>>,
    () => (isDemoUser() ? { llmService: false, privacyPolicy: false } : { llmService: true, privacyPolicy: true })
  )
  return res.data
}

/**
 * 提交用户对 LLM 服务条款与隐私政策的同意。
 */
export async function submitConsents(): Promise<void> {
  await apiCall(
    () =>
      request.post('/users/consents', {
        consentTypes: ['LLM_SERVICE', 'PRIVACY_POLICY'],
        version: '1.0'
      }) as Promise<ApiResponse<void>>,
    () => undefined
  )
}
