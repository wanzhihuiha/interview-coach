<template>
  <AppLayout>
    <div class="page-container">
      <h2 class="page-title">开始面试</h2>

      <el-row :gutter="24">
        <el-col :span="12">
          <el-card>
            <template #header>
              <span class="card-title">选择简历</span>
            </template>
            <div v-if="resumes.length === 0" class="empty-tip">
              <el-empty description="暂无简历，请先上传">
                <el-button type="primary" @click="$router.push('/resume')">去上传</el-button>
              </el-empty>
            </div>
            <el-radio-group v-else v-model="selectedResumeId" class="selection-list">
              <el-radio
                v-for="resume in resumes"
                :key="resume.resumeId"
                :value="resume.resumeId"
                border
                class="selection-item"
              >
                <div class="selection-name">{{ resume.fileName }}</div>
                <div class="selection-meta">{{ resume.jobCategory }} · {{ resume.status === 'CONFIRMED' ? '已确认' : '待确认' }}</div>
              </el-radio>
            </el-radio-group>
          </el-card>
        </el-col>

        <el-col :span="12">
          <el-card>
            <template #header>
              <span class="card-title">选择岗位</span>
            </template>
            <div v-if="positions.length === 0" class="empty-tip">
              <el-empty description="暂无岗位，请先添加">
                <el-button type="primary" @click="$router.push('/position')">去添加</el-button>
              </el-empty>
            </div>
            <el-radio-group v-else v-model="selectedPositionId" class="selection-list">
              <el-radio
                v-for="position in positions"
                :key="position.positionId"
                :value="position.positionId"
                border
                class="selection-item"
              >
                <div class="selection-name">{{ position.positionName }}</div>
                <div class="selection-meta">{{ position.companyName }} · {{ position.jobCategory }}</div>
              </el-radio>
            </el-radio-group>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="phase-card">
        <template #header>
          <span class="card-title">选择面试环节</span>
        </template>
        <div class="phase-desc">固定顺序：自我介绍 → 专业面试 → 简历探讨 → 行为面试 → 结束</div>
        <el-checkbox-group v-model="selectedPhases" class="phase-list">
          <el-checkbox
            v-for="phase in phases"
            :key="phase.key"
            :value="phase.key"
            border
            class="phase-item"
          >
            <div class="phase-name">{{ phase.name }}</div>
            <div class="phase-desc-text">{{ phase.description }}</div>
          </el-checkbox>
        </el-checkbox-group>
      </el-card>

      <div class="action-bar">
        <el-button
          type="primary"
          size="large"
          :disabled="!canStart"
          :loading="starting"
          @click="handleStart"
        >
          开始模拟面试
        </el-button>
      </div>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppLayout from '@/components/AppLayout.vue'
import { getResumeList, getPositionList, startInterview } from '@/api'
import type { Resume, Position, InterviewPhase } from '@/types'

const router = useRouter()
const resumes = ref<Resume[]>([])
const positions = ref<Position[]>([])
const selectedResumeId = ref<number | null>(null)
const selectedPositionId = ref<number | null>(null)
const selectedPhases = ref<string[]>(['intro', 'professional', 'resume', 'behavior'])
const starting = ref(false)

const phases: InterviewPhase[] = [
  { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1, completed: false, current: false },
  { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8, completed: false, current: false },
  { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3, completed: false, current: false },
  { key: 'behavior', name: '行为面试', description: 'STAR问题，考察软技能', questionCount: 4, completed: false, current: false }
]

const canStart = computed(() => {
  return selectedResumeId.value && selectedPositionId.value && selectedPhases.value.length > 0
})

onMounted(async () => {
  const [rList, pList] = await Promise.all([
    getResumeList(),
    getPositionList()
  ])
  resumes.value = rList
  positions.value = pList
  if (rList[0]) selectedResumeId.value = rList[0].resumeId
  if (pList[0]) selectedPositionId.value = pList[0].positionId
})

async function handleStart() {
  if (!canStart.value) return
  starting.value = true
  try {
    const session = await startInterview({
      resumeId: selectedResumeId.value!,
      positionId: selectedPositionId.value!,
      phases: selectedPhases.value
    })
    router.push(`/interview/${session.id}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '启动面试失败')
  } finally {
    starting.value = false
  }
}
</script>

<style scoped>
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 20px;
}

.card-title {
  font-weight: 600;
}

.selection-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.selection-item {
  width: 100%;
  height: auto;
  padding: 12px;
  margin: 0;
}

.selection-name {
  font-weight: 600;
  margin-bottom: 4px;
}

.selection-meta {
  font-size: 12px;
  color: #909399;
}

.phase-card {
  margin-top: 24px;
}

.phase-desc {
  font-size: 13px;
  color: #909399;
  margin-bottom: 16px;
}

.phase-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.phase-item {
  width: calc(50% - 6px);
  height: auto;
  margin: 0;
  padding: 12px;
}

.phase-name {
  font-weight: 600;
  margin-bottom: 4px;
}

.phase-desc-text {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.action-bar {
  margin-top: 24px;
  text-align: right;
}

.empty-tip {
  text-align: center;
  padding: 20px 0;
}
</style>
