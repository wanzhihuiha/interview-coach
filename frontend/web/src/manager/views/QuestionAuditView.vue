<template>
  <div class="audit-view">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">QUESTION GOVERNANCE</span>
        <h1 class="page-title">题目审核</h1>
        <p class="page-subtitle">复核临时题目内容与参考答案，通过后纳入职衡永久题库。</p>
      </div>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>待审核题目</strong>
          <p>编辑内容后再决定入库或拒绝。</p>
        </div>
        <span class="admin-data-count">{{ questions.length }} PENDING</span>
      </div>

      <el-table :data="questions" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="jobCategoryLabel" label="岗位类别" width="120" />
        <el-table-column prop="phaseLabel" label="面试环节" width="120" />
        <el-table-column prop="topicName" label="主题" width="160" />
        <el-table-column prop="difficultyLevel" label="难度等级" width="100">
          <template #default="{ row }">
            <el-rate v-model="row.difficultyLevel" disabled :max="5" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="题目内容" show-overflow-tooltip />
        <el-table-column prop="expectedAnswer" label="参考答案" show-overflow-tooltip />
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="success" size="small" @click="handlePromote(row)">通过</el-button>
            <el-button type="danger" size="small" @click="handleReject(row)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" title="编辑题目" width="600px" destroy-on-close>
      <el-form :model="editForm" label-position="top">
        <el-form-item label="主题">
          <el-input
            v-model="editForm.topicName"
            placeholder="请输入题目主题"
          />
        </el-form-item>
        <el-form-item label="题目内容">
          <el-input
            v-model="editForm.content"
            type="textarea"
            :rows="4"
            placeholder="请输入题目内容"
          />
        </el-form-item>
        <el-form-item label="参考答案">
          <el-input
            v-model="editForm.expectedAnswer"
            type="textarea"
            :rows="4"
            placeholder="请输入参考答案（可选）"
          />
        </el-form-item>
        <el-form-item label="难度等级（1-5）">
          <el-rate v-model="editForm.difficultyLevel" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleUpdate">保存</el-button>
      </template>
    </el-dialog>

    <ActionConfirmDialog
      v-model="auditActionVisible"
      :title="auditActionContent.title"
      :description="auditActionContent.description"
      subject-label="审核题目"
      :subject="pendingAuditAction?.questionContent || '当前题目'"
      :confirm-text="auditActionContent.confirmText"
      :cancel-text="auditActionContent.cancelText"
      :loading-text="auditActionContent.loadingText"
      :tone="pendingAuditAction?.type === 'reject' ? 'danger' : 'primary'"
      :impact="auditActionContent.impact"
      :loading="auditActionLoading"
      @confirm="executeAuditAction"
      @closed="resetAuditAction"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import { getPendingQuestions, promoteQuestion, rejectQuestion, updateQuestion } from '@/manager/api/admin'
import type { QuestionBankItem } from '@/manager/types'

type QuestionAuditActionType = 'promote' | 'reject'

interface PendingQuestionAuditAction {
  type: QuestionAuditActionType
  questionId: number
  questionContent: string
}

const QUESTION_AUDIT_CONTENT = {
  promote: {
    title: '将这道题加入永久题库？',
    description: '通过后，这道题会进入职衡的永久 RAG 题库，供后续检索和面试出题使用。',
    confirmText: '通过并入库',
    cancelText: '返回审核',
    loadingText: '正在入库',
    impact: [
      { label: '题库归属', value: '永久 RAG 题库' },
      { label: '后续使用', value: '可用于题目检索与面试出题' }
    ]
  },
  reject: {
    title: '拒绝这道待审核题目？',
    description: '题目会退出待审核队列，并且不会进入职衡的永久题库。',
    confirmText: '拒绝题目',
    cancelText: '继续审核',
    loadingText: '正在拒绝',
    impact: [
      { label: '审核结果', value: '标记为已拒绝' },
      { label: '题库归属', value: '不进入永久 RAG 题库' }
    ]
  }
} as const

const loading = ref(false)
const questions = ref<QuestionBankItem[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const auditActionVisible = ref(false)
const auditActionLoading = ref(false)
const pendingAuditAction = ref<PendingQuestionAuditAction | null>(null)
const editForm = reactive<{
  topicName: string
  content: string
  expectedAnswer: string
  difficultyLevel: number
}>({
  topicName: '',
  content: '',
  expectedAnswer: '',
  difficultyLevel: 3
})

const auditActionContent = computed(() => {
  return QUESTION_AUDIT_CONTENT[pendingAuditAction.value?.type || 'promote']
})

async function loadQuestions() {
  loading.value = true
  try {
    const res = await getPendingQuestions()
    questions.value = res.data
  } catch {
    ElMessage.error('加载题目列表失败')
  } finally {
    loading.value = false
  }
}

function handleEdit(row: QuestionBankItem) {
  editingId.value = row.id
  editForm.topicName = row.topicName || ''
  editForm.content = row.content || ''
  editForm.expectedAnswer = row.expectedAnswer || ''
  editForm.difficultyLevel = row.difficultyLevel || 3
  dialogVisible.value = true
}

async function handleUpdate() {
  if (!editingId.value) return
  if (!editForm.content.trim()) {
    ElMessage.warning('题目内容不能为空')
    return
  }
  try {
    await updateQuestion(editingId.value, {
      topicName: editForm.topicName.trim() || undefined,
      content: editForm.content.trim(),
      expectedAnswer: editForm.expectedAnswer.trim() || undefined,
      difficultyLevel: editForm.difficultyLevel
    })
    ElMessage.success('题目已更新')
    dialogVisible.value = false
    loadQuestions()
  } catch {
    ElMessage.error('更新题目失败')
  }
}

function handlePromote(row: QuestionBankItem) {
  openAuditAction('promote', row)
}

function handleReject(row: QuestionBankItem) {
  openAuditAction('reject', row)
}

function openAuditAction(type: QuestionAuditActionType, row: QuestionBankItem) {
  if (auditActionLoading.value) return
  pendingAuditAction.value = {
    type,
    questionId: row.id,
    questionContent: row.content
  }
  auditActionVisible.value = true
}

async function executeAuditAction() {
  const pending = pendingAuditAction.value
  if (!pending || auditActionLoading.value) return

  auditActionLoading.value = true
  try {
    if (pending.type === 'promote') {
      await promoteQuestion(pending.questionId)
      ElMessage.success('已加入永久 RAG')
    } else {
      await rejectQuestion(pending.questionId)
      ElMessage.success('已拒绝')
    }
    await loadQuestions()
    auditActionVisible.value = false
  } catch (error) {
    const fallback = pending.type === 'promote' ? '题目入库失败' : '拒绝题目失败'
    ElMessage.error((error as Error).message || fallback)
  } finally {
    auditActionLoading.value = false
  }
}

function resetAuditAction() {
  if (auditActionVisible.value || auditActionLoading.value) return
  pendingAuditAction.value = null
}

onMounted(loadQuestions)
</script>
