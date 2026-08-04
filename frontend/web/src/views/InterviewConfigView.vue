<template>
  <AppLayout>
    <main id="main-content" class="interview-config-page">
      <div class="setup-studio">
        <aside class="step-rail">
          <div class="rail-heading">
            <p class="rail-kicker">
              <span aria-hidden="true"></span>
              面试设置
            </p>
            <h1>新建<br>模拟面试</h1>
            <p class="rail-description">确认训练素材与面试结构，生成本次专属面试。</p>
          </div>

          <nav class="step-nav" aria-label="面试配置步骤">
            <button
              v-for="step in setupSteps"
              :key="step.id"
              class="step-button"
              :class="{
                'step-button--active': activeStep === step.id,
                'step-button--complete': step.complete
              }"
              type="button"
              :aria-current="activeStep === step.id ? 'step' : undefined"
              @click="goToStep(step.id)"
            >
              <span class="step-index">0{{ step.id }}</span>
              <span class="step-copy">
                <strong>{{ step.label }}</strong>
                <small :title="step.summary">{{ step.summary }}</small>
              </span>
              <el-icon v-if="step.complete" class="step-check"><Check /></el-icon>
            </button>
          </nav>

          <div class="rail-footer">
            <span>面试类型</span>
            <strong>AI 模拟面试</strong>
          </div>
        </aside>

        <section class="editor-panel" aria-labelledby="editor-title">
          <header class="editor-header">
            <div class="editor-meta">
              <span>步骤 {{ activeStep }}</span>
              <span>{{ currentStepMeta.count }}</span>
            </div>
            <div class="editor-title-row">
              <div>
                <h2 id="editor-title">{{ currentStepMeta.title }}</h2>
                <p>{{ currentStepMeta.description }}</p>
              </div>
              <el-button
                v-if="activeStep === 1"
                text
                class="manage-button"
                @click="router.push('/resume')"
              >
                管理简历
                <el-icon><ArrowRight /></el-icon>
              </el-button>
              <el-button
                v-else-if="activeStep === 2"
                text
                class="manage-button"
                @click="router.push('/position')"
              >
                管理岗位
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </header>

          <div v-if="loadError" class="load-error" role="alert">
            <div>
              <strong>配置内容未能同步</strong>
              <span>{{ loadError }}</span>
            </div>
            <el-button :icon="Refresh" text @click="loadConfigOptions">重新加载</el-button>
          </div>

          <div class="editor-body">
            <template v-if="activeStep === 1">
              <div v-if="loading" class="list-skeleton" aria-label="正在加载简历">
                <div v-for="index in 4" :key="index" class="skeleton-row">
                  <span></span>
                  <div><i></i><i></i></div>
                </div>
              </div>
              <div v-else-if="loadError && resumes.length === 0" class="state-panel">
                <el-icon><Document /></el-icon>
                <strong>简历列表暂不可用</strong>
                <p>重新加载后再选择本次面试使用的简历。</p>
                <el-button :icon="Refresh" @click="loadConfigOptions">重新加载</el-button>
              </div>
              <div v-else-if="resumes.length === 0" class="state-panel">
                <el-icon><Document /></el-icon>
                <strong>还没有可用简历</strong>
                <p>上传简历并确认画像后，即可用于模拟面试。</p>
                <el-button type="primary" :icon="Plus" @click="router.push('/resume')">
                  上传简历
                </el-button>
              </div>
              <el-radio-group
                v-else
                v-model="selectedResumeId"
                class="choice-list"
                aria-label="选择面试简历"
              >
                <el-radio
                  v-for="(resume, index) in resumes"
                  :key="resume.resumeId"
                  :value="resume.resumeId"
                  class="choice-row"
                >
                  <span class="choice-code">CV-{{ formatIndex(index) }}</span>
                  <span class="choice-main">
                    <strong :title="resume.fileName">{{ resume.fileName }}</strong>
                    <small>
                      {{ formatResumeMeta(resume) }}
                    </small>
                  </span>
                  <span class="choice-state">{{ resume.statusLabel || '状态未知' }}</span>
                  <el-icon class="choice-check"><Check /></el-icon>
                </el-radio>
              </el-radio-group>
            </template>

            <template v-else-if="activeStep === 2">
              <div v-if="loading" class="list-skeleton" aria-label="正在加载岗位">
                <div v-for="index in 4" :key="index" class="skeleton-row">
                  <span></span>
                  <div><i></i><i></i></div>
                </div>
              </div>
              <div v-else-if="loadError && positions.length === 0" class="state-panel">
                <el-icon><PositionIcon /></el-icon>
                <strong>岗位列表暂不可用</strong>
                <p>重新加载后再选择本次面试的目标岗位。</p>
                <el-button :icon="Refresh" @click="loadConfigOptions">重新加载</el-button>
              </div>
              <div v-else-if="positions.length === 0" class="state-panel">
                <el-icon><PositionIcon /></el-icon>
                <strong>还没有可用岗位</strong>
                <p>确认自己的岗位画像，或从公共岗位库中选择。</p>
                <el-button type="primary" :icon="Plus" @click="router.push('/position')">
                  添加岗位
                </el-button>
              </div>
              <el-radio-group
                v-else
                v-model="selectedPositionId"
                class="choice-list"
                aria-label="选择目标岗位"
              >
                <el-radio
                  v-for="(position, index) in positions"
                  :key="position.positionId"
                  :value="position.positionId"
                  class="choice-row"
                >
                  <span class="choice-code">JD-{{ formatIndex(index) }}</span>
                  <span class="choice-main">
                    <strong :title="position.positionName">{{ position.positionName }}</strong>
                    <small>
                      {{ formatPositionMeta(position) }}
                    </small>
                  </span>
                  <span class="choice-state">
                    {{ position.isPublic ? '公共岗位' : '个人岗位' }}
                  </span>
                  <el-icon class="choice-check"><Check /></el-icon>
                </el-radio>
              </el-radio-group>
            </template>

            <template v-else>
              <el-checkbox-group
                v-model="selectedPhases"
                class="phase-list"
                aria-label="选择面试环节"
              >
                <el-checkbox
                  v-for="(phase, index) in phases"
                  :key="phase.key"
                  :value="phase.key"
                  class="phase-row"
                >
                  <span class="phase-number">0{{ index + 1 }}</span>
                  <span class="phase-marker" aria-hidden="true"></span>
                  <span class="phase-main">
                    <strong>{{ phase.name }}</strong>
                    <small>{{ phase.description }}</small>
                  </span>
                  <span class="phase-count">{{ phase.questionCount }} 题</span>
                  <span class="phase-toggle" aria-hidden="true">
                    <el-icon v-if="selectedPhases.includes(phase.key)"><Check /></el-icon>
                  </span>
                </el-checkbox>
              </el-checkbox-group>
              <div class="phase-row phase-row--ending" aria-label="结束环节由系统自动添加">
                <span class="phase-number">05</span>
                <span class="phase-marker" aria-hidden="true"></span>
                <span class="phase-main">
                  <strong>生成复盘</strong>
                  <small>整理表现、评分与成长建议</small>
                </span>
                <span class="phase-count">自动</span>
                <span class="phase-toggle phase-toggle--locked" aria-hidden="true"><Check /></span>
              </div>
            </template>
          </div>

          <footer class="editor-footer">
            <el-button :icon="ArrowLeft" :disabled="activeStep === 1" @click="moveStep(-1)">
              上一步
            </el-button>
            <span class="step-progress" aria-hidden="true">
              <i
                v-for="step in 3"
                :key="step"
                :class="{ 'is-current': activeStep === step, 'is-passed': activeStep > step }"
              ></i>
            </span>
            <el-button
              v-if="activeStep < 3"
              class="next-button"
              @click="moveStep(1)"
            >
              下一步
              <el-icon><ArrowRight /></el-icon>
            </el-button>
            <span v-else class="footer-spacer" aria-hidden="true"></span>
          </footer>
        </section>

        <aside class="brief-panel" aria-label="本次面试摘要" aria-live="polite">
          <header class="brief-header">
            <span class="brief-code">本次面试</span>
            <span class="brief-status" :class="{ 'brief-status--ready': canStart }">
              <i aria-hidden="true"></i>
              {{ briefStatus }}
            </span>
          </header>

          <section class="brief-intro">
            <span>本次训练</span>
            <h2 :title="selectedPositionLabel">{{ selectedPositionLabel }}</h2>
            <p>{{ selectedPositionMeta }}</p>
          </section>

          <div class="match-flow">
            <div class="match-node">
              <span>简历</span>
              <strong :title="selectedResumeLabel">{{ selectedResumeLabel }}</strong>
              <small>{{ selectedResumeMeta }}</small>
            </div>
            <div class="match-connector" aria-hidden="true">
              <i></i>
              <span>AI 对齐</span>
              <i></i>
            </div>
            <div class="match-node match-node--position">
              <span>岗位</span>
              <strong :title="selectedPositionLabel">{{ selectedPositionLabel }}</strong>
              <small>{{ selectedPositionMeta }}</small>
            </div>
          </div>

          <section class="brief-phases">
            <header>
              <span>面试流程</span>
              <strong>{{ selectedQuestionCount }} 题</strong>
            </header>
            <ol>
              <li
                v-for="phase in phases"
                :key="phase.key"
                :class="{ 'is-selected': selectedPhases.includes(phase.key) }"
              >
                <i aria-hidden="true"></i>
                <span>{{ phase.name }}</span>
                <small>
                  {{ selectedPhases.includes(phase.key) ? `${phase.questionCount} 题` : '已跳过' }}
                </small>
              </li>
              <li class="is-selected is-ending">
                <i aria-hidden="true"></i>
                <span>生成复盘</span>
                <small>自动</small>
              </li>
            </ol>
          </section>

          <footer class="launch-area">
            <div class="readiness">
              <span>配置进度</span>
              <strong>{{ completedConfigCount }} / 3</strong>
            </div>
            <el-button
              class="launch-button"
              size="large"
              :icon="VideoCamera"
              :disabled="!canStart || starting"
              :loading="starting"
              @click="handleStart"
            >
              开始模拟面试
            </el-button>
            <p>{{ startHint }}</p>
          </footer>
        </aside>
      </div>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Document,
  Plus,
  Position as PositionIcon,
  Refresh,
  VideoCamera
} from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getAccessiblePositionList, getResumeList, loadAllPositionPages, startInterview } from '@/api'
import type { InterviewPhase, Position, Resume } from '@/types'

type SetupStep = 1 | 2 | 3

const SHOWCASE_RESUMES: Resume[] = [
  {
    resumeId: 9101,
    fileName: '高级Java后端工程师-8年经验.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    statusLabel: '画像已确认',
    jobCategory: 'JAVA_BACKEND',
    jobCategoryLabel: 'Java 后端',
    experienceLevel: 'SENIOR',
    experienceLevelLabel: '资深',
    hasConfirmedProfile: true
  },
  {
    resumeId: 9102,
    fileName: '分布式系统架构方向-7年经验.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    statusLabel: '画像已确认',
    jobCategory: 'DISTRIBUTED_SYSTEM',
    jobCategoryLabel: '分布式系统',
    experienceLevel: 'SENIOR',
    experienceLevelLabel: '资深',
    hasConfirmedProfile: true
  },
  {
    resumeId: 9103,
    fileName: '平台工程与性能治理-6年经验.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    statusLabel: '画像已确认',
    jobCategory: 'PLATFORM_ENGINEERING',
    jobCategoryLabel: '平台工程',
    experienceLevel: 'SENIOR',
    experienceLevelLabel: '高级',
    hasConfirmedProfile: true
  },
  {
    resumeId: 9104,
    fileName: '云原生基础架构-5年经验.pdf',
    fileType: 'PDF',
    status: 'CONFIRMED',
    statusLabel: '画像已确认',
    jobCategory: 'CLOUD_NATIVE',
    jobCategoryLabel: '云原生架构',
    experienceLevel: 'MIDDLE_SENIOR',
    experienceLevelLabel: '高级',
    hasConfirmedProfile: true
  }
]

const SHOWCASE_POSITIONS: Position[] = [
  {
    positionId: 9201,
    positionName: '高级 Java 后端工程师',
    companyName: '星河科技',
    jobCategory: 'JAVA_BACKEND',
    jobCategoryLabel: 'Java 后端',
    level: 'SENIOR',
    levelLabel: '高级',
    location: '上海 · 浦东',
    salaryRange: '30-45K · 15薪',
    isPublic: false,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: true
  },
  {
    positionId: 9202,
    positionName: '分布式系统架构师',
    companyName: '云岚数科',
    jobCategory: 'DISTRIBUTED_SYSTEM',
    jobCategoryLabel: '分布式系统',
    level: 'EXPERT',
    levelLabel: '专家',
    location: '杭州 · 余杭',
    salaryRange: '40-60K · 16薪',
    isPublic: true,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: false
  },
  {
    positionId: 9203,
    positionName: '平台工程技术专家',
    companyName: '远望网络',
    jobCategory: 'PLATFORM_ENGINEERING',
    jobCategoryLabel: '平台工程',
    level: 'EXPERT',
    levelLabel: '专家',
    location: '深圳 · 南山',
    salaryRange: '45-65K · 15薪',
    isPublic: true,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: false
  },
  {
    positionId: 9204,
    positionName: '云原生研发工程师',
    companyName: '矩阵智能',
    jobCategory: 'CLOUD_NATIVE',
    jobCategoryLabel: '云原生架构',
    level: 'SENIOR',
    levelLabel: '高级',
    location: '北京 · 海淀',
    salaryRange: '35-50K · 14薪',
    isPublic: true,
    archived: false,
    profileUsable: true,
    canConfirm: false,
    canRetry: false
  }
]

const router = useRouter()
const route = useRoute()
const showcaseMode = import.meta.env.DEV && route.query.showcase === '1'
const activeStep = ref<SetupStep>(showcaseMode ? resolveShowcaseStep(route.query.step) : 1)
const resumes = ref<Resume[]>(showcaseMode ? SHOWCASE_RESUMES : [])
const positions = ref<Position[]>(showcaseMode ? SHOWCASE_POSITIONS : [])
const selectedResumeId = ref<number | null>(showcaseMode ? SHOWCASE_RESUMES[0].resumeId : null)
const selectedPositionId = ref<number | null>(showcaseMode ? SHOWCASE_POSITIONS[0].positionId : null)
const selectedPhases = ref<string[]>(['intro', 'professional', 'resume', 'behavior'])
const starting = ref(false)
const loading = ref(!showcaseMode)
const loadError = ref('')

const phases: InterviewPhase[] = [
  { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1, completed: false, current: false },
  { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8, completed: false, current: false },
  { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3, completed: false, current: false },
  { key: 'behavior', name: '行为面试', description: 'STAR 问题，考察软技能', questionCount: 4, completed: false, current: false }
]

const selectedResume = computed(() => {
  return resumes.value.find(item => item.resumeId === selectedResumeId.value)
})

const selectedPosition = computed(() => {
  return positions.value.find(item => item.positionId === selectedPositionId.value)
})

const selectedResumeLabel = computed(() => selectedResume.value?.fileName || '未选择简历')
const selectedPositionLabel = computed(() => selectedPosition.value?.positionName || '未选择岗位')
const selectedResumeMeta = computed(() => {
  return selectedResume.value ? formatResumeMeta(selectedResume.value) : '简历能力画像待选择'
})
const selectedPositionMeta = computed(() => {
  return selectedPosition.value ? formatPositionMeta(selectedPosition.value) : '岗位画像待选择'
})

const selectedQuestionCount = computed(() => {
  return phases
    .filter(phase => selectedPhases.value.includes(phase.key))
    .reduce((total, phase) => total + phase.questionCount, 0)
})

const completedConfigCount = computed(() => {
  return Number(selectedResumeId.value !== null)
    + Number(selectedPositionId.value !== null)
    + Number(selectedPhases.value.length > 0)
})

const canStart = computed(() => {
  return !loading.value
    && selectedResumeId.value !== null
    && selectedPositionId.value !== null
    && selectedPhases.value.length > 0
})

const setupSteps = computed(() => [
  {
    id: 1 as SetupStep,
    label: '选择简历',
    summary: selectedResumeLabel.value,
    complete: selectedResumeId.value !== null
  },
  {
    id: 2 as SetupStep,
    label: '选择岗位',
    summary: selectedPositionLabel.value,
    complete: selectedPositionId.value !== null
  },
  {
    id: 3 as SetupStep,
    label: '面试结构',
    summary: selectedPhases.value.length > 0
      ? `${selectedPhases.value.length} 个环节 / ${selectedQuestionCount.value} 题`
      : '未选择环节',
    complete: selectedPhases.value.length > 0
  }
])

const currentStepMeta = computed(() => {
  const meta: Record<SetupStep, { title: string; description: string; count: string }> = {
    1: {
      title: '选择用于追问的简历',
      description: '面试官将围绕经历、项目与能力证据展开追问。',
      count: `${resumes.value.length} 份可用`
    },
    2: {
      title: '锁定本次目标岗位',
      description: '岗位画像决定专业问题的方向、深度与评价标准。',
      count: `${positions.value.length} 个可用`
    },
    3: {
      title: '编排面试流程',
      description: '环节按固定顺序执行，复盘会在面试结束后自动生成。',
      count: `${selectedPhases.value.length} / ${phases.length} 已启用`
    }
  }
  return meta[activeStep.value]
})

const briefStatus = computed(() => {
  if (loading.value) return '同步中'
  return canStart.value ? '已就绪' : '待完善'
})

const startHint = computed(() => {
  if (starting.value) return '正在创建面试会话...'
  if (loading.value) return '正在同步简历与岗位数据'
  if (selectedResumeId.value === null) return '还需选择一份面试简历'
  if (selectedPositionId.value === null) return '还需选择一个目标岗位'
  if (selectedPhases.value.length === 0) return '至少保留一个面试环节'
  return `预计 ${selectedQuestionCount.value} 题 / ${selectedPhases.value.length} 个面试环节`
})

onMounted(() => {
  if (!showcaseMode) loadConfigOptions()
})

function resolveShowcaseStep(value: unknown): SetupStep {
  const parsed = Number(value)
  return parsed === 2 || parsed === 3 ? parsed : 1
}

function formatIndex(index: number): string {
  return String(index + 1).padStart(2, '0')
}

function formatResumeMeta(resume: Resume): string {
  return [resume.jobCategoryLabel, resume.experienceLevelLabel, resume.fileType || 'FILE']
    .filter(Boolean)
    .join(' / ')
}

function formatPositionMeta(position: Position): string {
  return [
    position.companyName || '目标公司待定',
    position.location,
    position.levelLabel || position.jobCategoryLabel,
    position.salaryRange
  ].filter(Boolean).join(' / ')
}

function goToStep(step: SetupStep) {
  activeStep.value = step
}

function moveStep(offset: -1 | 1) {
  const nextStep = Math.min(3, Math.max(1, activeStep.value + offset)) as SetupStep
  activeStep.value = nextStep
}

async function loadConfigOptions() {
  loading.value = true
  loadError.value = ''

  try {
    const [resumeList, accessiblePositions] = await Promise.all([
      getResumeList(),
      loadAllPositionPages((page, size) => getAccessiblePositionList({ page, size }))
    ])

    const availableResumes = resumeList.filter(resume => resume.hasConfirmedProfile === true)
    const availablePositions = accessiblePositions
      .filter(position => position.profileUsable && !position.archived)

    resumes.value = availableResumes
    positions.value = availablePositions

    if (!availableResumes.some(item => item.resumeId === selectedResumeId.value)) {
      selectedResumeId.value = availableResumes[0]?.resumeId ?? null
    }

    const queryPositionId = Number(route.query.positionId)
    if (queryPositionId && positions.value.some(item => item.positionId === queryPositionId)) {
      selectedPositionId.value = queryPositionId
    } else if (!positions.value.some(item => item.positionId === selectedPositionId.value)) {
      selectedPositionId.value = positions.value[0]?.positionId ?? null
    }
  } catch {
    loadError.value = '暂时无法获取简历和岗位，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function handleStart() {
  if (!canStart.value || starting.value) return
  if (showcaseMode) {
    ElMessage.info('当前为产品展示预览，不会创建真实面试。')
    return
  }
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
.interview-config-page {
  --studio-bg: var(--color-canvas);
  --studio-paper: var(--color-surface);
  --studio-rail: var(--color-surface-subtle);
  --studio-ink: var(--color-ink);
  --studio-body: var(--color-body);
  --studio-muted: var(--color-muted);
  --studio-line: rgba(21, 21, 20, 0.09);
  --studio-accent: var(--color-brand-600);
  --studio-accent-hover: var(--color-brand-500);
  --studio-accent-soft: rgba(201, 59, 48, 0.075);
  --studio-dark: var(--color-console-soft);
  --studio-dark-line: rgba(255, 255, 255, 0.08);
  min-height: calc(100vh - var(--app-header-height));
  overflow-x: clip;
  color: var(--studio-ink);
  background: var(--studio-bg);
}

.setup-studio {
  display: grid;
  width: calc(100% - (var(--app-gutter) * 2));
  max-width: var(--app-content-max);
  min-height: calc(100vh - var(--app-header-height));
  grid-template-columns: clamp(196px, 16vw, 224px) minmax(0, 1fr) clamp(312px, 24vw, 376px);
  margin: 0 auto;
  border-right: 1px solid var(--studio-line);
  border-left: 1px solid var(--studio-line);
  background: var(--studio-paper);
}

.step-rail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border-right: 1px solid var(--studio-line);
  padding: 32px 24px 24px;
  background: var(--studio-rail);
}

.rail-kicker,
.editor-meta,
.brief-code,
.brief-status,
.brief-intro > span,
.match-node > span,
.match-connector,
.brief-phases header > span,
.rail-footer span {
  font-family: var(--font-sans);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0;
}

.choice-code,
.phase-number,
.phase-count,
.brief-phases header strong,
.readiness strong {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.rail-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--studio-muted);
  font-size: 11px;
  font-weight: 600;
}

.rail-kicker > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--studio-accent);
}

.rail-heading h1 {
  margin-top: 38px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.25;
}

.rail-description {
  margin-top: 15px;
  color: var(--studio-muted);
  font-size: 13px;
  line-height: 1.65;
}

.step-nav {
  display: grid;
  gap: 6px;
  margin-top: 42px;
}

.step-button {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 76px;
  grid-template-columns: 30px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  padding: 10px 12px;
  color: var(--studio-muted);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: color 180ms ease, background-color 180ms ease, box-shadow 180ms ease;
}

.step-button::before {
  display: none;
}

.step-button:hover,
.step-button:focus-visible {
  color: var(--studio-ink);
  outline: none;
  background: rgba(29, 29, 31, 0.035);
}

.step-button:focus-visible {
  box-shadow: inset 0 0 0 2px rgba(201, 59, 48, 0.42);
}

.step-button--active {
  color: var(--studio-ink);
  background: var(--studio-accent-soft);
}

.step-button--active:hover,
.step-button--active:focus-visible {
  background: var(--studio-accent-soft);
}

.step-index {
  color: var(--studio-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.step-button--active .step-index {
  color: var(--studio-accent);
}

.step-copy {
  display: block;
  min-width: 0;
}

.step-copy strong,
.step-copy small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.step-copy strong {
  color: inherit;
  font-size: 14px;
  font-weight: 600;
}

.step-copy small {
  margin-top: 5px;
  color: var(--studio-muted);
  font-size: 12px;
}

.step-check {
  color: #2f8a58;
  font-size: 15px;
}

.rail-footer {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-top: 1px solid var(--studio-line);
  margin-top: auto;
  padding-top: 18px;
}

.rail-footer span {
  color: var(--studio-muted);
  font-size: 11px;
}

.rail-footer strong {
  font-size: 13px;
  font-weight: 600;
}

.editor-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  background: var(--studio-paper);
}

.editor-header {
  min-height: 174px;
  border-bottom: 1px solid var(--studio-line);
  padding: 30px 40px 26px;
}

.editor-meta {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  color: var(--studio-muted);
  font-size: 11px;
  font-weight: 600;
}

.editor-meta span:first-child {
  color: var(--studio-accent);
}

.editor-title-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-top: 26px;
}

.editor-title-row > div {
  min-width: 0;
}

.editor-title-row h2 {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.25;
}

.editor-title-row p {
  max-width: 580px;
  margin-top: 9px;
  color: var(--studio-muted);
  font-size: 13px;
  line-height: 1.6;
}

.manage-button.el-button {
  flex: 0 0 auto;
  border-radius: 8px;
  padding: 8px 0;
  color: #424750;
  font-size: 13px;
}

.manage-button.el-button:hover,
.manage-button.el-button:focus-visible {
  color: var(--studio-accent);
  background: transparent;
}

.load-error {
  display: flex;
  min-height: 62px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid rgba(180, 35, 24, 0.22);
  padding: 10px 40px;
  color: #a3261b;
  background: #fff8f7;
}

.load-error > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.load-error strong {
  font-size: 13px;
}

.load-error span {
  overflow: hidden;
  color: #765d59;
  font-size: 11px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.load-error :deep(.el-button) {
  flex: 0 0 auto;
  color: #a3261b;
}

.editor-body {
  min-height: 0;
  flex: 1;
  padding: 22px 40px 28px;
}

.choice-list,
.phase-list {
  display: block;
  overflow: hidden;
  width: 100%;
  border: 1px solid var(--studio-line);
  border-radius: 8px;
  background: var(--studio-paper);
}

.choice-row.el-radio {
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 76px;
  align-items: center;
  border-bottom: 1px solid var(--studio-line);
  margin: 0;
  padding: 0 14px;
  color: var(--studio-ink);
  background: transparent;
  transition: background-color 180ms ease;
}

.choice-row.el-radio::before {
  display: none;
}

.choice-row.el-radio:hover {
  background: #fafafc;
}

.choice-row.el-radio.is-checked {
  background: var(--studio-accent-soft);
}

.choice-row.el-radio:last-child {
  border-bottom: 0;
}

.choice-row.el-radio:focus-within {
  box-shadow: inset 0 0 0 2px rgba(201, 59, 48, 0.42);
}

.choice-row :deep(.el-radio__input) {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
}

.choice-row :deep(.el-radio__label) {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 58px minmax(0, 1fr) auto 22px;
  align-items: center;
  gap: 14px;
  padding: 0;
  color: inherit;
  white-space: normal;
}

.choice-code {
  color: var(--studio-muted);
  font-size: 11px;
  font-weight: 600;
}

.choice-main {
  display: block;
  min-width: 0;
}

.choice-main strong,
.choice-main small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.choice-main strong {
  font-size: 14px;
  font-weight: 600;
}

.choice-main small {
  margin-top: 5px;
  color: var(--studio-muted);
  font-size: 11px;
}

.choice-main small span {
  margin: 0 4px;
  color: #a3a7ad;
}

.choice-state {
  border: 0;
  border-radius: var(--radius-pill);
  padding: 4px 8px;
  color: var(--studio-muted);
  background: rgba(29, 29, 31, 0.045);
  font-size: 11px;
  line-height: 1.2;
  white-space: nowrap;
}

.choice-check {
  color: transparent;
  font-size: 16px;
}

.choice-row.is-checked .choice-check {
  color: var(--studio-accent);
}

.phase-row.el-checkbox,
.phase-row--ending {
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 68px;
  align-items: center;
  border-bottom: 1px solid var(--studio-line);
  margin: 0;
  padding: 0 14px;
  color: #5f646d;
  background: transparent;
  transition: color 180ms ease, background-color 180ms ease;
}

.phase-row.el-checkbox:hover {
  background: #fafafc;
}

.phase-row.el-checkbox.is-checked {
  color: var(--studio-ink);
  background: rgba(201, 59, 48, 0.05);
}

.phase-row.el-checkbox:focus-within {
  box-shadow: inset 0 0 0 2px rgba(201, 59, 48, 0.42);
}

.phase-row :deep(.el-checkbox__input) {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
}

.phase-row :deep(.el-checkbox__label),
.phase-row--ending {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 34px 16px minmax(0, 1fr) 44px 24px;
  align-items: center;
  gap: 12px;
  padding-right: 14px;
  padding-left: 14px;
  color: inherit;
  white-space: normal;
}

.phase-row.el-checkbox {
  padding: 0;
}

.phase-number,
.phase-count {
  color: var(--studio-muted);
  font-size: 11px;
}

.phase-marker {
  position: relative;
  width: 8px;
  height: 8px;
  border: 1px solid #aeb2b9;
  border-radius: 50%;
}

.phase-row.el-checkbox .phase-marker::after {
  position: absolute;
  top: 8px;
  left: 3px;
  width: 1px;
  height: 60px;
  content: '';
  background: var(--studio-line);
}

.phase-row.is-checked .phase-marker,
.phase-row--ending .phase-marker {
  border-color: var(--studio-accent);
  background: var(--studio-accent);
}

.phase-main {
  display: block;
  min-width: 0;
}

.phase-main strong,
.phase-main small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.phase-main strong {
  font-size: 14px;
  font-weight: 600;
}

.phase-main small {
  margin-top: 3px;
  color: var(--studio-muted);
  font-size: 11px;
}

.phase-toggle {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border: 1px solid #bfc3ca;
  border-radius: 50%;
  color: #ffffff;
  font-size: 13px;
}

.phase-row.is-checked .phase-toggle,
.phase-toggle--locked {
  border-color: var(--studio-accent);
  background: var(--studio-accent);
}

.phase-row--ending {
  color: var(--studio-ink);
  border: 1px solid var(--studio-line);
  border-top: 0;
  border-radius: 0 0 8px 8px;
  background: #f5f5f7;
}

.phase-list {
  border-radius: 8px 8px 0 0;
}

.phase-list .phase-row:last-child {
  border-bottom: 0;
}

.phase-row--ending .phase-count {
  color: #2f8a58;
  font-weight: 600;
}

.list-skeleton {
  border-top: 1px solid var(--studio-line);
}

.skeleton-row {
  display: grid;
  min-height: 76px;
  grid-template-columns: 54px minmax(0, 1fr);
  align-items: center;
  gap: 18px;
  border-bottom: 1px solid var(--studio-line);
  padding: 0 16px;
}

.skeleton-row > span,
.skeleton-row i {
  display: block;
  height: 10px;
  background: #e5e6e8;
  animation: skeleton-pulse 1.2s ease-in-out infinite alternate;
}

.skeleton-row > span {
  width: 38px;
}

.skeleton-row div {
  display: grid;
  gap: 9px;
}

.skeleton-row i:first-child {
  width: 46%;
  height: 13px;
}

.skeleton-row i:last-child {
  width: 68%;
}

@keyframes skeleton-pulse {
  to {
    opacity: 0.42;
  }
}

.state-panel {
  display: flex;
  min-height: 286px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  border-top: 1px solid var(--studio-line);
  border-bottom: 1px solid var(--studio-line);
  padding: 32px;
  text-align: center;
}

.state-panel > .el-icon {
  color: #8b9098;
  font-size: 34px;
}

.state-panel strong {
  margin-top: 18px;
  font-size: 16px;
}

.state-panel p {
  margin-top: 7px;
  color: var(--studio-muted);
  font-size: 12px;
}

.state-panel :deep(.el-button) {
  margin-top: 22px;
}

.editor-footer {
  display: grid;
  min-height: 78px;
  grid-template-columns: 120px minmax(0, 1fr) 120px;
  align-items: center;
  border-top: 1px solid var(--studio-line);
  padding: 12px 40px;
}

.editor-footer :deep(.el-button) {
  width: 112px;
  margin: 0;
  border-radius: 8px;
}

.next-button.el-button {
  justify-self: end;
  border-color: #d2d2d7;
  color: var(--studio-ink);
  background: var(--studio-paper);
}

.next-button.el-button:hover,
.next-button.el-button:focus-visible {
  border-color: #b8b8bd;
  color: var(--studio-accent);
  background: #f5f5f7;
}

.step-progress {
  display: flex;
  justify-self: center;
  gap: 6px;
}

.step-progress i {
  width: 22px;
  height: 3px;
  border-radius: var(--radius-pill);
  background: #d0d3d8;
}

.step-progress i.is-current,
.step-progress i.is-passed {
  background: var(--studio-accent);
}

.footer-spacer {
  width: 112px;
  justify-self: end;
}

.brief-panel {
  position: sticky;
  top: var(--app-header-height);
  display: flex;
  min-width: 0;
  min-height: calc(100vh - var(--app-header-height));
  align-self: start;
  flex-direction: column;
  padding: 18px 26px 14px;
  color: #f5f5f7;
  background: var(--studio-dark);
}

.brief-header {
  display: flex;
  min-height: 22px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.brief-code {
  color: #a1a1a6;
  font-size: 10px;
  font-weight: 600;
}

.brief-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #a1a1a6;
  font-size: 10px;
  font-weight: 600;
}

.brief-status i {
  width: 7px;
  height: 7px;
  border: 1px solid #8b8e95;
  border-radius: 50%;
}

.brief-status--ready {
  color: #6ee781;
}

.brief-status--ready i {
  border-color: #32d74b;
  background: #32d74b;
}

.brief-intro {
  margin-top: 18px;
}

.brief-intro > span {
  color: #a1a1a6;
  font-size: 10px;
}

.brief-intro h2 {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 8px;
  color: #f5f5f7;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.25;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.brief-intro p {
  overflow: hidden;
  margin-top: 7px;
  color: #a1a1a6;
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.match-flow {
  margin-top: 16px;
}

.match-node {
  min-height: 42px;
  border-top: 1px solid var(--studio-dark-line);
  border-bottom: 1px solid var(--studio-dark-line);
  padding: 6px 0;
}

.match-node > span {
  display: block;
  color: #86868b;
  font-size: 10px;
  line-height: 1.2;
}

.match-node strong,
.match-node small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.match-node strong {
  margin-top: 3px;
  color: #f5f5f7;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
}

.match-node small {
  margin-top: 2px;
  color: #a1a1a6;
  font-size: 10px;
  line-height: 1.2;
}

.match-node--position {
  border-top: 0;
}

.match-connector {
  display: grid;
  height: 18px;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 8px;
  color: var(--color-brand-500);
  font-size: 10px;
}

.match-connector i {
  height: 1px;
  background: rgba(233, 76, 58, 0.34);
}

.brief-phases {
  margin-top: 18px;
}

.brief-phases header {
  display: flex;
  min-height: 20px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--studio-dark-line);
  padding-bottom: 7px;
}

.brief-phases header > span {
  color: #a1a1a6;
  font-size: 10px;
}

.brief-phases header strong {
  color: var(--color-brand-500);
  font-size: 11px;
}

.brief-phases ol {
  list-style: none;
}

.brief-phases li {
  display: grid;
  min-height: 32px;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--studio-dark-line);
  color: #86868b;
  font-size: 11px;
}

.brief-phases li > i {
  width: 6px;
  height: 6px;
  border: 1px solid #555961;
  border-radius: 50%;
}

.brief-phases li small {
  color: #86868b;
  font-family: var(--font-mono);
  font-size: 10px;
}

.brief-phases li.is-selected {
  color: #f5f5f7;
}

.brief-phases li.is-selected > i {
  border-color: var(--studio-accent);
  background: var(--studio-accent);
}

.brief-phases li.is-selected small {
  color: #a1a1a6;
}

.brief-phases li.is-ending > i {
  border-color: #32d74b;
  background: #32d74b;
}

.launch-area {
  border-top: 1px solid var(--studio-dark-line);
  margin-top: auto;
  padding-top: 12px;
}

.readiness {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #a1a1a6;
  font-size: 10px;
}

.readiness strong {
  color: var(--color-brand-500);
  font-size: 11px;
}

.launch-button.el-button {
  width: 100%;
  min-height: 48px;
  margin-top: 10px;
  border-color: var(--studio-accent);
  border-radius: var(--radius-pill);
  color: #ffffff;
  background: var(--studio-accent);
  font-weight: 600;
}

.launch-button.el-button:hover,
.launch-button.el-button:focus-visible {
  border-color: var(--studio-accent-hover);
  background: var(--studio-accent-hover);
  box-shadow: 0 0 0 3px rgba(233, 76, 58, 0.28);
}

.launch-button.el-button.is-disabled,
.launch-button.el-button.is-disabled:hover {
  border-color: #3a3a3c;
  color: #8e8e93;
  background: #3a3a3c;
}

.launch-area > p {
  overflow: hidden;
  margin-top: 7px;
  color: #86868b;
  font-size: 10px;
  text-align: center;
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (max-width: 1365px) {
  .setup-studio {
    grid-template-columns: 188px minmax(0, 1fr) 304px;
  }

  .step-rail {
    padding-right: 20px;
    padding-left: 20px;
  }

  .editor-header {
    padding-right: 28px;
    padding-left: 28px;
  }

  .editor-body {
    padding-right: 28px;
    padding-left: 28px;
  }

  .editor-footer {
    padding-right: 28px;
    padding-left: 28px;
  }

  .load-error {
    padding-right: 28px;
    padding-left: 28px;
  }

  .brief-panel {
    padding-right: 20px;
    padding-left: 20px;
  }

  .choice-row :deep(.el-radio__label) {
    grid-template-columns: 50px minmax(0, 1fr) auto 20px;
    gap: 10px;
  }
}
</style>
