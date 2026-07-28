<template>
  <AppLayout>
    <div class="page-container">
      <div class="page-header">
        <h2 class="page-title">面试报告</h2>
        <el-button type="primary" :icon="Download" :disabled="!report" @click="downloadMd">下载报告 MD</el-button>
      </div>

      <div v-if="loading" class="empty-report">
        <el-empty description="报告加载中..." />
      </div>

      <el-row v-else-if="report" :gutter="24">
        <el-col :span="16">
          <el-card class="score-card">
            <div class="score-overview">
              <div class="total-score">
                <div class="score-value">{{ report?.totalScore }}</div>
                <div class="score-label">综合评分</div>
              </div>
              <div class="score-level">
                <div class="level-value">{{ report?.level }}</div>
                <div class="score-label">等级</div>
              </div>
            </div>

            <div class="dimension-scores">
              <div
                v-for="item in report?.scores"
                :key="item.name"
                class="dimension-item"
              >
                <div class="dimension-name">{{ item.name }}</div>
                <el-progress
                  :percentage="item.score"
                  :color="scoreColor"
                  :stroke-width="16"
                  :show-text="false"
                />
                <div class="dimension-value">{{ item.score }}</div>
              </div>
            </div>
          </el-card>

          <el-card class="section-card">
            <template #header>
              <span class="card-title">环节完成情况</span>
            </template>
            <div class="phase-summary-list">
              <div v-for="(summary, index) in report?.phaseSummary" :key="index" class="phase-summary-item">
                {{ summary }}
              </div>
            </div>
          </el-card>

          <el-card class="section-card">
            <template #header>
              <span class="card-title">薄弱知识点</span>
            </template>
            <ul class="point-list weak">
              <li v-for="point in report?.weakPoints" :key="point">{{ point }}</li>
            </ul>
          </el-card>

          <el-card class="section-card">
            <template #header>
              <span class="card-title">优势知识点</span>
            </template>
            <ul class="point-list strong">
              <li v-for="point in report?.strongPoints" :key="point">{{ point }}</li>
            </ul>
          </el-card>
        </el-col>

        <el-col :span="8">
          <el-card class="action-card">
            <el-button type="primary" size="large" class="action-btn" @click="viewGrowth">
              查看成长方案
            </el-button>
            <el-button size="large" class="action-btn" @click="$router.push('/interview/config')">
              再面一次
            </el-button>
          </el-card>
        </el-col>
      </el-row>

      <div v-else-if="error" class="empty-report">
        <el-empty :description="error">
          <el-button v-if="error.includes('403') || error.includes('未登录') || error.includes('认证') || error.includes('Unauthorized')" type="primary" @click="$router.push('/login')">去登录</el-button>
          <el-button v-else type="primary" @click="$router.push('/history')">查看历史</el-button>
        </el-empty>
      </div>

      <div v-else class="empty-report">
        <el-empty description="暂无面试报告">
          <el-button type="primary" @click="$router.push('/history')">查看历史</el-button>
        </el-empty>
      </div>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getInterviewReport } from '@/api'
import { getToken } from '@/utils/storage'
import type { InterviewReport } from '@/types'

const route = useRoute()
const router = useRouter()
const interviewId = Number(route.params.id)

const report = ref<InterviewReport | null>(null)
const loading = ref(false)
const error = ref('')
const isLoggedIn = ref(!!getToken())
const scoreColor = [
  { color: '#F56C6C', percentage: 60 },
  { color: '#E6A23C', percentage: 75 },
  { color: '#67C23A', percentage: 90 }
]

onMounted(async () => {
  if (!isLoggedIn.value) {
    error.value = '未登录'
    return
  }
  loading.value = true
  error.value = ''
  try {
    report.value = await getInterviewReport(interviewId)
  } catch (err) {
    error.value = (err as Error).message || '加载报告失败'
    console.error('[ReportView] 加载报告失败', err)
  } finally {
    loading.value = false
  }
})

function viewGrowth() {
  router.push(`/interview/${interviewId}/growth`)
}

function downloadMd() {
  if (!report.value) return
  const blob = new Blob([report.value.mdContent], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `面试报告-${report.value.id}.md`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('报告下载成功')
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 0;
}

.score-card {
  margin-bottom: 20px;
}

.score-overview {
  display: flex;
  justify-content: center;
  gap: 60px;
  padding: 20px 0;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 24px;
}

.total-score, .score-level {
  text-align: center;
}

.score-value, .level-value {
  font-size: 48px;
  font-weight: 700;
  color: #409EFF;
  line-height: 1;
}

.level-value {
  color: #67C23A;
}

.score-label {
  margin-top: 8px;
  font-size: 14px;
  color: #909399;
}

.dimension-scores {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dimension-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dimension-name {
  width: 80px;
  font-size: 14px;
  color: #606266;
}

.dimension-item :deep(.el-progress) {
  flex: 1;
}

.dimension-value {
  width: 36px;
  text-align: right;
  font-weight: 600;
  color: #303133;
}

.section-card {
  margin-bottom: 20px;
}

.card-title {
  font-weight: 600;
}

.phase-summary-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.phase-summary-item {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
  color: #606266;
  font-size: 14px;
}

.point-list {
  padding-left: 18px;
  line-height: 2;
  color: #606266;
}

.point-list.weak li::marker {
  color: #F56C6C;
}

.point-list.strong li::marker {
  color: #67C23A;
}

.action-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.action-btn {
  width: 100%;
}

.empty-report {
  min-height: 500px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
