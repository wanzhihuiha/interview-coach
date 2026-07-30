<template>
  <div class="audit-view">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">POSITION GOVERNANCE</span>
        <h1 class="page-title">岗位审核</h1>
        <p class="page-subtitle">校验岗位内容与公开范围，保证后续匹配分析基于可靠输入。</p>
      </div>
      <el-radio-group v-model="filterStatus" @change="loadPositions">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="PENDING">待审核</el-radio-button>
        <el-radio-button label="APPROVED">已通过</el-radio-button>
        <el-radio-button label="REJECTED">已拒绝</el-radio-button>
      </el-radio-group>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>岗位清单</strong>
          <p>对待审核条目执行通过或拒绝操作。</p>
        </div>
        <span class="admin-data-count">{{ totalElements }} RECORDS</span>
      </div>

      <el-table :data="positions" v-loading="loading" stripe>
        <el-table-column prop="positionId" label="ID" width="80" />
        <el-table-column prop="positionName" label="岗位名称" />
        <el-table-column prop="jobCategoryLabel" label="岗位类别" width="120" />
        <el-table-column prop="companyName" label="公司" />
        <el-table-column prop="auditStatus" label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.auditStatus)">{{ row.auditStatusLabel || '状态未知' }}</el-tag>
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
        class="admin-pagination"
        background
        layout="prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadPositions"
      />
    </section>

    <ActionConfirmDialog
      v-model="auditActionVisible"
      :title="auditActionContent.title"
      :description="auditActionContent.description"
      subject-label="审核岗位"
      :subject="pendingAuditAction?.positionName || '当前岗位'"
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
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import { auditPosition, getAdminPositions } from '@/manager/api/admin'
import type { PositionListItem } from '@/manager/types'

type PositionAuditActionType = 'approve' | 'reject'

interface PendingPositionAuditAction {
  type: PositionAuditActionType
  positionId: number
  positionName: string
}

const POSITION_AUDIT_CONTENT = {
  approve: {
    title: '通过这个岗位？',
    description: '通过后，岗位会完成审核，并可按照当前公开设置进入后续匹配流程。',
    confirmText: '通过岗位',
    cancelText: '返回审核',
    loadingText: '正在通过',
    impact: [
      { label: '审核状态', value: '更新为已通过' },
      { label: '公开范围', value: '沿用岗位当前公开设置' }
    ]
  },
  reject: {
    title: '拒绝这个岗位？',
    description: '岗位会被标记为已拒绝，不再进入公共岗位与后续匹配流程。',
    confirmText: '拒绝岗位',
    cancelText: '继续审核',
    loadingText: '正在拒绝',
    impact: [
      { label: '审核状态', value: '更新为已拒绝' },
      { label: '后续使用', value: '不进入公共岗位与匹配流程' }
    ]
  }
} as const

const loading = ref(false)
const positions = ref<PositionListItem[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const filterStatus = ref('')
const auditActionVisible = ref(false)
const auditActionLoading = ref(false)
const pendingAuditAction = ref<PendingPositionAuditAction | null>(null)

const auditActionContent = computed(() => {
  return POSITION_AUDIT_CONTENT[pendingAuditAction.value?.type || 'approve']
})

function statusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger'
  }
  return map[status] || 'info'
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

function handleApprove(row: PositionListItem) {
  openAuditAction('approve', row)
}

function handleReject(row: PositionListItem) {
  openAuditAction('reject', row)
}

function openAuditAction(type: PositionAuditActionType, row: PositionListItem) {
  if (auditActionLoading.value) return
  pendingAuditAction.value = {
    type,
    positionId: row.positionId,
    positionName: row.positionName
  }
  auditActionVisible.value = true
}

async function executeAuditAction() {
  const pending = pendingAuditAction.value
  if (!pending || auditActionLoading.value) return

  auditActionLoading.value = true
  try {
    const status = pending.type === 'approve' ? 'APPROVED' : 'REJECTED'
    await auditPosition(pending.positionId, status)
    ElMessage.success(pending.type === 'approve' ? '已通过' : '已拒绝')
    await loadPositions()
    auditActionVisible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '审核失败')
  } finally {
    auditActionLoading.value = false
  }
}

function resetAuditAction() {
  if (auditActionVisible.value || auditActionLoading.value) return
  pendingAuditAction.value = null
}

onMounted(loadPositions)
</script>
