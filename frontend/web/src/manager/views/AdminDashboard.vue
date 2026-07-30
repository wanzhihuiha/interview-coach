<template>
  <div class="admin-dashboard">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">OPERATIONS OVERVIEW</span>
        <h1 class="page-title">管理概览</h1>
        <p class="page-subtitle">集中查看待办、用户规模与当日审计情况，快速进入治理任务。</p>
      </div>
      <span class="admin-status-note">管理服务正常</span>
    </header>

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

    <section class="quick-entry">
      <div class="quick-entry-heading">
        <div>
          <span>QUICK ACTIONS</span>
          <h2>快捷入口</h2>
        </div>
        <p>按当前优先级进入运营与审核任务。</p>
      </div>
      <div class="quick-links">
        <el-button type="primary" :icon="OfficeBuilding" @click="$router.push('/admin/positions')">岗位审核</el-button>
        <el-button :icon="Document" @click="$router.push('/admin/questions')">题目审核</el-button>
        <el-button :icon="User" @click="$router.push('/admin/users')">用户管理</el-button>
        <el-button :icon="Tickets" @click="$router.push('/admin/audit-logs')">审计日志</el-button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, OfficeBuilding, Tickets, User } from '@element-plus/icons-vue'
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
  padding: 28px 32px 48px;
}

.admin-dashboard :deep(.el-card) {
  min-height: 142px;
  border-top: 2px solid var(--color-ink);
}

.admin-dashboard :deep(.el-card__body) {
  padding: 24px;
}

.stat-title {
  font-size: 14px;
  color: var(--color-muted);
  margin-bottom: 8px;
}

.stat-value {
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 40px;
  font-weight: 650;
}

.quick-entry {
  display: grid;
  grid-template-columns: minmax(280px, 0.78fr) minmax(600px, 1.22fr);
  align-items: center;
  gap: 48px;
  margin-top: 28px;
  border-top: 2px solid var(--color-ink);
  border-bottom: 1px solid var(--color-border);
  padding: 30px 24px;
  background: var(--color-surface);
}

.quick-entry-heading {
  display: flex;
  min-width: 0;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.quick-entry-heading span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.quick-entry-heading h2 {
  margin: 7px 0 0;
  color: var(--color-ink);
  font-size: 18px;
}

.quick-entry-heading p {
  max-width: 220px;
  margin: 0;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.6;
}

.quick-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}

.quick-links .el-button + .el-button {
  margin-left: 0;
}
</style>
