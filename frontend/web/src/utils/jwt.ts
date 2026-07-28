/**
 * 解析 JWT payload（不校验签名）。
 */
export function parseJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split('.')[1]
    if (!payload) return null
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return null
  }
}

/**
 * 获取 token 过期时间（毫秒时间戳）。
 */
export function getTokenExpiration(token: string): number | null {
  const payload = parseJwtPayload(token)
  if (!payload || typeof payload.exp !== 'number') return null
  return payload.exp * 1000
}

/**
 * 获取 token 剩余有效分钟数。
 */
export function getTokenRemainingMinutes(token: string): number | null {
  const exp = getTokenExpiration(token)
  if (!exp) return null
  return Math.floor((exp - Date.now()) / 60000)
}
