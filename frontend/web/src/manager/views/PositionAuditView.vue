<template>
  <div class="audit-view">
    <el-card shadow="never">
      <template #header>
        <div class="audit-header">
          <span>岗位审核</span>
          <el-radio-group v-model="filterStatus" size="small" @change="loadPositions">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="PENDING">待审核</el-radio-button>
            <el-radio-button label="APPROVED">已通过</el-radio-button>
            <el-radio-button label="REJECTED">已拒绝</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <el-table :data="positions" v-loading="loading" stripe>
        <el-table-column prop="positionId" label="ID" width="80" />
        <el-table-column prop="positionName" label="岗位名称" />
        <el-table-column prop="jobCategory" label="岗位类别" width="120" />
        <el-table-column prop="companyName" label="公司" />
        <el-table-column prop="auditStatus" label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.auditStatus)">{{ statusText(row.auditStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="isPublic" label="是否公共" width="100">
          <template #default="{ row }">
            {{ row.isPublic ? '是' : '否' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button
              v-if="row.auditStatus === 'PENDING'"
              type="success"
              size="small"
              @click="handleApprove(row)"
            >通过</el-button>
            <el-button
              v-if="row.auditStatus === 'PENDING'"
              type="danger"
              size="small"
              @click="handleReject(row)"
            >拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadPositions"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditPosition, getAdminPositions } from '@/manager/api/admin'
import type { PositionListItem } from '@/manager/types'

const loading = ref(false)
const positions = ref<PositionListItem[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const filterStatus = ref('')

function statusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger'
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝'
  }
  return map[status] || status
}

async function loadPositions() {
  loading.value = true
  try {
    const res = await getAdminPositions(currentPage.value - 1, pageSize.value, filterStatus.value || undefined)
    positions.value = res.data.content
    totalElements.value = res.data.totalElements
  } catch {
    ElMessage.error('加载岗位列表失败')
  } finally {
    loading.value = false
  }
}

async function handleApprove(row: PositionListItem) {
  try {
    await ElMessageBox.confirm(`确认通过岗位 "${row.positionName}" 吗？`, '提示', { type: 'warning' })
  } catch {
    // 取消操作
    return
  }
  try {
    await auditPosition(row.positionId, 'APPROVED')
    ElMessage.success('已通过')
    loadPositions()
  } catch (error) {
    ElMessage.error((error as Error).message || '审核失败')
  }
}

async function handleReject(row: PositionListItem) {
  try {
    await ElMessageBox.confirm(`确认拒绝岗位 "${row.positionName}" 吗？`, '提示', { type: 'warning' })
  } catch {
    // 取消操作
    return
  }
  try {
    await auditPosition(row.positionId, 'REJECTED')
    ElMessage.success('已拒绝')
    loadPositions()
  } catch (error) {
    ElMessage.error((error as Error).message || '审核失败')
  }
}

onMounted(loadPositions)
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

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
