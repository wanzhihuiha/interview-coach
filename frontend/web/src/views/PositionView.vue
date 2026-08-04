<template>
  <AppLayout>
    <main class="page-container position-page">
      <header class="position-overview" aria-label="岗位库概览">
        <div class="overview-lead">
          <div class="overview-lead-copy">
            <span>POSITION INTELLIGENCE</span>
            <div class="overview-heading">
              <h1 class="page-title">
                {{ isPublicTab ? '公共岗位库' : isArchivedTab ? '已归档岗位' : '我的目标岗位' }}
              </h1>
              <p>
                {{ isPublicTab
                  ? '选择标准岗位画像，快速开始简历分析与模拟面试。'
                  : isArchivedTab
                    ? '查看已下架的个人岗位，并在确认无历史占用后永久删除。'
                    : '沉淀岗位要求、能力标准与考察重点，支撑一致的人才判断。' }}
              </p>
            </div>
          </div>
          <el-button v-if="!isPublicTab && !isArchivedTab" size="large" :icon="Plus" @click="openCreateDialog">
            新增岗位
          </el-button>
        </div>
        <dl class="overview-metric">
          <dt>当前岗位</dt>
          <dd>{{ positions.length }}</dd>
          <small>{{ isPublicTab ? '可访问岗位' : isArchivedTab ? '归档记录' : '个人岗位档案' }}</small>
        </dl>
        <dl class="overview-metric">
          <dt>正式画像可用</dt>
          <dd>{{ confirmedPositionCount }}</dd>
          <small>可直接用于面试准备</small>
        </dl>
        <dl
          class="overview-metric"
          :class="{ 'overview-metric--accent': attentionPositionCount > 0 }"
        >
          <dt>需要关注</dt>
          <dd>{{ attentionPositionCount }}</dd>
          <small>准备中、待确认或解析失败</small>
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
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'archived'"
              :class="{ 'is-active': activeTab === 'archived' }"
              @click="changeTab('archived')"
            >
              已归档
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
              <span>{{ isPublicTab ? 'PUBLIC POSITIONS' : isArchivedTab ? 'ARCHIVED POSITIONS' : 'MY POSITIONS' }}</span>
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
                  <span class="status-label" :class="statusClass(position)">
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
                  <span class="status-label" :class="statusClass(selectedPosition)">
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
                  v-if="selectedPosition.canConfirm && !isPublicTab && !isArchivedTab"
                  :icon="CircleCheck"
                  @click="confirm(selectedPosition)"
                >
                  确认画像
                </el-button>
                <el-button
                  type="primary"
                  :icon="VideoPlay"
                  :disabled="!selectedPosition.profileUsable || selectedPosition.archived"
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
                      <el-dropdown-item
                        v-if="!isArchivedTab"
                        command="reparse"
                        :disabled="!selectedPosition.canRetry"
                      >重新解析</el-dropdown-item>
                      <el-dropdown-item v-if="!isArchivedTab" command="archive">归档岗位</el-dropdown-item>
                      <el-dropdown-item v-else command="delete" class="danger-command">永久删除</el-dropdown-item>
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
                <h3>{{ statusHeadline(selectedPosition) }}</h3>
                <p>{{ statusDescription(selectedPosition) }}</p>

                <ol class="readiness-steps" aria-label="岗位准备进度">
                  <li class="is-completed">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>岗位信息</strong>
                      <small>已收录</small>
                    </span>
                  </li>
                  <li :class="profileStepClass(selectedPosition)">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>AI 岗位画像</strong>
                      <small>{{ profileStepText(selectedPosition) }}</small>
                    </span>
                  </li>
                  <li :class="interviewStepClass(selectedPosition)">
                    <i aria-hidden="true"></i>
                    <span>
                      <strong>面试准备</strong>
                      <small>{{ selectedPosition.profileUsable ? '可以开始' : '等待正式画像' }}</small>
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
              : isPublicTab ? '暂时没有可用的公共岗位' : isArchivedTab ? '没有已归档岗位' : '还没有目标岗位' }}
          </h2>
          <p>
            {{ positions.length
              ? '尝试更换岗位名称或公司关键词。'
              : isPublicTab
                ? '公共岗位更新后会展示在这里。'
                : isArchivedTab
                  ? '归档后的个人岗位会展示在这里。'
                  : '新增岗位 JD 后，职衡会自动提取能力要求与考察重点。' }}
          </p>
          <el-button v-if="positions.length" :icon="RefreshLeft" @click="filterKeyword = ''">清除搜索</el-button>
          <el-button v-else-if="!isPublicTab && !isArchivedTab" type="primary" :icon="Plus" @click="openCreateDialog">新增岗位</el-button>
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
          <div class="create-form-grid" :class="{ 'is-single': inputMode === 'file' }">
            <el-form-item label="岗位名称" prop="positionName">
              <el-input v-model="form.positionName" placeholder="例如：Java 高级工程师" />
            </el-form-item>
            <el-form-item v-if="inputMode === 'text'" label="公司">
              <el-input v-model="form.companyName" placeholder="例如：某科技公司" />
            </el-form-item>
          </div>

          <el-form-item v-if="inputMode === 'text'" label="岗位大类" prop="jobCategory">
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
              <el-radio-group v-model="inputMode" size="small" @change="handleInputModeChange">
                <el-radio-button label="text">粘贴文本</el-radio-button>
                <el-radio-button label="file">上传文件</el-radio-button>
              </el-radio-group>
            </header>
            <el-form-item v-if="inputMode === 'text'" label="JD 描述" prop="jdContent">
              <el-input
                v-model="form.jdContent"
                type="textarea"
                :rows="7"
                resize="none"
                placeholder="粘贴完整岗位职责、任职要求和加分项"
              />
              <div class="jd-limit" :class="{ 'is-over': jdCodePointCount > JD_MAX_CODE_POINTS }">
                <span>Unicode 完整字符</span>
                <strong>{{ jdCodePointCount }} / {{ JD_MAX_CODE_POINTS }}</strong>
              </div>
            </el-form-item>
            <div v-else class="upload-row">
              <div>
                <strong>上传 JD 文件</strong>
                <p>PDF / UTF-8 TXT，最大 10 MiB；PDF 最多 20 页</p>
                <small v-if="fileValidating">正在校验文件内容...</small>
                <small v-else-if="fileValidation" class="file-validation-success">
                  已读取 {{ fileValidation.codePointCount }} 个完整字符
                  <template v-if="fileValidation.pdfPages"> / {{ fileValidation.pdfPages }} 页</template>
                </small>
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
            <el-button
              type="primary"
              :loading="submitting || fileValidating"
              @click="submitPosition"
            >创建并解析</el-button>
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
            <p>{{ profileDialogDescription }}</p>
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
              v-if="currentProfileResponse?.canConfirm"
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
  getPositionAnalysisStatus,
  getPositionDetail,
  getPositionList,
  getPositionProfile,
  getPublicPositionList,
  loadAllPositionPages,
  archivePosition,
  reparsePosition,
  uploadPosition
} from '@/api/position'
import type {
  Position,
  PositionAnalysisStatus,
  PositionProfileData,
  PositionProfileResponse,
  ProbingDirection
} from '@/types'
import type { FormInstance, FormRules, UploadFile, UploadInstance } from 'element-plus'
import { usePositionAnalysisPolling, isPositionTaskActive } from '@/composables/usePositionAnalysisPolling'
import {
  countUnicodeCodePoints,
  JD_MAX_CODE_POINTS,
  normalizeJdContent,
  validateJdFile,
  validateJdText,
  type JdFileValidationResult
} from '@/utils/jdFileValidation'

type PositionTab = 'mine' | 'public' | 'archived'
type PositionActionType = 'reparse' | 'archive' | 'delete'
type JdInputMode = 'text' | 'file'

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
  archive: {
    title: '归档这个目标岗位？',
    description: '归档会立即停止公开或个人工作区中的使用，但不会删除历史面试快照。',
    confirmText: '归档岗位',
    cancelText: '保留岗位',
    loadingText: '正在归档',
    impact: [
      { label: '岗位状态', value: '从活动列表移入归档' },
      { label: '历史面试', value: '创建时快照继续保留' }
    ]
  },
  delete: {
    title: '删除这个目标岗位？',
    description: '仅当岗位已归档且没有进行中的面试时才能永久删除，删除后无法恢复。',
    confirmText: '永久删除',
    cancelText: '保留归档',
    loadingText: '正在永久删除',
    impact: [
      { label: '档案内容', value: '岗位、候选画像和终态任务一并移除' },
      { label: '恢复方式', value: '删除后无法恢复' }
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
const inputMode = ref<JdInputMode>('text')
const showCreateDialog = ref(false)
const showProfileDialog = ref(false)
const profileLoading = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const uploadRef = ref<UploadInstance>()
const currentPosition = ref<Position | null>(null)
const currentProfile = ref<PositionProfileData | null>(null)
const currentProfileResponse = ref<PositionProfileResponse | null>(null)
const currentFile = ref<File | null>(null)
const fileValidation = ref<JdFileValidationResult | null>(null)
const fileValidating = ref(false)
const positionActionVisible = ref(false)
const positionActionLoading = ref(false)
const pendingPositionAction = ref<PendingPositionAction | null>(null)
let profileRequestSequence = 0
let detailRequestSequence = 0
let fileValidationSequence = 0
// 轮询与显式操作可能并发，请求发出顺序和成功提交顺序必须分开记录。
let listRequestSequence = 0
let latestCommittedListRequestSequence = 0
let positionListRevision = 0
let selectionRevision = 0
let tabRevision = 0

const form = ref({
  positionName: '',
  companyName: '',
  jobCategory: '',
  jdContent: ''
})

const formRules: FormRules = {
  positionName: [{ required: true, message: '请输入岗位名称', trigger: 'blur' }],
  jobCategory: [{
    validator: (_rule, value: string, callback) => {
      if (inputMode.value === 'text' && !value) {
        callback(new Error('请选择岗位大类'))
        return
      }
      callback()
    },
    trigger: 'change'
  }],
  jdContent: [{
    validator: (_rule, value: string, callback) => {
      if (inputMode.value !== 'text') {
        callback()
        return
      }
      try {
        validateJdText(value || '')
        callback()
      } catch (error) {
        callback(error as Error)
      }
    },
    trigger: 'blur'
  }]
}

const isPublicTab = computed(() => activeTab.value === 'public')
const isArchivedTab = computed(() => activeTab.value === 'archived')
const jdCodePointCount = computed(() => countUnicodeCodePoints(normalizeJdContent(form.value.jdContent || '')))
const profileDialogDescription = computed(() => {
  if (currentProfileResponse.value?.canConfirm) {
    return '这是本次解析生成的候选画像；确认后才会替换正式岗位画像。'
  }
  return '从岗位原文中提取的正式能力要求、考察深度与面试重点。'
})

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
  return positions.value.filter(position => position.profileUsable).length
})

const attentionPositionCount = computed(() => {
  return positions.value.filter(position => {
    return position.canConfirm
      || (position.canRetry && position.latestTaskStatus === 'FAILED')
      || isPositionTaskActive(position.latestTaskStatus)
  }).length
})

const sortedProbingDirections = computed<ProbingDirection[]>(() => {
  const directions = currentProfile.value?.probingDirections || []
  return [...directions].sort((left, right) => left.priority - right.priority)
})

const positionActionContent = computed(() => {
  return POSITION_ACTION_CONTENT[pendingPositionAction.value?.type || 'reparse']
})

const polling = usePositionAnalysisPolling({
  fetchStatus: getPositionAnalysisStatus,
  onStatus: handleAnalysisStatus
})

onMounted(() => {
  void loadPositions()
})

async function loadPositions(
  preferredPositionId?: number | null,
  invalidatePendingResults = false
): Promise<boolean> {
  if (invalidatePendingResults) positionListRevision += 1
  const requestSequence = ++listRequestSequence
  const requestedTab = activeTab.value
  const tabRevisionAtStart = tabRevision
  const positionListRevisionAtStart = positionListRevision
  const selectionRevisionAtStart = selectionRevision
  const requestedSelectionId = preferredPositionId === undefined
    ? selectedId.value
    : preferredPositionId
  loading.value = true
  try {
    const nextPositions = requestedTab === 'mine'
      ? await loadAllPositionPages((page, size) => getPositionList({ page, size }))
      : requestedTab === 'archived'
        ? await loadAllPositionPages((page, size) => getPositionList({ page, size, archived: true }))
        : await loadAllPositionPages((page, size) => getPublicPositionList({ page, size }))
    if (
      activeTab.value !== requestedTab
      || tabRevision !== tabRevisionAtStart
      || positionListRevision !== positionListRevisionAtStart
      || requestSequence < latestCommittedListRequestSequence
    ) return true
    latestCommittedListRequestSequence = requestSequence
    positions.value = nextPositions
    const shouldApplyRequestedSelection = selectionRevision === selectionRevisionAtStart
    const selectionId = shouldApplyRequestedSelection
      ? requestedSelectionId
      : selectedId.value
    const preferredExists = selectionId !== null
      && positions.value.some(position => position.positionId === selectionId)
    selectedId.value = preferredExists
      ? selectionId
      : positions.value[0]?.positionId ?? null
    if (shouldApplyRequestedSelection && preferredPositionId !== undefined) {
      selectionRevision += 1
    }
    syncPositionPolling(requestedTab, positions.value)
    if (selectedId.value !== null) void loadPositionDetail(selectedId.value)
    return true
  } catch (error) {
    if (
      activeTab.value !== requestedTab
      || tabRevision !== tabRevisionAtStart
      || positionListRevision !== positionListRevisionAtStart
      || requestSequence < latestCommittedListRequestSequence
    ) return true
    ElMessage.error((error as Error).message || '岗位列表加载失败')
    return false
  } finally {
    loading.value = false
  }
}

async function changeTab(tab: PositionTab) {
  if (activeTab.value === tab || loading.value) return
  polling.stopAll()
  const previousTab = activeTab.value
  const previousKeyword = filterKeyword.value
  const previousSelectedId = selectedId.value
  const previousPositions = [...positions.value]
  tabRevision += 1
  activeTab.value = tab
  filterKeyword.value = ''
  selectionRevision += 1
  selectedId.value = null
  if (!await loadPositions(null)) {
    tabRevision += 1
    activeTab.value = previousTab
    filterKeyword.value = previousKeyword
    selectionRevision += 1
    positions.value = previousPositions
    selectedId.value = previousSelectedId
    syncPositionPolling(previousTab, previousPositions)
  }
}

function syncPositionPolling(tab: PositionTab, nextPositions: Position[]) {
  polling.sync(
    tab === 'public'
      ? []
      : nextPositions
        .filter(position => isPositionTaskActive(position.latestTaskStatus))
        .map(position => position.positionId)
  )
}

function selectPosition(position: Position) {
  selectionRevision += 1
  selectedId.value = position.positionId
  void loadPositionDetail(position.positionId)
}

function selectForInterview(positionId: number) {
  const position = positions.value.find(item => item.positionId === positionId)
  if (!position?.profileUsable || position.archived) {
    ElMessage.warning('正式岗位画像就绪后才能开始面试')
    return
  }
  router.push({ path: '/interview/config', query: { positionId: String(positionId) } })
}

function openCreateDialog() {
  form.value = { positionName: '', companyName: '', jobCategory: '', jdContent: '' }
  inputMode.value = 'text'
  currentFile.value = null
  fileValidation.value = null
  fileValidating.value = false
  uploadRef.value?.clearFiles()
  showCreateDialog.value = true
}

function resetCreateDialog() {
  formRef.value?.clearValidate()
  fileValidationSequence += 1
  fileValidating.value = false
  currentFile.value = null
  fileValidation.value = null
  uploadRef.value?.clearFiles()
}

function handleInputModeChange() {
  formRef.value?.clearValidate()
  fileValidationSequence += 1
  fileValidating.value = false
  fileValidation.value = null
  currentFile.value = null
  uploadRef.value?.clearFiles()
  if (inputMode.value === 'file') {
    form.value.jdContent = ''
    form.value.companyName = ''
    form.value.jobCategory = ''
  }
}

async function handleFileChange(uploadFile: UploadFile) {
  const file = uploadFile.raw
  fileValidationSequence += 1
  const sequence = fileValidationSequence
  currentFile.value = null
  fileValidation.value = null
  if (!file) return
  fileValidating.value = true
  try {
    const result = await validateJdFile(file)
    if (sequence !== fileValidationSequence) return
    currentFile.value = file
    fileValidation.value = result
    formRef.value?.clearValidate('jdContent')
  } catch (error) {
    if (sequence !== fileValidationSequence) return
    uploadRef.value?.clearFiles()
    ElMessage.error((error as Error).message || '文件校验失败')
  } finally {
    if (sequence === fileValidationSequence) fileValidating.value = false
  }
}

function handleFileRemove() {
  fileValidationSequence += 1
  fileValidating.value = false
  fileValidation.value = null
  currentFile.value = null
  formRef.value?.clearValidate('jdContent')
}

async function submitPosition() {
  if (!formRef.value) return
  if (fileValidating.value) {
    ElMessage.info('文件仍在校验，请稍候')
    return
  }
  if (inputMode.value === 'file' && !currentFile.value) {
    ElMessage.warning('请选择并通过校验一个 JD 文件')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const result = inputMode.value === 'file' && currentFile.value
      ? await uploadPosition(currentFile.value, form.value.positionName)
      : await createPosition({
        ...form.value,
        jdContent: normalizeJdContent(form.value.jdContent)
      })
    showCreateDialog.value = false
    ElMessage.success('新增成功，AI 正在解析岗位画像')
    await loadPositions(result.positionId, true)
    polling.start(result.positionId)
  } catch (error) {
    ElMessage.error((error as Error).message || '新增失败')
  } finally {
    submitting.value = false
  }
}

async function handleAnalysisStatus(status: PositionAnalysisStatus) {
  const index = positions.value.findIndex(position => position.positionId === status.positionId)
  if (index < 0) return
  const previous = positions.value[index]
  positions.value[index] = {
    ...previous,
    latestTaskId: status.taskId,
    latestTaskStatus: status.latestTaskStatus,
    latestTaskStatusLabel: status.latestTaskStatusLabel,
    queueAhead: status.queueAhead,
    analysisErrorCode: status.analysisErrorCode,
    analysisErrorMessage: status.analysisErrorMessage,
    profileUsable: status.profileUsable,
    canConfirm: status.canConfirm,
    canRetry: status.canRetry,
    archived: status.archived
  }
  if (status.latestTaskStatus === 'SUCCEEDED') {
    await loadPositions()
    if (status.canConfirm) ElMessage.success('岗位候选画像已生成，请查看并确认')
  } else if (status.latestTaskStatus === 'FAILED') {
    await loadPositions()
    ElMessage.error(formatAnalysisFailure(status.analysisErrorCode, status.analysisErrorMessage))
  }
}

async function loadPositionDetail(positionId: number) {
  const sequence = ++detailRequestSequence
  try {
    const detail = await getPositionDetail(positionId)
    if (sequence !== detailRequestSequence) return
    const index = positions.value.findIndex(position => position.positionId === positionId)
    if (index >= 0) {
      positions.value[index] = {
        ...positions.value[index],
        ...detail,
        queueAhead: positions.value[index].queueAhead
      }
    }
  } catch {
    // 列表字段已经足够展示，详情字段失败时不打断主列表。
  }
}

async function openDetail(position: Position) {
  const requestSequence = ++profileRequestSequence
  currentPosition.value = position
  currentProfile.value = null
  currentProfileResponse.value = null
  profileLoading.value = true
  showProfileDialog.value = true
  try {
    const response = await getPositionProfile(position.positionId)
    if (requestSequence !== profileRequestSequence || !showProfileDialog.value) return
    currentProfileResponse.value = response
    currentProfile.value = response.canConfirm
      ? response.candidateProfile || null
      : response.profile || null
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
  currentProfileResponse.value = null
  profileLoading.value = false
}

async function loadCandidateProfile(position: Position): Promise<PositionProfileResponse | null> {
  try {
    const response = await getPositionProfile(position.positionId)
    if (!response.canConfirm || !response.candidateProfile || !response.taskId) {
      ElMessage.warning('当前没有可确认的候选画像')
      return null
    }
    return response
  } catch (error) {
    ElMessage.error((error as Error).message || '获取候选画像失败')
    return null
  }
}

async function confirm(position: Position) {
  const response = await loadCandidateProfile(position)
  if (!response?.candidateProfile || !response.taskId) {
    return
  }
  try {
    await confirmPosition(position.positionId, response.taskId, response.candidateProfile)
    ElMessage.success('确认成功')
    await loadPositions(position.positionId, true)
  } catch (error) {
    ElMessage.error((error as Error).message || '确认失败')
  }
}

async function confirmFromDialog() {
  const position = currentPosition.value
  const profile = currentProfile.value
  const taskId = currentProfileResponse.value?.taskId
  if (!position || !profile || !taskId) return
  try {
    await confirmPosition(position.positionId, taskId, profile)
    ElMessage.success('确认成功')
    showProfileDialog.value = false
    await loadPositions(position.positionId, true)
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
      const result = await reparsePosition(pending.positionId)
      ElMessage.success('重新解析已提交')
      await loadPositions(pending.positionId, true)
      polling.start(result.positionId)
    } else if (pending.type === 'archive') {
      await archivePosition(pending.positionId)
      ElMessage.success('岗位已归档')
      await loadPositions(null, true)
    } else {
      await deletePosition(pending.positionId)
      ElMessage.success('岗位已永久删除')
      await loadPositions(null, true)
    }
    positionActionVisible.value = false
  } catch (error) {
    const fallback = pending.type === 'reparse'
      ? '重新解析失败'
      : pending.type === 'archive' ? '归档失败' : '永久删除失败'
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
  if (position.archived) return '已归档'
  if (position.latestTaskStatus === 'WAITING') return position.profileUsable ? '画像更新准备中' : '画像准备中'
  if (position.latestTaskStatus === 'RUNNING') return position.profileUsable ? '画像更新中' : '解析中'
  if (position.latestTaskStatus === 'SUCCEEDED' && position.canConfirm) return '待确认'
  if (position.latestTaskStatus === 'FAILED') return position.profileUsable ? '更新失败' : '解析失败'
  if (position.profileUsable) return '正式画像可用'
  return position.latestTaskStatusLabel || '状态待同步'
}

function statusClass(position: Position) {
  if (position.archived) return 'is-unknown'
  if (position.latestTaskStatus === 'FAILED') return 'is-failed'
  if (position.canConfirm) return 'is-attention'
  if (isPositionTaskActive(position.latestTaskStatus)) return 'is-processing'
  if (position.profileUsable) return 'is-confirmed'
  return 'is-unknown'
}

function statusHeadline(position: Position) {
  if (position.archived) return '岗位已经归档'
  if (position.canConfirm) return '岗位候选画像等待确认'
  if (position.latestTaskStatus === 'FAILED') return '本次岗位解析未完成'
  if (position.latestTaskStatus === 'RUNNING') return 'AI 正在分析岗位要求'
  if (position.latestTaskStatus === 'WAITING') return '岗位画像准备中'
  if (position.profileUsable) return '岗位正式画像已经就绪'
  return '岗位状态等待同步'
}

function statusDescription(position: Position) {
  if (position.archived) return '归档岗位不会进入新的简历匹配或模拟面试。历史面试继续使用创建时快照。'
  if (position.canConfirm) return '候选画像已经生成，确认后才会成为新的正式岗位画像。'
  if (position.latestTaskStatus === 'FAILED') {
    return `${formatAnalysisFailure(position.analysisErrorCode, position.analysisErrorMessage)} 可以重新提交解析。`
  }
  if (position.latestTaskStatus === 'RUNNING') return '系统正在从 JD 中提取技能要求、优先级与面试重点。'
  if (position.latestTaskStatus === 'WAITING') {
    return position.queueAhead === undefined
      ? '岗位信息已提交，系统将自动开始分析，页面会持续更新进度。'
      : `岗位信息已提交，前方约有 ${position.queueAhead} 个任务，页面会持续更新进度。`
  }
  if (position.profileUsable) return '能力要求与考察方向已经确认，可以直接用于简历匹配和模拟面试。'
  return '稍后刷新页面，查看最新的岗位画像状态。'
}

function profileStepClass(position: Position) {
  if (position.profileUsable || position.canConfirm) return 'is-completed'
  if (isPositionTaskActive(position.latestTaskStatus)) return 'is-current'
  if (position.latestTaskStatus === 'FAILED') return 'is-failed'
  return 'is-pending'
}

function profileStepText(position: Position) {
  if (position.canConfirm) return '候选待确认'
  if (position.profileUsable) return position.latestTaskStatus ? '旧画像可用' : '已确认'
  if (isPositionTaskActive(position.latestTaskStatus)) return '分析中'
  if (position.latestTaskStatus === 'FAILED') return '需要重试'
  return '等待同步'
}

function interviewStepClass(position: Position) {
  if (position.profileUsable) return 'is-completed'
  if (position.canConfirm) return 'is-current'
  return 'is-pending'
}

function formatAnalysisFailure(errorCode?: string, errorMessage?: string) {
  if (errorMessage?.trim()) return errorMessage
  const labels: Record<string, string> = {
    INPUT_INVALID: '岗位输入无效',
    LLM_TIMEOUT: '模型响应超时',
    WORKER_INTERRUPTED: '解析任务被中断',
    LLM_REQUEST_FAILED: '模型请求失败',
    LLM_EMPTY_RESPONSE: '模型返回为空',
    LLM_INVALID_JSON: '模型结果格式无效',
    LLM_INVALID_PROFILE: '岗位画像结构无效',
    QUEUE_RESERVATION_INVALID: '队列资源暂时不可用',
    WORKER_SUBMISSION_FAILED: '解析执行资源提交失败',
    APPLICATION_RESTARTED: '应用重启中断了本次解析',
    UNEXPECTED_ERROR: '解析服务出现异常'
  }
  return labels[errorCode || ''] || '岗位解析失败'
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

.create-form-grid.is-single {
  grid-template-columns: minmax(0, 1fr);
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

.jd-limit {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.jd-limit.is-over {
  color: var(--color-danger);
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

.upload-row > div > small {
  display: block;
  margin-top: 7px;
  color: var(--color-muted);
  font-size: 11px;
}

.upload-row > div > small.file-validation-success {
  color: var(--color-success);
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
