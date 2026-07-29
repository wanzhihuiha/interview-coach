<template>
  <AppLayout>
    <div class="page-container">
      <h2 class="page-title">面试历史</h2>

      <el-card>
        <div class="filter-bar">
          <el-select v-model="filter.position" placeholder="全部岗位" clearable style="width: 160px">
            <el-option
              v-for="pos in positionOptions"
              :key="pos"
              :label="pos"
              :value="pos"
            />
          </el-select>
          <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 160px">
            <el-option label="已完成" value="completed" />
            <el-option label="进行中" value="ongoing" />
            <el-option label="已中断" value="interrupted" />
          </el-select>
          <el-date-picker
            v-model="filter.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </div>

        <el-table :data="filteredHistory" stripe v-loading="loading" @row-click="selectSession" highlight-current-row>
          <el-table-column prop="positionTitle" label="岗位" />
          <el-table-column prop="company" label="公司" />
          <el-table-column prop="startTime" label="面试时间" />
          <el-table-column label="综合评分">
            <template #default="{ row }">
              <span v-if="row.score">{{ row.score }} · {{ row.level }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)">{{ row.statusLabel || '状态未知' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'completed' || row.status === 'interrupted'" link type="primary" @click="viewReport(row.id)">查看报告</el-button>
              <el-button v-else-if="row.status === 'ongoing'" link type="primary" @click="continueInterview(row.id)">继续</el-button>
              <el-button link type="success" @click="viewGrowth(row.id)">成长方案</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="selectedSession" class="detail-card">
        <template #header>
          <span class="detail-title">
            {{ selectedSession.positionTitle }} · {{ selectedSession.company }}
            <span v-if="selectedSession.score"> · {{ selectedSession.score }}分 · {{ selectedSession.level }}</span>
          </span>
        </template>
        <p><strong>当前状态：</strong>
          <el-tag :type="statusTagType(selectedSession.status)">{{ selectedSession.statusLabel || '状态未知' }}</el-tag>
        </p>
        <p v-if="selectedSession.startTime"><strong>面试时间：</strong>{{ selectedSession.startTime }}</p>
        <p v-if="selectedSession.currentPhase"><strong>当前/最后环节：</strong>{{ selectedSession.currentPhaseLabel || '未知环节' }}</p>
        <div class="detail-actions">
          <el-button v-if="selectedSession.status === 'completed' || selectedSession.status === 'interrupted'" type="primary" @click="viewReport(selectedSession.id)">查看报告</el-button>
          <el-button v-else type="primary" @click="continueInterview(selectedSession.id)">继续面试</el-button>
          <el-button @click="viewGrowth(selectedSession.id)">查看成长方案</el-button>
        </div>
      </el-card>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppLayout from '@/components/AppLayout.vue'
import { getInterviewHistory } from '@/api'
import type { InterviewSession } from '@/types'

const router = useRouter()
const history = ref<InterviewSession[]>([])
const loading = ref(false)
const selectedSession = ref<InterviewSession | null>(null)
const filter = ref({
  position: '',
  status: '',
  dateRange: null as [Date, Date] | null
})

const positionOptions = computed(() => {
  return [...new Set(history.value.map(h => h.positionTitle))]
})

const filteredHistory = computed(() => {
  return history.value.filter(item => {
    const matchPosition = !filter.value.position || item.positionTitle === filter.value.position
    const matchStatus = !filter.value.status || item.status === filter.value.status
    return matchPosition && matchStatus
  })
})

onMounted(async () => {
  loading.value = true
  try {
    const list = await getInterviewHistory()
    console.log('[HistoryView] loaded', list)
    history.value = list
    selectedSession.value = history.value[0] || null
  } catch (error) {
    console.error('[HistoryView] failed', error)
    ElMessage.error((error as Error).message || '加载历史失败')
  } finally {
    loading.value = false
  }
})

function viewReport(id: number) {
  router.push(`/interview/${id}/report`)
}

function viewGrowth(id: number) {
  router.push(`/interview/${id}/growth`)
}

function continueInterview(id: number) {
  router.push(`/interview/${id}`)
}

function selectSession(row: InterviewSession) {
  selectedSession.value = row
}

function statusTagType(status: InterviewSession['status']) {
  if (status === 'completed') return 'success'
  if (status === 'ongoing') return 'primary'
  return 'info'
}
</script>

<style scoped>
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 20px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.detail-card {
  margin-top: 24px;
}

.detail-title {
  font-weight: 600;
}

.detail-actions {
  margin-top: 16px;
}
</style>
