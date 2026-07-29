import Taro from '@tarojs/taro';

const TOKEN_KEY = 'interview_coach_token';
const USER_KEY = 'interview_coach_user';

export function getToken(): string | undefined {
  try {
    return Taro.getStorageSync(TOKEN_KEY) || undefined;
  } catch (e) {
    console.error('[Storage] getToken failed', e);
    return undefined;
  }
}

export function setToken(token: string): void {
  Taro.setStorageSync(TOKEN_KEY, token);
}

export function removeToken(): void {
  Taro.removeStorageSync(TOKEN_KEY);
}

export function getStoredUser<T>(): T | undefined {
  try {
    return Taro.getStorageSync(USER_KEY) || undefined;
  } catch (e) {
    console.error('[Storage] getStoredUser failed', e);
    return undefined;
  }
}

export function setStoredUser<T>(user: T): void {
  Taro.setStorageSync(USER_KEY, user);
}

export function removeStoredUser(): void {
  Taro.removeStorageSync(USER_KEY);
}
