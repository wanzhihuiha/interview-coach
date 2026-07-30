<template>
  <AppLayout>
    <div class="page-container">
      <header class="page-header">
        <div class="page-heading-copy">
          <span class="page-eyebrow">ACCOUNT & SECURITY</span>
          <h1 class="page-title">个人中心</h1>
          <p class="page-subtitle">管理账户资料、安全信息与个人面试数据概览。</p>
        </div>
      </header>

      <el-row :gutter="24">
        <el-col :span="8">
          <el-card class="profile-card">
            <div class="avatar-section">
              <el-avatar :size="80" :src="userStore.userInfo?.avatar">
                {{ userStore.userInfo?.username?.charAt(0).toUpperCase() }}
              </el-avatar>
              <div class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</div>
              <div class="user-id">ID: {{ userStore.userInfo?.id }}</div>
            </div>
            <el-divider />
            <div class="stat-list">
              <div class="stat-item">
                <span class="stat-label">已完成面试</span>
                <span class="stat-value">{{ stats.completed }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">平均评分</span>
                <span class="stat-value">{{ stats.avgScore }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">上传简历</span>
                <span class="stat-value">{{ stats.resumes }}</span>
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :span="16">
          <el-card>
            <template #header>
              <span class="card-title">基本信息</span>
            </template>
            <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
              <el-form-item label="用户名">
                <el-input v-model="form.username" disabled />
              </el-form-item>
              <el-form-item label="昵称" prop="nickname">
                <el-input v-model="form.nickname" placeholder="请输入昵称" />
              </el-form-item>
              <el-form-item label="手机号" prop="phone">
                <el-input v-model="form.phone" placeholder="请输入手机号" disabled />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="saveProfile">保存修改</el-button>
                <el-button @click="resetForm">重置</el-button>
              </el-form-item>
            </el-form>
          </el-card>

          <el-card style="margin-top: 20px;">
            <template #header>
              <span class="card-title">修改密码</span>
            </template>
            <el-form :model="passwordForm" label-width="100px" :rules="passwordRules" ref="passwordFormRef">
              <el-form-item label="当前密码" prop="currentPassword">
                <el-input v-model="passwordForm.currentPassword" type="password" placeholder="请输入当前密码" />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input v-model="passwordForm.newPassword" type="password" placeholder="请输入新密码" />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input v-model="passwordForm.confirmPassword" type="password" placeholder="请再次输入新密码" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="savePassword">修改密码</el-button>
                <el-button @click="resetPasswordForm">重置</el-button>
              </el-form-item>
            </el-form>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import AppLayout from '@/components/AppLayout.vue'
import { useUserStore } from '@/stores/user'
import { getInterviewHistory, getResumeList } from '@/api'
import { updateProfile, changePassword } from '@/api/auth'
import type { FormInstance, FormRules } from 'element-plus'

const userStore = useUserStore()
const formRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()

const form = reactive({
  username: userStore.userInfo?.username || '',
  nickname: userStore.userInfo?.nickname || '',
  phone: userStore.userInfo?.phone || '',
})

const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const stats = reactive({
  completed: 0,
  avgScore: 0,
  resumes: 0
})

const rules: FormRules = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }]
}

const validateConfirmPassword = (rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== passwordForm.newPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

const passwordRules: FormRules = {
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*?&]{8,20}$/, message: '密码应为 8-20 位且包含字母和数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

onMounted(async () => {
  await userStore.fetchUserInfo()
  resetForm()
  try {
    const [history, resumes] = await Promise.all([
      getInterviewHistory(),
      getResumeList()
    ])
    const completed = history.filter(h => h.status === 'completed')
    stats.completed = completed.length
    stats.avgScore = completed.length
      ? Math.round(completed.reduce((sum, h) => sum + (h.score || 0), 0) / completed.length)
      : 0
    stats.resumes = resumes.length
  } catch {
    // ignore
  }
})

async function saveProfile() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    await updateProfile({ nickname: form.nickname })
    ElMessage.success('保存成功')
    await userStore.fetchUserInfo()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  }
}

async function savePassword() {
  if (!passwordFormRef.value) return
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    await changePassword({
      oldPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword,
      confirmPassword: passwordForm.confirmPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    resetPasswordForm()
    userStore.logout()
    window.location.href = '/login'
  } catch (error) {
    ElMessage.error((error as Error).message || '密码修改失败')
  }
}

function resetForm() {
  form.nickname = userStore.userInfo?.nickname || ''
  form.phone = userStore.userInfo?.phone || ''
}

function resetPasswordForm() {
  passwordForm.currentPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}
</script>

<style scoped>
.profile-card {
  text-align: center;
}

.avatar-section {
  padding: 20px 0;
}

.username {
  margin-top: 12px;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-ink);
}

.user-id {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-muted);
}

.stat-list {
  padding: 0 20px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}

.stat-item:last-child {
  border-bottom: none;
}

.stat-label {
  color: var(--color-body);
}

.stat-value {
  font-weight: 600;
  color: var(--color-ink);
}

.card-title {
  font-weight: 600;
}
</style>
