<template>
  <AppLayout>
    <div class="page-container interview-page">
      <div v-if="!session" class="empty-interview">
        <el-empty description="暂无面试数据，请从面试配置页开始">
          <el-button type="primary" @click="$router.push('/interview/config')">去开始面试</el-button>
        </el-empty>
      </div>

      <template v-else>
        <div class="interview-header">
          <div class="interview-heading">
            <span class="page-eyebrow">LIVE INTERVIEW</span>
            <h1 class="interview-title">{{ isEnded ? '面试详情' : '面试进行中' }}</h1>
            <p>{{ session.positionTitle }} · 第 {{ currentQuestionNumber }} 题</p>
          </div>
          <div class="header-actions">
            <el-button v-if="isEnded" type="success" link @click="viewReport">查看报告</el-button>
            <el-button type="danger" link @click="handleInterrupt">{{ isEnded ? '离开' : '中断面试' }}</el-button>
          </div>
        </div>

        <el-row :gutter="24" class="interview-content">
          <el-col :span="16">
            <el-card class="question-card">
              <div class="current-info">
                <el-tag type="primary">当前环节 · {{ currentPhaseName }}</el-tag>
                <el-tag type="info">当前主题 · {{ currentTopic || '-' }}</el-tag>
                <el-tag type="warning">问题深度 · L{{ currentDepth }}</el-tag>
              </div>

              <div class="interviewer-area">
                <div class="area-label">面试官：</div>
                <div class="bubble question-bubble">
                  <div v-if="thinking" class="thinking-text">面试官正在思考...</div>
                  <div v-else>{{ displayedQuestion }}</div>
                </div>
              </div>

              <div class="answer-area">
                <div class="area-label">你的回答：</div>
                <div v-if="isEnded" class="ended-tip">
                  <el-alert
                    :title="session?.status === 'interrupted' ? '该面试已中断，无法继续回答' : '该面试已结束，无法继续回答'"
                    type="info"
                    :closable="false"
                    show-icon
                  />
                </div>
                <el-input
                  v-model="answer"
                  type="textarea"
                  :rows="8"
                  :placeholder="isEnded ? '该面试已结束' : '请输入你的回答...'"
                  resize="none"
                  :disabled="isEnded"
                />
              </div>

              <div class="submit-bar">
                <el-button
                  type="primary"
                  size="large"
                  :loading="submitting"
                  :disabled="!answer.trim() || thinking || isEnded"
                  @click="handleSubmit"
                >
                  提交回答
                </el-button>
              </div>
            </el-card>
          </el-col>

          <el-col :span="8">
            <InterviewProgress v-if="session" :phases="session.phases" />
          </el-col>
        </el-row>

        <p class="interview-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>回答提交后，面试官会根据内容决定继续追问或切换主题。</span>
        </p>

        <!-- 环节切换弹窗 -->
        <el-dialog v-model="phaseChangeVisible" title="环节切换" width="400px" :show-close="false" :close-on-click-modal="false">
          <div class="phase-change-content">
            <el-icon class="phase-complete-icon" :size="48"><CircleCheck /></el-icon>
            <h3>{{ previousPhaseName }}环节完成</h3>
            <p>即将进入：{{ nextPhaseName }}</p>
          </div>
          <template #footer>
            <el-button type="primary" @click="phaseChangeVisible = false">继续</el-button>
          </template>
        </el-dialog>
      </template>

      <ActionConfirmDialog
        v-model="exitDialogVisible"
        :title="exitActionContent.title"
        :description="exitActionContent.description"
        subject-label="当前面试"
        :subject="session?.positionTitle || '模拟面试'"
        :confirm-text="exitActionContent.confirmText"
        :cancel-text="exitActionContent.cancelText"
        :loading-text="exitActionContent.loadingText"
        :tone="pendingExitAction === 'interrupt' ? 'danger' : 'primary'"
        :impact="exitActionContent.impact"
        :loading="exitDialogLoading"
        @confirm="executeInterviewExit"
        @closed="resetExitDialog"
      />
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, InfoFilled } from '@element-plus/icons-vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import AppLayout from '@/components/AppLayout.vue'
import InterviewProgress from '@/components/InterviewProgress.vue'
import { getInterview, getInterviewMessages, submitAnswer, endInterview } from '@/api'
import { ensureTokenFresh } from '@/api/auth'
import type { InterviewSession, InterviewMessage } from '@/types'

type ExitAction = 'interrupt' | 'leave'

const EXIT_ACTION_CONTENT = {
  interrupt: {
    title: '中断当前面试？',
    description: '职衡会结束本次作答流程，并把这场面试标记为已中断。',
    confirmText: '中断面试',
    cancelText: '继续面试',
    loadingText: '正在中断',
    impact: [
      { label: '面试状态', value: '立即标记为已中断' },
      { label: '已产生记录', value: '仍可在历史记录中查看' }
    ]
  },
  leave: {
    title: '离开面试详情？',
    description: '本次面试已经结束，离开不会改变面试记录或报告。',
    confirmText: '离开详情',
    cancelText: '留在此页',
    loadingText: '正在离开',
    impact: [
      { label: '面试记录', value: '保持不变' },
      { label: '返回位置', value: '历史记录' }
    ]
  }
} as const

const route = useRoute()
const router = useRouter()
const interviewId = Number(route.params.id)

const session = ref<InterviewSession | null>(null)
const messages = ref<InterviewMessage[]>([])
const answer = ref('')
const submitting = ref(false)
const thinking = ref(false)
const displayedQuestion = ref('')
const phaseChangeVisible = ref(false)
const previousPhaseName = ref('')
const nextPhaseName = ref('')
const isEnded = ref(false)
const exitDialogVisible = ref(false)
const exitDialogLoading = ref(false)
const pendingExitAction = ref<ExitAction | null>(null)
let abortController: { abort: () => void } | null = null
let tokenRefreshTimer: number | null = null

const currentQuestion = computed<InterviewMessage | null>(() => {
  return [...messages.value].reverse().find(m => m.role === 'interviewer') || null
})

const currentPhaseName = computed(() => {
  const phase = session.value?.phases.find(p => p.current)
  return phase?.name || '-'
})

const currentTopic = computed(() => currentQuestion.value?.topic || '-')
const currentDepth = computed(() => currentQuestion.value?.depth ?? 1)
const currentQuestionNumber = computed(() => messages.value.filter(m => m.role === 'interviewer').length)
const exitActionContent = computed(() => EXIT_ACTION_CONTENT[pendingExitAction.value || 'leave'])

onMounted(async () => {
  try {
    const [detail, msgList] = await Promise.all([
      getInterview(interviewId),
      getInterviewMessages(interviewId)
    ])
    if (!detail) {
      ElMessage.error('面试不存在')
      return
    }
    session.value = detail
    messages.value = msgList
    isEnded.value = detail.status === 'completed' || detail.status === 'interrupted'
    if (currentQuestion.value) {
      typewriter(currentQuestion.value.content)
    }
    // 面试过程中主动检查 token 有效期，提前续期，避免中断
    ensureTokenFresh(10)
    tokenRefreshTimer = window.setInterval(() => {
      if (!isEnded.value) {
        ensureTokenFresh(5)
      }
    }, 60000)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载面试失败')
  }
})

onUnmounted(() => {
  abortController?.abort()
  if (tokenRefreshTimer) {
    clearInterval(tokenRefreshTimer)
    tokenRefreshTimer = null
  }
})

function typewriter(text: string) {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    displayedQuestion.value = text
    thinking.value = false
    return
  }
  displayedQuestion.value = ''
  thinking.value = true
  let index = 0
  setTimeout(() => {
    thinking.value = false
    const timer = setInterval(() => {
      if (index >= text.length) {
        clearInterval(timer)
        return
      }
      displayedQuestion.value += text[index]
      index++
    }, 30)
  }, 500)
}

function normalizePhaseKey(phase: string): string {
  switch (phase) {
    case 'SELF_INTRO': return 'intro'
    case 'PROFESSIONAL': return 'professional'
    case 'RESUME_DISCUSSION': return 'resume'
    case 'BEHAVIORAL': return 'behavior'
    case 'ENDING': return 'ending'
    default: return phase.toLowerCase()
  }
}

function updateSessionPhase(currentPhase: string) {
  if (!session.value) return
  const currentPhaseKey = normalizePhaseKey(currentPhase)
  let reachedCurrent = false
  session.value.phases = session.value.phases.map(p => {
    const isCurrent = p.key === currentPhaseKey
    if (isCurrent) reachedCurrent = true
    return { ...p, current: isCurrent, completed: !isCurrent && !reachedCurrent }
  })
}

async function handleSubmit() {
  if (!answer.value.trim() || !session.value) return
  submitting.value = true
  thinking.value = true

  // 在发起 SSE 长连接前续期 token，防止面试中途 token 失效
  await ensureTokenFresh(2)

  const userAnswer = answer.value.trim()
  answer.value = ''

  abortController = submitAnswer(interviewId, userAnswer, (event) => {
    switch (event.type) {
      case 'thinking':
        thinking.value = true
        break
      case 'phaseChange':
        previousPhaseName.value = event.previousPhaseLabel || '上一环节'
        nextPhaseName.value = event.currentPhaseLabel || '下一环节'
        if (event.currentPhase) {
          updateSessionPhase(event.currentPhase)
        }
        phaseChangeVisible.value = true
        break
      case 'question':
        if (event.content) {
          messages.value.push({
            messageId: Date.now(),
            phase: event.phase || session.value!.currentPhase || '',
            phaseLabel: event.phaseLabel,
            role: 'interviewer',
            content: event.content,
            topic: event.topicName,
            depth: event.depth,
            seqNo: messages.value.length + 1,
            createdAt: new Date().toISOString()
          })
          updateSessionPhase(event.phase || session.value!.currentPhase || '')
          typewriter(event.content)
        }
        break
      case 'interviewEnd':
        isEnded.value = true
        session.value!.status = 'completed'
        ElMessage.success('面试已结束')
        break
      case 'done':
        submitting.value = false
        thinking.value = false
        break
      case 'error':
        submitting.value = false
        thinking.value = false
        ElMessage.error(event.message || '回答处理失败')
        break
    }
  })
}

function handleInterrupt() {
  if (exitDialogLoading.value) return
  pendingExitAction.value = isEnded.value ? 'leave' : 'interrupt'
  exitDialogVisible.value = true
}

async function executeInterviewExit() {
  const action = pendingExitAction.value
  if (!action || exitDialogLoading.value) return

  exitDialogLoading.value = true
  try {
    if (action === 'interrupt') {
      await endInterview(interviewId)
    }
    exitDialogVisible.value = false
    await router.push('/history')
  } catch (error) {
    const fallback = action === 'interrupt' ? '中断面试失败' : '离开面试详情失败'
    ElMessage.error((error as Error).message || fallback)
  } finally {
    exitDialogLoading.value = false
  }
}

function resetExitDialog() {
  if (exitDialogVisible.value || exitDialogLoading.value) return
  pendingExitAction.value = null
}

function viewReport() {
  router.push(`/interview/${interviewId}/report`)
}
</script>

<style scoped>
.interview-page {
  padding-bottom: 56px;
}

.interview-header {
  display: flex;
  min-height: 96px;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 28px;
  padding-bottom: 22px;
}

.interview-heading p {
  margin: 9px 0 0;
  color: var(--color-muted);
  font-size: 14px;
}

.interview-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 30px;
  font-weight: 720;
  line-height: 1.2;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.interview-content {
  align-items: stretch;
}

.question-card {
  min-height: 600px;
  border-top: 2px solid var(--color-ink);
}

.current-info {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.area-label {
  font-size: 14px;
  color: var(--color-body);
  margin-bottom: 8px;
}

.interviewer-area {
  margin-bottom: 24px;
}

.bubble {
  min-height: 96px;
  border-left: 3px solid var(--color-brand-500);
  border-radius: var(--radius-sm);
  padding: 22px 24px;
  color: var(--color-ink);
  background: var(--color-surface-subtle);
  line-height: 1.8;
}

.thinking-text {
  color: var(--color-muted);
  font-style: italic;
}

.answer-area {
  margin-bottom: 16px;
}

.ended-tip {
  margin-bottom: 12px;
}

.submit-bar {
  text-align: right;
}

.interview-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  color: var(--color-muted);
  font-size: 13px;
}

.interview-tip .el-icon {
  color: var(--color-brand-600);
}

.phase-change-content {
  text-align: center;
  padding: 20px 0;
}

.phase-change-content h3 {
  margin: 16px 0 8px;
}

.phase-complete-icon {
  color: var(--color-success);
}

.empty-interview {
  min-height: 500px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
