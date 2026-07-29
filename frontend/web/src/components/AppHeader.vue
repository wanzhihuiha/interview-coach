<template>
  <el-header class="app-header" :class="{ 'app-header--home': isHome }">
    <div class="header-left">
      <button class="logo" type="button" aria-label="返回首页" @click="goHome">
        <span class="logo-mark"><el-icon :size="20"><Monitor /></el-icon></span>
        <span class="logo-copy">
          <span class="logo-text">面试教练</span>
          <small>INTERVIEW COACH</small>
        </span>
      </button>
      <el-menu
        v-if="userStore.isLoggedIn"
        :default-active="activeIndex"
        class="header-menu"
        mode="horizontal"
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
        <span class="user-info">
          <el-avatar :size="32" :src="userStore.userInfo?.avatar">
            {{ userStore.userInfo?.username?.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
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
import { Monitor, ArrowDown, Menu } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const mobileMenuOpen = ref(false)

const activeIndex = computed(() => route.path)
const isHome = computed(() => route.name === 'Home')

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
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 24px;
  height: 60px;
  transition: background-color 200ms ease, border-color 200ms ease, color 200ms ease;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 32px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  padding: 0;
  cursor: pointer;
  color: inherit;
  background: transparent;
  text-align: left;
}

.logo:focus-visible,
.mobile-menu-button:focus-visible {
  outline: 2px solid #6d77ff;
  outline-offset: 4px;
}

.logo-mark {
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid #409eff;
  border-radius: 4px;
  color: #409eff;
}

.logo-copy {
  display: flex;
  flex-direction: column;
}

.logo-copy small {
  margin-top: 1px;
  color: #909399;
  font-family: Consolas, monospace;
  font-size: 7px;
  letter-spacing: 0;
}

.logo-text {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.header-menu {
  min-width: 430px;
  border-bottom: none;
}

.header-right {
  display: flex;
  align-items: center;
}

.mobile-menu-button {
  display: none;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  color: #303133;
  background: transparent;
  cursor: pointer;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.username {
  font-size: 14px;
  color: #606266;
}

.app-header--home {
  height: 72px;
  border-bottom-color: rgba(255, 255, 255, 0.11);
  color: #f1efe8;
  background: #08090b;
}

.app-header--home .logo-mark {
  border-color: #6d77ff;
  color: #8d95ff;
  background: rgba(109, 119, 255, 0.12);
}

.app-header--home .logo-text {
  color: #f1efe8;
}

.app-header--home .logo-copy small,
.app-header--home .username {
  color: #888b93;
}

.app-header--home .header-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #8d9098;
  --el-menu-hover-text-color: #f3f1e9;
  --el-menu-active-color: #f3f1e9;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.04);
  --el-menu-border-color: transparent;
}

.app-header--home .mobile-menu-button {
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
