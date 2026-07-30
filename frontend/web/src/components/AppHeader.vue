<template>
  <el-header class="app-header" :class="{ 'app-header--dark': isDarkContext }">
    <div class="header-shell">
      <div class="header-left">
        <button class="logo" type="button" aria-label="返回首页" @click="goHome">
          <span class="logo-mark" aria-hidden="true"></span>
          <span class="logo-copy">
            <span class="logo-text">职衡</span>
            <small>ROLEFIT AI</small>
          </span>
        </button>
        <el-menu
          v-if="userStore.isLoggedIn"
          :default-active="activeIndex"
          class="header-menu"
          mode="horizontal"
          aria-label="主导航"
          router
        >
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/resume">简历</el-menu-item>
          <el-menu-item index="/position">岗位</el-menu-item>
          <el-menu-item index="/interview/config">面试</el-menu-item>
          <el-menu-item index="/history">历史</el-menu-item>
        </el-menu>
      </div>

      <div class="header-right">
        <el-dropdown v-if="userStore.isLoggedIn" @command="handleCommand">
          <button class="user-info" type="button" aria-label="打开用户菜单">
            <el-avatar :size="32" :src="userStore.userInfo?.avatar">
              {{ userStore.userInfo?.username?.charAt(0).toUpperCase() }}
            </el-avatar>
            <span class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item v-if="userStore.isAdmin" command="admin">后台管理</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-else type="primary" @click="$router.push('/login')">登录</el-button>
        <button
          v-if="userStore.isLoggedIn"
          class="mobile-menu-button"
          type="button"
          aria-label="打开导航菜单"
          :aria-expanded="mobileMenuOpen"
          @click="mobileMenuOpen = true"
        >
          <el-icon><Menu /></el-icon>
        </button>
      </div>
    </div>
  </el-header>

  <el-drawer
    v-if="userStore.isLoggedIn"
    v-model="mobileMenuOpen"
    class="mobile-nav-drawer"
    direction="rtl"
    size="min(82vw, 320px)"
    title="导航"
  >
    <el-menu :default-active="activeIndex" router @select="mobileMenuOpen = false">
      <el-menu-item index="/">首页</el-menu-item>
      <el-menu-item index="/resume">简历</el-menu-item>
      <el-menu-item index="/position">岗位</el-menu-item>
      <el-menu-item index="/interview/config">面试</el-menu-item>
      <el-menu-item index="/history">历史</el-menu-item>
    </el-menu>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Menu } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const mobileMenuOpen = ref(false)

const activeIndex = computed(() => {
  if (route.path.startsWith('/interview/')) return '/interview/config'
  return route.path
})
const isDarkContext = computed(() => route.name === 'HomeLegacy')

function goHome() {
  router.push('/')
}

function handleCommand(command: string) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: var(--app-header-height);
  border-bottom: 1px solid rgba(29, 29, 31, 0.08);
  padding: 0;
  color: var(--color-ink);
  background: rgba(250, 250, 252, 0.88);
  backdrop-filter: saturate(180%) blur(20px);
  transition: background-color 200ms ease, border-color 200ms ease, color 200ms ease;
}

.header-shell {
  display: flex;
  width: 100%;
  max-width: var(--app-content-max);
  height: 100%;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-6);
  margin: 0 auto;
  padding: 0 var(--app-gutter);
}

.header-left {
  display: flex;
  min-width: 0;
  height: 100%;
  align-items: center;
  gap: var(--space-8);
}

.logo {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--space-2);
  border: 0;
  padding: 0;
  cursor: pointer;
  color: inherit;
  background: transparent;
  text-align: left;
}

.logo:focus-visible,
.mobile-menu-button:focus-visible {
  outline: 2px solid var(--color-brand-600);
  outline-offset: 3px;
}

.logo-mark {
  width: 4px;
  height: 30px;
  background: var(--color-brand-500);
}

.logo-copy {
  display: flex;
  flex-direction: column;
}

.logo-copy small {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 650;
  line-height: 1.1;
  letter-spacing: 0;
}

.logo-text {
  color: var(--color-ink);
  font-size: 20px;
  font-weight: 750;
  line-height: 1.2;
}

.header-menu {
  min-width: 420px;
  height: 100%;
  border-bottom: none;
  --el-menu-horizontal-height: calc(var(--app-header-height) - 1px);
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--color-body);
  --el-menu-hover-text-color: var(--color-ink);
  --el-menu-active-color: var(--color-brand-600);
  --el-menu-hover-bg-color: var(--color-surface-subtle);
  --el-menu-border-color: transparent;
}

.header-menu :deep(.el-menu-item) {
  min-width: 66px;
  justify-content: center;
  padding: 0 var(--space-4);
  border-bottom-width: 2px;
  font-size: 14px;
  font-weight: 500;
}

.header-right {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--space-3);
}

.mobile-menu-button {
  display: none;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  color: var(--color-ink);
  background: transparent;
  cursor: pointer;
}

.user-info {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: var(--space-2);
  border: 0;
  border-radius: var(--radius-control);
  padding: 4px 6px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  outline: none;
  transition: background-color var(--motion-fast) var(--ease-standard);
}

.user-info:hover,
.user-info:focus-visible {
  background: var(--color-surface-subtle);
}

.user-info:focus-visible {
  box-shadow: 0 0 0 2px var(--color-brand-600);
}

.username {
  max-width: 180px;
  overflow: hidden;
  font-size: 14px;
  color: var(--color-body);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.app-header--dark {
  height: var(--app-header-height);
  border-bottom-color: rgba(255, 255, 255, 0.11);
  color: #f1efe8;
  background: rgba(8, 9, 11, 0.94);
}

.app-header--dark .logo-mark {
  background: var(--color-brand-500);
}

.app-header--dark .logo-text {
  color: #f1efe8;
}

.app-header--dark .logo-copy small,
.app-header--dark .username {
  color: #888b93;
}

.app-header--dark .header-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #8d9098;
  --el-menu-hover-text-color: #f3f1e9;
  --el-menu-active-color: var(--color-brand-500);
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.04);
  --el-menu-border-color: transparent;
}

.app-header--dark .user-info:hover,
.app-header--dark .user-info:focus-visible {
  background: rgba(255, 255, 255, 0.06);
}

.app-header--dark .mobile-menu-button {
  border-color: rgba(255, 255, 255, 0.18);
  color: #f3f1e9;
}

:global(.mobile-nav-drawer .el-drawer__body) {
  padding: 0;
}

:global(.mobile-nav-drawer .el-menu) {
  border-right: 0;
}

@media (max-width: 860px) {
  .app-header {
    height: 64px;
  }

  .header-shell {
    padding: 0 16px;
  }

  .header-left {
    gap: 0;
  }

  .header-menu {
    display: none;
    min-width: 0;
  }

  .header-right {
    gap: 10px;
  }

  .mobile-menu-button {
    display: inline-flex;
  }

  .username {
    display: none;
  }
}

@media (max-width: 480px) {
  .logo-text {
    font-size: 17px;
  }

  .user-info > .el-icon {
    display: none;
  }
}
</style>
