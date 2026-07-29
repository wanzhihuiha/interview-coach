import request, { withMockFallback } from './request';
import type { ApiResponse } from './request';
import { isMockEnabled } from '@/constants/env';

export interface UserInfo {
  id: number;
  username: string;
  nickname?: string;
  phone?: string;
  avatar?: string;
  roles?: string[];
}

export interface LoginParams {
  username: string;
  password: string;
}

export interface LoginData {
  token: string;
  user: UserInfo;
}

export interface RegisterParams {
  username: string;
  phone: string;
  password: string;
  confirmPassword: string;
  smsCode: string;
}

export interface UpdateProfileParams {
  nickname?: string;
  avatar?: string;
  gender?: string;
  birthday?: string;
  bio?: string;
}

interface BackendLoginResponse {
  token: string;
  user: {
    userId: number;
    username: string;
    phone?: string;
    roles?: string[];
  };
}

interface BackendUserProfileResponse {
  userId: number;
  username: string;
  phone?: string;
  email?: string;
  roles?: string[];
  profile?: {
    nickname?: string;
    avatar?: string;
  };
}

function normalizeUser(
  data: BackendLoginResponse['user'] | BackendUserProfileResponse
): UserInfo {
  const base: UserInfo = {
    id: data.userId,
    username: data.username,
    phone: data.phone,
  };
  if ('roles' in data && data.roles) {
    base.roles = data.roles;
  }
  if ('profile' in data) {
    base.nickname = data.profile?.nickname;
    base.avatar = data.profile?.avatar;
  }
  return base;
}

const mockUser: UserInfo = {
  id: 1,
  username: 'demo',
  nickname: '候选人',
  roles: ['USER'],
};

const mockLoginData: LoginData = {
  token: 'mock-token-for-preview-only',
  user: mockUser,
};

const mockLoginResponse: ApiResponse<LoginData> = {
  code: 0,
  message: 'ok',
  data: mockLoginData,
  timestamp: Date.now(),
};

export async function login(params: LoginParams): Promise<ApiResponse<LoginData>> {
  return withMockFallback(async () => {
    const res = await request.post<BackendLoginResponse>('/users/login', { data: params });
    return {
      ...res,
      data: {
        token: res.data.token,
        user: normalizeUser(res.data.user),
      },
    };
  }, mockLoginResponse, 'login');
}

export async function register(params: RegisterParams): Promise<ApiResponse<LoginData>> {
  return withMockFallback(async () => {
    const res = await request.post<BackendLoginResponse>('/users/register', { data: params });
    return {
      ...res,
      data: {
        token: res.data.token,
        user: normalizeUser(res.data.user),
      },
    };
  }, { ...mockLoginResponse, data: { ...mockLoginData, user: { ...mockUser, username: params.username } } }, 'register');
}

export async function getCurrentUser(): Promise<ApiResponse<UserInfo>> {
  return withMockFallback(async () => {
    const res = await request.get<BackendUserProfileResponse>('/users/me');
    return {
      ...res,
      data: normalizeUser(res.data),
    };
  }, { code: 0, message: 'ok', data: mockUser, timestamp: Date.now() }, 'getCurrentUser');
}

export async function updateProfile(params: UpdateProfileParams): Promise<ApiResponse<void>> {
  return withMockFallback(async () => request.put('/users/me', { data: params }), { code: 0, message: 'ok', data: undefined as void, timestamp: Date.now() }, 'updateProfile');
}

/**
 * 一键登录为演示用户，仅用于开发/预览阶段快速体验。
 * 仅在 isMockEnabled() 返回 true 时可用。
 */
export async function loginAsDemo(): Promise<ApiResponse<LoginData> | null> {
  if (!isMockEnabled()) return null;
  return mockLoginResponse;
}
