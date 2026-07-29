import Taro from '@tarojs/taro';
import { getToken, setToken, removeToken, removeStoredUser } from '@/utils/storage';
import { isMockEnabled } from '@/constants/env';

export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

const BASE_URL = '/api/v1';

/**
 * 将请求异常统一转换为可读的 Error 对象。
 * Taro 在部分失败场景（如云预览沙箱无法访问 localhost）会抛出空对象，
 * 直接抛出会导致日志仅显示 `{}`，不利于定位问题。
 */
function normalizeRequestError(err: unknown, url: string): Error {
  if (err instanceof Error) {
    return err;
  }
  if (err && typeof err === 'object') {
    const msg = (err as Record<string, unknown>).errMsg
      || (err as Record<string, unknown>).message
      || (err as Record<string, unknown>).statusText;
    if (typeof msg === 'string' && msg.trim()) {
      return new Error(msg);
    }
  }
  return new Error(`请求 ${url} 失败，请检查网络或后端服务`);
}

/**
 * 在 isMockEnabled() 返回 true 时，接口失败可降级到指定的 Mock 数据，
 * 避免云预览环境无法访问本地后端导致页面空白。
 * 真实用户登录后不走 Mock，确保使用真实接口数据。
 */
export async function withMockFallback<T>(fn: () => Promise<T>, fallback: T, label?: string): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    if (isMockEnabled()) {
      console.warn(`[Mock] ${label || 'api'} fallback, reason:`, err instanceof Error ? err.message : err);
      return fallback;
    }
    throw err;
  }
}

let isRefreshing = false;
let refreshSubscribers: Array<(token: string) => void> = [];

function onTokenRefreshed(newToken: string) {
  refreshSubscribers.forEach((cb) => cb(newToken));
  refreshSubscribers = [];
}

async function doRefreshToken(): Promise<string> {
  const token = getToken();
  if (!token) {
    throw new Error('当前未登录');
  }
  const res = await Taro.request({
    url: `${BASE_URL}/users/refresh-token`,
    method: 'POST',
    header: {
      Authorization: `Bearer ${token}`,
      'X-Client-Type': 'mini-program',
    },
  });
  const data = res.data as ApiResponse<{ token: string }>;
  if (data.code !== 0 || !data.data?.token) {
    throw new Error(data.message || '刷新失败');
  }
  setToken(data.data.token);
  return data.data.token;
}

async function baseRequest<T>(options: Taro.request.Option & { _retry?: boolean }): Promise<ApiResponse<T>> {
  const token = getToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Client-Type': 'mini-program',
    ...(options.header as Record<string, string> || {}),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  try {
    const res = await Taro.request({
      ...options,
      url: options.url.startsWith('http') ? options.url : `${BASE_URL}${options.url}`,
      header: headers,
    });

    const statusCode = res.statusCode || 200;
    const data = (res.data || {}) as ApiResponse<T>;

    // 后端返回非 JSON 或空响应时，data 可能不是对象，需防御
    if (!data || typeof data !== 'object' || !('code' in data)) {
      if (statusCode >= 400) {
        throw new Error(`请求失败(${statusCode})`);
      }
      return { code: 0, message: 'ok', data: data as T, timestamp: Date.now() };
    }

    if (statusCode === 401 && !options._retry) {
      if (isRefreshing) {
        return new Promise((resolve) => {
          refreshSubscribers.push((newToken: string) => {
            headers.Authorization = `Bearer ${newToken}`;
            resolve(baseRequest({ ...options, header: headers, _retry: true }));
          });
        });
      }

      isRefreshing = true;
      try {
        const newToken = await doRefreshToken();
        isRefreshing = false;
        onTokenRefreshed(newToken);
        headers.Authorization = `Bearer ${newToken}`;
        return baseRequest({ ...options, header: headers, _retry: true });
      } catch (err) {
        isRefreshing = false;
        refreshSubscribers = [];
        removeToken();
        removeStoredUser();
        Taro.showToast({ title: '登录已过期，请重新登录', icon: 'none' });
        setTimeout(() => Taro.navigateTo({ url: '/pages/login/index' }), 1500);
        throw err;
      }
    }

    if (statusCode >= 400 || data.code !== 0) {
      throw new Error(data.message || `请求失败(${statusCode})`);
    }

    return data;
  } catch (err) {
    const normalized = normalizeRequestError(err, options.url);
    console.error('[Request]', options.url, normalized.message, err);
    throw normalized;
  }
}

const request = {
  get: <T,>(url: string, options?: Omit<Taro.request.Option, 'url' | 'method'>) =>
    baseRequest<T>({ ...options, url, method: 'GET' }),
  post: <T,>(url: string, options?: Omit<Taro.request.Option, 'url' | 'method'>) =>
    baseRequest<T>({ ...options, url, method: 'POST' }),
  put: <T,>(url: string, options?: Omit<Taro.request.Option, 'url' | 'method'>) =>
    baseRequest<T>({ ...options, url, method: 'PUT' }),
  delete: <T,>(url: string, options?: Omit<Taro.request.Option, 'url' | 'method'>) =>
    baseRequest<T>({ ...options, url, method: 'DELETE' }),
};

export async function uploadFile<T>(url: string, filePath: string, name: string, formData?: Record<string, string>): Promise<ApiResponse<T>> {
  const token = getToken();
  const header: Record<string, string> = {
    'X-Client-Type': 'mini-program',
  };
  if (token) {
    header.Authorization = `Bearer ${token}`;
  }

  const res = await Taro.uploadFile({
    url: `${BASE_URL}${url}`,
    filePath,
    name,
    formData,
    header,
  });

  let data: ApiResponse<T>;
  try {
    data = JSON.parse(res.data) as ApiResponse<T>;
  } catch {
    throw new Error('上传响应解析失败');
  }

  if (res.statusCode >= 400 || data.code !== 0) {
    throw new Error(data.message || `上传失败(${res.statusCode})`);
  }

  return data;
}

export default request;
