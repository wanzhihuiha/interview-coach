<template>
  <div class="audit-view">
    <header class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">ACCOUNT GOVERNANCE</span>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">查看账号身份、角色与当前状态，并处理异常账号访问。</p>
      </div>
    </header>

    <section class="admin-data-panel">
      <div class="admin-data-heading">
        <div>
          <strong>账号清单</strong>
          <p>禁用操作会阻止账号继续访问受保护能力。</p>
        </div>
        <span class="admin-data-count">{{ totalElements }} ACCOUNTS</span>
      </div>

      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="userId" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="roles" label="角色">
          <template #default="{ row }">
            <el-tag v-for="(roleLabel, index) in (row.roleLabels || [])" :key="row.roles[index] || roleLabel" class="role-tag" size="small">
              {{ roleLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">
              {{ row.statusLabel || '状态未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              type="danger"
              size="small"
              @click="handleToggleStatus(row, 'DISABLED')"
            >禁用</el-button>
            <el-button
              v-else
              type="success"
              size="small"
              @click="handleToggleStatus(row, 'ACTIVE')"
            >启用</el-button>
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
        @current-change="loadUsers"
      />
    </section>

    <ActionConfirmDialog
      v-model="statusActionVisible"
      :title="statusActionContent.title"
      :description="statusActionContent.description"
      subject-label="用户账号"
      :subject="pendingStatusAction?.username || '当前用户'"
      :confirm-text="statusActionContent.confirmText"
      :cancel-text="statusActionContent.cancelText"
      :loading-text="statusActionContent.loadingText"
      :tone="pendingStatusAction?.status === 'DISABLED' ? 'danger' : 'primary'"
      :impact="statusActionContent.impact"
      :loading="statusActionLoading"
      @confirm="executeStatusAction"
      @closed="resetStatusAction"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import { getAdminUsers, updateUserStatus } from '@/manager/api/admin'
import type { UserListItem } from '@/manager/types'

type UserStatusAction = 'ACTIVE' | 'DISABLED'

interface PendingUserStatusAction {
  userId: number
  username: string
  status: UserStatusAction
}

const USER_STATUS_CONTENT = {
  ACTIVE: {
    title: '启用这个用户账号？',
    description: '账号会恢复为可用状态，可以继续访问职衡的受保护功能。',
    confirmText: '启用账号',
    cancelText: '保持禁用',
    loadingText: '正在启用',
    impact: [
      { label: '账号状态', value: '更新为启用' },
      { label: '后续访问', value: '恢复受保护功能的访问资格' }
    ]
  },
  DISABLED: {
    title: '禁用这个用户账号？',
    description: '账号会停止访问职衡的受保护功能，直至管理员再次启用。',
    confirmText: '禁用账号',
    cancelText: '保留账号',
    loadingText: '正在禁用',
    impact: [
      { label: '账号状态', value: '更新为禁用' },
      { label: '后续访问', value: '受保护功能将被阻止' }
    ]
  }
} as const

const loading = ref(false)
const users = ref<UserListItem[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusActionVisible = ref(false)
const statusActionLoading = ref(false)
const pendingStatusAction = ref<PendingUserStatusAction | null>(null)

const statusActionContent = computed(() => {
  return USER_STATUS_CONTENT[pendingStatusAction.value?.status || 'ACTIVE']
})

async function loadUsers() {
  loading.value = true
  try {
    const res = await getAdminUsers(currentPage.value - 1, pageSize.value)
    users.value = res.data.content
    totalElements.value = res.data.totalElements
  } catch {
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function handleToggleStatus(row: UserListItem, status: UserStatusAction) {
  if (statusActionLoading.value) return
  pendingStatusAction.value = {
    userId: row.userId,
    username: row.username,
    status
  }
  statusActionVisible.value = true
}

async function executeStatusAction() {
  const pending = pendingStatusAction.value
  if (!pending || statusActionLoading.value) return

  const action = pending.status === 'ACTIVE' ? '启用' : '禁用'
  statusActionLoading.value = true
  try {
    await updateUserStatus(pending.userId, pending.status)
    ElMessage.success(`已${action}`)
    await loadUsers()
    statusActionVisible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || `${action}账号失败`)
  } finally {
    statusActionLoading.value = false
  }
}

function resetStatusAction() {
  if (statusActionVisible.value || statusActionLoading.value) return
  pendingStatusAction.value = null
}

onMounted(loadUsers)
</script>

<style scoped>
.role-tag + .role-tag {
  margin-left: 4px;
}
</style>
