import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, removeToken, setStoredUser, removeStoredUser, getStoredUser } from '@/utils/storage'
import { login, register, getCurrentUser } from '@/api'
import type { UserInfo } from '@/types'
import type { RegisterParams } from '@/api/auth'

function storeUserInfo(user: UserInfo | null) {
  if (user) {
    // 安全：roles 不持久化到 localStorage，仅保留在内存中
    setStoredUser({
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      phone: user.phone,
      avatar: user.avatar
    })
  } else {
    removeStoredUser()
  }
}

function isAdminUser(user: UserInfo | null): boolean {
  return !!user?.roles?.includes('ADMIN')
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(getToken())
  const storedUser = getStoredUser()
  const userInfo = ref<UserInfo | null>(storedUser)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => isAdminUser(userInfo.value))

  async function loginByPassword(username: string, password: string) {
    loading.value = true
    try {
      const res = await login({ username, password })
      token.value = res.data.token
      userInfo.value = res.data.user
      setToken(res.data.token)
      storeUserInfo(res.data.user)
      return res
    } finally {
      loading.value = false
    }
  }

  async function registerAccount(data: RegisterParams) {
    loading.value = true
    try {
      const res = await register(data)
      token.value = res.data.token
      userInfo.value = res.data.user
      setToken(res.data.token)
      storeUserInfo(res.data.user)
      return res
    } finally {
      loading.value = false
    }
  }

  async function fetchUserInfo() {
    if (!token.value) return
    try {
      const res = await getCurrentUser()
      userInfo.value = res.data
      storeUserInfo(res.data)
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = null
    userInfo.value = null
    removeToken()
    removeStoredUser()
  }

  return {
    token,
    userInfo,
    loading,
    isLoggedIn,
    isAdmin,
    loginByPassword,
    registerAccount,
    fetchUserInfo,
    logout
  }
})
