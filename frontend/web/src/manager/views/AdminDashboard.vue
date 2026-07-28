<template>
  <div class="admin-dashboard">
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card>
          <div class="stat-title">待审核岗位</div>
          <div class="stat-value">{{ stats.pendingPositions }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat-title">待审核题目</div>
          <div class="stat-value">{{ stats.pendingQuestions }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat-title">用户总数</div>
          <div class="stat-value">{{ stats.totalUsers }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat-title">今日审计</div>
          <div class="stat-value">{{ stats.todayAudits }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="quick-entry" shadow="never">
      <template #header>
        <span>快捷入口</span>
      </template>
      <div class="quick-links">
        <el-button type="primary" @click="$router.push('/admin/positions')">岗位审核</el-button>
        <el-button type="primary" @click="$router.push('/admin/questions')">题目审核</el-button>
        <el-button @click="$router.push('/admin/users')">用户管理</el-button>
        <el-button @click="$router.push('/admin/audit-logs')">审计日志</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminDashboardStats } from '@/manager/api/admin'
import type { AdminDashboardStats } from '@/manager/types'

const stats = reactive<AdminDashboardStats>({
  pendingPositions: 0,
  pendingQuestions: 0,
  totalUsers: 0,
  todayAudits: 0
})

async function loadStats() {
  try {
    const res = await getAdminDashboardStats()
    Object.assign(stats, res.data)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载统计数据失败')
  }
}

onMounted(loadStats)
</script>

<style scoped>
.admin-dashboard {
  padding: 16px;
}

.stat-title {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
}

.quick-entry {
  margin-top: 16px;
}

.quick-links {
  display: flex;
  gap: 12px;
}
</style>
