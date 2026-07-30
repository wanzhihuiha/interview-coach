<template>
  <AppLayout>
    <div class="page-container">
      <div class="section-header">
        <div class="page-heading-copy">
          <span class="page-eyebrow">POSITION ALIGNMENT</span>
          <h1 class="page-title">目标岗位</h1>
          <p class="page-subtitle">沉淀岗位要求与考察重点，让每次匹配判断和面试提问都有明确依据。</p>
        </div>
        <el-button v-if="!isPublicTab" type="primary" :icon="Plus" @click="openCreateDialog">新增岗位</el-button>
      </div>

      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="我的岗位" name="mine" />
        <el-tab-pane label="公共岗位" name="public" />
      </el-tabs>

      <el-table :data="positions" v-loading="loading" style="width: 100%" highlight-current-row
        @row-click="(row: Position) => selectedId = row.positionId">
        <el-table-column prop="positionName" label="岗位名称" min-width="160" />
        <el-table-column prop="companyName" label="公司" min-width="140" />
        <el-table-column prop="jobCategoryLabel" label="岗位大类" min-width="100" />
        <el-table-column prop="parseStatus" label="解析状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.parseStatus)">{{ row.parseStatusLabel || '未知状态' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
            <template v-if="!isPublicTab">
              <el-button link type="warning" @click.stop="reparse(row.positionId)">重新解析</el-button>
              <el-button v-if="row.parseStatus === 'PENDING_CONFIRM'" link type="success"
                @click.stop="confirm(row)">确认</el-button>
              <el-button link type="danger" @click.stop="remove(row.positionId)">删除</el-button>
            </template>
            <template v-else>
              <el-button link type="success" @click.stop="selectForInterview(row.positionId)">去面试</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-card v-if="selectedPosition" class="detail-panel">
        <template #header>
          <div class="detail-header">
            <span class="detail-title">{{ selectedPosition.positionName }}</span>
            <el-button type="primary" @click="goToInterviewConfig">去面试</el-button>
          </div>
        </template>
        <p><strong>公司：</strong>{{ selectedPosition.companyName || '-' }}</p>
        <p><strong>岗位大类：</strong>{{ selectedPosition.jobCategoryLabel || '-' }}</p>
        <p><strong>等级：</strong>{{ selectedPosition.levelLabel || '-' }}</p>
        <p><strong>JD 描述：</strong></p>
        <p class="jd-desc">{{ selectedPosition.jdContent || '暂无岗位描述。' }}</p>
      </el-card>

      <!-- 新增岗位弹窗 -->
      <el-dialog v-model="showCreateDialog" title="新增岗位" width="600px">
        <el-form :model="form" label-position="top" :rules="formRules" ref="formRef">
          <el-form-item label="岗位名称" prop="positionName">
            <el-input v-model="form.positionName" placeholder="例如：Java高级工程师" />
          </el-form-item>
          <el-form-item label="公司">
            <el-input v-model="form.companyName" placeholder="例如：字节跳动" />
          </el-form-item>
          <el-form-item label="岗位大类" prop="jobCategory">
            <el-select v-model="form.jobCategory" placeholder="请选择" style="width: 100%">
              <el-option label="技术族" value="TECH" />
              <el-option label="产品族" value="PRODUCT" />
              <el-option label="运营族" value="OPERATION" />
              <el-option label="设计族" value="DESIGN" />
            </el-select>
          </el-form-item>
          <el-form-item label="JD 描述" prop="jdContent">
            <el-input v-model="form.jdContent" type="textarea" :rows="6"
              placeholder="粘贴岗位 JD 描述，AI 将自动解析技能要求与考察点" />
          </el-form-item>
          <el-form-item label="或上传 JD 文件">
            <el-upload ref="uploadRef" :auto-upload="false" :limit="1" :on-change="handleFileChange"
              :on-remove="handleFileRemove">
              <el-button type="primary">选择文件</el-button>
              <template #tip>
                <div class="el-upload__tip">支持 PDF / TXT 格式</div>
              </template>
            </el-upload>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="showCreateDialog = false">取消</el-button>
          <el-button type="primary" @click="submitPosition" :loading="submitting">确定</el-button>
        </template>
      </el-dialog>

      <!-- 岗位画像弹窗 -->
      <el-dialog
        v-model="showProfileDialog"
        title="岗位画像"
        width="700px"
        class="position-profile-dialog"
        @closed="resetProfileDialog"
      >
        <div v-if="profileLoading" class="profile-loading">画像解析中，请稍候...</div>
        <div v-else-if="currentProfile" class="profile-content">
          <h4>基本信息</h4>
          <p>岗位：{{ currentProfile.basicInfo?.title || '-' }}</p>
          <p>公司：{{ currentProfile.basicInfo?.company || '-' }}</p>
          <p>地点：{{ currentProfile.basicInfo?.location || '-' }}</p>
          <p>等级：{{ currentPosition?.levelLabel || '-' }}</p>
          <p>薪资：{{ currentProfile.basicInfo?.salaryRange || '-' }}</p>

          <h4>必备技能</h4>
          <el-tag v-for="(item, idx) in currentProfile.requiredSkills" :key="'r' + idx" class="skill-tag">
            {{ item.skill }}（{{ item.importance }} / {{ item.depth }}）
          </el-tag>

          <h4>加分技能</h4>
          <el-tag v-for="(item, idx) in currentProfile.preferredSkills" :key="'p' + idx" class="skill-tag"
            type="warning">
            {{ item.skill }}（{{ item.importance }} / {{ item.depth }}）
          </el-tag>

          <h4>考察方向</h4>
          <div v-for="(dir, idx) in sortedProbingDirections" :key="'d' + idx" class="probing-item">
            <p><strong>{{ dir.direction }}</strong>（优先级 {{ dir.priority }}，深度 {{ dir.depthRange }}）</p>
            <ul>
              <li v-for="(q, qidx) in dir.sampleQuestions" :key="'q' + qidx">{{ q }}</li>
            </ul>
          </div>

          <h4>面试重点</h4>
          <el-tag v-for="(focus, idx) in currentProfile.interviewFocus" :key="'f' + idx" class="skill-tag"
            type="success">
            {{ focus }}
          </el-tag>
        </div>
        <template #footer>
          <el-button @click="showProfileDialog = false">关闭</el-button>
          <el-button v-if="currentPosition?.parseStatus === 'PENDING_CONFIRM'" type="primary"
            @click="confirmFromDialog">确认画像</el-button>
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
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import AppLayout from '@/components/AppLayout.vue'
import {
  getPositionList,
  getPublicPositionList,
  createPosition,
  uploadPosition,
  getPositionProfile,
  confirmPosition,
  reparsePosition,
  deletePosition
} from '@/api/position'
import type { Position, PositionProfileData, ProbingDirection } from '@/types'
import type { FormInstance, FormRules, UploadFile, UploadInstance } from 'element-plus'

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

const router = useRouter()
const positions = ref<Position[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(false)
const activeTab = ref<'mine' | 'public'>('mine')
const isPublicTab = computed(() => activeTab.value === 'public')
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

const selectedPosition = computed(() => {
  return positions.value.find(p => p.positionId === selectedId.value) || null
})

const sortedProbingDirections = computed<ProbingDirection[]>(() => {
  const dirs = currentProfile.value?.probingDirections || []
  return [...dirs].sort((a, b) => a.priority - b.priority)
})

const positionActionContent = computed(() => {
  return POSITION_ACTION_CONTENT[pendingPositionAction.value?.type || 'reparse']
})

onMounted(async () => {
  await loadPositions()
})

async function loadPositions() {
  loading.value = true
  try {
    positions.value = activeTab.value === 'mine'
      ? await getPositionList()
      : await getPublicPositionList()
    selectedId.value = positions.value.length > 0 ? positions.value[0].positionId : null
  } finally {
    loading.value = false
  }
}

function handleTabChange() {
  selectedId.value = null
  loadPositions()
}

function selectForInterview(positionId: number) {
  router.push({ path: '/interview/config', query: { positionId: String(positionId) } })
}

function openCreateDialog() {
  showCreateDialog.value = true
  form.value = { positionName: '', companyName: '', jobCategory: '', jdContent: '' }
  currentFile.value = null
  uploadRef.value?.clearFiles()
}

function handleFileChange(uploadFile: UploadFile) {
  currentFile.value = uploadFile.raw || null
}

function handleFileRemove() {
  currentFile.value = null
}

async function submitPosition() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  try {
      submitting.value = true
      let position: Position
      if (currentFile.value) {
        position = await uploadPosition(currentFile.value, form.value.positionName)
      } else {
        position = await createPosition(form.value)
      }
      positions.value.unshift(position)
      selectedId.value = position.positionId
      showCreateDialog.value = false
      form.value = { positionName: '', companyName: '', jobCategory: '', jdContent: '' }
      currentFile.value = null
      uploadRef.value?.clearFiles()
      ElMessage.success('新增成功，AI 正在解析岗位画像')
      // 轮询解析状态
      pollParseStatus(position.positionId)
    } catch (error) {
      ElMessage.error((error as Error).message || '新增失败')
    } finally {
      submitting.value = false
    }
}

async function pollParseStatus(positionId: number) {
  const maxAttempts = 30
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise(resolve => setTimeout(resolve, 2000))
    const profile = await getPositionProfile(positionId)
    if (profile.parseStatus && profile.parseStatus !== 'PENDING' && profile.parseStatus !== 'PARSING') {
      await loadPositions()
      if (profile.parseStatus === 'PENDING_CONFIRM') {
        ElMessage.success('岗位画像解析完成，请查看并确认')
      }
      return
    }
  }
}

async function openDetail(row: Position) {
  const requestSequence = ++profileRequestSequence
  currentPosition.value = row
  currentProfile.value = null
  showProfileDialog.value = true
  profileLoading.value = true
  try {
    const res = await getPositionProfile(row.positionId)
    if (requestSequence !== profileRequestSequence || !showProfileDialog.value) return
    currentProfile.value = res.profile || null
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
  profileRequestSequence++
  currentPosition.value = null
  currentProfile.value = null
  profileLoading.value = false
}

async function confirm(row: Position) {
  if (!row.profile) {
    const res = await getPositionProfile(row.positionId)
    row.profile = res.profile
  }
  if (!row.profile) {
    ElMessage.warning('暂无画像可确认')
    return
  }
  try {
    await confirmPosition(row.positionId, row.profile)
    ElMessage.success('确认成功')
    await loadPositions()
  } catch (error) {
    ElMessage.error((error as Error).message || '确认失败')
  }
}

async function confirmFromDialog() {
  if (!currentPosition.value || !currentProfile.value) return
  try {
    await confirmPosition(currentPosition.value.positionId, currentProfile.value)
    ElMessage.success('确认成功')
    showProfileDialog.value = false
    await loadPositions()
  } catch (error) {
    ElMessage.error((error as Error).message || '确认失败')
  }
}

function reparse(positionId: number) {
  openPositionAction('reparse', positionId)
}

function remove(positionId: number) {
  openPositionAction('delete', positionId)
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
      await loadPositions().catch(error => {
        ElMessage.error((error as Error).message || '岗位列表刷新失败，请稍后重试')
      })
      void pollParseStatus(pending.positionId)
    } else {
      await deletePosition(pending.positionId)
      ElMessage.success('删除成功')
      positions.value = positions.value.filter(position => position.positionId !== pending.positionId)
      if (selectedId.value === pending.positionId) {
        selectedId.value = positions.value.length > 0 ? positions.value[0].positionId : null
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

function goToInterviewConfig() {
  router.push('/interview/config')
}

function statusTagType(status: string) {
  switch (status) {
    case 'CONFIRMED': return 'success'
    case 'PENDING_CONFIRM': return 'warning'
    case 'PARSING': return 'info'
    case 'PARSE_FAILED': return 'danger'
    default: return ''
  }
}

</script>

<style scoped>
.detail-panel {
  margin-top: 24px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
}

.jd-desc {
  color: var(--color-body);
  line-height: 1.8;
  margin-top: 8px;
  white-space: pre-wrap;
}

.profile-loading {
  text-align: center;
  padding: 40px 0;
  color: var(--color-muted);
}

.profile-content h4 {
  margin: 16px 0 8px;
  color: var(--color-ink);
  border-left: 4px solid var(--color-brand-500);
  padding-left: 8px;
}

.skill-tag {
  margin-right: 8px;
  margin-bottom: 8px;
}

.probing-item {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-subtle);
}

.probing-item ul {
  margin: 4px 0 0 16px;
  color: var(--color-body);
}
</style>

<style>
.position-profile-dialog .el-dialog__body {
  max-height: 55vh;
  overflow-y: auto;
}
</style>
