<template>
  <AppLayout>
    <main class="page-container position-page">
      <header class="position-overview" aria-label="岗位库概览">
        <div class="overview-lead">
          <div class="overview-lead-copy">
            <span>POSITION INTELLIGENCE</span>
            <div class="overview-heading">
              <h1 class="page-title">{{ isPublicTab ? '公共岗位库' : '我的目标岗位' }}</h1>
              <p>
                {{ isPublicTab
                  ? '选择标准岗位画像，快速开始简历分析与模拟面试。'
                  : '沉淀岗位要求、能力标准与考察重点，支撑一致的人才判断。' }}
              </p>
            </div>
          </div>
          <el-button v-if="!isPublicTab" size="large" :icon="Plus" @click="openCreateDialog">
            新增岗位
          </el-button>
        </div>
        <dl class="overview-metric">
          <dt>当前岗位</dt>
          <dd>{{ positions.length }}</dd>
          <small>{{ isPublicTab ? '可访问岗位' : '个人岗位档案' }}</small>
        </dl>
        <dl class="overview-metric">
          <dt>画像已确认</dt>
          <dd>{{ confirmedPositionCount }}</dd>
          <small>可直接用于面试准备</small>
        </dl>
        <dl
          class="overview-metric"
          :class="{ 'overview-metric--accent': attentionPositionCount > 0 }"
        >
          <dt>需要关注</dt>
          <dd>{{ attentionPositionCount }}</dd>
          <small>待确认或解析未完成</small>
        </dl>
      </header>

      <section class="position-workspace" v-loading="loading" aria-label="目标岗位工作区">
        <header class="library-toolbar">
          <div class="position-tabs" role="tablist" aria-label="岗位来源">
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'mine'"
              :class="{ 'is-active': activeTab === 'mine' }"
              @click="changeTab('mine')"
            >
              我的岗位
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'public'"
              :class="{ 'is-active': activeTab === 'public' }"
              @click="changeTab('public')"
            >
              公共岗位
            </button>
          </div>

          <div class="toolbar-search">
            <el-input
              v-model="filterKeyword"
              :prefix-icon="Search"
              clearable
              aria-label="搜索岗位或公司"
              placeholder="搜索岗位或公司"
            />
            <span>{{ filteredPositions.length }} 个岗位</span>
          </div>
        </header>

        <div v-if="filteredPositions.length" class="workspace-body">
          <aside class="position-library" aria-label="岗位列表">
            <header class="library-heading">
              <span>{{ isPublicTab ? 'PUBLIC POSITIONS' : 'MY POSITIONS' }}</span>
              <small>选择岗位查看完整要求</small>
            </header>

            <div class="position-list" role="listbox" aria-label="岗位档案">
              <button
                v-for="position in filteredPositions"
                :key="position.positionId"
                class="position-list-item"
                :class="{ 'is-active': selectedPosition?.positionId === position.positionId }"
                type="button"
                role="option"
                :aria-selected="selectedPosition?.positionId === position.positionId"
                :aria-label="`查看岗位 ${position.positionName}`"
                @click="selectPosition(position)"
              >
                <span class="position-item-topline">
                  <span class="position-code">{{ positionCode(position.positionId) }}</span>
                  <span class="status-label" :class="statusClass(position.parseStatus)">
                    <i aria-hidden="true"></i>
                    {{ positionStatusLabel(position) }}
                  </span>
                </span>
                <span class="position-item-copy">
                  <strong>{{ position.positionName || '未命名岗位' }}</strong>
                  <small>{{ position.companyName || '公司待补充' }}</small>
                </span>
                <span class="position-item-footer">
                  <span>{{ categoryLabel(position) }}</span>
                  <span>
                    查看简报
                    <el-icon aria-hidden="true"><ArrowRight /></el-icon>
                  </span>
                </span>
              </button>
            </div>
          </aside>

          <article v-if="selectedPosition" class="position-detail" aria-live="polite">
            <header class="detail-header">
              <div class="detail-heading-copy">
                <div class="detail-kicker">
                  <span>{{ positionCode(selectedPosition.positionId) }}</span>
                  <span class="status-label" :class="statusClass(selectedPosition.parseStatus)">
                    <i aria-hidden="true"></i>
                    {{ positionStatusLabel(selectedPosition) }}
                  </span>
                </div>
                <h2>{{ selectedPosition.positionName || '未命名岗位' }}</h2>
                <p>
                  {{ selectedPosition.companyName || '公司待补充' }}
                  <span aria-hidden="true">/</span>
                  {{ categoryLabel(selectedPosition) }}
                </p>
              </div>

              <div class="detail-actions">
                <el-button :icon="DataAnalysis" @click="openDetail(selectedPosition)">查看岗位画像</el-button>
                <el-button
                  v-if="selectedPosition.parseStatus === 'PENDING_CONFIRM' && !isPublicTab"
                  :icon="CircleCheck"
                  @click="confirm(selectedPosition)"
                >
                  确认画像
                </el-button>
                <el-button
                  type="primary"
                  :icon="VideoPlay"
                  @click="selectForInterview(selectedPosition.positionId)"
                >
                  用于模拟面试
                </el-button>
                <el-dropdown v-if="!isPublicTab" trigger="click" @command="handlePositionCommand">
                  <el-button
                    class="more-action"
                    :icon="MoreFilled"
                    circle
                    title="更多岗位操作"
                    aria-label="更多岗位操作"
                  />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="reparse">重新解析</el-dropdown-item>
                      <el-dropdown-item command="delete" class="danger-command">删除岗位</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </header>

            <dl class="position-facts">
              <div>
                <dt>公司</dt>
                <dd>{{ selectedPosition.companyName || '待补充' }}</dd>
              </div>
              <div>
                <dt>岗位大类</dt>
                <dd>{{ categoryLabel(selectedPosition) }}</dd>
              </div>
              <div>
                <dt>岗位等级</dt>
                <dd>{{ selectedPosition.levelLabel || selectedPosition.level || '待识别' }}</dd>
              </div>
              <div>
                <dt>工作地点</dt>
                <dd>{{ selectedPosition.location || '待补充' }}</dd>
              </div>
              <div>
                <dt>薪资范围</dt>
                <dd>{{ selectedPosition.salaryRange || '待补充' }}</dd>
              </div>
            </dl>

            <div class="position-brief-grid">
              <section class="jd-document">
                <header class="content-heading">
                  <div>
                    <span>JOB DESCRIPTION</span>
                    <h3>岗位说明</h3>
                  </div>
                  <small>{{ selectedPosition.jdContent?.trim() ? 'JD 原文' : '内容待补充' }}</small>
                </header>
                <div v-if="selectedPosition.jdContent?.trim()" class="jd-content">
                  {{ selectedPosition.jdContent }}
                </div>
                <div v-else class="jd-empty">
                  <el-icon aria-hidden="true"><Document /></el-icon>
                  <strong>暂未提供岗位说明</strong>
                  <p>补充完整 JD 后，岗位画像和面试考察方向会更准确。</p>
                </div>
              </section>

              <aside class="readiness-panel">
                <span>AI POSITION PROFILE</span>
                <h3>{{ statusHeadline(selectedPosition.parseStatus) }}</h3>
                <p>{{ statusDescription(selectedPosition.parseStatus) }}</p>

                <ol class="readiness-steps" aria-label="岗位准备进度">
                  <li class="is-completed">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>岗位信息</strong>
                      <small>已收录</small>
                    </span>
                  </li>
                  <li :class="profileStepClass(selectedPosition.parseStatus)">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>AI 岗位画像</strong>
                      <small>{{ profileStepText(selectedPosition.parseStatus) }}</small>
                    </span>
                  </li>
                  <li :class="interviewStepClass(selectedPosition.parseStatus)">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>面试准备</strong>
                      <small>{{ selectedPosition.parseStatus === 'CONFIRMED' ? '可以开始' : '等待画像就绪' }}</small>
                    </span>
                  </li>
                </ol>

                <el-button class="profile-entry" :icon="DataAnalysis" @click="openDetail(selectedPosition)">
                  打开完整岗位画像
                </el-button>
              </aside>
            </div>

            <footer class="detail-note">
              <span>INTERVIEW BASIS</span>
              <strong>面试问题将基于这份岗位要求与候选人简历共同生成</strong>
              <p>开始前仍可在面试配置中选择简历与具体考察环节。</p>
            </footer>
          </article>
        </div>

        <div v-else-if="!loading" class="position-empty">
          <el-icon aria-hidden="true"><Briefcase /></el-icon>
          <span>{{ positions.length ? 'NO MATCHED POSITIONS' : 'NO POSITION FILES' }}</span>
          <h2>
            {{ positions.length
              ? '没有找到符合条件的岗位'
              : isPublicTab ? '暂时没有可用的公共岗位' : '还没有目标岗位' }}
          </h2>
          <p>
            {{ positions.length
              ? '尝试更换岗位名称或公司关键词。'
              : isPublicTab ? '公共岗位更新后会展示在这里。' : '新增岗位 JD 后，职衡会自动提取能力要求与考察重点。' }}
          </p>
          <el-button v-if="positions.length" :icon="RefreshLeft" @click="filterKeyword = ''">清除搜索</el-button>
          <el-button v-else-if="!isPublicTab" type="primary" :icon="Plus" @click="openCreateDialog">新增岗位</el-button>
        </div>
      </section>

      <el-dialog
        v-model="showCreateDialog"
        class="position-create-dialog"
        width="760px"
        align-center
        :close-on-click-modal="false"
        @closed="resetCreateDialog"
      >
        <template #header>
          <div class="dialog-heading">
            <span>CREATE POSITION</span>
            <h2>新增目标岗位</h2>
            <p>录入岗位基本信息与 JD，系统会继续生成能力要求和面试考察方向。</p>
          </div>
        </template>

        <el-form ref="formRef" class="position-create-form" :model="form" :rules="formRules" label-position="top">
          <div class="create-form-grid">
            <el-form-item label="岗位名称" prop="positionName">
              <el-input v-model="form.positionName" placeholder="例如：Java 高级工程师" />
            </el-form-item>
            <el-form-item label="公司">
              <el-input v-model="form.companyName" placeholder="例如：某科技公司" />
            </el-form-item>
          </div>

          <el-form-item label="岗位大类" prop="jobCategory">
            <el-select v-model="form.jobCategory" placeholder="选择岗位所属职能" style="width: 100%">
              <el-option label="技术族" value="TECH" />
              <el-option label="产品族" value="PRODUCT" />
              <el-option label="运营族" value="OPERATION" />
              <el-option label="设计族" value="DESIGN" />
            </el-select>
          </el-form-item>

          <section class="jd-input-section">
            <header>
              <div>
                <span>JD SOURCE</span>
                <strong>岗位说明来源</strong>
              </div>
              <small>粘贴文字或上传文件，任选一种</small>
            </header>
            <el-form-item label="JD 描述" prop="jdContent">
              <el-input
                v-model="form.jdContent"
                type="textarea"
                :rows="7"
                resize="none"
                placeholder="粘贴完整岗位职责、任职要求和加分项"
              />
            </el-form-item>
            <div class="upload-row">
              <div>
                <strong>也可以上传 JD 文件</strong>
                <p>支持 PDF / TXT，单个文件</p>
              </div>
              <el-upload
                ref="uploadRef"
                accept=".pdf,.txt"
                :auto-upload="false"
                :limit="1"
                :on-change="handleFileChange"
                :on-remove="handleFileRemove"
              >
                <el-button :icon="Upload">选择文件</el-button>
              </el-upload>
            </div>
          </section>
        </el-form>

        <template #footer>
          <div class="dialog-footer-actions">
            <el-button @click="showCreateDialog = false">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="submitPosition">创建并解析</el-button>
          </div>
        </template>
      </el-dialog>

      <el-dialog
        v-model="showProfileDialog"
        class="position-profile-dialog"
        width="960px"
        align-center
        @closed="resetProfileDialog"
      >
        <template #header>
          <div class="dialog-heading">
            <span>POSITION PROFILE</span>
            <h2>{{ currentPosition?.positionName || '岗位画像' }}</h2>
            <p>从岗位原文中提取的能力要求、考察深度与面试重点。</p>
          </div>
        </template>

        <div v-if="profileLoading" class="profile-loading-state">
          <el-icon class="is-rotating" aria-hidden="true"><Loading /></el-icon>
          <span>ANALYZING POSITION</span>
          <h3>正在读取岗位画像</h3>
          <p>岗位要求与考察方向加载完成后会在这里展开。</p>
        </div>

        <div v-else-if="currentProfile" class="profile-content">
          <dl class="profile-facts">
            <div>
              <dt>岗位</dt>
              <dd>{{ currentProfile.basicInfo?.title || currentPosition?.positionName || '待识别' }}</dd>
            </div>
            <div>
              <dt>公司</dt>
              <dd>{{ currentProfile.basicInfo?.company || currentPosition?.companyName || '待补充' }}</dd>
            </div>
            <div>
              <dt>地点</dt>
              <dd>{{ currentProfile.basicInfo?.location || currentPosition?.location || '待补充' }}</dd>
            </div>
            <div>
              <dt>等级</dt>
              <dd>{{ currentPosition?.levelLabel || currentProfile.basicInfo?.level || '待识别' }}</dd>
            </div>
            <div>
              <dt>薪资</dt>
              <dd>{{ currentProfile.basicInfo?.salaryRange || currentPosition?.salaryRange || '待补充' }}</dd>
            </div>
          </dl>

          <div class="profile-layout">
            <div class="profile-main">
              <section class="profile-section">
                <header class="profile-section-heading">
                  <div>
                    <span>REQUIRED SKILLS</span>
                    <h3>必备技能</h3>
                  </div>
                  <small>{{ currentProfile.requiredSkills?.length || 0 }} 项</small>
                </header>
                <div v-if="currentProfile.requiredSkills?.length" class="skill-requirements">
                  <div v-for="item in currentProfile.requiredSkills" :key="item.skill" class="skill-requirement-item">
                    <strong>{{ item.skill }}</strong>
                    <span>{{ item.importance || '重要性待判断' }}</span>
                    <small>{{ item.depth || '深度待判断' }}</small>
                  </div>
                </div>
                <p v-else class="profile-empty-copy">暂无明确的必备技能数据。</p>
              </section>

              <section class="profile-section probing-section">
                <header class="profile-section-heading">
                  <div>
                    <span>PROBING DIRECTIONS</span>
                    <h3>考察方向</h3>
                  </div>
                  <small>{{ sortedProbingDirections.length }} 个方向</small>
                </header>
                <div v-if="sortedProbingDirections.length" class="probing-list">
                  <article v-for="(direction, index) in sortedProbingDirections" :key="`${direction.direction}-${index}`">
                    <span>{{ String(index + 1).padStart(2, '0') }}</span>
                    <div>
                      <header>
                        <strong>{{ direction.direction }}</strong>
                        <small>优先级 {{ direction.priority }} / {{ direction.depthRange }}</small>
                      </header>
                      <ul v-if="direction.sampleQuestions?.length">
                        <li v-for="question in direction.sampleQuestions" :key="question">{{ question }}</li>
                      </ul>
                    </div>
                  </article>
                </div>
                <p v-else class="profile-empty-copy">暂无可用的考察方向。</p>
              </section>
            </div>

            <aside class="profile-aside">
              <section class="profile-section">
                <header class="profile-section-heading">
                  <div>
                    <span>PREFERRED SKILLS</span>
                    <h3>加分技能</h3>
                  </div>
                </header>
                <ul v-if="currentProfile.preferredSkills?.length" class="compact-skill-list">
                  <li v-for="item in currentProfile.preferredSkills" :key="item.skill">
                    <strong>{{ item.skill }}</strong>
                    <small>{{ item.importance }} / {{ item.depth }}</small>
                  </li>
                </ul>
                <p v-else class="profile-empty-copy">暂无明确的加分技能。</p>
              </section>

              <section class="profile-section focus-section">
                <header class="profile-section-heading">
                  <div>
                    <span>INTERVIEW FOCUS</span>
                    <h3>面试重点</h3>
                  </div>
                </header>
                <ol v-if="currentProfile.interviewFocus?.length" class="focus-list">
                  <li v-for="(focus, index) in currentProfile.interviewFocus" :key="focus">
                    <span>{{ index + 1 }}</span>
                    <p>{{ focus }}</p>
                  </li>
                </ol>
                <p v-else class="profile-empty-copy">暂无明确的面试重点。</p>
              </section>
            </aside>
          </div>
        </div>

        <div v-else class="profile-empty-state">
          <el-icon aria-hidden="true"><DataAnalysis /></el-icon>
          <h3>岗位画像暂未生成</h3>
          <p>可以关闭后重新解析这份岗位 JD。</p>
        </div>

        <template #footer>
          <div class="dialog-footer-actions">
            <el-button @click="showProfileDialog = false">关闭</el-button>
            <el-button
              v-if="currentPosition?.parseStatus === 'PENDING_CONFIRM'"
              type="primary"
              :icon="CircleCheck"
              @click="confirmFromDialog"
            >
              确认画像
            </el-button>
          </div>
        </template>
      </el-dialog>

      <ActionConfirmDialog
        v-model="positionActionVisible"
        :title="positionActionContent.title"
        :description="positionActionContent.description"
        subject-label="目标岗位"
        :subject="pendingPositionAction?.positionName || '当前岗位'"
        :confirm-text="positionActionContent.confirmText"
        :cancel-text="positionActionContent.cancelText"
        :loading-text="positionActionContent.loadingText"
        :tone="pendingPositionAction?.type === 'delete' ? 'danger' : 'primary'"
        :impact="positionActionContent.impact"
        :loading="positionActionLoading"
        @confirm="executePositionAction"
        @closed="resetPositionAction"
      />
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Briefcase,
  CircleCheck,
  DataAnalysis,
  Document,
  Loading,
  MoreFilled,
  Plus,
  RefreshLeft,
  Search,
  Upload,
  VideoPlay
} from '@element-plus/icons-vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import AppLayout from '@/components/AppLayout.vue'
import {
  confirmPosition,
  createPosition,
  deletePosition,
  getPositionList,
  getPositionProfile,
  getPublicPositionList,
  reparsePosition,
  uploadPosition
} from '@/api/position'
import type { Position, PositionProfileData, ProbingDirection } from '@/types'
import type { FormInstance, FormRules, UploadFile, UploadInstance } from 'element-plus'

type PositionTab = 'mine' | 'public'
type PositionActionType = 'reparse' | 'delete'

interface PendingPositionAction {
  type: PositionActionType
  positionId: number
  positionName: string
}

interface PositionActionContent {
  title: string
  description: string
  confirmText: string
  cancelText: string
  loadingText: string
  impact: Array<{ label: string; value: string }>
}

const POSITION_ACTION_CONTENT: Record<PositionActionType, PositionActionContent> = {
  reparse: {
    title: '重新解析这份岗位 JD？',
    description: '职衡会重新分析岗位要求与考察方向，解析期间保留现有 JD 原文。',
    confirmText: '开始解析',
    cancelText: '暂不解析',
    loadingText: '正在提交',
    impact: [
      { label: 'JD 原文', value: '保持不变' },
      { label: '岗位画像', value: '解析完成后更新' }
    ]
  },
  delete: {
    title: '删除这个目标岗位？',
    description: '岗位档案及其画像会从当前账号中移除，之后不能继续用于匹配与面试配置。',
    confirmText: '删除岗位',
    cancelText: '保留岗位',
    loadingText: '正在删除',
    impact: [
      { label: '档案内容', value: '岗位与画像一并移除' },
      { label: '恢复方式', value: '删除后无法在页面内撤销' }
    ]
  }
}

const CATEGORY_LABELS: Record<string, string> = {
  TECH: '技术类',
  PRODUCT: '产品类',
  OPERATION: '运营类',
  DESIGN: '设计类'
}

const router = useRouter()
const positions = ref<Position[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(false)
const activeTab = ref<PositionTab>('mine')
const filterKeyword = ref('')
const showCreateDialog = ref(false)
const showProfileDialog = ref(false)
const profileLoading = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const uploadRef = ref<UploadInstance>()
const currentPosition = ref<Position | null>(null)
const currentProfile = ref<PositionProfileData | null>(null)
const currentFile = ref<File | null>(null)
const positionActionVisible = ref(false)
const positionActionLoading = ref(false)
const pendingPositionAction = ref<PendingPositionAction | null>(null)
let profileRequestSequence = 0

const form = ref({
  positionName: '',
  companyName: '',
  jobCategory: '',
  jdContent: ''
})

const formRules: FormRules = {
  positionName: [{ required: true, message: '请输入岗位名称', trigger: 'blur' }],
  jobCategory: [{ required: true, message: '请选择岗位大类', trigger: 'change' }],
  jdContent: [{
    validator: (_rule, value: string, callback) => {
      if ((!value || value.trim() === '') && !currentFile.value) {
        callback(new Error('请输入 JD 描述或上传 JD 文件'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

const isPublicTab = computed(() => activeTab.value === 'public')

const filteredPositions = computed(() => {
  const keyword = filterKeyword.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return positions.value
  return positions.value.filter(position => {
    const searchable = `${position.positionName} ${position.companyName || ''}`.toLocaleLowerCase('zh-CN')
    return searchable.includes(keyword)
  })
})

const selectedPosition = computed(() => {
  return filteredPositions.value.find(position => position.positionId === selectedId.value)
    || filteredPositions.value[0]
    || null
})

const confirmedPositionCount = computed(() => {
  return positions.value.filter(position => position.parseStatus === 'CONFIRMED').length
})

const attentionPositionCount = computed(() => {
  return positions.value.filter(position => {
    return position.parseStatus === 'PENDING_CONFIRM' || position.parseStatus === 'PARSE_FAILED'
  }).length
})

const sortedProbingDirections = computed<ProbingDirection[]>(() => {
  const directions = currentProfile.value?.probingDirections || []
  return [...directions].sort((left, right) => left.priority - right.priority)
})

const positionActionContent = computed(() => {
  return POSITION_ACTION_CONTENT[pendingPositionAction.value?.type || 'reparse']
})

onMounted(() => {
  void loadPositions()
})

async function loadPositions(preferredPositionId: number | null = selectedId.value) {
  loading.value = true
  try {
    positions.value = activeTab.value === 'mine'
      ? await getPositionList()
      : await getPublicPositionList()
    const preferredExists = preferredPositionId !== null
      && positions.value.some(position => position.positionId === preferredPositionId)
    selectedId.value = preferredExists
      ? preferredPositionId
      : positions.value[0]?.positionId ?? null
  } catch (error) {
    positions.value = []
    selectedId.value = null
    ElMessage.error((error as Error).message || '岗位列表加载失败')
  } finally {
    loading.value = false
  }
}

function changeTab(tab: PositionTab) {
  if (activeTab.value === tab || loading.value) return
  activeTab.value = tab
  filterKeyword.value = ''
  selectedId.value = null
  void loadPositions(null)
}

function selectPosition(position: Position) {
  selectedId.value = position.positionId
}

function selectForInterview(positionId: number) {
  router.push({ path: '/interview/config', query: { positionId: String(positionId) } })
}

function openCreateDialog() {
  form.value = { positionName: '', companyName: '', jobCategory: '', jdContent: '' }
  currentFile.value = null
  uploadRef.value?.clearFiles()
  showCreateDialog.value = true
}

function resetCreateDialog() {
  formRef.value?.clearValidate()
  currentFile.value = null
  uploadRef.value?.clearFiles()
}

function handleFileChange(uploadFile: UploadFile) {
  currentFile.value = uploadFile.raw || null
  formRef.value?.clearValidate('jdContent')
}

function handleFileRemove() {
  currentFile.value = null
}

async function submitPosition() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const position = currentFile.value
      ? await uploadPosition(currentFile.value, form.value.positionName)
      : await createPosition(form.value)
    positions.value.unshift(position)
    selectedId.value = position.positionId
    showCreateDialog.value = false
    ElMessage.success('新增成功，AI 正在解析岗位画像')
    void pollParseStatus(position.positionId)
  } catch (error) {
    ElMessage.error((error as Error).message || '新增失败')
  } finally {
    submitting.value = false
  }
}

async function pollParseStatus(positionId: number) {
  const maxAttempts = 30
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 2000))
    const profile = await getPositionProfile(positionId)
    if (profile.parseStatus && profile.parseStatus !== 'PENDING' && profile.parseStatus !== 'PARSING') {
      await loadPositions(positionId)
      if (profile.parseStatus === 'PENDING_CONFIRM') {
        ElMessage.success('岗位画像解析完成，请查看并确认')
      }
      return
    }
  }
}

async function openDetail(position: Position) {
  const requestSequence = ++profileRequestSequence
  currentPosition.value = position
  currentProfile.value = null
  profileLoading.value = true
  showProfileDialog.value = true
  try {
    const response = await getPositionProfile(position.positionId)
    if (requestSequence !== profileRequestSequence || !showProfileDialog.value) return
    currentProfile.value = response.profile || null
  } catch (error) {
    if (requestSequence !== profileRequestSequence || !showProfileDialog.value) return
    ElMessage.error((error as Error).message || '获取岗位画像失败')
    showProfileDialog.value = false
  } finally {
    if (requestSequence === profileRequestSequence) {
      profileLoading.value = false
    }
  }
}

function resetProfileDialog() {
  profileRequestSequence += 1
  currentPosition.value = null
  currentProfile.value = null
  profileLoading.value = false
}

async function confirm(position: Position) {
  if (!position.profile) {
    const response = await getPositionProfile(position.positionId)
    position.profile = response.profile
  }
  if (!position.profile) {
    ElMessage.warning('暂无画像可确认')
    return
  }
  try {
    await confirmPosition(position.positionId, position.profile)
    ElMessage.success('确认成功')
    await loadPositions(position.positionId)
  } catch (error) {
    ElMessage.error((error as Error).message || '确认失败')
  }
}

async function confirmFromDialog() {
  if (!currentPosition.value || !currentProfile.value) return
  const positionId = currentPosition.value.positionId
  try {
    await confirmPosition(positionId, currentProfile.value)
    ElMessage.success('确认成功')
    showProfileDialog.value = false
    await loadPositions(positionId)
  } catch (error) {
    ElMessage.error((error as Error).message || '确认失败')
  }
}

function handlePositionCommand(command: PositionActionType) {
  if (!selectedPosition.value) return
  openPositionAction(command, selectedPosition.value.positionId)
}

function openPositionAction(type: PositionActionType, positionId: number) {
  if (positionActionLoading.value) return
  const position = positions.value.find(item => item.positionId === positionId)
  if (!position) {
    ElMessage.error('未找到这个岗位，请刷新后重试')
    return
  }
  pendingPositionAction.value = {
    type,
    positionId,
    positionName: position.positionName
  }
  positionActionVisible.value = true
}

async function executePositionAction() {
  const pending = pendingPositionAction.value
  if (!pending || positionActionLoading.value) return

  positionActionLoading.value = true
  try {
    if (pending.type === 'reparse') {
      await reparsePosition(pending.positionId)
      ElMessage.success('重新解析已提交')
      await loadPositions(pending.positionId).catch(error => {
        ElMessage.error((error as Error).message || '岗位列表刷新失败，请稍后重试')
      })
      void pollParseStatus(pending.positionId)
    } else {
      await deletePosition(pending.positionId)
      ElMessage.success('删除成功')
      positions.value = positions.value.filter(position => position.positionId !== pending.positionId)
      if (selectedId.value === pending.positionId) {
        selectedId.value = positions.value[0]?.positionId ?? null
      }
    }
    positionActionVisible.value = false
  } catch (error) {
    const fallback = pending.type === 'reparse' ? '重新解析失败' : '删除失败'
    ElMessage.error((error as Error).message || fallback)
  } finally {
    positionActionLoading.value = false
  }
}

function resetPositionAction() {
  if (positionActionVisible.value || positionActionLoading.value) return
  pendingPositionAction.value = null
}

function positionCode(positionId: number) {
  return `POS-${String(positionId).padStart(4, '0')}`
}

function categoryLabel(position: Position) {
  return position.jobCategoryLabel || CATEGORY_LABELS[position.jobCategory] || '类别待识别'
}

function positionStatusLabel(position: Position) {
  if (position.parseStatusLabel) return position.parseStatusLabel
  const labels: Record<string, string> = {
    CONFIRMED: '已确认',
    PENDING_CONFIRM: '待确认',
    PARSING: '解析中',
    PENDING: '等待解析',
    PARSE_FAILED: '解析未完成'
  }
  return labels[position.parseStatus] || '状态待同步'
}

function statusClass(status: string) {
  if (status === 'CONFIRMED') return 'is-confirmed'
  if (status === 'PENDING_CONFIRM') return 'is-attention'
  if (status === 'PARSING' || status === 'PENDING') return 'is-processing'
  if (status === 'PARSE_FAILED') return 'is-failed'
  return 'is-unknown'
}

function statusHeadline(status: string) {
  if (status === 'CONFIRMED') return '岗位画像已经就绪'
  if (status === 'PENDING_CONFIRM') return '岗位画像等待确认'
  if (status === 'PARSING' || status === 'PENDING') return 'AI 正在分析岗位要求'
  if (status === 'PARSE_FAILED') return '本次岗位解析未完成'
  return '岗位状态等待同步'
}

function statusDescription(status: string) {
  if (status === 'CONFIRMED') return '能力要求与考察方向已经确认，可以直接用于简历匹配和模拟面试。'
  if (status === 'PENDING_CONFIRM') return '画像已经生成，确认后即可作为稳定的岗位判断依据。'
  if (status === 'PARSING' || status === 'PENDING') return '系统正在从 JD 中提取技能要求、优先级与面试重点。'
  if (status === 'PARSE_FAILED') return '岗位原文仍然保留，可以从更多操作中重新提交解析。'
  return '稍后刷新页面，查看最新的岗位画像状态。'
}

function profileStepClass(status: string) {
  if (status === 'CONFIRMED' || status === 'PENDING_CONFIRM') return 'is-completed'
  if (status === 'PARSING' || status === 'PENDING') return 'is-current'
  if (status === 'PARSE_FAILED') return 'is-failed'
  return 'is-pending'
}

function profileStepText(status: string) {
  if (status === 'CONFIRMED' || status === 'PENDING_CONFIRM') return '已生成'
  if (status === 'PARSING' || status === 'PENDING') return '分析中'
  if (status === 'PARSE_FAILED') return '需要重试'
  return '等待同步'
}

function interviewStepClass(status: string) {
  if (status === 'CONFIRMED') return 'is-completed'
  if (status === 'PENDING_CONFIRM') return 'is-current'
  return 'is-pending'
}
</script>

<style scoped>
.position-page {
  --position-sidebar-width: 344px;

  padding-top: 32px;
  padding-bottom: 40px;
}

.position-overview {
  display: grid;
  min-height: 116px;
  overflow: hidden;
  grid-template-columns: minmax(620px, 2.2fr) repeat(3, minmax(170px, 0.7fr));
  border: 1px solid var(--color-border);
  border-top: 2px solid var(--color-ink);
  border-radius: var(--radius-panel);
  margin-bottom: 18px;
  background: var(--color-surface);
}

.overview-lead,
.overview-metric {
  display: flex;
  min-width: 0;
  padding: 18px 24px;
}

.overview-lead {
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: var(--color-surface-subtle);
}

.overview-lead-copy {
  min-width: 0;
}

.overview-lead-copy > span,
.library-heading > span,
.detail-kicker > span:first-child,
.content-heading span,
.detail-note > span,
.position-empty > span,
.readiness-panel > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
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

.overview-lead .el-button {
  min-width: 132px;
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
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
}

.overview-metric small {
  margin-top: 5px;
  color: var(--color-muted);
  font-size: 12px;
}

.overview-metric--accent dd {
  color: var(--color-brand-600);
}

.position-workspace {
  overflow: hidden;
  min-height: 580px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
}

.library-toolbar {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--color-border);
  padding: 14px 20px;
}

.position-tabs {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  padding: 3px;
  background: var(--color-surface-subtle);
}

.position-tabs button {
  min-width: 104px;
  min-height: 36px;
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: 5px;
  padding: 7px 16px;
  color: var(--color-muted);
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  transition: color var(--motion-fast) var(--ease-standard),
    border-color var(--motion-fast) var(--ease-standard),
    background-color var(--motion-fast) var(--ease-standard);
}

.position-tabs button:hover {
  color: var(--color-ink);
}

.position-tabs button.is-active {
  border-color: var(--color-border);
  color: var(--color-ink);
  background: var(--color-surface);
}

.toolbar-search {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}

.toolbar-search .el-input {
  width: 300px;
}

.toolbar-search > span {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.workspace-body {
  display: grid;
  min-height: 506px;
  grid-template-columns: var(--position-sidebar-width) minmax(0, 1fr);
}

.position-library {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.library-heading {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--color-border);
  padding: 12px 18px;
}

.library-heading > span {
  color: var(--color-ink);
}

.library-heading small {
  color: var(--color-muted);
  font-size: 11px;
}

.position-list {
  max-height: 656px;
  overflow-y: auto;
}

.position-list-item {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 132px;
  cursor: pointer;
  flex-direction: column;
  justify-content: space-between;
  gap: 11px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  padding: 16px 18px 14px;
  color: var(--color-body);
  background: transparent;
  text-align: left;
  transition: color var(--motion-base) var(--ease-standard),
    background-color var(--motion-base) var(--ease-standard);
}

.position-list-item::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  content: '';
  background: transparent;
}

.position-list-item:hover {
  background: #f0f0ed;
}

.position-list-item.is-active {
  color: var(--color-ink);
  background: var(--color-surface);
}

.position-list-item.is-active::before {
  background: var(--color-brand-500);
}

.position-item-topline,
.position-item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.position-code {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
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

.status-label.is-confirmed i {
  background: var(--color-success);
}

.status-label.is-attention i {
  background: var(--color-warning);
}

.status-label.is-processing i {
  background: var(--color-brand-500);
  box-shadow: 0 0 0 3px var(--color-brand-100);
}

.status-label.is-failed i {
  background: var(--color-danger);
}

.position-item-copy {
  min-width: 0;
}

.position-item-copy strong,
.position-item-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.position-item-copy strong {
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
  line-height: 1.35;
}

.position-item-copy small {
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 12px;
}

.position-item-footer {
  color: var(--color-muted);
  font-size: 11px;
}

.position-item-footer > span:last-child {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 600;
}

.position-item-footer .el-icon {
  transition: transform var(--motion-fast) var(--ease-standard);
}

.position-list-item:hover .position-item-footer .el-icon {
  transform: translateX(2px);
}

.position-detail {
  min-width: 0;
  padding: 24px 28px 26px;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 20px;
}

.detail-heading-copy {
  min-width: 0;
}

.detail-kicker {
  display: flex;
  align-items: center;
  gap: 14px;
}

.detail-kicker > span:first-child {
  color: var(--color-muted);
}

.detail-heading-copy h2 {
  margin-top: 8px;
  color: var(--color-ink);
  font-size: 25px;
  font-weight: 720;
  line-height: 1.25;
}

.detail-heading-copy p {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  color: var(--color-muted);
  font-size: 12px;
}

.detail-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.detail-actions .el-button + .el-button {
  margin-left: 0;
}

.more-action {
  width: 40px;
  height: 40px;
}

.position-facts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.position-facts > div {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  padding: 15px 16px;
}

.position-facts > div:last-child {
  border-right: 0;
}

.position-facts dt {
  color: var(--color-muted);
  font-size: 11px;
}

.position-facts dd {
  overflow: hidden;
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.position-brief-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(290px, 0.58fr);
  gap: 20px;
  margin-top: 22px;
}

.jd-document {
  min-width: 0;
  border-top: 2px solid var(--color-ink);
  padding-top: 16px;
}

.content-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.content-heading span {
  color: var(--color-muted);
}

.content-heading h3 {
  margin-top: 3px;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
}

.content-heading small {
  color: var(--color-muted);
  font-size: 11px;
}

.jd-content {
  max-height: 254px;
  overflow-y: auto;
  margin-top: 14px;
  color: var(--color-body);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.jd-empty {
  display: flex;
  min-height: 186px;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  margin-top: 12px;
  color: var(--color-muted);
}

.jd-empty .el-icon {
  font-size: 25px;
}

.jd-empty strong {
  margin-top: 10px;
  color: var(--color-ink);
  font-size: 14px;
}

.jd-empty p {
  max-width: 420px;
  margin-top: 5px;
  font-size: 12px;
}

.readiness-panel {
  display: flex;
  min-width: 0;
  min-height: 286px;
  flex-direction: column;
  border-radius: var(--radius-panel);
  padding: 22px 24px;
  color: var(--color-on-console);
  background: var(--color-console);
}

.readiness-panel > span {
  color: #df7468;
}

.readiness-panel > h3 {
  margin-top: 10px;
  color: var(--color-on-console);
  font-size: 18px;
  font-weight: 680;
}

.readiness-panel > p {
  margin-top: 7px;
  color: #bbb9b1;
  font-size: 12px;
  line-height: 1.6;
}

.readiness-steps {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 18px 0;
  list-style: none;
}

.readiness-steps li {
  display: flex;
  align-items: center;
  gap: 10px;
}

.readiness-steps li > i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border: 1px solid #686862;
  border-radius: 50%;
}

.readiness-steps li > span {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.readiness-steps strong {
  color: #f3f2ed;
  font-size: 12px;
  font-weight: 600;
}

.readiness-steps small {
  color: #94938d;
  font-size: 11px;
}

.readiness-steps li.is-completed > i {
  border-color: #58a97a;
  background: #58a97a;
}

.readiness-steps li.is-current > i {
  border-color: var(--color-brand-500);
  background: var(--color-brand-500);
  box-shadow: 0 0 0 3px rgba(233, 76, 58, 0.2);
}

.readiness-steps li.is-failed > i {
  border-color: #df7468;
  background: #df7468;
}

.profile-entry {
  width: 100%;
  margin-top: auto;
}

.readiness-panel :deep(.profile-entry) {
  --el-button-text-color: var(--color-on-console);
  --el-button-bg-color: transparent;
  --el-button-border-color: #565650;
  --el-button-hover-text-color: var(--color-on-console);
  --el-button-hover-bg-color: #242421;
  --el-button-hover-border-color: #74746d;
}

.detail-note {
  border-top: 1px solid var(--color-border);
  margin-top: 22px;
  padding: 17px 0 0 14px;
  border-left: 2px solid var(--color-brand-500);
}

.detail-note strong {
  display: block;
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 680;
}

.detail-note p {
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 12px;
}

.position-empty {
  display: flex;
  min-height: 506px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  text-align: center;
}

.position-empty > .el-icon {
  width: 54px;
  height: 54px;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  margin-bottom: 18px;
  color: var(--color-muted);
  font-size: 22px;
  background: var(--color-surface-subtle);
}

.position-empty h2 {
  margin-top: 10px;
  color: var(--color-ink);
  font-size: 21px;
}

.position-empty p {
  max-width: 440px;
  margin: 8px 0 20px;
  color: var(--color-muted);
  font-size: 13px;
}

@media (max-width: 1199px) {
  .position-page {
    --position-sidebar-width: 300px;
  }

  .position-overview {
    grid-template-columns: minmax(420px, 1.7fr) repeat(3, minmax(135px, 0.7fr));
  }

  .overview-heading p {
    white-space: normal;
    text-wrap: balance;
  }

  .overview-lead,
  .overview-metric {
    padding-right: 18px;
    padding-left: 18px;
  }

  .position-detail {
    padding-right: 22px;
    padding-left: 22px;
  }

  .detail-actions {
    max-width: 410px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .position-brief-grid {
    grid-template-columns: minmax(0, 1fr) 280px;
  }

  .position-facts > div {
    padding-right: 12px;
    padding-left: 12px;
  }
}

@media (min-width: 1024px) and (max-height: 1100px) {
  .position-page {
    padding-top: 24px;
    padding-bottom: 24px;
  }

  .position-overview {
    min-height: 104px;
    margin-bottom: 14px;
  }

  .overview-lead,
  .overview-metric {
    padding: 14px 20px;
  }

  .position-workspace {
    min-height: 0;
  }

  .library-toolbar {
    min-height: 62px;
    padding-top: 9px;
    padding-bottom: 9px;
  }

  .workspace-body {
    min-height: 0;
  }

  .library-heading {
    min-height: 46px;
    padding-top: 9px;
    padding-bottom: 9px;
  }

  .position-list-item {
    min-height: 122px;
    padding-top: 13px;
    padding-bottom: 12px;
  }

  .position-detail {
    padding-top: 18px;
    padding-bottom: 18px;
  }

  .detail-header {
    padding-bottom: 14px;
  }

  .detail-heading-copy h2 {
    margin-top: 5px;
    font-size: 23px;
  }

  .detail-heading-copy p {
    margin-top: 3px;
  }

  .position-facts > div {
    padding-top: 11px;
    padding-bottom: 11px;
  }

  .position-brief-grid {
    margin-top: 16px;
  }

  .jd-document {
    padding-top: 12px;
  }

  .jd-content {
    max-height: 206px;
    margin-top: 10px;
  }

  .jd-empty {
    min-height: 150px;
  }

  .readiness-panel {
    min-height: 244px;
    padding: 17px 20px;
  }

  .readiness-panel > h3 {
    margin-top: 7px;
  }

  .readiness-panel > p {
    margin-top: 4px;
  }

  .readiness-steps {
    gap: 9px;
    margin: 12px 0;
  }

  .detail-note {
    margin-top: 16px;
    padding-top: 12px;
  }
}
</style>

<style>
.danger-command {
  color: var(--color-danger) !important;
}

.position-create-dialog,
.position-profile-dialog {
  overflow: hidden;
  border-top: 2px solid var(--color-ink);
}

.position-create-dialog .el-dialog__header,
.position-profile-dialog .el-dialog__header {
  margin-right: 0;
  border-bottom: 1px solid var(--color-border);
  padding: 24px 28px 20px;
}

.dialog-heading > span,
.jd-input-section > header span,
.profile-section-heading span,
.profile-loading-state > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.dialog-heading h2 {
  margin: 6px 0 0;
  color: var(--color-ink);
  font-size: 23px;
  font-weight: 720;
  line-height: 1.25;
}

.dialog-heading p {
  margin: 6px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}

.position-create-dialog .el-dialog__body {
  max-height: 68vh;
  overflow-y: auto;
  padding: 22px 28px 8px;
}

.position-create-dialog .el-dialog__footer,
.position-profile-dialog .el-dialog__footer {
  border-top: 1px solid var(--color-border);
  padding: 16px 28px 20px;
}

.create-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.jd-input-section {
  border-top: 1px solid var(--color-border);
  margin-top: 6px;
  padding-top: 18px;
}

.jd-input-section > header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 14px;
}

.jd-input-section > header strong {
  display: block;
  margin-top: 3px;
  color: var(--color-ink);
  font-size: 15px;
}

.jd-input-section > header small {
  color: var(--color-muted);
  font-size: 11px;
}

.upload-row {
  display: flex;
  min-height: 62px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid var(--color-border);
  padding: 14px 0 2px;
}

.upload-row > div > strong {
  color: var(--color-ink);
  font-size: 13px;
}

.upload-row > div > p {
  margin: 3px 0 0;
  color: var(--color-muted);
  font-size: 11px;
}

.upload-row .el-upload-list {
  max-width: 360px;
}

.dialog-footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.dialog-footer-actions .el-button + .el-button {
  margin-left: 0;
}

.position-profile-dialog .el-dialog__body {
  max-height: 68vh;
  overflow-y: auto;
  padding: 0 28px 24px;
}

.profile-loading-state,
.profile-empty-state {
  display: flex;
  min-height: 360px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.profile-loading-state > .el-icon,
.profile-empty-state > .el-icon {
  width: 54px;
  height: 54px;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  margin-bottom: 16px;
  color: var(--color-brand-600);
  font-size: 22px;
  background: var(--color-surface-subtle);
}

.profile-loading-state h3,
.profile-empty-state h3 {
  margin: 8px 0 0;
  color: var(--color-ink);
  font-size: 19px;
}

.profile-loading-state p,
.profile-empty-state p {
  margin: 6px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}

.profile-loading-state .is-rotating {
  animation: position-profile-rotate 1s linear infinite;
}

@keyframes position-profile-rotate {
  to {
    transform: rotate(360deg);
  }
}

.profile-facts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.profile-facts > div {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  padding: 16px;
}

.profile-facts > div:last-child {
  border-right: 0;
}

.profile-facts dt {
  color: var(--color-muted);
  font-size: 11px;
}

.profile-facts dd {
  overflow: hidden;
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(260px, 0.75fr);
  gap: 28px;
  padding-top: 24px;
}

.profile-main,
.profile-aside {
  min-width: 0;
}

.profile-aside {
  border-left: 1px solid var(--color-border);
  padding-left: 24px;
}

.profile-section + .profile-section {
  border-top: 1px solid var(--color-border);
  margin-top: 24px;
  padding-top: 22px;
}

.profile-section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.profile-section-heading span {
  color: var(--color-muted);
}

.profile-section-heading h3 {
  margin: 3px 0 0;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 680;
}

.profile-section-heading > small {
  color: var(--color-muted);
  font-size: 11px;
}

.skill-requirements {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--color-border);
  border-left: 1px solid var(--color-border);
}

.skill-requirement-item {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 12px;
  border-right: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
  padding: 12px 14px;
}

.skill-requirement-item strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-requirement-item span {
  color: var(--color-brand-600);
  font-size: 11px;
  font-weight: 600;
}

.skill-requirement-item small {
  grid-column: 1 / -1;
  color: var(--color-muted);
  font-size: 11px;
}

.probing-list {
  border-top: 1px solid var(--color-border);
}

.probing-list article {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
  border-bottom: 1px solid var(--color-border);
  padding: 14px 0;
}

.probing-list article > span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
}

.probing-list article header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.probing-list article header strong {
  color: var(--color-ink);
  font-size: 13px;
}

.probing-list article header small {
  color: var(--color-muted);
  font-size: 10px;
  white-space: nowrap;
}

.probing-list ul {
  margin: 8px 0 0;
  padding-left: 16px;
  color: var(--color-body);
  font-size: 12px;
  line-height: 1.65;
}

.compact-skill-list,
.focus-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.compact-skill-list li {
  border-bottom: 1px solid var(--color-border);
  padding: 10px 0;
}

.compact-skill-list li:first-child {
  padding-top: 0;
}

.compact-skill-list strong,
.compact-skill-list small {
  display: block;
}

.compact-skill-list strong {
  color: var(--color-ink);
  font-size: 13px;
}

.compact-skill-list small {
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 11px;
}

.focus-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.focus-list li {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 9px;
}

.focus-list li > span {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
}

.focus-list p {
  color: var(--color-body);
  font-size: 12px;
  line-height: 1.55;
}

.profile-empty-copy {
  color: var(--color-muted);
  font-size: 12px;
}

@media (prefers-reduced-motion: reduce) {
  .profile-loading-state .is-rotating {
    animation: none;
  }
}
</style>
