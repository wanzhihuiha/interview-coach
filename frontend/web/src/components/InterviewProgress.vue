<template>
  <div class="interview-progress">
    <div class="progress-header">
      <span class="progress-title">面试进度</span>
      <span class="progress-text">{{ progressPercent }}%</span>
    </div>
    <el-progress :percentage="progressPercent" :stroke-width="10" />

    <div class="phase-list">
      <div
        v-for="phase in phases"
        :key="phase.key"
        class="phase-item"
        :class="{ completed: phase.completed, current: phase.current }"
      >
        <el-icon v-if="phase.completed" class="phase-icon"><Check /></el-icon>
        <el-icon v-else-if="phase.current" class="phase-icon current-icon"><Loading /></el-icon>
        <el-icon v-else class="phase-icon"><Minus /></el-icon>
        <span class="phase-name">{{ phase.name }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Check, Minus, Loading } from '@element-plus/icons-vue'
import type { InterviewPhase } from '@/types'

const props = defineProps<{
  phases: InterviewPhase[]
}>()

const progressPercent = computed(() => {
  const completed = props.phases.filter(p => p.completed).length
  return Math.round((completed / props.phases.length) * 100)
})
</script>

<style scoped>
.interview-progress {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.progress-title {
  font-weight: 600;
  color: #303133;
}

.progress-text {
  font-size: 14px;
  color: #409EFF;
  font-weight: 600;
}

.phase-list {
  margin-top: 20px;
}

.phase-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  color: #909399;
  border-bottom: 1px solid #f0f2f5;
}

.phase-item:last-child {
  border-bottom: none;
}

.phase-item.completed {
  color: #67C23A;
}

.phase-item.current {
  color: #409EFF;
  font-weight: 600;
}

.phase-icon {
  font-size: 16px;
}

.current-icon {
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
