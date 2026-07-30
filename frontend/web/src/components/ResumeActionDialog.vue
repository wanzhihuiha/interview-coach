<template>
  <ActionConfirmDialog
    v-model="visible"
    :title="content.title"
    :description="content.description"
    subject-label="简历文件"
    :subject="fileName || '当前简历'"
    :confirm-text="content.confirmText"
    :cancel-text="content.cancelText"
    :loading-text="content.loadingText"
    :tone="action === 'delete' ? 'danger' : 'primary'"
    :impact="content.impact"
    :loading="loading"
    @confirm="emit('confirm')"
    @closed="emit('closed')"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'

type ResumeAction = 'confirm' | 'reparse' | 'delete'

interface ActionContent {
  title: string
  description: string
  confirmText: string
  cancelText: string
  loadingText: string
  impact: Array<{ label: string; value: string }>
}

const props = withDefaults(defineProps<{
  modelValue: boolean
  action: ResumeAction
  fileName?: string
  loading?: boolean
}>(), {
  fileName: '',
  loading: false
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'confirm'): void
  (event: 'closed'): void
}>()

const ACTION_CONTENT: Record<ResumeAction, ActionContent> = {
  confirm: {
    title: '确认这份简历画像？',
    description: '确认后，职衡会把当前画像作为岗位匹配和后续面试的统一依据。',
    confirmText: '确认画像',
    cancelText: '返回检查',
    loadingText: '正在确认',
    impact: [
      { label: '原始文件', value: '保持不变' },
      { label: '使用范围', value: '岗位匹配与后续面试' }
    ]
  },
  reparse: {
    title: '重新解析这份简历？',
    description: '职衡将重新调用 AI 完整分析这份简历，解析期间仍会保留原始文件。',
    confirmText: '开始解析',
    cancelText: '暂不解析',
    loadingText: '正在提交',
    impact: [
      { label: '原始文件', value: '保持不变' },
      { label: '当前画像', value: '解析完成后更新' }
    ]
  },
  delete: {
    title: '删除这份简历？',
    description: '简历文件与已经生成的画像都会从你的档案中移除。',
    confirmText: '删除简历',
    cancelText: '保留简历',
    loadingText: '正在删除',
    impact: [
      { label: '档案内容', value: '简历文件与画像一并移除' },
      { label: '恢复方式', value: '删除后无法在页面内撤销' }
    ]
  }
}

const content = computed(() => ACTION_CONTENT[props.action])
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})
</script>
