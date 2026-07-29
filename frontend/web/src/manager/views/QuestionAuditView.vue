<template>
  <div class="audit-view">
    <el-card shadow="never">
      <template #header>
        <div class="audit-header">
          <span>题目审核（临时 RAG -> 永久 RAG）</span>
        </div>
      </template>

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
    </el-card>

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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPendingQuestions, promoteQuestion, rejectQuestion, updateQuestion } from '@/manager/api/admin'
import type { QuestionBankItem } from '@/manager/types'

const loading = ref(false)
const questions = ref<QuestionBankItem[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
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

async function handlePromote(row: QuestionBankItem) {
  try {
    await ElMessageBox.confirm('确认将该题目加入永久 RAG 吗？', '提示', { type: 'warning' })
    await promoteQuestion(row.id)
    ElMessage.success('已加入永久 RAG')
    loadQuestions()
  } catch {
    // 取消操作
  }
}

async function handleReject(row: QuestionBankItem) {
  try {
    await ElMessageBox.confirm('确认拒绝该题目吗？', '提示', { type: 'warning' })
    await rejectQuestion(row.id)
    ElMessage.success('已拒绝')
    loadQuestions()
  } catch {
    // 取消操作
  }
}

onMounted(loadQuestions)
</script>

<style scoped>
.audit-view {
  padding: 16px;
}

.audit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
