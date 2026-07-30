<template>
  <AppLayout>
    <div class="page-container resume-page">
      <header class="section-header">
        <div class="page-heading-copy">
          <span class="page-eyebrow">RESUME INTELLIGENCE</span>
          <h1 class="page-title">我的简历</h1>
          <p class="page-subtitle">维护候选人经历与能力证据，为岗位匹配和模拟面试提供统一输入。</p>
        </div>
        <el-upload
          accept=".pdf,.doc,.docx,.txt"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="handleFileChange"
        >
          <el-button type="primary" :icon="Upload">上传简历</el-button>
        </el-upload>
      </header>

      <section class="resume-workspace" v-loading="loading" aria-label="简历档案工作区">
        <aside class="resume-library" aria-label="简历档案列表">
          <header class="library-header">
            <div>
              <span>RESUME FILES</span>
              <strong>简历档案</strong>
            </div>
            <small>{{ resumes.length }} 份</small>
          </header>

          <div v-if="resumes.length" class="resume-list">
            <button
              v-for="(resume, index) in resumes"
              :key="resume.resumeId"
              class="resume-list-item"
              :class="{
                'is-active': selectedResume?.resumeId === resume.resumeId,
                'is-processing': isParseActive(resume.status)
              }"
              type="button"
              :aria-label="`选择简历 ${resume.fileName}`"
              :aria-pressed="selectedResume?.resumeId === resume.resumeId"
              @click="selectResume(resume)"
            >
              <span class="resume-code">CV-{{ formatIndex(index) }}</span>
              <span class="resume-item-copy">
                <strong :title="resume.fileName">{{ resume.fileName }}</strong>
                <small>{{ resume.jobCategoryLabel || '岗位类型待识别' }}</small>
                <time>{{ formatDate(resume.createdAt) }}</time>
              </span>
              <span class="resume-item-state" :class="resumeStateClass(resume.status)">
                <i aria-hidden="true"></i>
                {{ resume.statusLabel || '未知状态' }}
              </span>
            </button>
          </div>

          <div v-else class="library-empty">
            <el-icon><Document /></el-icon>
            <strong>暂无简历档案</strong>
            <p>上传后，解析状态会在这里持续更新。</p>
          </div>
        </aside>

        <section class="resume-detail" aria-live="polite">
          <template v-if="selectedResume">
            <header class="detail-header">
              <div class="detail-heading-copy">
                <div class="detail-kicker">
                  <span>{{ resumeCode(selectedResume) }}</span>
                  <span class="detail-status" :class="resumeStateClass(selectedResume.status)">
                    <i aria-hidden="true"></i>
                    {{ selectedResume.statusLabel || '未知状态' }}
                  </span>
                </div>
                <h2 :title="selectedResume.fileName">{{ selectedResume.fileName }}</h2>
                <p>
                  {{ selectedResume.jobCategoryLabel || '岗位类型待识别' }}
                  <span aria-hidden="true">/</span>
                  上传于 {{ formatDate(selectedResume.createdAt) }}
                </p>
              </div>

              <div class="detail-actions">
                <el-button
                  v-if="selectedResume.status === 'PENDING_CONFIRM'"
                  type="primary"
                  :icon="CircleCheck"
                  @click="confirmResume(selectedResume)"
                >
                  确认画像
                </el-button>
                <el-button
                  :icon="RefreshRight"
                  :disabled="isParseActive(selectedResume.status)"
                  @click="reparseResume(selectedResume.resumeId)"
                >
                  重新解析
                </el-button>
                <el-dropdown trigger="click" @command="handleResumeCommand">
                  <el-button
                    class="more-action"
                    :icon="MoreFilled"
                    circle
                    title="更多操作"
                    aria-label="更多简历操作"
                  />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="delete" class="danger-command">删除简历</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </header>

            <div class="profile-facts">
              <div>
                <span>当前职位</span>
                <strong>{{ selectedResume.parsedData?.basicInfo?.currentPosition || '待识别' }}</strong>
              </div>
              <div>
                <span>工作年限</span>
                <strong>{{ selectedResume.parsedData?.basicInfo?.workingYears || '待识别' }}</strong>
              </div>
              <div class="fact-contact">
                <span>联系方式</span>
                <strong>{{ accountContact }}</strong>
              </div>
              <div class="fact-education">
                <span>学历</span>
                <strong class="education-value">
                  {{ splitEducation(selectedResume.parsedData?.basicInfo?.education).school }}
                  <small>{{ splitEducation(selectedResume.parsedData?.basicInfo?.education).detail }}</small>
                </strong>
              </div>
              <div>
                <span>经验等级</span>
                <strong>{{ selectedResume.experienceLevelLabel || '待评估' }}</strong>
              </div>
            </div>

            <div v-if="detailLoading" class="detail-state detail-loading" v-loading="detailLoading" />

            <div v-else-if="isParseActive(selectedResume.status)" class="detail-state parse-progress-panel">
              <el-icon class="state-icon is-rotating"><Loading /></el-icon>
              <span class="state-kicker">PROFILE ANALYSIS</span>
              <h3>{{ parseStatusText(selectedResume.status) }}</h3>
              <p>页面会持续同步解析进度，完成后自动展示能力画像。</p>
              <el-progress
                :percentage="progressFor(selectedResume)"
                :indeterminate="true"
                :duration="2"
                :stroke-width="6"
              />
            </div>

            <div v-else-if="selectedResume.status === 'PARSE_FAILED'" class="detail-state failure-state">
              <el-icon class="state-icon"><WarningFilled /></el-icon>
              <span class="state-kicker">ANALYSIS INTERRUPTED</span>
              <h3>这份简历暂未完成解析</h3>
              <p>可以重新提交解析任务，原始简历文件不会因此改变。</p>
              <el-button type="primary" :icon="RefreshRight" @click="reparseResume(selectedResume.resumeId)">
                重新解析
              </el-button>
            </div>

            <el-tabs v-else-if="selectedResume.parsedData" v-model="activeDetailTab" class="profile-tabs">
              <el-tab-pane label="画像概览" name="overview">
                <div class="overview-grid">
                  <div class="overview-main">
                    <section class="profile-section">
                      <header class="profile-section-heading">
                        <div>
                          <span>CAPABILITY MAP</span>
                          <h3>技能与能力证据</h3>
                        </div>
                        <small>{{ selectedResume.parsedData.skillTags?.length || 0 }} 项</small>
                      </header>
                      <div v-if="selectedResume.parsedData.skillTags?.length" class="skill-tags">
                        <el-tag
                          v-for="skill in selectedResume.parsedData.skillTags"
                          :key="skill"
                          class="skill-tag"
                          :class="{ 'is-gold': isGoldSkill(skill) }"
                          effect="plain"
                        >
                          {{ skill }}
                        </el-tag>
                      </div>
                      <p v-else class="empty-copy">暂未识别到明确技能标签。</p>
                    </section>

                    <section class="profile-section">
                      <header class="profile-section-heading">
                        <div>
                          <span>SKILL LEVEL</span>
                          <h3>技能成熟度</h3>
                        </div>
                      </header>
                      <div v-if="selectedSkillLevels.length" class="skill-level-grid">
                        <div
                          v-for="item in selectedSkillLevels"
                          :key="item.skill"
                          class="skill-level-item"
                        >
                          <span>{{ item.skill }}</span>
                          <span class="skill-level-result">
                            <strong>{{ item.level }}</strong>
                            <small v-if="item.inferred">AI 推断 · 待验证</small>
                          </span>
                        </div>
                      </div>
                      <p v-else class="empty-copy">暂无可用的技能等级判断。</p>
                    </section>
                  </div>

                  <aside class="overview-aside">
                    <section class="evidence-section">
                      <header class="profile-section-heading">
                        <div>
                          <span>STRENGTHS</span>
                          <h3>优势证据</h3>
                        </div>
                      </header>
                      <ul v-if="selectedResume.analysis?.strengths?.length" class="evidence-list strengths-list">
                        <li v-for="(item, index) in selectedResume.analysis.strengths" :key="index">
                          {{ item.content }}
                        </li>
                      </ul>
                      <p v-else class="empty-copy">{{ analysisEmptyCopy(selectedResume, 'strengths') }}</p>
                    </section>

                    <section class="evidence-section">
                      <header class="profile-section-heading">
                        <div>
                          <span>VERIFICATION</span>
                          <h3>待验证项</h3>
                        </div>
                      </header>
                      <ul
                        v-if="selectedResume.analysis?.verificationPoints?.length"
                        class="evidence-list gaps-list"
                      >
                        <li v-for="(item, index) in selectedResume.analysis.verificationPoints" :key="index">
                          {{ item.content }}
                        </li>
                      </ul>
                      <p v-else class="empty-copy">{{ analysisEmptyCopy(selectedResume, 'verification') }}</p>
                    </section>
                  </aside>
                </div>
              </el-tab-pane>

              <el-tab-pane label="工作经历" name="experience">
                <section class="records-panel">
                  <header class="records-heading">
                    <div>
                      <span>WORK EXPERIENCE</span>
                      <h3>工作经历</h3>
                    </div>
                    <small>{{ selectedResume.parsedData.workExperience?.length || 0 }} 段</small>
                  </header>
                  <ol v-if="selectedResume.parsedData.workExperience?.length" class="experience-timeline">
                    <li v-for="(work, index) in selectedResume.parsedData.workExperience" :key="index">
                      <span class="timeline-marker" aria-hidden="true"></span>
                      <div class="record-heading">
                        <div>
                          <h4>{{ work.company || '公司信息待识别' }}</h4>
                          <p>{{ work.position || '职位信息待识别' }}</p>
                        </div>
                        <time>{{ work.duration || '时间待识别' }}</time>
                      </div>
                      <ul v-if="work.highlights?.length" class="record-list">
                        <li v-for="(highlight, hIndex) in work.highlights" :key="hIndex">{{ highlight }}</li>
                      </ul>
                      <p v-else class="empty-copy">暂无工作亮点说明。</p>
                    </li>
                  </ol>
                  <el-empty v-else description="暂无工作经历信息" />
                </section>
              </el-tab-pane>

              <el-tab-pane label="项目经历" name="projects">
                <section class="records-panel">
                  <header class="records-heading">
                    <div>
                      <span>PROJECT EXPERIENCE</span>
                      <h3>项目经历</h3>
                    </div>
                    <small>{{ selectedResume.parsedData.projectExperience?.length || 0 }} 个</small>
                  </header>
                  <div v-if="selectedResume.parsedData.projectExperience?.length" class="project-records">
                    <article
                      v-for="(project, index) in selectedResume.parsedData.projectExperience"
                      :key="index"
                      class="project-record"
                    >
                      <header class="record-heading">
                        <div>
                          <h4>{{ project.name || '项目名称待识别' }}</h4>
                          <p>{{ project.role || '项目角色待识别' }}</p>
                        </div>
                        <span class="record-index">PROJECT {{ formatIndex(index) }}</span>
                      </header>
                      <p class="project-description">{{ project.description || '暂无项目说明。' }}</p>
                      <div v-if="project.techStack?.length" class="skill-tags project-tech-stack">
                        <el-tag
                          v-for="tech in project.techStack"
                          :key="tech"
                          class="skill-tag"
                          :class="{ 'is-gold': isGoldSkill(tech) }"
                          effect="plain"
                          size="small"
                        >
                          {{ tech }}
                        </el-tag>
                      </div>
                    </article>
                  </div>
                  <el-empty v-else description="暂无项目经历信息" />
                </section>
              </el-tab-pane>
            </el-tabs>

            <div v-else class="detail-state">
              <el-icon class="state-icon"><Document /></el-icon>
              <span class="state-kicker">PROFILE UNAVAILABLE</span>
              <h3>暂时没有可展示的简历画像</h3>
              <p>{{ emptyDescription(selectedResume.status) }}</p>
            </div>
          </template>

          <div v-else class="detail-empty">
            <el-icon><Document /></el-icon>
            <span>RESUME INTELLIGENCE</span>
            <h2>建立第一份候选人档案</h2>
            <p>上传简历后，职衡会整理基本信息、能力证据与经历结构。</p>
          </div>
        </section>
      </section>

      <ResumeActionDialog
        v-model="actionDialogVisible"
        :action="pendingResumeAction?.type || 'reparse'"
        :file-name="pendingResumeAction?.fileName || ''"
        :loading="actionDialogLoading"
        @confirm="executeResumeAction"
        @closed="resetResumeActionDialog"
      />
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleCheck,
  Document,
  Loading,
  MoreFilled,
  RefreshRight,
  Upload,
  WarningFilled
} from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import ResumeActionDialog from '@/components/ResumeActionDialog.vue'
import { getResumeList, uploadResume, getResumeDetail, getResumeProfile, getResumeParseStatus, confirmResume as apiConfirmResume, deleteResume, reparseResume as apiReparseResume } from '@/api'
import { useUserStore } from '@/stores/user'
import type { Resume, ResumeParseStatus } from '@/types'
import type { UploadFile } from 'element-plus'

type ResumeActionType = 'confirm' | 'reparse' | 'delete'

interface PendingResumeAction {
  type: ResumeActionType
  resumeId: number
  fileName: string
}

interface SkillLevelEntry {
  skill: string
  level: string
  inferred: boolean
}

const userStore = useUserStore()
const resumes = ref<Resume[]>([])
const selectedResume = ref<Resume | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const activeDetailTab = ref('overview')
const actionDialogVisible = ref(false)
const actionDialogLoading = ref(false)
const pendingResumeAction = ref<PendingResumeAction | null>(null)

const POLL_INTERVAL_MS = 5000
const MAX_POLL_ATTEMPTS = 36
const GOLD_SKILL_PATTERNS = [
  /\b(ai|llm|rag|agent|juc|rpc|k8s|kubernetes|cluster|kafka|rocketmq|rabbitmq|dubbo|nacos|mycat)\b/i,
  /人工智能|大模型|机器学习|深度学习/,
  /高并发|并发|线程|异步|协程|限流|熔断|降级/,
  /分布式|微服务|集群|云原生|容器编排|服务网格|服务治理|注册中心|配置中心|服务发现/,
  /消息队列|主从复制|读写分离|幂等|spring\s*cloud/i,
  /性能|故障排查|稳定性|压测|调优/
]
const pollingResumeIds = new Set<number>()
const pollTimers = new Map<number, number>()
const pollAttempts = new Map<number, number>()
let detailRequestSequence = 0

const accountContact = computed(() => userStore.userInfo?.phone || '待补充')
const selectedSkillLevels = computed<SkillLevelEntry[]>(() => {
  const resume = selectedResume.value
  if (!resume) return []

  const entries = Object.entries(resume.parsedData?.skillLevel || {}).map(([skill, level]) => ({
    skill,
    level,
    inferred: false
  }))
  const explicitSkills = new Set(entries.map(item => item.skill.toLocaleLowerCase()))

  for (const assessment of resume.analysis?.skillAssessments || []) {
    if (!assessment.skill || !assessment.inferredLevel) continue
    if (explicitSkills.has(assessment.skill.toLocaleLowerCase())) continue
    entries.push({
      skill: assessment.skill,
      level: assessment.inferredLevel,
      inferred: true
    })
  }
  return entries
})

onMounted(loadResumes)
onBeforeUnmount(stopAllPolling)

async function loadResumes() {
  loading.value = true
  try {
    resumes.value = (await getResumeList()).map(resume => ({
      ...resume,
      parseProgress: progressFor(resume)
    }))
    resumes.value
      .filter(resume => isParseActive(resume.status) || isAnalysisActive(resume.analysisStatus))
      .forEach(resume => startPolling(resume.resumeId))
    if (resumes.value.length > 0) {
      const current = resumes.value.find(resume => resume.resumeId === selectedResume.value?.resumeId)
      await selectResume(current || resumes.value[0])
    } else {
      selectedResume.value = null
    }
  } finally {
    loading.value = false
  }
}

async function loadDetail(resume: Resume) {
  try {
    const detail = await getResumeDetail(resume.resumeId)
    if (!canLoadProfile(detail.status)) {
      const merged = { ...resume, ...detail, parsedData: undefined, parseProgress: progressFor(detail) }
      updateResumeState(merged)
      return merged
    }
    const profile = await getResumeProfile(resume.resumeId)
    const merged = {
      ...detail,
      parsedData: profile.profile,
      analysis: profile.analysis,
      hasConfirmedProfile: profile.hasConfirmedProfile ?? detail.hasConfirmedProfile,
      parseGeneration: profile.parseGeneration ?? detail.parseGeneration ?? resume.parseGeneration,
      analysisStatus: profile.analysisStatus ?? detail.analysisStatus ?? resume.analysisStatus,
      analysisErrorMessage: profile.analysisErrorMessage
        ?? detail.analysisErrorMessage
        ?? resume.analysisErrorMessage,
      experienceLevel: profile.experienceLevel,
      experienceLevelLabel: profile.experienceLevelLabel,
      statusLabel: profile.statusLabel || detail.statusLabel,
      parseProgress: 100
    }
    updateResumeState(merged)
    return merged
  } catch (error) {
    ElMessage.error((error as Error).message || '获取简历详情失败')
    return resume
  }
}

async function selectResume(resume: Resume) {
  const requestSequence = ++detailRequestSequence
  selectedResume.value = resume
  activeDetailTab.value = 'overview'

  if (isParseActive(resume.status) || resume.status === 'PARSE_FAILED') {
    detailLoading.value = false
    return
  }

  detailLoading.value = true
  try {
    const loaded = await loadDetail(resume)
    if (isAnalysisActive(loaded.analysisStatus)) {
      startPolling(loaded.resumeId)
    }
  } finally {
    if (requestSequence === detailRequestSequence) {
      detailLoading.value = false
    }
  }
}

async function handleFileChange(uploadFile: UploadFile) {
  if (!uploadFile.raw) return
  try {
    const resume = await uploadResume(uploadFile.raw)
    ElMessage.success('上传成功')
    const pendingResume = { ...resume, parsedData: undefined, parseProgress: 10 }
    resumes.value.unshift(pendingResume)
    detailRequestSequence++
    selectedResume.value = pendingResume
    activeDetailTab.value = 'overview'
    detailLoading.value = false
    startPolling(resume.resumeId)
  } catch (error) {
    ElMessage.error((error as Error).message || '上传失败')
  }
}

function confirmResume(resume: Resume) {
  if (!resume.parsedData) {
    ElMessage.warning('暂无解析结果，无法确认')
    return
  }
  openResumeAction('confirm', resume)
}

function reparseResume(resumeId: number) {
  const resume = resumes.value.find(item => item.resumeId === resumeId)
  if (!resume) {
    ElMessage.error('未找到这份简历，请刷新后重试')
    return
  }
  openResumeAction('reparse', resume)
}

function removeResume(resumeId: number) {
  const resume = resumes.value.find(item => item.resumeId === resumeId)
  if (!resume) {
    ElMessage.error('未找到这份简历，请刷新后重试')
    return
  }
  openResumeAction('delete', resume)
}

function openResumeAction(type: ResumeActionType, resume: Resume) {
  if (actionDialogLoading.value) return
  pendingResumeAction.value = {
    type,
    resumeId: resume.resumeId,
    fileName: resume.fileName
  }
  actionDialogVisible.value = true
}

async function executeResumeAction() {
  const pending = pendingResumeAction.value
  if (!pending || actionDialogLoading.value) return

  actionDialogLoading.value = true
  try {
    if (pending.type === 'confirm') {
      await submitResumeConfirmation(pending.resumeId)
    } else if (pending.type === 'reparse') {
      await submitResumeReparse(pending.resumeId)
    } else {
      await submitResumeDeletion(pending.resumeId)
    }
    actionDialogVisible.value = false
  } catch (error) {
    ElMessage.error(errorMessage(error, actionErrorFallback(pending.type)))
  } finally {
    actionDialogLoading.value = false
  }
}

async function submitResumeConfirmation(resumeId: number) {
  const resume = resumes.value.find(item => item.resumeId === resumeId)
  if (!resume?.parsedData) {
    throw new Error('暂无解析结果，无法确认')
  }

  if (resume.parseGeneration === undefined) {
    throw new Error('简历画像版本已失效，请重新打开后再确认')
  }

  await apiConfirmResume(resumeId, resume.parsedData, resume.parseGeneration)
  ElMessage.success('确认成功')
  const loaded = await loadDetail(resume)
  if (isAnalysisActive(loaded.analysisStatus)) {
    startPolling(resumeId)
  }
}

async function submitResumeReparse(resumeId: number) {
  const reparseResult = await apiReparseResume(resumeId)
  const index = resumes.value.findIndex(resume => resume.resumeId === resumeId)
  if (index >= 0) {
    resumes.value[index] = {
      ...resumes.value[index],
      status: reparseResult.status,
      statusLabel: reparseResult.statusLabel,
      parsedData: undefined,
      analysis: undefined,
      parseGeneration: undefined,
      analysisStatus: undefined,
      analysisErrorMessage: undefined,
      parseProgress: reparseResult.parseProgress ?? 10
    }
    updateResumeState(resumes.value[index])
  }
  if (selectedResume.value?.resumeId === resumeId) {
    activeDetailTab.value = 'overview'
  }
  ElMessage.success('重新解析已提交')
  startPolling(resumeId)
}

async function submitResumeDeletion(resumeId: number) {
  await deleteResume(resumeId)
  stopPolling(resumeId)
  resumes.value = resumes.value.filter(resume => resume.resumeId !== resumeId)
  if (selectedResume.value?.resumeId === resumeId) {
    detailRequestSequence++
    selectedResume.value = null
    if (resumes.value[0]) {
      await selectResume(resumes.value[0])
    }
  }
  ElMessage.success('删除成功')
}

function resetResumeActionDialog() {
  if (actionDialogVisible.value || actionDialogLoading.value) return
  pendingResumeAction.value = null
}

function actionErrorFallback(type: ResumeActionType) {
  if (type === 'confirm') return '确认失败'
  if (type === 'reparse') return '重新解析失败'
  return '删除失败'
}

function isParseActive(status?: string) {
  return status === 'PENDING' || status === 'PARSING'
}

function isAnalysisActive(status?: string) {
  return status === 'PENDING' || status === 'RUNNING'
}

function canLoadProfile(status?: string) {
  return status === 'PENDING_CONFIRM' || status === 'CONFIRMED'
}

function progressFor(resume?: Pick<Resume, 'status' | 'parseProgress'> | null) {
  if (resume?.parseProgress !== undefined) return resume.parseProgress
  switch (resume?.status) {
    case 'PARSING': return 50
    case 'PENDING_CONFIRM':
    case 'CONFIRMED': return 100
    case 'PARSE_FAILED': return 0
    default: return 10
  }
}

function parseStatusText(status?: string) {
  return status === 'PARSING' ? 'AI 正在分析简历，请稍候' : '解析任务正在排队'
}

function emptyDescription(status?: string) {
  return status === 'PARSE_FAILED' ? '简历解析失败，请重新解析' : '暂无解析详情'
}

function formatIndex(index: number) {
  return String(index + 1).padStart(2, '0')
}

function formatDate(value?: string) {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value.replace('T', ' ').slice(0, 16)
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

function splitEducation(value?: string) {
  if (!value?.trim()) {
    return { school: '待识别', detail: '专业与学历待识别' }
  }

  const normalized = value.trim().replace(/\s+/g, ' ')
  const parenthesizedSchool = normalized.match(/^(.+?[）)])\s+(.+)$/)
  if (parenthesizedSchool) {
    return { school: parenthesizedSchool[1], detail: parenthesizedSchool[2] }
  }

  const separatorParts = normalized.split(/\s*[|｜/]\s*/).filter(Boolean)
  if (separatorParts.length > 1) {
    return { school: separatorParts[0], detail: separatorParts.slice(1).join(' ') }
  }

  const firstSpace = normalized.indexOf(' ')
  if (firstSpace > 0) {
    return {
      school: normalized.slice(0, firstSpace),
      detail: normalized.slice(firstSpace + 1)
    }
  }

  return { school: normalized, detail: '专业与学历待识别' }
}

function resumeCode(resume: Resume) {
  const index = resumes.value.findIndex(item => item.resumeId === resume.resumeId)
  return index >= 0 ? `CV-${formatIndex(index)}` : 'CV'
}

function resumeStateClass(status?: string) {
  if (status === 'CONFIRMED') return 'is-confirmed'
  if (status === 'PENDING_CONFIRM') return 'is-awaiting'
  if (status === 'PARSE_FAILED') return 'is-failed'
  if (isParseActive(status)) return 'is-processing'
  return 'is-neutral'
}

function isGoldSkill(skill: string) {
  return GOLD_SKILL_PATTERNS.some(pattern => pattern.test(skill))
}

function analysisEmptyCopy(resume: Resume, section: 'strengths' | 'verification') {
  if (isAnalysisActive(resume.analysisStatus)) {
    return 'AI 辅助分析正在生成，完成后会自动更新。'
  }
  if (resume.analysisStatus === 'FAILED') {
    return '辅助分析暂不可用，不影响简历画像与模拟面试。'
  }
  return section === 'strengths' ? '暂无明确优势证据。' : '暂无需要额外验证的能力点。'
}

function errorMessage(error: unknown, fallback: string) {
  if (typeof error === 'string') return error
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  }
  return fallback
}

function handleResumeCommand(command: string) {
  if (command === 'delete' && selectedResume.value) {
    removeResume(selectedResume.value.resumeId)
  }
}

function updateResumeState(updated: Resume) {
  const idx = resumes.value.findIndex(resume => resume.resumeId === updated.resumeId)
  if (idx >= 0) {
    resumes.value[idx] = { ...resumes.value[idx], ...updated }
  }
  if (selectedResume.value?.resumeId === updated.resumeId) {
    selectedResume.value = { ...selectedResume.value, ...updated }
  }
}

function startPolling(resumeId: number) {
  if (pollingResumeIds.has(resumeId)) return
  pollingResumeIds.add(resumeId)
  pollAttempts.set(resumeId, 0)
  scheduleNextPoll(resumeId)
}

function scheduleNextPoll(resumeId: number) {
  const timer = window.setTimeout(() => {
    pollTimers.delete(resumeId)
    void pollResumeStatus(resumeId)
  }, POLL_INTERVAL_MS)
  pollTimers.set(resumeId, timer)
}

async function pollResumeStatus(resumeId: number) {
  if (!pollingResumeIds.has(resumeId)) return
  const attempts = (pollAttempts.get(resumeId) || 0) + 1
  pollAttempts.set(resumeId, attempts)

  try {
    const previous = resumes.value.find(item => item.resumeId === resumeId)
    const wasParsing = isParseActive(previous?.status)
    const parseStatus = await getResumeParseStatus(resumeId)
    applyParseStatus(parseStatus)
    if (!isParseActive(parseStatus.status)) {
      const resume = resumes.value.find(item => item.resumeId === resumeId)
      if (resume && canLoadProfile(parseStatus.status)) {
        const loaded = await loadDetail(resume)
        if (wasParsing) {
          ElMessage.success('简历画像解析完成，请查看并确认')
        }
        if (isAnalysisActive(loaded.analysisStatus)) {
          if (attempts >= MAX_POLL_ATTEMPTS) {
            stopPolling(resumeId)
            ElMessage.warning('简历辅助分析耗时较长，可稍后重新打开查看结果')
            return
          }
          scheduleNextPoll(resumeId)
          return
        }
      } else if (parseStatus.status === 'PARSE_FAILED') {
        ElMessage.error('简历解析失败，请重新解析')
      }
      stopPolling(resumeId)
      return
    }
  } catch {
    // 短暂网络错误不改变后端任务状态，下一轮继续查询。
  }

  if (attempts >= MAX_POLL_ATTEMPTS) {
    stopPolling(resumeId)
    ElMessage.warning('简历解析耗时较长，可稍后刷新查看结果')
    return
  }
  scheduleNextPoll(resumeId)
}

function applyParseStatus(parseStatus: ResumeParseStatus) {
  const current = resumes.value.find(resume => resume.resumeId === parseStatus.resumeId)
  if (!current) return
  updateResumeState({
    ...current,
    status: parseStatus.status,
    statusLabel: parseStatus.statusLabel,
    parseProgress: parseStatus.parseProgress,
    hasConfirmedProfile: parseStatus.hasConfirmedProfile ?? current.hasConfirmedProfile,
    parseGeneration: parseStatus.parseGeneration ?? current.parseGeneration,
    analysisStatus: parseStatus.analysisStatus ?? current.analysisStatus,
    analysisErrorMessage: parseStatus.analysisErrorMessage ?? current.analysisErrorMessage,
    updatedAt: parseStatus.updatedAt,
    parsedData: isParseActive(parseStatus.status) || parseStatus.status === 'PARSE_FAILED'
      ? undefined
      : current.parsedData
  })
}

function stopPolling(resumeId: number) {
  const timer = pollTimers.get(resumeId)
  if (timer !== undefined) {
    window.clearTimeout(timer)
  }
  pollTimers.delete(resumeId)
  pollAttempts.delete(resumeId)
  pollingResumeIds.delete(resumeId)
}

function stopAllPolling() {
  Array.from(pollingResumeIds).forEach(stopPolling)
}

</script>

<style scoped>
.resume-page {
  --resume-library-width: 340px;
  --resume-header-height: 128px;
}

.resume-workspace {
  position: relative;
  display: grid;
  min-height: 600px;
  grid-template-columns: minmax(300px, var(--resume-library-width)) minmax(0, 1fr);
  overflow: hidden;
  border-top: 2px solid var(--color-ink);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.resume-library {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.library-header {
  display: flex;
  height: var(--resume-header-height);
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid var(--color-border);
  padding: 16px 20px;
}

.library-header > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.library-header span,
.state-kicker,
.profile-section-heading span,
.records-heading span,
.detail-kicker > span:first-child,
.detail-empty > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.library-header strong {
  margin-top: 5px;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
}

.library-header small {
  flex: 0 0 auto;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.resume-list {
  display: flex;
  flex-direction: column;
}

.resume-list-item {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 116px;
  grid-template-columns: 44px minmax(0, 1fr);
  align-content: center;
  gap: 5px 12px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  border-left: 3px solid transparent;
  padding: 18px 18px 18px 15px;
  color: var(--color-body);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: color var(--motion-fast) var(--ease-standard),
    background-color var(--motion-fast) var(--ease-standard),
    border-color var(--motion-fast) var(--ease-standard);
}

.resume-list-item:hover,
.resume-list-item:focus-visible {
  background: var(--color-surface);
}

.resume-list-item.is-active {
  border-left-color: var(--color-brand-500);
  color: var(--color-ink);
  background: var(--color-surface);
}

.resume-code {
  grid-row: 1 / span 2;
  padding-top: 2px;
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.resume-item-copy {
  display: block;
  min-width: 0;
}

.resume-item-copy strong {
  display: -webkit-box;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.45;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.resume-item-copy small,
.resume-item-copy time {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: var(--color-muted);
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.resume-item-copy time {
  font-family: var(--font-mono);
  font-size: 9px;
}

.resume-item-state,
.detail-status {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 7px;
  color: var(--color-muted);
  font-size: 11px;
  font-weight: 600;
}

.resume-item-state {
  grid-column: 2;
  margin-top: 5px;
}

.resume-item-state i,
.detail-status i {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  background: currentColor;
}

.resume-item-state.is-confirmed,
.detail-status.is-confirmed {
  color: var(--color-success);
}

.resume-item-state.is-awaiting,
.detail-status.is-awaiting {
  color: var(--color-warning);
}

.resume-item-state.is-failed,
.detail-status.is-failed {
  color: var(--color-danger);
}

.resume-item-state.is-processing,
.detail-status.is-processing {
  color: var(--color-brand-600);
}

.resume-item-state.is-processing i,
.detail-status.is-processing i {
  animation: resume-state-pulse 1.2s ease-in-out infinite;
}

.library-empty {
  display: flex;
  min-height: 340px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 32px;
  color: var(--color-muted);
  text-align: center;
}

.library-empty .el-icon {
  color: var(--color-border-strong);
  font-size: 32px;
}

.library-empty strong {
  margin-top: 18px;
  color: var(--color-ink);
  font-size: 14px;
}

.library-empty p {
  max-width: 220px;
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.65;
}

.resume-detail {
  min-width: 0;
  background: var(--color-surface);
}

.detail-header {
  display: flex;
  height: var(--resume-header-height);
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  border-bottom: 1px solid var(--color-border);
  padding: 24px 32px;
}

.detail-heading-copy {
  min-width: 0;
}

.detail-kicker {
  display: flex;
  align-items: center;
  gap: 14px;
}

.detail-heading-copy h2 {
  max-width: 680px;
  margin: 10px 0 0;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 24px;
  font-weight: 720;
  line-height: 1.25;
  overflow-wrap: anywhere;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-heading-copy > p {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}

.detail-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.detail-actions .el-button + .el-button {
  margin-left: 0;
}

.more-action {
  width: 44px;
  height: 44px;
}

.profile-facts {
  display: grid;
  grid-template-columns:
    minmax(0, 1.2fr)
    minmax(0, 0.7fr)
    minmax(0, 0.95fr)
    minmax(0, 1.45fr)
    minmax(0, 0.7fr);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.profile-facts > div {
  min-width: 0;
  min-height: 94px;
  border-right: 1px solid var(--color-border);
  padding: 20px 24px;
}

.profile-facts > div:last-child {
  border-right: 0;
}

.profile-facts span,
.profile-section-heading small {
  display: block;
  color: var(--color-muted);
  font-size: 10px;
}

.records-heading small {
  display: block;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 600;
}

.profile-facts strong {
  display: block;
  margin-top: 9px;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.education-value small {
  display: block;
  margin-top: 3px;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.45;
}

.detail-state,
.detail-empty {
  display: flex;
  min-height: 420px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 56px 32px;
  text-align: center;
}

.detail-loading {
  position: relative;
}

.state-icon,
.detail-empty .el-icon {
  color: var(--color-brand-500);
  font-size: 34px;
}

.failure-state .state-icon {
  color: var(--color-danger);
}

.state-kicker,
.detail-empty > span {
  margin-top: 20px;
}

.detail-state h3,
.detail-empty h2 {
  margin: 10px 0 0;
  color: var(--color-ink);
  font-size: 22px;
  font-weight: 680;
}

.detail-state > p,
.detail-empty > p {
  max-width: 480px;
  margin: 10px 0 0;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.7;
}

.detail-state > .el-button {
  margin-top: 24px;
}

.parse-progress-panel :deep(.el-progress) {
  width: min(100%, 460px);
  margin-top: 28px;
}

.profile-tabs {
  padding: 0 32px 40px;
}

.profile-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.profile-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--color-border);
}

.profile-tabs :deep(.el-tabs__item) {
  height: 60px;
  padding: 0 22px;
  font-size: 13px;
  font-weight: 600;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(280px, 0.8fr);
}

.overview-main {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  padding-right: 32px;
}

.overview-aside {
  min-width: 0;
  padding-left: 32px;
}

.profile-section,
.evidence-section {
  border-bottom: 1px solid var(--color-border);
  padding: 28px 0;
}

.profile-section:last-child,
.evidence-section:last-child {
  border-bottom: 0;
}

.profile-section-heading,
.records-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.profile-section-heading h3,
.records-heading h3 {
  margin: 6px 0 0;
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 680;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 20px;
}

.skill-tag {
  margin: 0;
}

.profile-tabs :deep(.skill-tag.el-tag) {
  border-color: #d7e0e3;
  color: #40545d;
  background: #f0f4f5;
}

.profile-tabs :deep(.skill-tag.el-tag.is-gold) {
  border-color: #b98212;
  color: #4e3600;
  background: linear-gradient(115deg, #fff8d9 0%, #f5d05c 34%, #fff0a3 62%, #e7ab20 100%);
  font-weight: 700;
}

.profile-tabs :deep(.skill-tag.el-tag.is-gold)::before {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  margin-right: 6px;
  content: '';
  background: #a96e00;
  transform: rotate(45deg);
}

.skill-level-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 18px;
  border-top: 1px solid var(--color-border);
}

.skill-level-item {
  display: flex;
  min-width: 0;
  min-height: 50px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--color-border);
  padding: 10px 12px 10px 0;
}

.skill-level-item:nth-child(odd) {
  border-right: 1px solid var(--color-border);
  padding-right: 20px;
}

.skill-level-item:nth-child(even) {
  padding-left: 20px;
}

.skill-level-item > span:first-child {
  overflow: hidden;
  color: var(--color-body);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-level-item strong {
  color: var(--color-ink);
  font-size: 12px;
  font-weight: 650;
}

.skill-level-result {
  display: flex;
  flex: 0 0 auto;
  align-items: baseline;
  gap: 8px;
}

.skill-level-result small {
  color: var(--color-warning);
  font-size: 10px;
  white-space: nowrap;
}

.evidence-list {
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}

.evidence-list li {
  position: relative;
  border-top: 1px solid var(--color-border);
  padding: 12px 0 12px 18px;
  color: var(--color-body);
  font-size: 12px;
  line-height: 1.65;
}

.evidence-list li::before {
  position: absolute;
  top: 20px;
  left: 0;
  width: 6px;
  height: 6px;
  content: '';
  background: var(--color-success);
}

.gaps-list li::before {
  background: var(--color-warning);
}

.empty-copy {
  margin: 18px 0 0;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.65;
}

.records-panel {
  padding-top: 28px;
}

.records-heading {
  border-bottom: 1px solid var(--color-ink);
  padding-bottom: 20px;
}

.experience-timeline {
  position: relative;
  margin: 0;
  padding: 0;
  list-style: none;
}

.experience-timeline::before {
  position: absolute;
  top: 36px;
  bottom: 36px;
  left: 5px;
  width: 1px;
  content: '';
  background: var(--color-border-strong);
}

.experience-timeline > li {
  position: relative;
  border-bottom: 1px solid var(--color-border);
  padding: 28px 0 28px 34px;
}

.timeline-marker {
  position: absolute;
  top: 36px;
  left: 0;
  z-index: 1;
  width: 11px;
  height: 11px;
  border: 3px solid var(--color-surface);
  background: var(--color-brand-500);
  outline: 1px solid var(--color-brand-500);
}

.record-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32px;
}

.record-heading > div {
  min-width: 0;
}

.record-heading h4 {
  margin: 0;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
  overflow-wrap: anywhere;
}

.record-heading p {
  margin: 6px 0 0;
  color: var(--color-body);
  font-size: 13px;
}

.record-heading time,
.record-index {
  flex: 0 0 auto;
  color: var(--color-body);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
}

.record-list {
  margin: 18px 0 0;
  padding-left: 20px;
  color: var(--color-body);
  font-size: 13px;
  line-height: 1.75;
}

.record-list li + li {
  margin-top: 7px;
}

.project-record {
  border-bottom: 1px solid var(--color-border);
  padding: 28px 0;
}

.project-description {
  max-width: 880px;
  margin: 18px 0 0;
  color: var(--color-body);
  font-size: 13px;
  line-height: 1.75;
}

.project-tech-stack {
  margin-top: 16px;
}

:global(.el-dropdown-menu__item.danger-command) {
  color: var(--color-danger);
}

.is-rotating {
  animation: resume-rotate 1.4s linear infinite;
}

@keyframes resume-state-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

@keyframes resume-rotate {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1365px) {
  .resume-page {
    --resume-library-width: 304px;
  }

  .detail-header {
    gap: 20px;
    padding-right: 24px;
    padding-left: 24px;
  }

  .detail-heading-copy h2 {
    max-width: 440px;
    font-size: 21px;
  }

  .profile-facts > div {
    padding-right: 18px;
    padding-left: 18px;
  }

  .profile-tabs {
    padding-right: 24px;
    padding-left: 24px;
  }

  .overview-grid {
    grid-template-columns: minmax(0, 1.08fr) minmax(260px, 0.92fr);
  }

  .overview-main {
    padding-right: 24px;
  }

  .overview-aside {
    padding-left: 24px;
  }
}

@media (min-width: 2560px) {
  .resume-page {
    --resume-library-width: 360px;
  }

  .detail-header,
  .profile-tabs {
    padding-right: 40px;
    padding-left: 40px;
  }

  .overview-main {
    padding-right: 40px;
  }

  .overview-aside {
    padding-left: 40px;
  }
}
</style>
