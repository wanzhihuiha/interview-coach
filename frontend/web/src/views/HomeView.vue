<template>
  <AppLayout>
    <div class="page-container">
      <!-- 欢迎卡片 -->
      <el-card class="welcome-card">
        <div class="welcome-content">
          <div>
            <h2 class="welcome-title">👋 欢迎回来，{{ userStore.userInfo?.nickname || userStore.userInfo?.username || '候选人' }}</h2>
            <p class="welcome-desc">开始一场模拟面试，提升你的面试能力</p>
            <div class="welcome-actions">
              <el-button type="primary" size="large" @click="$router.push('/interview/config')">
                开始面试
              </el-button>
              <el-button size="large" @click="$router.push('/history')">查看历史</el-button>
            </div>
          </div>
          <el-icon :size="120" color="#E6F2FF"><Monitor /></el-icon>
        </div>
      </el-card>

      <!-- 我的简历 -->
      <section class="section">
        <div class="section-header">
          <h3 class="section-title">📄 我的简历</h3>
          <el-button link type="primary" @click="$router.push('/resume')">查看全部</el-button>
        </div>
        <div class="card-list">
          <el-card
            v-for="resume in resumes"
            :key="resume.resumeId"
            class="clickable-card info-card"
            shadow="hover"
            @click="$router.push('/resume')"
          >
            <div class="card-title">{{ resume.fileName }}</div>
            <div class="card-meta">{{ resume.jobCategory || '-' }} · {{ resume.status === 'CONFIRMED' ? '已确认' : '待确认' }}</div>
            <div class="card-date">{{ formatTime(resume.createdAt) }}</div>
          </el-card>
          <el-card class="clickable-card info-card add-card" shadow="hover" @click="$router.push('/resume')">
            <el-icon :size="32" color="#C0C4CC"><Plus /></el-icon>
            <div class="add-text">上传新简历</div>
          </el-card>
        </div>
      </section>

      <!-- 目标岗位 -->
      <section class="section">
        <div class="section-header">
          <h3 class="section-title">💼 目标岗位</h3>
          <el-button link type="primary" @click="$router.push('/position')">查看全部</el-button>
        </div>
        <div class="card-list">
          <el-card
            v-for="position in positions"
            :key="position.positionId"
            class="clickable-card info-card"
            shadow="hover"
            @click="$router.push('/position')"
          >
            <div class="card-title">{{ position.positionName }}</div>
            <div class="card-meta">{{ position.companyName }}</div>
            <div class="card-date">{{ position.jobCategory }}</div>
          </el-card>
          <el-card class="clickable-card info-card add-card" shadow="hover" @click="$router.push('/position')">
            <el-icon :size="32" color="#C0C4CC"><Plus /></el-icon>
            <div class="add-text">选择岗位</div>
          </el-card>
        </div>
      </section>

      <!-- 最近面试 -->
      <section class="section">
        <div class="section-header">
          <h3 class="section-title">📊 最近面试</h3>
          <el-button link type="primary" @click="$router.push('/history')">查看全部</el-button>
        </div>
        <el-card>
          <el-table :data="recentInterviews" stripe>
            <el-table-column prop="positionTitle" label="岗位" />
            <el-table-column prop="startTime" label="面试时间" />
            <el-table-column label="综合评分">
              <template #default="{ row }">
                <span v-if="row.score">{{ row.score }} · {{ row.level }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewReport(row.id)">查看报告</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </section>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Monitor, Plus } from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { useUserStore } from '@/stores/user'
import { getResumeList, getAccessiblePositionList, getInterviewHistory } from '@/api'
import type { Resume, Position, InterviewSession } from '@/types'

const router = useRouter()
const userStore = useUserStore()

const resumes = ref<Resume[]>([])
const positions = ref<Position[]>([])
const recentInterviews = ref<InterviewSession[]>([])

onMounted(async () => {
  userStore.fetchUserInfo()
  const [rList, accessiblePositions, hList] = await Promise.all([
    getResumeList(),
    getAccessiblePositionList({ size: 5 }),
    getInterviewHistory()
  ])
  resumes.value = rList.slice(0, 2)
  positions.value = accessiblePositions.slice(0, 5)
  recentInterviews.value = hList.slice(0, 3)
})

function viewReport(id: number) {
  router.push(`/interview/${id}/report`)
}

function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (isNaN(date.getTime())) return value
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
</script>

<style scoped>
.welcome-card {
  margin-bottom: 24px;
  background: linear-gradient(135deg, #ecf5ff 0%, #ffffff 100%);
  border: none;
}

.welcome-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
}

.welcome-title {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.welcome-desc {
  color: #606266;
  margin-bottom: 20px;
}

.welcome-actions {
  display: flex;
  gap: 12px;
}

.section {
  margin-bottom: 24px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.info-card {
  width: 220px;
  min-height: 120px;
}

.card-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.card-meta {
  font-size: 13px;
  color: #606266;
  margin-bottom: 4px;
}

.card-date {
  font-size: 12px;
  color: #909399;
}

.add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-style: dashed;
}

.add-text {
  margin-top: 8px;
  color: #909399;
  font-size: 14px;
}
</style>
