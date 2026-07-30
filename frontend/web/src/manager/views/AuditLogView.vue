<template>
  <div class="audit-view">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">AGENT AUDIT TRAIL</span>
        <h1 class="page-title">审计日志</h1>
        <p class="page-subtitle">追踪 AI Agent 的调用主体、权限结果、耗时与异常，支持治理回溯。</p>
      </div>
      <div class="audit-filters">
        <el-select v-model="filterCaller" placeholder="Agent" clearable style="width: 148px">
          <el-option label="面试官" value="INTERVIEWER" />
          <el-option label="回答评估" value="EVALUATOR" />
          <el-option label="报告生成" value="REPORT" />
          <el-option label="成长教练" value="COACH" />
          <el-option label="流程协调" value="COORDINATOR" />
          <el-option label="简历分析" value="RESUME_ANALYSIS" />
          <el-option label="岗位分析" value="JD_ANALYSIS" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 120px">
          <el-option label="允许" value="ALLOWED" />
          <el-option label="拒绝" value="DENIED" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-button type="primary" @click="loadLogs">查询</el-button>
      </div>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>调用记录</strong>
          <p>敏感调用结果与失败原因集中留痕。</p>
        </div>
        <span class="admin-data-count">{{ totalElements }} RECORDS</span>
      </div>

      <el-table :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="callerLabel" label="调用者" width="120" />
        <el-table-column prop="operation" label="操作" width="160" />
        <el-table-column prop="methodKey" label="方法" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.statusLabel || '状态未知' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>

      <el-pagination
        class="admin-pagination"
        background
        layout="prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadLogs"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuditLogs } from '@/manager/api/admin'
import type { AuditLogItem } from '@/manager/types'

const loading = ref(false)
const logs = ref<AuditLogItem[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterCaller = ref('')
const filterStatus = ref('')

function statusType(status: string) {
  const map: Record<string, string> = {
    ALLOWED: 'success',
    DENIED: 'warning',
    FAILED: 'danger'
  }
  return map[status] || 'info'
}

async function loadLogs() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (filterCaller.value) params.caller = filterCaller.value
    if (filterStatus.value) params.status = filterStatus.value
    const res = await getAuditLogs(currentPage.value - 1, pageSize.value, params)
    logs.value = res.data.content
    totalElements.value = res.data.totalElements
  } catch {
    ElMessage.error('加载审计日志失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadLogs)
</script>

<style scoped>
.audit-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
