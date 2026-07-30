<template>
  <router-view />
  <ConsentDialog v-model="showConsent" @agree="handleConsentAgree" @decline="handleConsentDecline" />
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import ConsentDialog from '@/components/ConsentDialog.vue'
import { getConsentStatus, submitConsents } from '@/api/consent'

const userStore = useUserStore()
const showConsent = ref(false)

/**
 * 检查当前登录用户是否已完成首次使用同意。
 */
async function checkConsent() {
  if (!userStore.token) {
    showConsent.value = false
    return
  }
  try {
    const status = await getConsentStatus()
    if (!status.llmService || !status.privacyPolicy) {
      showConsent.value = true
    }
  } catch {
    showConsent.value = false
  }
}

/**
 * 用户点击同意：提交同意记录并关闭弹窗。
 */
async function handleConsentAgree() {
  try {
    await submitConsents()
    showConsent.value = false
  } catch (error) {
    // 提交失败时保持弹窗，让用户重试
    console.error('提交同意记录失败', error)
  }
}

/**
 * 用户点击不同意：退出登录并跳转到登录页。
 */
function handleConsentDecline() {
  userStore.logout()
  showConsent.value = false
  window.location.href = '/login'
}

onMounted(() => {
  // 应用启动时刷新用户信息，确保角色等字段最新
  if (userStore.token) {
    userStore.fetchUserInfo().then(() => checkConsent())
  }
})

// 用户登录后触发同意状态检查
watch(
  () => userStore.token,
  (newToken, oldToken) => {
    if (newToken && !oldToken) {
      checkConsent()
    }
  }
)
</script>
