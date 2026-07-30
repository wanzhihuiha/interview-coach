<template>
  <AppLayout>
    <main class="page-container report-page">
      <header class="page-header report-header">
        <div class="page-heading-copy">
          <span class="page-eyebrow">INTERVIEW PERFORMANCE</span>
          <h1 class="page-title">面试报告</h1>
          <p class="page-subtitle">把本轮判断、能力证据与下一步行动整理成一份可执行的复盘。</p>
        </div>

        <div class="report-header-actions">
          <span v-if="report" class="report-reference">REPORT / {{ formatReportId(report.id) }}</span>
          <el-button
            :icon="Download"
            :disabled="!canDownload"
            @click="downloadMd"
          >
            下载报告 MD
          </el-button>
        </div>
      </header>

      <section v-if="loading" class="report-loading" role="status" aria-live="polite">
        <div class="loading-copy">
          <span class="section-kicker">REPORT ASSEMBLY</span>
          <h2>正在整理面试证据</h2>
          <p>系统正在汇总评分、环节完成情况和能力反馈。</p>
        </div>
        <div class="loading-layout" aria-hidden="true">
          <span class="loading-block loading-block--score"></span>
          <span class="loading-block"></span>
          <span class="loading-block loading-block--action"></span>
        </div>
      </section>

      <template v-else-if="report">
        <section class="report-overview" aria-labelledby="report-verdict-title">
          <div class="score-panel">
            <span class="section-kicker">OVERALL SCORE</span>
            <div class="score-number-line">
              <strong>{{ clampedTotalScore }}</strong>
              <span>/ 100</span>
            </div>
            <div
              class="score-rail"
              role="progressbar"
              aria-label="综合评分"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuenow="clampedTotalScore"
            >
              <span :style="{ width: `${clampedTotalScore}%` }"></span>
            </div>
            <p>综合评分</p>
          </div>

          <div class="verdict-panel">
            <span class="section-kicker">ASSESSMENT</span>
            <div class="verdict-heading">
              <h2 id="report-verdict-title">{{ report.level || '待评定' }}</h2>
              <span class="verdict-chip">{{ overallBand.label }}</span>
            </div>
            <p class="verdict-summary">{{ reportConclusion }}</p>

            <dl class="verdict-metrics" aria-label="报告数据概览">
              <div>
                <dt>已完成环节</dt>
                <dd>{{ completedPhaseCount }} / {{ parsedPhases.length }}</dd>
              </div>
              <div>
                <dt>累计提问</dt>
                <dd>{{ totalQuestionCount }} 题</dd>
              </div>
              <div>
                <dt>能力维度</dt>
                <dd>{{ scoreItems.length }} 项</dd>
              </div>
            </dl>
          </div>

          <aside class="next-action-panel" aria-labelledby="next-action-title">
            <span class="section-kicker section-kicker--inverse">NEXT BEST ACTION</span>
            <h3 id="next-action-title">{{ recommendationTitle }}</h3>
            <p>{{ recommendationBody }}</p>
            <div class="next-action-buttons">
              <el-button type="primary" size="large" :icon="TrendCharts" @click="viewGrowth">
                查看成长方案
              </el-button>
              <el-button size="large" :icon="VideoPlay" @click="router.push('/interview/config')">
                再面一次
              </el-button>
            </div>
          </aside>
        </section>

        <section class="report-analysis-grid" aria-label="面试表现分析">
          <article class="report-panel ability-panel">
            <header class="panel-header">
              <div>
                <span class="section-kicker">ABILITY STRUCTURE</span>
                <h2>能力结构</h2>
                <p>横向比较五项能力，快速识别相对优势与优先补强项。</p>
              </div>
              <span v-if="strongestScore" class="panel-highlight">
                相对优势 · {{ strongestScore.name }}
              </span>
            </header>

            <div v-if="scoreItems.length" class="dimension-list" role="list">
              <div
                v-for="item in scoreItems"
                :key="item.name"
                class="dimension-item"
                :class="dimensionClass(item)"
                role="listitem"
              >
                <div class="dimension-copy">
                  <strong>{{ item.name }}</strong>
                  <span>{{ dimensionLabel(item) }}</span>
                </div>
                <div
                  class="dimension-track"
                  role="progressbar"
                  :aria-label="`${item.name}得分`"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="item.score"
                >
                  <span :style="{ width: `${item.score}%` }"></span>
                </div>
                <strong class="dimension-score">{{ item.score }}</strong>
              </div>
            </div>
            <p v-else class="panel-empty">暂无能力维度数据。</p>
          </article>

          <article class="report-panel phase-panel">
            <header class="panel-header">
              <div>
                <span class="section-kicker">INTERVIEW ROUTE</span>
                <h2>环节完成情况</h2>
                <p>按本轮面试顺序回看题量与完成状态。</p>
              </div>
              <span class="panel-count">{{ completedPhaseCount }} / {{ parsedPhases.length }}</span>
            </header>

            <ol v-if="parsedPhases.length" class="phase-list">
              <li
                v-for="(phase, index) in parsedPhases"
                :key="`${phase.name}-${index}`"
                :class="{ 'is-completed': phase.completed }"
              >
                <span class="phase-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <div class="phase-copy">
                  <strong>{{ phase.name }}</strong>
                  <span>{{ phaseQuestionLabel(phase.questionCount) }}</span>
                </div>
                <span class="phase-status">{{ phase.status }}</span>
              </li>
            </ol>
            <p v-else class="panel-empty">暂无环节完成记录。</p>
          </article>
        </section>

        <section class="evidence-section" aria-labelledby="evidence-title">
          <header class="evidence-heading">
            <div>
              <span class="section-kicker">CAPABILITY EVIDENCE</span>
              <h2 id="evidence-title">能力证据</h2>
            </div>
            <p>优势用于确认可复用的方法，薄弱项用于确定下一轮准备顺序。</p>
          </header>

          <div class="evidence-grid">
            <article class="evidence-panel evidence-panel--strong">
              <header>
                <span>PROVEN STRENGTHS</span>
                <h3>已经表现出的优势</h3>
                <p>{{ strongEvidence.length }} 项可继续放大的能力证据</p>
              </header>
              <ol v-if="strongEvidence.length" class="evidence-list">
                <li v-for="(point, index) in strongEvidence" :key="`${point.title}-${index}`">
                  <span>{{ String(index + 1).padStart(2, '0') }}</span>
                  <div>
                    <strong>{{ point.title }}</strong>
                    <p>{{ point.detail }}</p>
                  </div>
                </li>
              </ol>
              <p v-else class="panel-empty">暂无明确的优势证据。</p>
            </article>

            <article class="evidence-panel evidence-panel--focus">
              <header>
                <span>FOCUS AREAS</span>
                <h3>下一轮优先补强</h3>
                <p>{{ weakEvidence.length }} 项需要转化为具体练习</p>
              </header>
              <ol v-if="weakEvidence.length" class="evidence-list">
                <li v-for="(point, index) in weakEvidence" :key="`${point.title}-${index}`">
                  <span>{{ String(index + 1).padStart(2, '0') }}</span>
                  <div>
                    <strong>{{ point.title }}</strong>
                    <p>{{ point.detail }}</p>
                  </div>
                </li>
              </ol>
              <p v-else class="panel-empty">暂无明确的补强项。</p>
            </article>
          </div>
        </section>
      </template>

      <section v-else-if="error" class="report-state" aria-live="polite">
        <span class="section-kicker">REPORT UNAVAILABLE</span>
        <h2>{{ needsLogin ? '登录后查看面试报告' : '这份报告暂时无法读取' }}</h2>
        <p>{{ needsLogin ? '报告包含个人面试记录与能力反馈，需要完成身份验证。' : error }}</p>
        <el-button
          type="primary"
          @click="router.push(needsLogin ? '/login' : '/history')"
        >
          {{ needsLogin ? '去登录' : '返回面试历史' }}
        </el-button>
      </section>

      <section v-else class="report-state">
        <span class="section-kicker">NO REPORT DATA</span>
        <h2>暂无面试报告</h2>
        <p>完成一次面试后，评分、能力证据和成长建议会展示在这里。</p>
        <el-button type="primary" @click="router.push('/history')">查看面试历史</el-button>
      </section>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, TrendCharts, VideoPlay } from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getInterviewReport } from '@/api'
import { getToken } from '@/utils/storage'
import type { InterviewReport, ScoreItem } from '@/types'

interface ParsedPhase {
  name: string
  questionCount: number | null
  completed: boolean
  status: string
}

interface EvidencePoint {
  title: string
  detail: string
}

const route = useRoute()
const router = useRouter()
const interviewId = Number(route.params.id)

const report = ref<InterviewReport | null>(null)
const loading = ref(false)
const error = ref('')
const isLoggedIn = ref(!!getToken())

const clampedTotalScore = computed(() => clampScore(report.value?.totalScore ?? 0))

const scoreItems = computed<ScoreItem[]>(() => {
  return (report.value?.scores ?? []).map((item) => ({
    ...item,
    score: clampScore(item.score)
  }))
})

const strongestScore = computed<ScoreItem | null>(() => findScoreBoundary('max'))
const weakestScore = computed<ScoreItem | null>(() => findScoreBoundary('min'))

const parsedPhases = computed<ParsedPhase[]>(() => {
  return (report.value?.phaseSummary ?? []).map(parsePhaseSummary)
})

const completedPhaseCount = computed(() => {
  return parsedPhases.value.filter((phase) => phase.completed).length
})

const totalQuestionCount = computed(() => {
  return parsedPhases.value.reduce((total, phase) => total + (phase.questionCount ?? 0), 0)
})

const strongEvidence = computed<EvidencePoint[]>(() => {
  return (report.value?.strongPoints ?? []).map((point) => {
    return parseEvidencePoint(point, '这项能力在本轮问答中表现稳定，可以继续沉淀为可复用方法。')
  })
})

const weakEvidence = computed<EvidencePoint[]>(() => {
  return (report.value?.weakPoints ?? []).map((point) => {
    return parseEvidencePoint(point, '建议在下一轮面试前完成一次针对性学习与表达练习。')
  })
})

const overallBand = computed(() => {
  const score = clampedTotalScore.value
  if (score >= 85) return { label: '优势清晰', description: '能力表现突出，可以继续提高问题难度。' }
  if (score >= 75) return { label: '结构稳定', description: '基础较稳，下一步适合集中补齐短板。' }
  if (score >= 60) return { label: '达到基础线', description: '已经具备基础能力，需要让关键回答更深入。' }
  return { label: '需要补强', description: '建议先补齐核心能力，再进入下一轮面试。' }
})

const reportConclusion = computed(() => {
  return report.value?.conclusion?.trim() || overallBand.value.description
})

const recommendationTitle = computed(() => {
  return weakestScore.value ? `下一轮先补强 ${weakestScore.value.name}` : '继续巩固关键能力'
})

const recommendationBody = computed(() => {
  if (strongestScore.value && weakestScore.value) {
    if (strongestScore.value.name === weakestScore.value.name) {
      return `${strongestScore.value.name} 是当前最关键的能力方向，建议结合成长方案完成一次专项练习。`
    }
    return `本轮 ${strongestScore.value.name} 相对突出，${weakestScore.value.name} 是最值得集中投入的提升方向。`
  }
  return '查看成长方案，把报告中的薄弱项转化为下一轮可验证的准备动作。'
})

const canDownload = computed(() => Boolean(report.value?.mdContent?.trim()))

const needsLogin = computed(() => {
  const message = error.value
  return !isLoggedIn.value
    || message.includes('403')
    || message.includes('未登录')
    || message.includes('认证')
    || message.includes('Unauthorized')
})

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

function clampScore(score: number): number {
  if (!Number.isFinite(score)) return 0
  return Math.min(100, Math.max(0, Math.round(score)))
}

function findScoreBoundary(mode: 'min' | 'max'): ScoreItem | null {
  const [first, ...rest] = scoreItems.value
  if (!first) return null
  return rest.reduce((current, item) => {
    if (mode === 'max') return item.score > current.score ? item : current
    return item.score < current.score ? item : current
  }, first)
}

function parsePhaseSummary(summary: string): ParsedPhase {
  const normalized = summary.trim()
  const match = normalized.match(/^(.*?)\s*\((\d+)题\)\s*(?:·\s*(.*))?$/)
  const completed = normalized.includes('已完成')

  if (!match) {
    return {
      name: normalized || '未知环节',
      questionCount: null,
      completed,
      status: completed ? '已完成' : '待完成'
    }
  }

  return {
    name: match[1].trim() || '未知环节',
    questionCount: Number(match[2]),
    completed,
    status: match[3]?.trim() || (completed ? '已完成' : '待完成')
  }
}

function parseEvidencePoint(point: string, fallbackDetail: string): EvidencePoint {
  const normalized = point.trim()
  const separators = [' - ', ' — ', ' – ', '：']
  const separator = separators.find((item) => normalized.includes(item))

  if (!separator) {
    return {
      title: normalized || '待补充',
      detail: fallbackDetail
    }
  }

  const [title, ...details] = normalized.split(separator)
  return {
    title: title.trim() || '待补充',
    detail: details.join(separator).trim() || fallbackDetail
  }
}

function dimensionLabel(item: ScoreItem): string {
  if (scoreItems.value.length === 1) return '关键能力'
  if (item.name === strongestScore.value?.name) return '相对优势'
  if (item.name === weakestScore.value?.name) return '优先补强'
  return '能力基线'
}

function dimensionClass(item: ScoreItem): Record<string, boolean> {
  return {
    'is-strongest': scoreItems.value.length > 1 && item.name === strongestScore.value?.name,
    'is-weakest': scoreItems.value.length > 1 && item.name === weakestScore.value?.name
  }
}

function phaseQuestionLabel(questionCount: number | null): string {
  return questionCount === null ? '题量待确认' : `${questionCount} 道提问`
}

function formatReportId(id: number): string {
  return String(id).padStart(4, '0')
}

function viewGrowth() {
  router.push(`/interview/${interviewId}/growth`)
}

function downloadMd() {
  if (!report.value?.mdContent) return
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
.report-page {
  padding-bottom: 72px;
}

.report-header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.report-reference,
.section-kicker,
.evidence-panel > header > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  line-height: 1.2;
}

.report-reference {
  color: var(--color-muted);
  white-space: nowrap;
}

.report-overview {
  display: grid;
  grid-template-columns: minmax(220px, 0.72fr) minmax(420px, 1.6fr) minmax(320px, 1fr);
  overflow: hidden;
  border-top: 2px solid var(--color-ink);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.score-panel,
.verdict-panel,
.next-action-panel {
  min-width: 0;
  padding: 30px 32px;
}

.score-panel,
.verdict-panel {
  border-right: 1px solid var(--color-border);
}

.score-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.score-number-line {
  display: flex;
  align-items: flex-end;
  gap: 9px;
  margin: 18px 0 16px;
}

.score-number-line strong {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: clamp(58px, 5vw, 82px);
  font-weight: 700;
  letter-spacing: -0.08em;
  line-height: 0.82;
}

.score-number-line span {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.score-rail {
  height: 5px;
  overflow: hidden;
  background: var(--color-border);
}

.score-rail > span {
  display: block;
  height: 100%;
  background: var(--color-brand-500);
  transition: width var(--motion-base) var(--ease-standard);
}

.score-panel > p {
  margin-top: 10px;
  color: var(--color-muted);
  font-size: 13px;
}

.verdict-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.verdict-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 14px 0 10px;
}

.verdict-heading h2 {
  margin: 0;
  color: var(--color-ink);
  font-size: clamp(34px, 3vw, 48px);
  font-weight: 720;
  line-height: 1;
}

.verdict-chip {
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  padding: 4px 10px;
  color: var(--color-body);
  background: var(--color-surface-subtle);
  font-size: 12px;
  white-space: nowrap;
}

.verdict-summary {
  max-width: 700px;
  color: var(--color-body);
  font-size: 14px;
  line-height: 1.7;
}

.verdict-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  margin-top: 24px;
  border-top: 1px solid var(--color-border);
  padding-top: 18px;
}

.verdict-metrics > div + div {
  border-left: 1px solid var(--color-border);
  padding-left: 20px;
}

.verdict-metrics dt {
  color: var(--color-muted);
  font-size: 11px;
}

.verdict-metrics dd {
  margin-top: 4px;
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 16px;
  font-weight: 700;
}

.next-action-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: var(--color-on-console);
  background: var(--color-console);
}

.section-kicker--inverse {
  color: #ff7668;
}

.next-action-panel h3 {
  margin: 16px 0 10px;
  color: var(--color-on-console);
  font-size: 22px;
  line-height: 1.35;
}

.next-action-panel > p {
  color: rgba(250, 249, 245, 0.68);
  font-size: 13px;
  line-height: 1.75;
}

.next-action-buttons {
  display: grid;
  gap: 10px;
  margin-top: 24px;
}

.next-action-buttons .el-button {
  width: 100%;
  margin-left: 0;
}

.next-action-buttons .el-button:not(.el-button--primary) {
  --el-button-bg-color: transparent;
  --el-button-border-color: rgba(250, 249, 245, 0.28);
  --el-button-text-color: var(--color-on-console);
  --el-button-hover-bg-color: rgba(250, 249, 245, 0.08);
  --el-button-hover-border-color: rgba(250, 249, 245, 0.5);
  --el-button-hover-text-color: var(--color-on-console);
  --el-button-active-bg-color: rgba(250, 249, 245, 0.12);
  --el-button-active-border-color: rgba(250, 249, 245, 0.6);
  --el-button-active-text-color: var(--color-on-console);
}

.report-analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.48fr) minmax(360px, 0.82fr);
  gap: 20px;
  margin-top: 20px;
}

.report-panel,
.evidence-section {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--color-border);
  padding: 22px 26px 20px;
}

.panel-header h2,
.evidence-heading h2 {
  margin: 7px 0 0;
  color: var(--color-ink);
  font-size: 20px;
  line-height: 1.3;
}

.panel-header p {
  margin-top: 7px;
  color: var(--color-muted);
  font-size: 12px;
}

.panel-highlight,
.panel-count {
  flex: 0 0 auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  padding: 5px 10px;
  color: var(--color-body);
  background: var(--color-surface-subtle);
  font-family: var(--font-mono);
  font-size: 10px;
  white-space: nowrap;
}

.dimension-list {
  padding: 10px 26px 16px;
}

.dimension-item {
  display: grid;
  grid-template-columns: 144px minmax(160px, 1fr) 36px;
  align-items: center;
  gap: 18px;
  min-height: 52px;
  border-bottom: 1px solid var(--color-border);
}

.dimension-item:last-child {
  border-bottom: 0;
}

.dimension-copy {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.dimension-copy strong {
  color: var(--color-ink);
  font-size: 13px;
}

.dimension-copy span {
  color: var(--color-muted);
  font-size: 10px;
  white-space: nowrap;
}

.dimension-track {
  height: 8px;
  overflow: hidden;
  background: #e9e9e5;
}

.dimension-track > span {
  display: block;
  height: 100%;
  background: var(--color-ink);
  transition: width var(--motion-base) var(--ease-standard);
}

.dimension-item.is-strongest .dimension-track > span {
  background: var(--color-success);
}

.dimension-item.is-weakest .dimension-track > span {
  background: var(--color-brand-500);
}

.dimension-item.is-strongest .dimension-copy span {
  color: var(--color-success);
}

.dimension-item.is-weakest .dimension-copy span {
  color: var(--color-brand-600);
}

.dimension-score {
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 13px;
  text-align: right;
}

.phase-list {
  list-style: none;
  padding: 10px 26px 16px;
}

.phase-list li {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  border-bottom: 1px solid var(--color-border);
}

.phase-list li:last-child {
  border-bottom: 0;
}

.phase-index {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.phase-list li.is-completed .phase-index {
  color: var(--color-success);
}

.phase-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.phase-copy strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-copy span,
.phase-status {
  color: var(--color-muted);
  font-size: 10px;
}

.phase-status {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  padding: 3px 8px;
  white-space: nowrap;
}

.phase-list li.is-completed .phase-status {
  color: var(--color-success);
  border-color: rgba(30, 122, 70, 0.24);
  background: rgba(30, 122, 70, 0.06);
}

.panel-empty {
  padding: 28px;
  color: var(--color-muted);
  font-size: 13px;
}

.evidence-section {
  margin-top: 20px;
  border-top: 2px solid var(--color-ink);
}

.evidence-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  border-bottom: 1px solid var(--color-border);
  padding: 24px 26px;
}

.evidence-heading > p {
  max-width: 520px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.7;
  text-align: right;
}

.evidence-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.evidence-panel {
  min-width: 0;
  padding: 26px;
}

.evidence-panel + .evidence-panel {
  border-left: 1px solid var(--color-border);
}

.evidence-panel--focus {
  background: var(--color-surface-subtle);
}

.evidence-panel--focus > header > span {
  color: var(--color-brand-600);
}

.evidence-panel > header h3 {
  margin: 8px 0 5px;
  color: var(--color-ink);
  font-size: 18px;
}

.evidence-panel > header p {
  color: var(--color-muted);
  font-size: 12px;
}

.evidence-list {
  display: grid;
  gap: 10px;
  margin-top: 20px;
  list-style: none;
}

.evidence-list li {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  border-top: 1px solid var(--color-border);
  padding-top: 14px;
}

.evidence-list li > span {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.evidence-list strong {
  display: block;
  color: var(--color-ink);
  font-size: 13px;
}

.evidence-list p {
  margin-top: 4px;
  color: var(--color-body);
  font-size: 12px;
  line-height: 1.65;
}

.report-loading,
.report-state {
  min-height: 540px;
  border-top: 2px solid var(--color-ink);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.report-loading {
  padding: 42px;
}

.loading-copy h2,
.report-state h2 {
  margin-top: 12px;
  color: var(--color-ink);
  font-size: 28px;
}

.loading-copy p,
.report-state p {
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 14px;
}

.loading-layout {
  display: grid;
  grid-template-columns: 0.7fr 1.5fr 1fr;
  gap: 16px;
  margin-top: 36px;
}

.loading-block {
  min-height: 280px;
  background: linear-gradient(90deg, #f0f0ed 20%, #f7f7f5 50%, #f0f0ed 80%);
  background-size: 200% 100%;
  animation: loading-shift 1.4s ease-in-out infinite;
}

.loading-block--action {
  background: #e6e6e2;
}

.report-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  padding: 64px;
}

.report-state p {
  max-width: 560px;
  margin-bottom: 24px;
}

@keyframes loading-shift {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}

@media (max-width: 1240px) {
  .report-overview {
    grid-template-columns: minmax(220px, 0.7fr) minmax(0, 1.3fr);
  }

  .verdict-panel {
    border-right: 0;
  }

  .next-action-panel {
    grid-column: 1 / -1;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(280px, 0.7fr);
    column-gap: 32px;
  }

  .next-action-panel .section-kicker,
  .next-action-panel h3,
  .next-action-panel > p {
    grid-column: 1;
  }

  .next-action-buttons {
    grid-column: 2;
    grid-row: 1 / span 3;
    align-self: center;
    margin-top: 0;
  }

  .report-analysis-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .report-header,
  .evidence-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .report-header-actions {
    width: 100%;
    justify-content: space-between;
  }

  .report-overview,
  .evidence-grid,
  .loading-layout {
    grid-template-columns: 1fr;
  }

  .score-panel,
  .verdict-panel {
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }

  .next-action-panel {
    display: flex;
  }

  .next-action-buttons {
    width: 100%;
    margin-top: 24px;
  }

  .evidence-heading > p {
    text-align: left;
  }

  .evidence-panel + .evidence-panel {
    border-top: 1px solid var(--color-border);
    border-left: 0;
  }

  .dimension-item {
    grid-template-columns: 120px minmax(100px, 1fr) 32px;
    gap: 10px;
  }

  .dimension-copy span {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .loading-block {
    animation: none;
  }
}
</style>
