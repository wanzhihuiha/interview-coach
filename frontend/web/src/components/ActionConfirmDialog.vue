<template>
  <el-dialog
    v-model="visible"
    class="action-confirm-dialog"
    modal-class="action-confirm-dialog-overlay"
    width="680px"
    align-center
    append-to-body
    destroy-on-close
    :show-close="false"
    :close-on-click-modal="!loading"
    :close-on-press-escape="!loading"
    :before-close="handleBeforeClose"
    @opened="focusPreferredAction"
    @closed="emit('closed')"
  >
    <template #header="{ titleId, titleClass }">
      <div class="action-dialog-heading">
        <button
          class="action-dialog-close"
          type="button"
          title="关闭"
          aria-label="关闭弹窗"
          :disabled="loading"
          @click="requestClose"
        >
          <el-icon><Close /></el-icon>
        </button>

        <p class="action-dialog-context">
          <span>{{ subjectLabel }}</span>
          <strong :title="subject || '当前对象'">{{ subject || '当前对象' }}</strong>
        </p>
        <h2 :id="titleId" :class="[titleClass, 'action-dialog-title']">{{ title }}</h2>
        <p class="action-dialog-description">{{ description }}</p>
      </div>
    </template>

    <dl v-if="impact.length" class="action-dialog-impact">
      <div v-for="item in impact" :key="item.label" class="action-dialog-impact-row">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value }}</dd>
      </div>
    </dl>

    <div class="action-dialog-actions" :aria-busy="loading">
      <button
        ref="cancelButtonRef"
        class="action-dialog-button is-secondary"
        type="button"
        :disabled="loading"
        @click="requestClose"
      >
        {{ cancelText }}
      </button>
      <button
        ref="confirmButtonRef"
        class="action-dialog-button is-primary"
        :class="{ 'is-danger': tone === 'danger' }"
        type="button"
        :disabled="loading"
        @click="emit('confirm')"
      >
        <el-icon v-if="loading" class="action-dialog-loading" aria-hidden="true"><Loading /></el-icon>
        <span>{{ loading ? loadingText : confirmText }}</span>
      </button>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Close, Loading } from '@element-plus/icons-vue'

interface ActionImpactItem {
  label: string
  value: string
}

const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  description: string
  subject?: string
  subjectLabel?: string
  confirmText: string
  cancelText?: string
  loadingText?: string
  tone?: 'primary' | 'danger'
  impact?: readonly ActionImpactItem[]
  loading?: boolean
}>(), {
  subject: '',
  subjectLabel: '操作对象',
  cancelText: '取消',
  loadingText: '正在处理',
  tone: 'primary',
  impact: () => [],
  loading: false
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'confirm'): void
  (event: 'closed'): void
}>()

const cancelButtonRef = ref<HTMLButtonElement | null>(null)
const confirmButtonRef = ref<HTMLButtonElement | null>(null)
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => {
    if (!value && props.loading) return
    emit('update:modelValue', value)
  }
})

function requestClose() {
  if (props.loading) return
  emit('update:modelValue', false)
}

function handleBeforeClose(done: () => void) {
  if (!props.loading) done()
}

function focusPreferredAction() {
  void nextTick(() => {
    const target = props.tone === 'danger' ? cancelButtonRef.value : confirmButtonRef.value
    target?.focus()
  })
}
</script>

<style scoped>
:global(.action-confirm-dialog-overlay) {
  background: rgba(21, 21, 20, 0.46);
  backdrop-filter: blur(3px) saturate(0.8);
}

:global(.action-confirm-dialog) {
  display: flex;
  max-width: calc(100vw - 80px);
  max-height: calc(100vh - 64px);
  margin: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(21, 21, 20, 0.12);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
  box-shadow: 0 24px 72px rgba(21, 21, 20, 0.2);
}

:global(.action-confirm-dialog .el-dialog__header) {
  margin: 0;
  padding: 40px 44px 0;
}

:global(.action-confirm-dialog .el-dialog__body) {
  min-height: 0;
  padding: 0 44px 40px;
  flex: 1 1 auto;
  overflow-y: auto;
}

.action-dialog-heading {
  position: relative;
}

.action-dialog-close {
  position: absolute;
  top: -12px;
  right: -12px;
  display: inline-flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  color: var(--color-muted);
  background: transparent;
  cursor: pointer;
  font-size: 20px;
  transition: color var(--motion-fast) var(--ease-standard),
    background-color var(--motion-fast) var(--ease-standard);
}

.action-dialog-close:hover:not(:disabled) {
  color: var(--color-ink);
  background: var(--color-surface-subtle);
}

.action-dialog-close:focus-visible,
.action-dialog-button:focus-visible {
  outline: 3px solid var(--color-focus-ring);
  outline-offset: 2px;
}

.action-dialog-close:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.action-dialog-context {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  gap: 12px;
  margin: 0;
  padding-right: 56px;
}

.action-dialog-context span {
  color: var(--color-brand-600);
  font-size: 12px;
  font-weight: 700;
}

.action-dialog-context strong {
  min-width: 0;
  color: var(--color-muted);
  font-size: 14px;
  font-weight: 550;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.action-dialog-title {
  margin: 12px 0 0;
  color: var(--color-ink);
  font-size: 28px;
  font-weight: 720;
  line-height: 1.28;
  letter-spacing: 0;
}

.action-dialog-description {
  max-width: 560px;
  margin: 14px 0 0;
  color: var(--color-body);
  font-size: 16px;
  line-height: 1.75;
}

.action-dialog-impact {
  margin: 32px 0 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.action-dialog-impact-row {
  display: grid;
  min-height: 58px;
  grid-template-columns: 112px minmax(0, 1fr);
  align-items: center;
  gap: 20px;
}

.action-dialog-impact-row + .action-dialog-impact-row {
  border-top: 1px solid var(--color-border);
}

.action-dialog-impact dt {
  color: var(--color-muted);
  font-size: 13px;
  font-weight: 550;
}

.action-dialog-impact dd {
  margin: 0;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.action-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 32px;
}

.action-dialog-button {
  display: inline-flex;
  min-width: 132px;
  height: 48px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  padding: 0 22px;
  cursor: pointer;
  font-family: inherit;
  font-size: 15px;
  font-weight: 650;
  line-height: 1;
  transition: color var(--motion-fast) var(--ease-standard),
    border-color var(--motion-fast) var(--ease-standard),
    background-color var(--motion-fast) var(--ease-standard),
    box-shadow var(--motion-fast) var(--ease-standard);
}

.action-dialog-button.is-secondary {
  border-color: var(--color-border);
  color: var(--color-ink);
  background: var(--color-surface);
}

.action-dialog-button.is-secondary:hover:not(:disabled) {
  border-color: var(--color-border-strong);
  background: var(--color-surface-subtle);
}

.action-dialog-button.is-primary {
  color: #ffffff;
  background: var(--color-brand-600);
  box-shadow: 0 8px 20px rgba(201, 59, 48, 0.18);
}

.action-dialog-button.is-primary:hover:not(:disabled) {
  background: var(--color-brand-700);
  box-shadow: 0 10px 24px rgba(201, 59, 48, 0.24);
}

.action-dialog-button.is-primary.is-danger {
  background: var(--color-danger);
  box-shadow: 0 8px 20px rgba(180, 35, 24, 0.17);
}

.action-dialog-button.is-primary.is-danger:hover:not(:disabled) {
  background: #8f1d14;
  box-shadow: 0 10px 24px rgba(180, 35, 24, 0.23);
}

.action-dialog-button:disabled {
  cursor: wait;
  opacity: 0.48;
  box-shadow: none;
}

.action-dialog-loading {
  animation: action-dialog-spin 1s linear infinite;
}

@keyframes action-dialog-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 900px) {
  :global(.action-confirm-dialog) {
    max-width: calc(100vw - 40px);
  }

  :global(.action-confirm-dialog .el-dialog__header) {
    padding: 32px 32px 0;
  }

  :global(.action-confirm-dialog .el-dialog__body) {
    padding: 0 32px 32px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .action-dialog-close,
  .action-dialog-button {
    transition: none;
  }

  .action-dialog-loading {
    animation: none;
  }
}
</style>
