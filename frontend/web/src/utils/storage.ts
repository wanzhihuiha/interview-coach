const TOKEN_KEY = 'interview_coach_token'
const USER_KEY = 'interview_coach_user'

export interface StoredUser {
  id: number
  username: string
  nickname?: string
  phone?: string
  avatar?: string
  /**
   * 注意：出于安全考虑，roles 不再持久化到 localStorage，
   * 仅保留在内存中，应用启动时通过 /users/me 重新获取。
   */
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as StoredUser
  } catch {
    return null
  }
}

export function setStoredUser(user: StoredUser | null): void {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  } else {
    localStorage.removeItem(USER_KEY)
  }
}

export function removeStoredUser(): void {
  localStorage.removeItem(USER_KEY)
}

export function isDemoUser(): boolean {
  const user = getStoredUser()
  return user?.username === 'demo'
}
