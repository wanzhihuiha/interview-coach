<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-header">
        <el-icon :size="48" color="#409EFF"><Monitor /></el-icon>
        <h1 class="auth-title">欢迎使用 面试教练</h1>
        <p class="auth-subtitle">AI 驱动的模拟面试平台</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="手机号 / 用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入手机号或用户名"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.remember">记住我</el-checkbox>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="auth-button"
          :loading="userStore.loading"
          @click="handleLogin"
        >
          登录
        </el-button>
      </el-form>

      <div class="auth-footer">
        <span>还没有账号？</span>
        <el-button link type="primary" @click="$router.push('/register')">立即注册</el-button>
      </div>

      <div class="auth-hint">
        测试账号：demo / Demo1234
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Monitor } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  remember: false
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入手机号或用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少 6 位', trigger: 'blur' }
  ]
}

async function handleLogin() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await userStore.loginByPassword(form.username, form.password)
      ElMessage.success('登录成功')
      router.push('/')
    } catch (error) {
      ElMessage.error((error as Error).message || '登录失败')
    }
  })
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e3f2fd 0%, #f5f7fa 100%);
}

.auth-card {
  width: 420px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.auth-header {
  text-align: center;
  margin-bottom: 32px;
}

.auth-title {
  margin-top: 16px;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.auth-subtitle {
  margin-top: 8px;
  font-size: 14px;
  color: #909399;
}

.auth-button {
  width: 100%;
}

.auth-footer {
  margin-top: 24px;
  text-align: center;
  color: #606266;
  font-size: 14px;
}

.auth-hint {
  margin-top: 12px;
  text-align: center;
  color: #909399;
  font-size: 12px;
}
</style>
