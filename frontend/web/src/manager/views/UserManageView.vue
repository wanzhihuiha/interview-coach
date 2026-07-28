<template>
  <div class="audit-view">
    <el-card shadow="never">
      <template #header>
        <span>用户管理</span>
      </template>

      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="userId" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="roles" label="角色">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles" :key="role" size="small" style="margin-right: 4px">
              {{ role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">
              {{ row.status === 'ACTIVE' ? '正常' : '禁用' }}
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
        class="pagination"
        background
        layout="prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadUsers"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminUsers, updateUserStatus } from '@/manager/api/admin'
import type { UserListItem } from '@/manager/types'

const loading = ref(false)
const users = ref<UserListItem[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)

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

async function handleToggleStatus(row: UserListItem, status: string) {
  const action = status === 'ACTIVE' ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确认${action}用户 "${row.username}" 吗？`, '提示', { type: 'warning' })
    await updateUserStatus(row.userId, status)
    ElMessage.success(`已${action}`)
    loadUsers()
  } catch {
    // 取消操作
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.audit-view {
  padding: 16px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
