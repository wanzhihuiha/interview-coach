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
        <el-table-column prop="jobCategory" label="岗位类别" width="120" />
        <el-table-column prop="phase" label="面试环节" width="120" />
        <el-table-column prop="topicName" label="主题" width="160" />
        <el-table-column prop="content" label="题目内容" show-overflow-tooltip />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button type="success" size="small" @click="handlePromote(row)">通过</el-button>
            <el-button type="danger" size="small" @click="handleReject(row)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPendingQuestions, promoteQuestion, rejectQuestion } from '@/manager/api/admin'
import type { QuestionBankItem } from '@/manager/types'

const loading = ref(false)
const questions = ref<QuestionBankItem[]>([])

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
