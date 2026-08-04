<template>
  <div class="public-position-admin">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">PUBLIC POSITION GOVERNANCE</span>
        <h1 class="page-title">公共岗位管理</h1>
        <p class="page-subtitle">创建和维护面向所有用户开放的正式岗位画像。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增公共岗位</el-button>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>公共岗位清单</strong>
          <p>解析完成后确认候选画像，确认即公开；归档后可永久删除。</p>
        </div>
        <div class="position-list-toolbar">
          <el-radio-group v-model="archived" size="small" @change="handleArchiveFilterChange">
            <el-radio-button :label="false">活动岗位</el-radio-button>
            <el-radio-button :label="true">已归档</el-radio-button>
          </el-radio-group>
          <span class="admin-data-count">{{ totalElements }} RECORDS</span>
        </div>
      </div>

      <el-table :data="positions" v-loading="loading" stripe>
        <el-table-column prop="positionId" label="ID" width="80" />
        <el-table-column prop="positionName" label="岗位名称" min-width="190" />
        <el-table-column prop="jobCategoryLabel" label="岗位类别" width="130">
          <template #default="{ row }">{{ categoryLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="companyName" label="公司" min-width="150">
          <template #default="{ row }">{{ row.companyName || '待补充' }}</template>
        </el-table-column>
        <el-table-column label="任务状态" width="150">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)">{{ statusLabel(row) }}</el-tag>
            <small v-if="row.latestTaskStatus === 'WAITING' && row.queueAhead !== undefined" class="queue-hint">
              前方 {{ row.queueAhead }} 个
            </small>
          </template>
        </el-table-column>
        <el-table-column label="正式画像" width="110">
          <template #default="{ row }">
            {{ row.profileUsable ? '可用' : '未就绪' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProfile(row)">查看画像</el-button>
            <el-button
              v-if="row.canConfirm && !row.archived"
              link
              type="success"
              @click="openProfile(row)"
            >确认</el-button>
            <el-button
              v-if="row.canRetry && !row.archived"
              link
              type="warning"
              @click="openAction('reparse', row)"
            >重试</el-button>
            <el-button
              v-if="!row.archived"
              link
              type="info"
              @click="openAction('archive', row)"
            >归档</el-button>
            <el-button
              v-else
              link
              type="danger"
              @click="openAction('delete', row)"
            >永久删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="admin-pagination"
        background
        layout="prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadPositions"
      />
    </section>

    <el-dialog
      v-model="createDialogVisible"
      class="admin-position-create-dialog"
      width="720px"
      align-center
      :close-on-click-modal="false"
      @closed="resetCreateDialog"
    >
      <template #header>
        <div class="dialog-heading">
          <span>CREATE PUBLIC POSITION</span>
          <h2>新增公共岗位</h2>
          <p>管理员创建的岗位解析完成后，需要确认候选画像才会面向用户公开。</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <div class="admin-form-grid" :class="{ 'is-single': inputMode === 'file' }">
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

        <section class="admin-jd-source">
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
          <div v-else class="admin-upload-row">
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
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting || fileValidating" @click="submitPosition">创建并解析</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="profileDialogVisible"
      class="admin-position-profile-dialog"
      width="820px"
      align-center
      @closed="resetProfileDialog"
    >
      <template #header>
        <div class="dialog-heading">
          <span>PUBLIC POSITION PROFILE</span>
          <h2>{{ currentPosition?.positionName || '岗位画像' }}</h2>
          <p>{{ currentProfileResponse?.canConfirm ? '候选画像待管理员确认' : '当前正式岗位画像' }}</p>
        </div>
      </template>
      <div v-if="profileLoading" class="profile-loading">正在读取岗位画像...</div>
      <div v-else-if="currentProfile" class="profile-summary">
        <dl class="profile-facts">
          <div><dt>岗位</dt><dd>{{ currentProfile.basicInfo?.title || currentPosition?.positionName || '待识别' }}</dd></div>
          <div><dt>公司</dt><dd>{{ currentProfile.basicInfo?.company || currentPosition?.companyName || '待补充' }}</dd></div>
          <div><dt>地点</dt><dd>{{ currentProfile.basicInfo?.location || currentPosition?.location || '待补充' }}</dd></div>
          <div><dt>等级</dt><dd>{{ currentProfile.basicInfo?.level || currentPosition?.levelLabel || '待识别' }}</dd></div>
        </dl>
        <div class="profile-columns">
          <section>
            <span class="profile-section-label">REQUIRED SKILLS</span>
            <h3>必备技能</h3>
            <ul v-if="currentProfile.requiredSkills?.length">
              <li v-for="item in currentProfile.requiredSkills" :key="item.skill">
                <strong>{{ item.skill }}</strong><small>{{ item.importance }} / {{ item.depth }}</small>
              </li>
            </ul>
            <p v-else class="profile-empty-copy">暂无明确的必备技能。</p>
          </section>
          <section>
            <span class="profile-section-label">INTERVIEW FOCUS</span>
            <h3>面试重点</h3>
            <ol v-if="currentProfile.interviewFocus?.length">
              <li v-for="(focus, index) in currentProfile.interviewFocus" :key="`${focus}-${index}`">{{ focus }}</li>
            </ol>
            <p v-else class="profile-empty-copy">暂无明确的面试重点。</p>
          </section>
        </div>
      </div>
      <div v-else class="profile-empty-copy">当前没有可展示的岗位画像。</div>
      <template #footer>
        <div class="dialog-footer-actions">
          <el-button @click="profileDialogVisible = false">关闭</el-button>
          <el-button
            v-if="currentProfileResponse?.canConfirm && !currentPosition?.archived"
            type="primary"
            :icon="CircleCheck"
            @click="confirmFromDialog"
          >确认并公开</el-button>
        </div>
      </template>
    </el-dialog>

    <ActionConfirmDialog
      v-model="actionDialogVisible"
      :title="actionContent.title"
      :description="actionContent.description"
      subject-label="公共岗位"
      :subject="pendingAction?.positionName || '当前岗位'"
      :confirm-text="actionContent.confirmText"
      :cancel-text="actionContent.cancelText"
      :loading-text="actionContent.loadingText"
      :tone="pendingAction?.type === 'delete' ? 'danger' : 'primary'"
      :impact="actionContent.impact"
      :loading="actionLoading"
      @confirm="executeAction"
      @closed="resetAction"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleCheck,
  Plus,
  Upload
} from '@element-plus/icons-vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import {
  archiveAdminPosition,
  confirmAdminPosition,
  createAdminPosition,
  deleteAdminPosition,
  getAdminPositionAnalysisStatus,
  getAdminPositionProfile,
  getAdminPositions,
  reparseAdminPosition,
  uploadAdminPosition
} from '@/manager/api/admin'
import type { PositionCreatePayload } from '@/api/position'
import type {
  PositionAnalysisStatus,
  PositionProfileData,
  PositionProfileResponse
} from '@/types'
import type { FormInstance, FormRules, UploadFile, UploadInstance } from 'element-plus'
import type { PositionListItem } from '@/manager/types'
import { usePositionAnalysisPolling, isPositionTaskActive } from '@/composables/usePositionAnalysisPolling'
import {
  countUnicodeCodePoints,
  JD_MAX_CODE_POINTS,
  normalizeJdContent,
  validateJdFile,
  validateJdText,
  type JdFileValidationResult
} from '@/utils/jdFileValidation'

type ActionType = 'reparse' | 'archive' | 'delete'
type JdInputMode = 'text' | 'file'

interface PendingAction {
  type: ActionType
  positionId: number
  positionName: string
}

interface ActionContent {
  title: string
  description: string
  confirmText: string
  cancelText: string
  loadingText: string
  impact: Array<{ label: string; value: string }>
}

const ACTION_CONTENT: Record<ActionType, ActionContent> = {
  reparse: {
    title: '重新解析这个公共岗位？',
    description: '会替换当前终态解析任务，解析完成前继续保留现有正式画像。',
    confirmText: '开始重试',
    cancelText: '暂不重试',
    loadingText: '正在提交',
    impact: [
      { label: '公开状态', value: '候选确认前沿用现有正式画像' },
      { label: '失败处理', value: '失败后可再次重试' }
    ]
  },
  archive: {
    title: '归档这个公共岗位？',
    description: '归档后普通用户会立即看不到这个岗位，但历史面试仍使用创建时快照。',
    confirmText: '归档岗位',
    cancelText: '保留岗位',
    loadingText: '正在归档',
    impact: [
      { label: '公开范围', value: '立即停止公共读取' },
      { label: '历史面试', value: '不受岗位归档影响' }
    ]
  },
  delete: {
    title: '永久删除这个公共岗位？',
    description: '仅归档且没有进行中面试时允许删除，操作完成后无法恢复。',
    confirmText: '永久删除',
    cancelText: '保留归档',
    loadingText: '正在永久删除',
    impact: [
      { label: '删除对象', value: '岗位、画像和终态任务' },
      { label: '不可逆点', value: '删除后无法恢复' }
    ]
  }
}

const CATEGORY_LABELS: Record<string, string> = {
  TECH: '技术类',
  PRODUCT: '产品类',
  OPERATION: '运营类',
  DESIGN: '设计类'
}

const archived = ref(false)
const positions = ref<PositionListItem[]>([])
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const createDialogVisible = ref(false)
const profileDialogVisible = ref(false)
const profileLoading = ref(false)
const submitting = ref(false)
const actionDialogVisible = ref(false)
const actionLoading = ref(false)
const pendingAction = ref<PendingAction | null>(null)
const currentPosition = ref<PositionListItem | null>(null)
const currentProfile = ref<PositionProfileData | null>(null)
const currentProfileResponse = ref<PositionProfileResponse | null>(null)
const formRef = ref<FormInstance>()
const uploadRef = ref<UploadInstance>()
const inputMode = ref<JdInputMode>('text')
const currentFile = ref<File | null>(null)
const fileValidation = ref<JdFileValidationResult | null>(null)
const fileValidating = ref(false)
let listRequestSequence = 0
let profileRequestSequence = 0
let fileValidationSequence = 0

const form = ref<PositionCreatePayload>({
  positionName: '',
  companyName: '',
  jobCategory: '',
  jdContent: ''
})

const formRules: FormRules = {
  positionName: [{ required: true, message: '请输入岗位名称', trigger: 'blur' }],
  jobCategory: [{
    validator: (_rule, value: string, callback) => {
      if (inputMode.value === 'text' && !value) callback(new Error('请选择岗位大类'))
      else callback()
    },
    trigger: 'change'
  }],
  jdContent: [{
    validator: (_rule, value: string, callback) => {
      if (inputMode.value !== 'text') callback()
      else {
        try {
          validateJdText(value || '')
          callback()
        } catch (error) {
          callback(error as Error)
        }
      }
    },
    trigger: 'blur'
  }]
}

const jdCodePointCount = computed(() => countUnicodeCodePoints(normalizeJdContent(form.value.jdContent || '')))
const actionContent = computed(() => ACTION_CONTENT[pendingAction.value?.type || 'archive'])

const polling = usePositionAnalysisPolling({
  fetchStatus: async (positionId, signal) => (await getAdminPositionAnalysisStatus(positionId, signal)).data,
  onStatus: handleAnalysisStatus
})

onMounted(() => {
  void loadPositions()
})

async function loadPositions() {
  const requestSequence = ++listRequestSequence
  const requestedPage = currentPage.value
  const requestedArchived = archived.value
  loading.value = true
  try {
    const response = await getAdminPositions(requestedPage - 1, pageSize.value, requestedArchived)
    if (
      requestSequence !== listRequestSequence
      || requestedPage !== currentPage.value
      || requestedArchived !== archived.value
    ) return
    positions.value = response.data.content
    totalElements.value = response.data.totalElements
    polling.sync(
      requestedArchived
        ? []
        : positions.value
          .filter(position => isPositionTaskActive(position.latestTaskStatus))
          .map(position => position.positionId)
    )
  } catch (error) {
    if (
      requestSequence !== listRequestSequence
      || requestedPage !== currentPage.value
      || requestedArchived !== archived.value
    ) return
    positions.value = []
    totalElements.value = 0
    polling.stopAll()
    ElMessage.error((error as Error).message || '加载公共岗位失败')
  } finally {
    if (
      requestSequence === listRequestSequence
      && requestedPage === currentPage.value
      && requestedArchived === archived.value
    ) loading.value = false
  }
}

async function handleAnalysisStatus(status: PositionAnalysisStatus) {
  const index = positions.value.findIndex(position => position.positionId === status.positionId)
  if (index < 0) return
  positions.value[index] = {
    ...positions.value[index],
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
  if (status.latestTaskStatus === 'SUCCEEDED' || status.latestTaskStatus === 'FAILED') {
    await loadPositions()
    if (status.latestTaskStatus === 'SUCCEEDED' && status.canConfirm) {
      ElMessage.success('候选画像已生成，请确认后公开')
    } else if (status.latestTaskStatus === 'FAILED') {
      ElMessage.error(formatAnalysisFailure(status.analysisErrorCode, status.analysisErrorMessage))
    }
  }
}

function openCreateDialog() {
  form.value = { positionName: '', companyName: '', jobCategory: '', jdContent: '' }
  inputMode.value = 'text'
  currentFile.value = null
  fileValidation.value = null
  fileValidating.value = false
  uploadRef.value?.clearFiles()
  createDialogVisible.value = true
}

function resetCreateDialog() {
  formRef.value?.clearValidate()
  fileValidationSequence += 1
  currentFile.value = null
  fileValidation.value = null
  fileValidating.value = false
  uploadRef.value?.clearFiles()
}

function handleInputModeChange() {
  formRef.value?.clearValidate()
  fileValidationSequence += 1
  currentFile.value = null
  fileValidation.value = null
  fileValidating.value = false
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
  currentFile.value = null
  fileValidation.value = null
  fileValidating.value = false
}

async function submitPosition() {
  if (!formRef.value || fileValidating.value) return
  if (inputMode.value === 'file' && !currentFile.value) {
    ElMessage.warning('请选择并通过校验一个 JD 文件')
    return
  }
  if (!await formRef.value.validate().catch(() => false)) return
  submitting.value = true
  try {
    const response = inputMode.value === 'file' && currentFile.value
      ? await uploadAdminPosition(currentFile.value, form.value.positionName)
      : await createAdminPosition({ ...form.value, jdContent: normalizeJdContent(form.value.jdContent) })
    createDialogVisible.value = false
    ElMessage.success('公共岗位已提交解析')
    archived.value = false
    currentPage.value = 1
    await loadPositions()
    polling.start(response.data.positionId)
  } catch (error) {
    ElMessage.error((error as Error).message || '创建公共岗位失败')
  } finally {
    submitting.value = false
  }
}

async function openProfile(position: PositionListItem) {
  const requestSequence = ++profileRequestSequence
  currentPosition.value = position
  currentProfile.value = null
  currentProfileResponse.value = null
  profileLoading.value = true
  profileDialogVisible.value = true
  try {
    const response = (await getAdminPositionProfile(position.positionId)).data
    if (requestSequence !== profileRequestSequence || !profileDialogVisible.value) return
    currentProfileResponse.value = response
    currentProfile.value = response.canConfirm
      ? response.candidateProfile || null
      : response.profile || null
  } catch (error) {
    if (requestSequence !== profileRequestSequence || !profileDialogVisible.value) return
    ElMessage.error((error as Error).message || '获取岗位画像失败')
    profileDialogVisible.value = false
  } finally {
    if (requestSequence === profileRequestSequence && profileDialogVisible.value) {
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

async function confirmFromDialog() {
  const position = currentPosition.value
  const profile = currentProfile.value
  const taskId = currentProfileResponse.value?.taskId
  if (!position || position.archived || !profile || !taskId) return
  try {
    await confirmAdminPosition(position.positionId, taskId, profile)
    ElMessage.success('已确认并公开公共岗位')
    profileDialogVisible.value = false
    await loadPositions()
  } catch (error) {
    ElMessage.error((error as Error).message || '确认公共岗位失败')
  }
}

function openAction(type: ActionType, position: PositionListItem) {
  if (actionLoading.value) return
  if (
    (type === 'delete' && !position.archived)
    || (type !== 'delete' && position.archived)
  ) {
    ElMessage.warning('岗位状态已变化，请刷新后重试')
    return
  }
  pendingAction.value = { type, positionId: position.positionId, positionName: position.positionName }
  actionDialogVisible.value = true
}

async function executeAction() {
  const action = pendingAction.value
  if (!action || actionLoading.value) return
  actionLoading.value = true
  try {
    if (action.type === 'reparse') {
      await reparseAdminPosition(action.positionId)
      ElMessage.success('重新解析已提交')
      await loadPositions()
      polling.start(action.positionId)
    } else if (action.type === 'archive') {
      await archiveAdminPosition(action.positionId)
      ElMessage.success('公共岗位已归档')
      await loadPositions()
    } else {
      await deleteAdminPosition(action.positionId)
      ElMessage.success('公共岗位已永久删除')
      await loadPositions()
    }
    actionDialogVisible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || ACTION_CONTENT[action.type].title.replace('？', '失败'))
  } finally {
    actionLoading.value = false
  }
}

function resetAction() {
  if (actionDialogVisible.value || actionLoading.value) return
  pendingAction.value = null
}

function handleArchiveFilterChange() {
  currentPage.value = 1
  void loadPositions()
}

function categoryLabel(position: PositionListItem) {
  return position.jobCategoryLabel || CATEGORY_LABELS[position.jobCategory] || '类别待识别'
}

function statusLabel(position: PositionListItem) {
  if (position.archived) return '已归档'
  if (position.latestTaskStatus === 'WAITING') return position.profileUsable ? '更新排队中' : '排队中'
  if (position.latestTaskStatus === 'RUNNING') return position.profileUsable ? '画像更新中' : '解析中'
  if (position.latestTaskStatus === 'SUCCEEDED' && position.canConfirm) return '待确认'
  if (position.latestTaskStatus === 'FAILED') return position.profileUsable ? '更新失败' : '解析失败'
  return position.profileUsable ? '已公开' : '等待同步'
}

function statusTagType(position: PositionListItem) {
  if (position.archived || position.latestTaskStatus === 'FAILED') return 'danger'
  if (position.canConfirm) return 'warning'
  if (isPositionTaskActive(position.latestTaskStatus)) return 'info'
  return position.profileUsable ? 'success' : 'warning'
}

function formatAnalysisFailure(errorCode?: string, errorMessage?: string) {
  if (errorMessage?.trim()) return errorMessage
  const labels: Record<string, string> = {
    INPUT_INVALID: '岗位输入无效',
    LLM_TIMEOUT: '模型响应超时',
    LLM_REQUEST_FAILED: '模型请求失败',
    LLM_EMPTY_RESPONSE: '模型返回为空',
    LLM_INVALID_JSON: '模型结果格式无效',
    LLM_INVALID_PROFILE: '岗位画像结构无效',
    WORKER_INTERRUPTED: '解析任务被中断',
    APPLICATION_RESTARTED: '应用重启中断了本次解析',
    UNEXPECTED_ERROR: '解析服务出现异常'
  }
  return labels[errorCode || ''] || '岗位解析失败'
}
</script>

<style scoped>
.public-position-admin {
  padding: 28px 32px 48px;
}

.public-position-admin .page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.position-list-toolbar {
  display: flex;
  align-items: center;
  gap: 18px;
}

.admin-data-count {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  white-space: nowrap;
}

.queue-hint {
  display: block;
  color: var(--color-muted);
  font-size: 10px;
}

.admin-position-create-dialog .el-dialog__body,
.admin-position-profile-dialog .el-dialog__body {
  max-height: 68vh;
  overflow-y: auto;
}

.admin-position-create-dialog .el-dialog__footer,
.admin-position-profile-dialog .el-dialog__footer {
  border-top: 1px solid var(--color-border);
}

.dialog-heading > span,
.profile-section-label {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.dialog-heading h2 {
  margin: 6px 0 0;
  color: var(--color-ink);
  font-size: 24px;
}

.dialog-heading p {
  margin: 6px 0 0;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.6;
}

.admin-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.admin-form-grid.is-single {
  grid-template-columns: minmax(0, 1fr);
}

.admin-jd-source {
  border-top: 1px solid var(--color-border);
  margin-top: 8px;
  padding-top: 18px;
}

.admin-jd-source > header,
.admin-upload-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.admin-jd-source > header {
  margin-bottom: 14px;
}

.admin-jd-source > header span,
.admin-jd-source > header strong {
  display: block;
}

.admin-jd-source > header span {
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.admin-jd-source > header strong {
  margin-top: 4px;
  color: var(--color-ink);
  font-size: 15px;
}

.admin-upload-row {
  align-items: flex-start;
  border-top: 1px solid var(--color-border);
  padding-top: 16px;
}

.admin-upload-row strong,
.admin-upload-row p,
.admin-upload-row small {
  display: block;
}

.admin-upload-row strong {
  color: var(--color-ink);
  font-size: 13px;
}

.admin-upload-row p,
.admin-upload-row small {
  margin: 4px 0 0;
  color: var(--color-muted);
  font-size: 11px;
}

.admin-upload-row .file-validation-success {
  color: var(--color-success);
}

.profile-loading {
  padding: 48px 0;
  color: var(--color-muted);
  text-align: center;
}

.profile-summary {
  padding-top: 4px;
}

.profile-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.profile-facts > div {
  min-width: 0;
  padding: 16px 14px;
}

.profile-facts > div + div {
  border-left: 1px solid var(--color-border);
}

.profile-facts dt {
  color: var(--color-muted);
  font-size: 11px;
}

.profile-facts dd {
  margin: 5px 0 0;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
  overflow-wrap: anywhere;
}

.profile-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 28px;
  padding-top: 24px;
}

.profile-columns section {
  min-width: 0;
}

.profile-columns h3 {
  margin: 5px 0 12px;
  color: var(--color-ink);
  font-size: 17px;
}

.profile-columns ul,
.profile-columns ol {
  margin: 0;
  padding-left: 18px;
}

.profile-columns li {
  margin: 0 0 9px;
  color: var(--color-body);
  line-height: 1.55;
}

.profile-columns li strong {
  color: var(--color-ink);
}

.profile-columns li small {
  margin-left: 8px;
  color: var(--color-muted);
}

.profile-empty-copy {
  margin: 0;
  color: var(--color-muted);
  font-size: 13px;
}

@media (max-width: 900px) {
  .public-position-admin {
    padding: 24px 20px 40px;
  }

  .public-position-admin .page-header,
  .position-list-toolbar,
  .admin-data-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .profile-facts,
  .profile-columns {
    grid-template-columns: 1fr 1fr;
  }

  .profile-facts > div:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--color-border);
  }

  .profile-facts > div:nth-child(4) {
    border-top: 1px solid var(--color-border);
  }
}

@media (max-width: 600px) {
  .public-position-admin {
    padding: 20px 14px 32px;
  }

  .admin-form-grid,
  .profile-facts,
  .profile-columns {
    grid-template-columns: 1fr;
  }

  .profile-facts > div + div,
  .profile-facts > div:nth-child(3),
  .profile-facts > div:nth-child(4) {
    border-left: 0;
    border-top: 1px solid var(--color-border);
  }
}
</style>
