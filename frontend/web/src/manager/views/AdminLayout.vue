<template>
  <el-container class="admin-layout">
    <el-aside width="240px" class="admin-aside">
      <button class="admin-logo" type="button" aria-label="返回职衡首页" @click="goHome">
        <span class="admin-logo-mark" aria-hidden="true"></span>
        <span class="admin-logo-copy">
          <strong>职衡</strong>
          <small>ROLEFIT ADMIN</small>
        </span>
      </button>
      <el-menu
        :default-active="activeMenu"
        class="admin-menu"
        router
      >
        <el-menu-item index="/admin">
          <el-icon><DataLine /></el-icon>
          <span>概览</span>
        </el-menu-item>
        <el-menu-item index="/admin/positions">
          <el-icon><OfficeBuilding /></el-icon>
          <span>公共岗位</span>
        </el-menu-item>
        <el-menu-item index="/admin/questions">
          <el-icon><Document /></el-icon>
          <span>题目审核</span>
        </el-menu-item>
        <el-menu-item index="/admin/question-bank">
          <el-icon><Collection /></el-icon>
          <span>题库管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/audit-logs">
          <el-icon><Tickets /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="admin-content" direction="vertical">
      <el-header class="admin-header">
        <div class="admin-header-left">
          <small>ROLEFIT CONTROL CENTER</small>
          <strong>职衡管理工作台</strong>
        </div>
        <div class="admin-header-right">
          <el-button text @click="goHome">返回前台</el-button>
          <el-dropdown @command="handleCommand">
            <span class="admin-user-info">
              <el-avatar :size="28" :src="userStore.userInfo?.avatar">
                {{ userStore.userInfo?.username?.charAt(0).toUpperCase() }}
              </el-avatar>
              <span>{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataLine,
  OfficeBuilding,
  Document,
  Collection,
  User,
  Tickets
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

function goHome() {
  router.push('/')
}

function handleCommand(command: string) {
  if (command === 'logout') {
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-aside {
  color: var(--color-on-console);
  background: var(--color-console);
}

.admin-content {
  min-width: 0;
}

.admin-logo {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 14px;
  height: 72px;
  border: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  padding: 0 24px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.admin-logo-mark {
  width: 4px;
  height: 32px;
  background: var(--color-brand-500);
}

.admin-logo-copy {
  display: flex;
  flex-direction: column;
}

.admin-logo-copy strong {
  color: #ffffff;
  font-size: 20px;
  font-weight: 750;
  line-height: 1;
}

.admin-logo-copy small {
  margin-top: 7px;
  color: #82817c;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 650;
}

.admin-menu {
  border-right: none;
  background-color: transparent;
  --el-menu-text-color: #aaa9a3;
  --el-menu-hover-text-color: #ffffff;
  --el-menu-active-color: #ffffff;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.05);
}

.admin-menu :deep(.el-menu-item) {
  height: 52px;
  border-left: 3px solid transparent;
  color: #aaa9a3;
}

.admin-menu :deep(.el-menu-item:hover) {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.05);
}

.admin-menu :deep(.el-menu-item.is-active) {
  border-left-color: var(--color-brand-500);
  color: #ffffff;
  background: rgba(233, 76, 58, 0.12);
}

.admin-header {
  position: sticky;
  top: 0;
  z-index: 80;
  display: flex;
  height: 72px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
  padding: 0 32px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: saturate(160%) blur(16px);
}

.admin-header-left {
  display: flex;
  flex-direction: column;
}

.admin-header-left small {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.admin-header-left strong {
  margin-top: 5px;
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 680;
}

.admin-header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.admin-user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.admin-main {
  min-width: 0;
  padding: 0;
  background: var(--color-canvas);
}

.admin-main :deep(.audit-view),
.admin-main :deep(.admin-dashboard) {
  width: 100%;
  max-width: 1680px;
  margin: 0 auto;
  padding: 28px 32px 48px;
}
</style>
