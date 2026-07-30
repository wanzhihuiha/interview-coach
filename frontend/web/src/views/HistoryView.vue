<template>
  <AppLayout>
    <main class="page-container history-page">
      <header class="overview-band" aria-label="面试记录概览">
        <div class="overview-intro">
          <div class="overview-intro-copy">
            <div class="overview-kicker">
              <span>INTERVIEW ARCHIVE</span>
              <small>{{ latestActivityText }}</small>
            </div>
            <div class="overview-heading">
              <h1 class="page-title">面试历史</h1>
              <p>沉淀每次回答、判断与改进方向，持续看见能力变化。</p>
            </div>
          </div>
          <el-button size="large" :icon="Plus" @click="startNewInterview">
            发起模拟面试
          </el-button>
        </div>
        <dl class="overview-metric">
          <dt>面试档案</dt>
          <dd>{{ history.length }}</dd>
          <small>累计记录</small>
        </dl>
        <dl class="overview-metric">
          <dt>可复盘记录</dt>
          <dd>{{ reviewableCount }}</dd>
          <small>已生成面试结果</small>
        </dl>
        <dl class="overview-metric overview-metric--accent">
          <dt>平均评分</dt>
          <dd>{{ averageScore ?? '—' }}<em v-if="averageScore !== null">分</em></dd>
          <small>{{ scoredCount ? `基于 ${scoredCount} 次有效评分` : '完成面试后生成' }}</small>
        </dl>
      </header>

      <section class="history-workspace" v-loading="loading" aria-label="面试档案工作区">
        <header class="archive-toolbar">
          <div class="archive-heading">
            <div>
              <span>INTERVIEW FILES</span>
              <h2>面试档案</h2>
            </div>
            <p>共 {{ filteredHistory.length }} 条记录</p>
          </div>

          <div class="filter-grid" role="search" aria-label="筛选面试记录">
            <el-input
              v-model="filter.keyword"
              :prefix-icon="Search"
              clearable
              aria-label="搜索岗位或公司"
              placeholder="搜索岗位或公司"
            />
            <el-select v-model="filter.position" clearable aria-label="筛选岗位" placeholder="全部岗位">
              <el-option v-for="position in positionOptions" :key="position" :label="position" :value="position" />
            </el-select>
            <el-select v-model="filter.status" clearable aria-label="筛选状态" placeholder="全部状态">
              <el-option label="已完成" value="completed" />
              <el-option label="进行中" value="ongoing" />
              <el-option label="已中断" value="interrupted" />
            </el-select>
            <el-date-picker
              v-model="filter.dateRange"
              class="date-filter"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              unlink-panels
              aria-label="筛选面试日期"
            />
            <el-button v-if="hasActiveFilters" :icon="RefreshLeft" @click="resetFilters">重置</el-button>
          </div>
        </header>

        <div v-if="filteredHistory.length" class="workspace-body">
          <aside class="session-index" aria-label="面试记录列表">
            <div class="index-heading">
              <span>按面试时间排列</span>
              <small>选择一条记录查看复盘摘要</small>
            </div>

            <div class="session-list" role="listbox" aria-label="面试记录">
              <button
                v-for="session in filteredHistory"
                :key="session.id"
                class="session-item"
                :class="{ 'is-active': activeSession?.id === session.id }"
                type="button"
                role="option"
                :aria-selected="activeSession?.id === session.id"
                :aria-label="`查看 ${session.positionTitle || '未命名岗位'} 面试记录`"
                @click="selectSession(session)"
              >
                <span class="session-item-topline">
                  <span class="record-code">{{ recordCode(session.id) }}</span>
                  <span class="status-label" :class="statusClass(session.status)">
                    <i aria-hidden="true"></i>
                    {{ sessionStatusLabel(session) }}
                  </span>
                </span>

                <span class="session-item-main">
                  <span class="session-copy">
                    <strong>{{ session.positionTitle || '未命名岗位' }}</strong>
                    <small>{{ session.company || '未填写公司' }}</small>
                  </span>
                  <span class="session-score" :class="{ 'is-empty': session.score === undefined }">
                    <strong>{{ session.score ?? '—' }}</strong>
                    <small>{{ session.score === undefined ? '待评分' : session.level || '综合分' }}</small>
                  </span>
                </span>

                <span class="session-item-footer">
                  <time>{{ formatCompactDate(session.startTime) }}</time>
                  <span>
                    查看摘要
                    <el-icon aria-hidden="true"><ArrowRight /></el-icon>
                  </span>
                </span>
              </button>
            </div>
          </aside>

          <article v-if="activeSession" class="session-review" aria-live="polite">
            <header class="review-header">
              <div class="review-heading-copy">
                <div class="review-kicker">
                  <span>{{ recordCode(activeSession.id) }}</span>
                  <span class="status-label" :class="statusClass(activeSession.status)">
                    <i aria-hidden="true"></i>
                    {{ sessionStatusLabel(activeSession) }}
                  </span>
                </div>
                <h2>{{ activeSession.positionTitle || '未命名岗位' }}</h2>
                <p>
                  {{ activeSession.company || '未填写公司' }}
                  <span aria-hidden="true">/</span>
                  {{ formatFullDate(activeSession.startTime) }}
                </p>
              </div>

              <div class="review-primary-action">
                <el-button
                  v-if="isReviewable(activeSession)"
                  type="primary"
                  :icon="DataAnalysis"
                  @click="viewReport(activeSession.id)"
                >
                  查看面试报告
                </el-button>
                <el-button
                  v-else
                  type="primary"
                  :icon="VideoPlay"
                  @click="continueInterview(activeSession.id)"
                >
                  继续面试
                </el-button>
              </div>
            </header>

            <section class="review-hero" aria-label="本次面试摘要">
              <div class="score-focus" :class="{ 'has-no-score': activeSession.score === undefined }">
                <span>OVERALL SCORE</span>
                <div class="score-number">
                  <strong>{{ activeSession.score ?? '—' }}</strong>
                  <small v-if="activeSession.score !== undefined">/ 100</small>
                </div>
                <p>{{ activeSession.level || (activeSession.status === 'ongoing' ? '完成后生成综合评分' : '本次记录暂无有效评分') }}</p>
                <div class="score-scale" aria-hidden="true">
                  <span :style="{ width: `${activeSession.score ?? 0}%` }"></span>
                </div>
              </div>

              <dl class="review-facts">
                <div>
                  <dt>面试状态</dt>
                  <dd>{{ sessionStatusLabel(activeSession) }}</dd>
                  <small>{{ statusDescription(activeSession.status) }}</small>
                </div>
                <div>
                  <dt>当前 / 最后环节</dt>
                  <dd>{{ activeSession.currentPhaseLabel || '暂无环节信息' }}</dd>
                  <small>{{ phaseProgressText(activeSession) }}</small>
                </div>
                <div>
                  <dt>面试时间</dt>
                  <dd>{{ formatDateOnly(activeSession.startTime) }}</dd>
                  <small>{{ formatTimeOnly(activeSession.startTime) }}</small>
                </div>
                <div>
                  <dt>成长方案</dt>
                  <dd>可持续跟进</dd>
                  <small>结合本次表现安排练习</small>
                </div>
              </dl>
            </section>

            <section class="phase-section">
              <header class="section-heading">
                <div>
                  <span>INTERVIEW JOURNEY</span>
                  <h3>面试进程</h3>
                </div>
                <strong>{{ phaseProgress(activeSession) }}%</strong>
              </header>

              <template v-if="activeSession.phases.length">
                <div
                  class="phase-progress"
                  role="progressbar"
                  aria-label="面试环节完成进度"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="phaseProgress(activeSession)"
                >
                  <span :style="{ width: `${phaseProgress(activeSession)}%` }"></span>
                </div>
                <div class="phase-list">
                  <div
                    v-for="phase in activeSession.phases"
                    :key="phase.key"
                    class="phase-item"
                    :class="phaseStateClass(activeSession, phase)"
                  >
                    <i aria-hidden="true"></i>
                    <strong>{{ phase.name }}</strong>
                    <small>{{ phaseStateText(activeSession, phase) }}</small>
                  </div>
                </div>
              </template>
              <p v-else class="phase-empty">这条历史记录暂未返回面试环节信息。</p>
            </section>

            <footer class="review-footer">
              <div class="next-step-copy">
                <span>NEXT STEP</span>
                <strong>{{ nextStepTitle(activeSession) }}</strong>
                <p>{{ nextStepDescription(activeSession) }}</p>
              </div>
              <div class="review-actions">
                <el-button :icon="TrendCharts" @click="viewGrowth(activeSession.id)">查看成长方案</el-button>
              </div>
            </footer>
          </article>
        </div>

        <div v-else-if="!loading" class="history-empty">
          <el-icon aria-hidden="true"><Collection /></el-icon>
          <span>{{ history.length ? 'NO MATCHED RECORDS' : 'NO INTERVIEW RECORDS' }}</span>
          <h2>{{ history.length ? '没有找到符合条件的记录' : '还没有面试档案' }}</h2>
          <p>{{ history.length ? '尝试调整岗位、状态或日期范围。' : '完成第一次模拟面试后，报告和成长路径会沉淀在这里。' }}</p>
          <el-button v-if="history.length" :icon="RefreshLeft" @click="resetFilters">清除筛选</el-button>
          <el-button v-else type="primary" :icon="Plus" @click="startNewInterview">发起模拟面试</el-button>
        </div>
      </section>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Collection,
  DataAnalysis,
  Plus,
  RefreshLeft,
  Search,
  TrendCharts,
  VideoPlay
} from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getInterviewHistory } from '@/api'
import type { InterviewPhase, InterviewSession } from '@/types'

const router = useRouter()
const history = ref<InterviewSession[]>([])
const loading = ref(false)
const selectedSessionId = ref<number | null>(null)
const filter = ref({
  keyword: '',
  position: '',
  status: '',
  dateRange: null as [Date, Date] | null
})

const positionOptions = computed(() => {
  return [...new Set(history.value.map(item => item.positionTitle).filter(Boolean))]
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
})

const filteredHistory = computed(() => {
  const keyword = filter.value.keyword.trim().toLocaleLowerCase('zh-CN')
  return history.value.filter(item => {
    const searchableText = `${item.positionTitle} ${item.company}`.toLocaleLowerCase('zh-CN')
    const matchKeyword = !keyword || searchableText.includes(keyword)
    const matchPosition = !filter.value.position || item.positionTitle === filter.value.position
    const matchStatus = !filter.value.status || item.status === filter.value.status
    const matchDate = isWithinDateRange(item.startTime, filter.value.dateRange)
    return matchKeyword && matchPosition && matchStatus && matchDate
  })
})

const activeSession = computed(() => {
  return filteredHistory.value.find(item => item.id === selectedSessionId.value)
    || filteredHistory.value[0]
    || null
})

const reviewableCount = computed(() => history.value.filter(isReviewable).length)
const scoredSessions = computed(() => history.value.filter(item => typeof item.score === 'number'))
const scoredCount = computed(() => scoredSessions.value.length)
const averageScore = computed(() => {
  if (!scoredSessions.value.length) return null
  const total = scoredSessions.value.reduce((sum, item) => sum + (item.score || 0), 0)
  return Math.round(total / scoredSessions.value.length)
})

const latestActivityText = computed(() => {
  const datedRecords = history.value
    .map(item => parseSessionDate(item.startTime))
    .filter((date): date is Date => date !== null)
    .sort((left, right) => right.getTime() - left.getTime())
  if (!datedRecords.length) return history.value.length ? '面试档案持续更新中' : '从第一次模拟面试开始积累'
  return `最近更新于 ${formatMonthDay(datedRecords[0])}`
})

const hasActiveFilters = computed(() => {
  return Boolean(
    filter.value.keyword
    || filter.value.position
    || filter.value.status
    || filter.value.dateRange
  )
})

onMounted(async () => {
  loading.value = true
  try {
    history.value = await getInterviewHistory()
    selectedSessionId.value = history.value[0]?.id ?? null
  } catch (error) {
    ElMessage.error((error as Error).message || '加载面试历史失败')
  } finally {
    loading.value = false
  }
})

function startNewInterview() {
  router.push('/interview/config')
}

function viewReport(id: number) {
  router.push(`/interview/${id}/report`)
}

function viewGrowth(id: number) {
  router.push(`/interview/${id}/growth`)
}

function continueInterview(id: number) {
  router.push(`/interview/${id}`)
}

function selectSession(session: InterviewSession) {
  selectedSessionId.value = session.id
}

function resetFilters() {
  filter.value = {
    keyword: '',
    position: '',
    status: '',
    dateRange: null
  }
}

function isReviewable(session: InterviewSession) {
  return session.status === 'completed' || session.status === 'interrupted'
}

function recordCode(id: number) {
  return `INT-${String(id).padStart(4, '0')}`
}

function sessionStatusLabel(session: InterviewSession) {
  if (session.statusLabel) return session.statusLabel
  if (session.status === 'completed') return '已完成'
  if (session.status === 'ongoing') return '进行中'
  return '已中断'
}

function statusClass(status: InterviewSession['status']) {
  return `is-${status}`
}

function statusDescription(status: InterviewSession['status']) {
  if (status === 'completed') return '本轮面试已完整结束'
  if (status === 'ongoing') return '可以从当前环节继续'
  return '面试提前结束，记录已保留'
}

function phaseProgress(session: InterviewSession) {
  if (!session.phases.length) return 0
  if (session.status === 'completed') return 100
  const completed = session.phases.filter(phase => phase.completed).length
  return Math.round((completed / session.phases.length) * 100)
}

function phaseProgressText(session: InterviewSession) {
  if (!session.phases.length) return '暂无进程数据'
  if (session.status === 'completed') return `${session.phases.length} 个环节已结束`
  const completed = session.phases.filter(phase => phase.completed).length
  return `已完成 ${completed} / ${session.phases.length} 个环节`
}

function phaseStateClass(session: InterviewSession, phase: InterviewPhase) {
  if (session.status === 'completed' || phase.completed) return 'is-completed'
  if (phase.current) return session.status === 'interrupted' ? 'is-interrupted' : 'is-current'
  return 'is-pending'
}

function phaseStateText(session: InterviewSession, phase: InterviewPhase) {
  if (session.status === 'completed' || phase.completed) return '已完成'
  if (phase.current && session.status === 'interrupted') return '中断于此'
  if (phase.current) return '当前环节'
  return '待开始'
}

function nextStepTitle(session: InterviewSession) {
  if (session.status === 'ongoing') return '从上次环节继续完成这轮面试'
  if (session.status === 'interrupted') return '先定位中断前暴露的问题，再安排练习'
  return '从能力证据出发，安排下一轮针对性提升'
}

function nextStepDescription(session: InterviewSession) {
  if (session.status === 'ongoing') {
    return `当前停留在“${session.currentPhaseLabel || '未记录环节'}”，已完成的回答会继续保留。`
  }
  if (session.status === 'interrupted') {
    return '报告保留本轮已有表现，成长方案可帮助你把问题拆成可执行的练习。'
  }
  return '先查看完整报告中的评分依据，再进入成长方案确定练习优先级。'
}

function parseSessionDate(value: string) {
  if (!value) return null
  const matched = value.match(/(\d{4})[\/-](\d{1,2})[\/-](\d{1,2})(?:\s+(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?/)
  if (matched) {
    const [, year, month, day, hour = '0', minute = '0', second = '0'] = matched
    const parsed = new Date(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second)
    )
    return Number.isNaN(parsed.getTime()) ? null : parsed
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function isWithinDateRange(value: string, range: [Date, Date] | null) {
  if (!range) return true
  const date = parseSessionDate(value)
  if (!date) return false
  const start = new Date(range[0])
  const end = new Date(range[1])
  start.setHours(0, 0, 0, 0)
  end.setHours(23, 59, 59, 999)
  return date >= start && date <= end
}

function formatCompactDate(value: string) {
  const date = parseSessionDate(value)
  if (!date) return value || '时间待记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function formatFullDate(value: string) {
  const date = parseSessionDate(value)
  if (!date) return value || '时间待记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function formatDateOnly(value: string) {
  const date = parseSessionDate(value)
  if (!date) return '待记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

function formatTimeOnly(value: string) {
  const date = parseSessionDate(value)
  if (!date) return '具体时间待记录'
  return new Intl.DateTimeFormat('zh-CN', {
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function formatMonthDay(date: Date) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(date)
}
</script>

<style scoped>
.history-page {
  --archive-sidebar-width: 368px;

  padding-top: 32px;
  padding-bottom: 40px;
}

.overview-band {
  display: grid;
  overflow: hidden;
  min-height: 116px;
  grid-template-columns: minmax(620px, 2.3fr) repeat(3, minmax(170px, 0.7fr));
  border: 1px solid var(--color-border);
  border-top: 2px solid var(--color-ink);
  border-radius: var(--radius-panel);
  margin-bottom: 18px;
  background: var(--color-surface);
}

.overview-intro,
.overview-metric {
  display: flex;
  min-width: 0;
  padding: 18px 24px;
}

.overview-intro {
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: var(--color-surface-subtle);
}

.overview-intro-copy {
  min-width: 0;
}

.overview-kicker {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.overview-kicker > span,
.archive-heading span,
.review-kicker > span:first-child,
.section-heading span,
.next-step-copy > span,
.history-empty > span,
.score-focus > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.overview-kicker small {
  color: var(--color-muted);
  font-size: 11px;
  line-height: 1.2;
}

.overview-heading {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 18px;
  margin-top: 8px;
}

.overview-heading .page-title {
  flex: none;
  font-size: 28px;
}

.overview-heading p {
  min-width: 260px;
  flex: 1;
  margin: 0;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.6;
  white-space: nowrap;
}

.overview-intro .el-button {
  min-width: 156px;
  flex: none;
}

.overview-metric {
  flex-direction: column;
  justify-content: center;
  border-left: 1px solid var(--color-border);
}

.overview-metric dt {
  color: var(--color-muted);
  font-size: 12px;
}

.overview-metric dd {
  margin-top: 4px;
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 30px;
  font-weight: 700;
  line-height: 1.15;
}

.overview-metric dd em {
  margin-left: 4px;
  font-family: var(--font-sans);
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
}

.overview-metric small {
  margin-top: 6px;
  color: var(--color-muted);
  font-size: 12px;
}

.overview-metric--accent dd {
  color: var(--color-brand-600);
}

.history-workspace {
  overflow: hidden;
  min-height: 564px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
}

.archive-toolbar {
  border-bottom: 1px solid var(--color-border);
  padding: 16px 22px;
}

.archive-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 12px;
}

.archive-heading h2 {
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 18px;
  font-weight: 680;
  line-height: 1.35;
}

.archive-heading p {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.filter-grid {
  display: grid;
  grid-template-columns: minmax(220px, 1.35fr) minmax(150px, 0.8fr) 140px minmax(260px, 1.25fr) auto;
  align-items: center;
  gap: 10px;
}

.filter-grid :deep(.date-filter) {
  width: 100%;
}

.workspace-body {
  display: grid;
  min-height: 482px;
  grid-template-columns: var(--archive-sidebar-width) minmax(0, 1fr);
}

.session-index {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.index-heading {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--color-border);
  padding: 12px 18px;
}

.index-heading span {
  color: var(--color-ink);
  font-size: 12px;
  font-weight: 650;
}

.index-heading small {
  color: var(--color-muted);
  font-size: 11px;
}

.session-list {
  max-height: 642px;
  overflow-y: auto;
}

.session-item {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 148px;
  cursor: pointer;
  flex-direction: column;
  gap: 13px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  padding: 17px 18px 15px;
  color: var(--color-body);
  background: transparent;
  text-align: left;
  transition: color var(--motion-base) var(--ease-standard),
    background-color var(--motion-base) var(--ease-standard);
}

.session-item::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  content: '';
  background: transparent;
}

.session-item:hover {
  background: #f0f0ed;
}

.session-item.is-active {
  color: var(--color-ink);
  background: var(--color-surface);
}

.session-item.is-active::before {
  background: var(--color-brand-500);
}

.session-item-topline,
.session-item-main,
.session-item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.record-code {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0;
}

.status-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.status-label i {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--color-muted);
}

.status-label.is-completed i {
  background: var(--color-success);
}

.status-label.is-ongoing i {
  background: var(--color-brand-500);
  box-shadow: 0 0 0 3px var(--color-brand-100);
}

.status-label.is-interrupted i {
  background: var(--color-warning);
}

.session-item-main {
  align-items: flex-start;
}

.session-copy {
  min-width: 0;
}

.session-copy strong,
.session-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-copy strong {
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
  line-height: 1.35;
}

.session-copy small {
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 12px;
}

.session-score {
  display: flex;
  min-width: 54px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: flex-end;
}

.session-score strong {
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 22px;
  line-height: 1;
}

.session-score small {
  margin-top: 5px;
  color: var(--color-muted);
  font-size: 10px;
}

.session-score.is-empty strong {
  color: var(--color-border-strong);
}

.session-item-footer {
  color: var(--color-muted);
  font-size: 11px;
}

.session-item-footer > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 600;
}

.session-item-footer .el-icon {
  transition: transform var(--motion-fast) var(--ease-standard);
}

.session-item:hover .session-item-footer .el-icon {
  transform: translateX(2px);
}

.session-review {
  min-width: 0;
  padding: 22px 28px 24px;
}

.review-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 18px;
}

.review-heading-copy {
  min-width: 0;
}

.review-kicker {
  display: flex;
  align-items: center;
  gap: 14px;
}

.review-kicker > span:first-child {
  color: var(--color-muted);
}

.review-heading-copy h2 {
  margin-top: 9px;
  color: var(--color-ink);
  font-size: 25px;
  font-weight: 720;
  line-height: 1.25;
}

.review-heading-copy p {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 7px;
  color: var(--color-muted);
  font-size: 12px;
}

.review-primary-action {
  flex: 0 0 auto;
}

.review-hero {
  display: grid;
  min-height: 182px;
  grid-template-columns: minmax(230px, 0.78fr) minmax(360px, 1.42fr);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
}

.score-focus {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  padding: 24px 26px;
  color: var(--color-on-console);
  background: var(--color-console);
}

.score-focus > span {
  color: #df7468;
}

.score-number {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.score-number strong {
  font-family: var(--font-mono);
  font-size: 52px;
  font-weight: 700;
  line-height: 0.95;
  letter-spacing: 0;
}

.score-number small {
  padding-bottom: 5px;
  color: #9d9d97;
  font-family: var(--font-mono);
  font-size: 11px;
}

.score-focus p {
  margin-top: 12px;
  color: #d1d0c9;
  font-size: 12px;
}

.score-scale {
  overflow: hidden;
  height: 3px;
  margin-top: 18px;
  background: #343431;
}

.score-scale span {
  display: block;
  height: 100%;
  background: var(--color-brand-500);
  transition: width 300ms var(--ease-standard);
}

.score-focus.has-no-score .score-number strong,
.score-focus.has-no-score p {
  color: #9d9d97;
}

.review-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  background: var(--color-surface-subtle);
}

.review-facts > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  border-left: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
  padding: 20px 22px;
}

.review-facts > div:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.review-facts dt {
  color: var(--color-muted);
  font-size: 11px;
}

.review-facts dd {
  overflow: hidden;
  margin-top: 6px;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-facts small {
  overflow: hidden;
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-section {
  margin-top: 20px;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.section-heading span {
  color: var(--color-muted);
}

.section-heading h3 {
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
}

.section-heading > strong {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 13px;
}

.phase-progress {
  overflow: hidden;
  height: 4px;
  margin-top: 14px;
  background: var(--color-border);
}

.phase-progress span {
  display: block;
  height: 100%;
  background: var(--color-brand-500);
  transition: width 300ms var(--ease-standard);
}

.phase-list {
  display: grid;
  grid-auto-columns: minmax(86px, 1fr);
  grid-auto-flow: column;
  gap: 12px;
  margin-top: 15px;
}

.phase-item {
  min-width: 0;
}

.phase-item i {
  display: block;
  width: 8px;
  height: 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 50%;
  margin-bottom: 8px;
  background: var(--color-surface);
}

.phase-item strong,
.phase-item small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-item strong {
  color: var(--color-body);
  font-size: 12px;
  font-weight: 650;
}

.phase-item small {
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 11px;
}

.phase-item.is-completed i {
  border-color: var(--color-success);
  background: var(--color-success);
}

.phase-item.is-current i {
  border-color: var(--color-brand-500);
  background: var(--color-brand-500);
  box-shadow: 0 0 0 4px var(--color-brand-100);
}

.phase-item.is-current strong {
  color: var(--color-brand-600);
}

.phase-item.is-interrupted i {
  border-color: var(--color-warning);
  background: var(--color-warning);
}

.phase-empty {
  margin-top: 14px;
  color: var(--color-muted);
  font-size: 12px;
}

.review-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid var(--color-border);
  margin-top: 20px;
  padding-top: 18px;
}

.next-step-copy {
  min-width: 0;
  padding-left: 14px;
  border-left: 2px solid var(--color-brand-500);
}

.next-step-copy strong {
  display: block;
  margin-top: 5px;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 680;
}

.next-step-copy p {
  max-width: 560px;
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.55;
}

.review-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.review-actions .el-button + .el-button {
  margin-left: 0;
}

.history-empty {
  display: flex;
  min-height: 480px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  text-align: center;
}

.history-empty > .el-icon {
  width: 54px;
  height: 54px;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  margin-bottom: 18px;
  color: var(--color-muted);
  font-size: 22px;
  background: var(--color-surface-subtle);
}

.history-empty h2 {
  margin-top: 10px;
  color: var(--color-ink);
  font-size: 21px;
}

.history-empty p {
  max-width: 440px;
  margin: 8px 0 20px;
  color: var(--color-muted);
  font-size: 13px;
}

@media (max-width: 1199px) {
  .history-page {
    --archive-sidebar-width: 320px;
  }

  .overview-band {
    grid-template-columns: minmax(440px, 1.8fr) repeat(3, minmax(135px, 0.7fr));
  }

  .overview-heading p {
    white-space: normal;
    text-wrap: balance;
  }

  .overview-intro,
  .overview-metric {
    padding-right: 18px;
    padding-left: 18px;
  }

  .filter-grid {
    grid-template-columns: minmax(190px, 1.1fr) 150px 130px minmax(230px, 1.2fr) auto;
  }

  .session-review {
    padding-right: 22px;
    padding-left: 22px;
  }

  .review-hero {
    grid-template-columns: minmax(200px, 0.72fr) minmax(320px, 1.28fr);
  }

  .review-facts > div {
    padding-right: 16px;
    padding-left: 16px;
  }

  .review-footer {
    align-items: flex-end;
  }

  .review-actions {
    flex-direction: column-reverse;
    align-items: stretch;
  }
}

@media (min-width: 1024px) and (max-height: 1100px) {
  .history-page {
    padding-top: 24px;
    padding-bottom: 24px;
  }

  .overview-band {
    min-height: 104px;
    margin-bottom: 14px;
  }

  .overview-intro,
  .overview-metric {
    padding: 14px 20px;
  }

  .overview-metric small {
    margin-top: 4px;
  }

  .overview-metric dd {
    font-size: 27px;
  }

  .history-workspace {
    min-height: 0;
  }

  .archive-toolbar {
    padding: 12px 18px;
  }

  .archive-heading {
    margin-bottom: 9px;
  }

  .archive-heading h2 {
    margin-top: 2px;
  }

  .workspace-body {
    min-height: 0;
  }

  .index-heading {
    min-height: 48px;
    padding-top: 10px;
    padding-bottom: 10px;
  }

  .session-item {
    min-height: 132px;
    gap: 10px;
    padding-top: 14px;
    padding-bottom: 13px;
  }

  .session-review {
    padding: 18px 26px;
  }

  .review-header {
    padding-bottom: 13px;
  }

  .review-heading-copy h2 {
    margin-top: 6px;
    font-size: 23px;
  }

  .review-heading-copy p {
    margin-top: 4px;
  }

  .review-hero {
    min-height: 160px;
  }

  .score-focus {
    padding: 18px 24px;
  }

  .score-number {
    margin-top: 8px;
  }

  .score-number strong {
    font-size: 46px;
  }

  .score-focus p {
    margin-top: 8px;
  }

  .score-scale {
    margin-top: 12px;
  }

  .review-facts > div {
    padding: 12px 18px;
  }

  .review-facts dd {
    margin-top: 4px;
  }

  .review-facts small {
    margin-top: 2px;
  }

  .phase-section {
    margin-top: 16px;
  }

  .section-heading h3 {
    margin-top: 2px;
  }

  .phase-progress {
    margin-top: 10px;
  }

  .phase-list {
    margin-top: 10px;
  }

  .phase-item i {
    margin-bottom: 5px;
  }

  .phase-item small {
    margin-top: 1px;
  }

  .review-footer {
    margin-top: 16px;
    padding-top: 14px;
  }

  .next-step-copy strong {
    margin-top: 3px;
  }

  .next-step-copy p {
    margin-top: 2px;
  }
}
</style>
