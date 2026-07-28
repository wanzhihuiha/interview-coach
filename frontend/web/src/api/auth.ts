import request from './request'
import { apiCall } from './request'
import { getToken, setToken } from '@/utils/storage'
import { getTokenExpiration } from '@/utils/jwt'
import type { ApiResponse, UserInfo } from '@/types'

export interface LoginParams {
  username: string
  password: string
}

export interface LoginData {
  token: string
  user: UserInfo
}

// 后端原始响应结构
interface BackendLoginResponse {
  token: string
  user: {
    userId: number
    username: string
    phone?: string
    roles?: string[]
  }
}

interface BackendUserProfileResponse {
  userId: number
  username: string
  phone?: string
  email?: string
  roles?: string[]
  profile?: {
    nickname?: string
    avatar?: string
  }
}

function normalizeUser(
  data: BackendLoginResponse['user'] | BackendUserProfileResponse
): UserInfo {
  const base: UserInfo = {
    id: data.userId,
    username: data.username,
    phone: data.phone
  }
  if ('roles' in data && data.roles) {
    base.roles = data.roles
  }
  if ('profile' in data) {
    base.nickname = data.profile?.nickname
    base.avatar = data.profile?.avatar
  }
  return base
}

const mockUser: UserInfo = {
  id: 1,
  username: 'demo',
  nickname: '张三',
  phone: '138****8000',
  avatar: '',
  roles: ['USER']
}

export async function login(params: LoginParams): Promise<ApiResponse<LoginData>> {
  const res = await apiCall(
    () => request.post('/users/login', params) as Promise<ApiResponse<BackendLoginResponse>>,
    () => ({
      token: 'mock-jwt-token-for-preview',
      user: { userId: 1, username: params.username, phone: '13800138000', roles: ['USER'] }
    })
  )
  return {
    ...res,
    data: {
      token: res.data.token,
      user: normalizeUser(res.data.user)
    }
  }
}

export interface RegisterParams {
  username: string
  phone: string
  password: string
  confirmPassword: string
  smsCode: string
}

export async function register(params: RegisterParams): Promise<ApiResponse<LoginData>> {
  const res = await apiCall(
    () => request.post('/users/register', params) as Promise<ApiResponse<BackendLoginResponse>>,
    () => ({
      token: 'mock-jwt-token-for-preview',
      user: { userId: 1, username: params.username, phone: params.phone, roles: ['USER'] }
    })
  )
  return {
    ...res,
    data: {
      token: res.data.token,
      user: normalizeUser(res.data.user)
    }
  }
}

export async function getCurrentUser(): Promise<ApiResponse<UserInfo>> {
  const res = await apiCall(
    () => request.get('/users/me') as Promise<ApiResponse<BackendUserProfileResponse>>,
    () => mockUser
  )
  return {
    ...res,
    data: normalizeUser(res.data)
  }
}

export async function refreshToken(): Promise<ApiResponse<LoginData>> {
  const res = (await request.post('/users/refresh-token')) as ApiResponse<BackendLoginResponse>
  const result: ApiResponse<LoginData> = {
    ...res,
    data: {
      token: res.data.token,
      user: normalizeUser(res.data.user)
    }
  }
  setToken(result.data.token)
  return result
}

/**
 * 在面试等长会话中主动检查 token 有效期，接近过期时提前续期，
 * 避免提交回答或拉取 SSE 时 token 失效导致中断。
 */
export async function ensureTokenFresh(minutesThreshold = 5): Promise<void> {
  const token = getToken()
  if (!token) return
  const exp = getTokenExpiration(token)
  if (!exp) return
  const remainingMinutes = Math.floor((exp - Date.now()) / 60000)
  if (remainingMinutes <= minutesThreshold) {
    await refreshToken()
  }
}

export interface UpdateProfileParams {
  nickname?: string
  avatar?: string
  gender?: string
  birthday?: string
  bio?: string
}

export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

export async function updateProfile(params: UpdateProfileParams): Promise<ApiResponse<void>> {
  return apiCall(
    () => request.put('/users/me', params) as Promise<ApiResponse<void>>,
    () => undefined
  )
}

export async function changePassword(params: ChangePasswordParams): Promise<ApiResponse<void>> {
  return apiCall(
    () => request.put('/users/password', params) as Promise<ApiResponse<void>>,
    () => undefined
  )
}

export async function sendSmsCode(phone: string): Promise<ApiResponse<void>> {
  return apiCall(
    () => request.post('/users/sms/send', { phone }) as Promise<ApiResponse<void>>,
    () => undefined
  )
}
