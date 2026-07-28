import axios, { AxiosError, AxiosRequestConfig } from 'axios'
import { getToken, setToken, removeToken, removeStoredUser, isDemoUser } from '@/utils/storage'
import type { ApiResponse } from '@/types'

interface RefreshResponse {
  token: string
}

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 独立的 refresh 客户端，避免触发自身的 401 拦截逻辑
const refreshClient = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`
  }
  config.headers['X-Client-Type'] = 'web'
  return config
})

let isRefreshing = false
let refreshSubscribers: Array<(token: string) => void> = []

function onTokenRefreshed(newToken: string) {
  refreshSubscribers.forEach((cb) => cb(newToken))
  refreshSubscribers = []
}

async function doRefreshToken(): Promise<string> {
  const token = getToken()
  if (!token) {
    throw new Error('当前未登录')
  }
  const res = await refreshClient.post<ApiResponse<RefreshResponse>>(
    '/users/refresh-token',
    {},
    { headers: { Authorization: `Bearer ${token}` } }
  )
  const data = res.data
  if (data.code !== 0 || !data.data?.token) {
    throw new Error(data.message || '刷新失败')
  }
  setToken(data.data.token)
  return data.data.token
}

request.interceptors.response.use(
  (response) => {
    const data = response.data as ApiResponse<unknown>
    // 后端统一响应中 code 不为 0 表示业务失败，需要以错误形式抛出
    if (data.code !== 0) {
      const error = new AxiosError(
        data.message || '请求失败',
        undefined,
        response.config,
        response.request,
        response
      )
      return Promise.reject(error)
    }
    return data
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const originalRequest = error.config as AxiosRequestConfig & { _retry?: boolean }
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '请求失败'

    // Token 过期时自动续期一次，并重新发送原请求，避免面试等长会话中断
    if (status === 401 && originalRequest && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve) => {
          refreshSubscribers.push((token: string) => {
            originalRequest.headers = originalRequest.headers || {}
            originalRequest.headers.Authorization = `Bearer ${token}`
            resolve(request(originalRequest))
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true
      return new Promise((resolve, reject) => {
        doRefreshToken()
          .then((newToken) => {
            isRefreshing = false
            onTokenRefreshed(newToken)
            originalRequest.headers = originalRequest.headers || {}
            originalRequest.headers.Authorization = `Bearer ${newToken}`
            resolve(request(originalRequest))
          })
          .catch((err) => {
            isRefreshing = false
            refreshSubscribers = []
            removeToken()
            removeStoredUser()
            window.location.href = '/login'
            reject(err)
          })
      })
    }

    return Promise.reject(
      new AxiosError(message, error.code, error.config, error.request, error.response)
    )
  }
)

function wrapMock<T>(data: T): ApiResponse<T> {
  return {
    code: 0,
    message: 'ok',
    data,
    timestamp: Date.now()
  }
}

/**
 * 包装请求，支持 mock fallback。
 * 当后端接口尚未实现时（返回 404/500），demo 用户展示 mock 数据；
 * 真实用户返回 emptyValue（避免看到示例数据），由调用方提供空状态兜底。
 */
export async function apiCall<T>(
  realRequest: () => Promise<ApiResponse<T>>,
  mockProvider: () => Promise<T> | T,
  emptyValue?: T
): Promise<ApiResponse<T>> {
  try {
    return await realRequest()
  } catch (error) {
    const err = error as AxiosError
    // 仅在后端未实现或网络错误时降级
    if (!err.response || err.response.status >= 500 || err.response.status === 404) {
      if (isDemoUser()) {
        console.warn('[Mock Fallback]', err.message)
        return wrapMock(await Promise.resolve(mockProvider()))
      }
      if (emptyValue !== undefined) {
        console.warn('[API not ready, returning empty]', err.message)
        return wrapMock(emptyValue)
      }
    }
    throw error
  }
}

export default request
