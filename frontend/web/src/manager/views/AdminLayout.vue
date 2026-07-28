<template>
  <el-container class="admin-layout">
    <el-aside width="220px" class="admin-aside">
      <div class="admin-logo" @click="goHome">
        <el-icon :size="24" color="#409EFF"><Setting /></el-icon>
        <span class="admin-logo-text">后台管理</span>
      </div>
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
          <span>岗位审核</span>
        </el-menu-item>
        <el-menu-item index="/admin/questions">
          <el-icon><Document /></el-icon>
          <span>题目审核</span>
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
    <el-container direction="vertical">
      <el-header class="admin-header">
        <div class="admin-header-left">{{ pageTitle }}</div>
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
  Setting,
  DataLine,
  OfficeBuilding,
  Document,
  User,
  Tickets
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    '/admin': '管理概览',
    '/admin/positions': '岗位审核',
    '/admin/questions': '题目审核',
    '/admin/users': '用户管理',
    '/admin/audit-logs': '审计日志'
  }
  return titles[route.path] || '后台管理'
})

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
  background-color: #304156;
  color: #fff;
}

.admin-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 60px;
  padding: 0 20px;
  cursor: pointer;
  border-bottom: 1px solid #1f2d3d;
}

.admin-logo-text {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
}

.admin-menu {
  border-right: none;
  background-color: transparent;
  --el-menu-text-color: #ffffff;
  --el-menu-hover-text-color: #ffffff;
  --el-menu-active-color: #409eff;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: #263445;
}

.admin-menu :deep(.el-menu-item) {
  color: #ffffff;
}

.admin-menu :deep(.el-menu-item:hover) {
  background-color: #263445;
}

.admin-menu :deep(.el-menu-item.is-active) {
  color: #409eff;
  background-color: #263445;
}

.admin-menu :deep(.el-sub-menu__title) {
  color: #ffffff;
}

.admin-menu :deep(.el-sub-menu__title:hover) {
  background-color: #263445;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.admin-header-left {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
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
  background-color: #f5f7fa;
}
</style>
