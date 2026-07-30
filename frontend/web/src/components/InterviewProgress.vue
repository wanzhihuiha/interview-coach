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
  min-height: 600px;
  border-radius: var(--radius-panel);
  padding: 26px;
  color: var(--color-on-console);
  background: var(--color-console-soft);
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.progress-title {
  font-weight: 600;
  color: var(--color-on-console);
}

.progress-text {
  font-size: 14px;
  color: var(--color-brand-500);
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
  color: #8f8e88;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.phase-item:last-child {
  border-bottom: none;
}

.phase-item.completed {
  color: var(--color-success);
}

.phase-item.current {
  color: var(--color-brand-500);
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
