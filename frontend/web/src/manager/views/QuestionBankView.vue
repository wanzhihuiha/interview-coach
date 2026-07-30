<template>
  <div class="audit-view">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">PERMANENT KNOWLEDGE BASE</span>
        <h1 class="page-title">题库管理</h1>
        <p class="page-subtitle">检索已审核题目与使用数据，为面试生成提供稳定知识来源。</p>
      </div>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>永久题库</strong>
          <p>按岗位、面试环节或内容关键字组合检索。</p>
        </div>
        <span class="admin-data-count">{{ totalElements }} RECORDS</span>
      </div>

      <div class="admin-toolbar">
        <el-form :inline="true" :model="queryForm" class="search-form">
          <el-form-item label="岗位类别">
            <el-input v-model="queryForm.jobCategory" placeholder="请输入岗位类别" clearable />
          </el-form-item>
          <el-form-item label="面试环节">
            <el-input v-model="queryForm.phase" placeholder="请输入面试环节" clearable />
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="queryForm.keyword" placeholder="主题/内容" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="questions" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="jobCategoryLabel" label="岗位类别" width="120" />
        <el-table-column prop="phaseLabel" label="面试环节" width="120" />
        <el-table-column prop="topicName" label="主题" width="160" />
        <el-table-column prop="difficultyLevel" label="难度等级" width="120">
          <template #default="{ row }">
            <el-rate v-model="row.difficultyLevel" disabled :max="5" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="题目内容" show-overflow-tooltip />
        <el-table-column prop="expectedAnswer" label="参考答案" show-overflow-tooltip />
        <el-table-column prop="usageCount" label="使用次数" width="100" />
      </el-table>

      <div class="admin-pagination">
        <el-pagination
          v-model:current-page="queryForm.page"
          v-model:page-size="queryForm.size"
          :total="totalElements"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSearch"
          @current-change="handleSearch"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPermanentQuestions } from '@/manager/api/admin'
import type { QuestionBankItem } from '@/manager/types'

const loading = ref(false)
const questions = ref<QuestionBankItem[]>([])
const totalElements = ref(0)

const queryForm = reactive({
  jobCategory: '',
  phase: '',
  keyword: '',
  page: 1,
  size: 20
})

async function loadQuestions() {
  loading.value = true
  try {
    const res = await getPermanentQuestions({
      jobCategory: queryForm.jobCategory || undefined,
      phase: queryForm.phase || undefined,
      keyword: queryForm.keyword || undefined,
      page: queryForm.page - 1,
      size: queryForm.size
    })
    questions.value = res.data.content
    totalElements.value = res.data.totalElements
  } catch {
    ElMessage.error('加载题库失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  loadQuestions()
}

function handleReset() {
  queryForm.jobCategory = ''
  queryForm.phase = ''
  queryForm.keyword = ''
  queryForm.page = 1
  queryForm.size = 20
  loadQuestions()
}

onMounted(loadQuestions)
</script>
