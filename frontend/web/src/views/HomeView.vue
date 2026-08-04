<template>
  <AppLayout>
    <main id="main-content" class="home-page">
      <section class="hero-section" aria-labelledby="home-title">
        <div class="hero-grid">
          <div class="hero-copy">
            <div class="eyebrow">
              <span class="eyebrow-dot" aria-hidden="true"></span>
              ROLEFIT AI · AI 人岗匹配与智能面试
            </div>

            <p class="welcome-line">{{ heroWelcome }}</p>
            <h1 id="home-title">
              正式面试之前，
              <span>先把关键问题答好</span>
            </h1>
            <p class="hero-description">
              上传简历，选择目标岗位。AI 会围绕你的经历与岗位要求连续追问，并在结束后给出逐题分析和具体改进建议。
            </p>

            <div class="hero-actions">
              <el-button class="primary-action" type="primary" size="large" @click="goToInterview">
                <el-icon><VideoCamera /></el-icon>
                开始模拟面试
                <el-icon class="action-arrow"><ArrowRight /></el-icon>
              </el-button>
              <el-button class="secondary-action" size="large" @click="goToHistory">
                <el-icon><Clock /></el-icon>
                {{ isGuest ? '登录查看训练记录' : '查看训练记录' }}
              </el-button>
            </div>

            <div class="hero-capabilities" aria-label="训练能力">
              <span><el-icon><Document /></el-icon> 基于简历</span>
              <span><el-icon><Position /></el-icon> 针对岗位</span>
              <span><el-icon><MagicStick /></el-icon> 连续追问</span>
              <span><el-icon><TrendCharts /></el-icon> 逐题复盘</span>
            </div>
          </div>

          <div class="system-visual" role="img" aria-label="展示从简历准备、岗位分析、模拟问答到逐题复盘和改进建议的完整训练过程">
            <div class="visual-meta">
              <span>从准备到复盘</span>
            </div>
            <TrainingArchitectureScene class="architecture-scene" />
          </div>
        </div>
        <div class="hero-footer">
          <span class="hero-footer-label">一次完整训练</span>
          <span>理解经历 · 对齐岗位 · 模拟追问 · 逐题复盘 · 持续改进</span>
        </div>
      </section>

      <section class="console-section" aria-labelledby="console-title">
        <div class="section-heading">
          <div>
            <p class="section-index">你的面试准备</p>
            <h2 id="console-title">从这里开始准备</h2>
          </div>
          <p>整理简历、确认目标岗位，准备好后开始模拟面试。</p>
        </div>

        <div v-if="isGuest" class="guest-access-bar" role="status">
          <div>
            <el-icon><User /></el-icon>
            <span><strong>当前可浏览完整训练流程</strong>，登录后可上传简历、保存目标岗位并开始模拟面试。</span>
          </div>
          <el-button link type="primary" @click="router.push('/login')">
            登录或注册
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <div v-if="loadError" class="load-notice" role="status">
          <span>部分内容暂时无法加载，你仍可以开始面试或进入对应页面查看。</span>
          <el-button link type="primary" @click="loadDashboard">重新加载</el-button>
        </div>

        <div class="console-grid">
          <section class="console-column" aria-labelledby="resume-title">
            <header class="column-header">
              <div class="column-title-wrap">
                <el-icon><Document /></el-icon>
                <div>
                  <span>01 / 准备简历</span>
                  <h3 id="resume-title">我的简历</h3>
                </div>
              </div>
              <span class="column-count">{{ resumes.length.toString().padStart(2, '0') }}</span>
            </header>

            <div v-if="loading" class="skeleton-list" aria-label="正在加载简历">
              <span v-for="item in 3" :key="item"></span>
            </div>
            <div v-else class="data-list">
              <button
                v-for="resume in resumes"
                :key="resume.resumeId"
                class="data-row"
                type="button"
                @click="router.push('/resume')"
              >
                <span class="row-main">
                  <strong>{{ resume.fileName }}</strong>
                  <small>{{ resume.jobCategoryLabel || '待完善求职方向' }} · {{ formatTime(resume.createdAt) }}</small>
                </span>
                <span class="row-state" :class="{ 'row-state--ready': resume.status === 'CONFIRMED' }">
                  {{ resume.statusLabel || '状态未知' }}
                </span>
              </button>
              <button v-if="!resumes.length" class="empty-row" type="button" @click="router.push('/resume')">
                上传一份简历，让 AI 了解你的经历
              </button>
            </div>

            <button class="column-action" type="button" @click="router.push('/resume')">
              <el-icon><Plus /></el-icon>
              管理简历
              <el-icon><ArrowRight /></el-icon>
            </button>
          </section>

          <section class="console-column" aria-labelledby="position-title">
            <header class="column-header">
              <div class="column-title-wrap">
                <el-icon><Position /></el-icon>
                <div>
                  <span>02 / 确认岗位</span>
                  <h3 id="position-title">目标岗位</h3>
                </div>
              </div>
              <span class="column-count">{{ positions.length.toString().padStart(2, '0') }}</span>
            </header>

            <div v-if="loading" class="skeleton-list" aria-label="正在加载岗位">
              <span v-for="item in 3" :key="item"></span>
            </div>
            <div v-else class="data-list">
              <button
                v-for="positionItem in positions"
                :key="positionItem.positionId"
                class="data-row"
                type="button"
                @click="router.push('/position')"
              >
                <span class="row-main">
                  <strong>{{ positionItem.positionName }}</strong>
                  <small>{{ positionItem.companyName || '目标公司待定' }} · {{ positionItem.jobCategoryLabel || '岗位类别待识别' }}</small>
                </span>
                <span class="row-state row-state--matched">可训练</span>
              </button>
              <button v-if="!positions.length" class="empty-row" type="button" @click="router.push('/position')">
                添加目标岗位，让提问贴近真实要求
              </button>
            </div>

            <button class="column-action" type="button" @click="router.push('/position')">
              <el-icon><Plus /></el-icon>
              选择岗位
              <el-icon><ArrowRight /></el-icon>
            </button>
          </section>

          <section class="console-column" aria-labelledby="history-title">
            <header class="column-header">
              <div class="column-title-wrap">
                <el-icon><TrendCharts /></el-icon>
                <div>
                  <span>03 / 查看复盘</span>
                  <h3 id="history-title">最近面试</h3>
                </div>
              </div>
              <span class="column-count">{{ recentInterviews.length.toString().padStart(2, '0') }}</span>
            </header>

            <div v-if="loading" class="skeleton-list" aria-label="正在加载面试记录">
              <span v-for="item in 3" :key="item"></span>
            </div>
            <div v-else class="data-list">
              <button
                v-for="interview in recentInterviews"
                :key="interview.id"
                class="data-row interview-row"
                type="button"
                @click="viewInterview(interview)"
              >
                <span class="score-cell">
                  <strong>{{ interview.score ?? '--' }}</strong>
                  <small>{{ interview.level || interview.statusLabel || '状态未知' }}</small>
                </span>
                <span class="row-main">
                  <strong>{{ interview.positionTitle }}</strong>
                  <small>{{ interview.company || '模拟面试' }} · {{ formatDateTime(interview.startTime) }}</small>
                </span>
                <el-icon><ArrowRight /></el-icon>
              </button>
              <button v-if="!recentInterviews.length" class="empty-row" type="button" @click="goToInterview">
                完成第一次模拟面试后，可在这里查看报告
              </button>
            </div>

            <button class="column-action" type="button" @click="router.push('/history')">
              <el-icon><Clock /></el-icon>
              全部记录
              <el-icon><ArrowRight /></el-icon>
            </button>
          </section>
        </div>
      </section>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Clock,
  Document,
  MagicStick,
  Plus,
  Position,
  TrendCharts,
  User,
  VideoCamera
} from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import TrainingArchitectureScene from '@/components/TrainingArchitectureScene.vue'
import { useUserStore } from '@/stores/user'
import { getResumeList, getAccessiblePositionList, getInterviewHistory } from '@/api'
import type { Resume, Position as PositionItem, InterviewSession } from '@/types'

const router = useRouter()
const userStore = useUserStore()

const resumes = ref<Resume[]>([])
const positions = ref<PositionItem[]>([])
const recentInterviews = ref<InterviewSession[]>([])
const loading = ref(true)
const loadError = ref(false)
const isGuest = computed(() => !userStore.isLoggedIn)

const displayName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.username || '访客'
)

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const heroWelcome = computed(() =>
  isGuest.value ? '为下一场面试，做一次有针对性的准备' : `${greeting.value}，${displayName.value}`
)

onMounted(async () => {
  if (isGuest.value) {
    loading.value = false
    return
  }

  await userStore.fetchUserInfo()
  if (userStore.isLoggedIn) {
    await loadDashboard()
  } else {
    loading.value = false
  }
})

async function loadDashboard() {
  if (!userStore.isLoggedIn) {
    resumes.value = []
    positions.value = []
    recentInterviews.value = []
    loadError.value = false
    loading.value = false
    return
  }

  loading.value = true
  loadError.value = false

  const [resumeResult, positionResult, historyResult] = await Promise.allSettled([
    getResumeList(),
    getAccessiblePositionList({ page: 0, size: 5 }),
    getInterviewHistory()
  ])

  if (resumeResult.status === 'fulfilled') {
    resumes.value = resumeResult.value.slice(0, 2)
  }
  if (positionResult.status === 'fulfilled') {
    positions.value = positionResult.value.content
      .filter(position => position.profileUsable && !position.archived)
      .slice(0, 5)
  }
  if (historyResult.status === 'fulfilled') {
    recentInterviews.value = historyResult.value.slice(0, 3)
  }

  loadError.value = [resumeResult, positionResult, historyResult].some(
    (result) => result.status === 'rejected'
  )
  loading.value = false
}

function goToInterview() {
  router.push(isGuest.value ? '/login' : '/interview/config')
}

function goToHistory() {
  router.push(isGuest.value ? '/login' : '/history')
}

function viewInterview(interview: InterviewSession) {
  if (interview.status === 'completed') {
    router.push(`/interview/${interview.id}/report`)
    return
  }
  router.push(`/interview/${interview.id}`)
}

function formatTime(value?: string): string {
  if (!value) return '时间待补充'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

function formatDateTime(value?: string): string {
  if (!value) return '时间待补充'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}
</script>

<style scoped>
.home-page {
  --home-bg: #08090b;
  --home-surface: #101216;
  --home-surface-soft: #15181e;
  --home-text: #f3f1e9;
  --home-muted: #9b9da5;
  --home-border: rgba(255, 255, 255, 0.12);
  --home-blue: var(--color-brand-500);
  --home-green: #65d67b;
  min-height: 100vh;
  overflow: hidden;
  color: var(--home-text);
  background: var(--home-bg);
}

.hero-section {
  position: relative;
  min-height: min(760px, calc(100dvh - 72px));
  padding: 56px max(32px, calc((100vw - 1440px) / 2)) 28px;
  border-bottom: 1px solid var(--home-border);
  background-color: var(--home-bg);
  isolation: isolate;
}

.hero-section::before {
  display: none;
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(560px, 1.1fr);
  gap: 40px;
  align-items: center;
  max-width: 1440px;
  min-height: 590px;
  margin: 0 auto;
}

.hero-copy {
  position: relative;
  z-index: 2;
  max-width: 660px;
  animation: hero-copy-enter 700ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.eyebrow,
.section-index,
.visual-meta,
.column-title-wrap span,
.hero-footer-label {
  font-family: Consolas, 'SFMono-Regular', monospace;
  letter-spacing: 0;
}

.eyebrow {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 64px;
  color: #d4d5da;
  font-size: 12px;
  font-weight: 700;
}

.eyebrow-dot {
  width: 8px;
  height: 8px;
  border: 2px solid var(--home-blue);
  background: transparent;
}

.welcome-line {
  margin-bottom: 16px;
  color: #b9bac0;
  font-size: 15px;
}

h1 {
  margin: 0;
  color: var(--home-text);
  font-size: 64px;
  line-height: 1.12;
  font-weight: 800;
}

h1 span {
  display: block;
  color: #cac7bd;
}

.hero-description {
  max-width: 540px;
  margin: 24px 0 0;
  color: #a8a9b0;
  font-size: 17px;
  line-height: 1.75;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 32px;
}

.hero-actions :deep(.el-button) {
  min-height: 52px;
  margin: 0;
  border-radius: 4px;
  padding: 0 22px;
  font-weight: 700;
  transition: color 200ms ease, background-color 200ms ease, border-color 200ms ease, box-shadow 200ms ease;
}

.primary-action.el-button {
  border-color: #ece9df;
  color: #0a0b0d;
  background: #ece9df;
}

.primary-action.el-button:hover,
.primary-action.el-button:focus-visible {
  border-color: #ffffff;
  background: #ffffff;
  box-shadow: 0 0 0 4px var(--color-focus-ring);
}

.secondary-action.el-button {
  border-color: rgba(255, 255, 255, 0.22);
  color: #e5e3dd;
  background: transparent;
}

.secondary-action.el-button:hover,
.secondary-action.el-button:focus-visible {
  border-color: rgba(255, 255, 255, 0.52);
  color: #ffffff;
  background: rgba(255, 255, 255, 0.06);
}

.action-arrow {
  margin-left: 6px;
}

.hero-capabilities {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 32px;
  color: #858891;
  font-size: 13px;
}

.hero-capabilities span {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.hero-capabilities .el-icon {
  color: var(--color-brand-500);
  font-size: 15px;
}

.system-visual {
  position: relative;
  align-self: stretch;
  min-height: 590px;
  perspective: 1500px;
  perspective-origin: 58% 42%;
  isolation: isolate;
  animation: system-visual-enter 850ms 120ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.system-visual::after {
  display: none;
}

.visual-meta {
  position: absolute;
  top: 8px;
  right: 12px;
  display: flex;
  gap: 12px;
  color: #74767e;
  font-size: 11px;
  z-index: 5;
}

.system-visual > .architecture-scene {
  position: absolute;
  inset: -26px -2% -44px -15%;
  width: auto;
  height: auto;
  z-index: 1;
}

@keyframes hero-copy-enter {
  from {
    opacity: 0;
    transform: translate3d(-24px, 0, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@keyframes system-visual-enter {
  from {
    opacity: 0;
    transform: translate3d(28px, 0, 0) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0) scale(1);
  }
}

.hero-footer {
  display: flex;
  max-width: 1440px;
  align-items: center;
  gap: 16px;
  margin: 22px auto 0;
  color: #777981;
  font-size: 12px;
}

.hero-footer::before {
  width: 2px;
  height: 42px;
  content: '';
  background: var(--home-blue);
}

.hero-footer-label {
  color: var(--color-brand-500);
  font-size: 10px;
  font-weight: 700;
}

.console-section {
  max-width: 1440px;
  margin: 0 auto;
  padding: 72px 32px 96px;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 44px;
}

.section-index {
  margin-bottom: 8px;
  color: var(--color-brand-500);
  font-size: 11px;
  font-weight: 700;
}

.section-heading h2 {
  margin: 0;
  color: var(--home-text);
  font-size: 34px;
  line-height: 1.2;
}

.section-heading > p {
  max-width: 420px;
  margin: 0;
  color: var(--home-muted);
  line-height: 1.7;
}

.load-notice {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  border: 1px solid rgba(241, 184, 70, 0.35);
  padding: 10px 16px;
  color: #d7cba8;
  background: rgba(241, 184, 70, 0.06);
  font-size: 13px;
}

.guest-access-bar {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  border-top: 1px solid rgba(233, 76, 58, 0.45);
  border-bottom: 1px solid rgba(233, 76, 58, 0.24);
  padding: 10px 4px;
  color: #aeb0b8;
  font-size: 13px;
}

.guest-access-bar > div {
  display: flex;
  align-items: center;
  gap: 10px;
  line-height: 1.6;
}

.guest-access-bar > div > .el-icon {
  flex: 0 0 auto;
  color: var(--color-brand-500);
  font-size: 20px;
}

.guest-access-bar strong {
  color: #e7e5de;
}

.guest-access-bar :deep(.el-button) {
  min-height: 44px;
  flex: 0 0 auto;
  color: var(--color-brand-100);
}

.console-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 40px;
}

.console-column {
  display: flex;
  min-width: 0;
  min-height: 430px;
  flex-direction: column;
  border-top: 1px solid rgba(255, 255, 255, 0.28);
  border-bottom: 1px solid var(--home-border);
}

.column-header {
  display: flex;
  min-height: 96px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--home-border);
}

.column-title-wrap {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}

.column-title-wrap > .el-icon {
  flex: 0 0 auto;
  color: var(--color-brand-500);
  font-size: 24px;
}

.column-title-wrap span {
  display: block;
  margin-bottom: 4px;
  color: #6d7079;
  font-size: 9px;
}

.column-title-wrap h3 {
  margin: 0;
  color: #e8e6df;
  font-size: 18px;
}

.column-count {
  color: #595c64;
  font-family: Consolas, monospace;
  font-size: 22px;
}

.data-list {
  flex: 1;
}

.data-row,
.empty-row,
.column-action {
  width: 100%;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.data-row {
  display: flex;
  min-height: 82px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  padding: 14px 4px;
  color: inherit;
  background: transparent;
  transition: padding 200ms ease, background-color 200ms ease;
}

.data-row:hover,
.data-row:focus-visible {
  padding-right: 12px;
  padding-left: 12px;
  outline: none;
  background: rgba(255, 255, 255, 0.045);
}

.data-row:focus-visible,
.empty-row:focus-visible,
.column-action:focus-visible {
  box-shadow: inset 0 0 0 2px var(--color-brand-500);
}

.row-main {
  display: block;
  min-width: 0;
}

.row-main strong {
  display: block;
  overflow: hidden;
  color: #deddd8;
  font-size: 14px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row-main small {
  display: block;
  overflow: hidden;
  margin-top: 7px;
  color: #7f828a;
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row-state {
  flex: 0 0 auto;
  border: 1px solid #72757d;
  border-radius: 3px;
  padding: 4px 6px;
  color: #a2a4aa;
  font-family: Consolas, monospace;
  font-size: 9px;
}

.row-state--ready,
.row-state--matched {
  border-color: rgba(101, 214, 123, 0.4);
  color: #72d986;
}

.score-cell {
  display: flex;
  width: 46px;
  flex: 0 0 46px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-right: 1px solid var(--home-border);
}

.score-cell strong {
  color: var(--color-brand-100);
  font-family: Consolas, monospace;
  font-size: 21px;
}

.score-cell small {
  margin-top: 3px;
  color: #6f727b;
  font-size: 9px;
}

.interview-row > .row-main {
  flex: 1;
}

.interview-row > .el-icon {
  flex: 0 0 auto;
  color: #646873;
}

.empty-row {
  display: flex;
  min-height: 164px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  padding: 24px;
  color: #8e9199;
  background: transparent;
  line-height: 1.7;
  text-align: center;
}

.empty-row:hover {
  color: #d7d8dd;
  background: rgba(255, 255, 255, 0.035);
}

.column-action {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) 20px;
  min-height: 58px;
  align-items: center;
  gap: 8px;
  border: 0;
  color: #aeb0b8;
  background: transparent;
  font-size: 13px;
  transition: color 200ms ease, background-color 200ms ease;
}

.column-action:hover {
  color: #ffffff;
  background: rgba(233, 76, 58, 0.08);
}

.skeleton-list {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.skeleton-list span {
  height: 82px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
}

@media (max-width: 1180px) {
  .hero-section {
    padding-top: 40px;
  }

  .hero-grid {
    grid-template-columns: minmax(0, 0.85fr) minmax(470px, 1.15fr);
    gap: 16px;
  }

  .eyebrow {
    margin-bottom: 42px;
  }

  h1 {
    font-size: 56px;
  }

  .system-visual > .architecture-scene {
    inset: -26px -12% -44px -7%;
  }

  .console-grid {
    gap: 24px;
  }
}

@media (max-width: 960px) {
  .hero-section {
    min-height: auto;
    padding: 36px 24px 28px;
  }

  .hero-grid {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .hero-copy {
    max-width: 720px;
  }

  .eyebrow {
    margin-bottom: 32px;
  }

  .system-visual {
    min-height: 520px;
  }

  .system-visual > .architecture-scene {
    inset: -22px -6% -38px -6%;
  }

  .console-grid {
    grid-template-columns: 1fr;
    gap: 40px;
  }

  .console-column {
    min-height: 0;
  }
}

@media (max-width: 640px) {
  .hero-section {
    padding: 24px 16px 20px;
  }

  .hero-grid {
    gap: 14px;
  }

  .eyebrow {
    margin-bottom: 22px;
    font-size: 9px;
  }

  .welcome-line {
    margin-bottom: 10px;
    font-size: 13px;
  }

  h1 {
    font-size: 36px;
    line-height: 1.14;
  }

  .hero-description {
    margin-top: 16px;
    max-width: 100%;
    overflow-wrap: anywhere;
    font-size: 16px;
    line-height: 1.65;
  }

  .hero-actions {
    display: grid;
    grid-template-columns: 1fr;
    margin-top: 22px;
  }

  .hero-actions :deep(.el-button) {
    width: 100%;
  }

  .hero-capabilities {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
    margin-top: 20px;
  }

  .system-visual {
    min-height: 360px;
  }

  .visual-meta {
    top: 4px;
    font-size: 8px;
  }

  .system-visual > .architecture-scene {
    inset: -12px -12% -20px -12%;
  }

  .hero-footer {
    display: grid;
    grid-template-columns: 2px 1fr;
    gap: 4px 12px;
    margin-top: 8px;
    font-size: 10px;
  }

  .hero-footer::before {
    grid-row: 1 / 3;
  }

  .console-section {
    padding: 52px 16px 72px;
  }

  .section-heading {
    display: block;
    margin-bottom: 32px;
  }

  .section-heading h2 {
    font-size: 28px;
  }

  .section-heading > p {
    margin-top: 12px;
    font-size: 14px;
  }

  .load-notice {
    align-items: flex-start;
    flex-direction: column;
  }

  .guest-access-bar {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-copy,
  .system-visual,
  .hero-actions :deep(.el-button),
  .data-row,
  .column-action {
    animation: none;
    transition: none;
  }
}
</style>
