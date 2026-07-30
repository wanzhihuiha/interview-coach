<template>
  <AuthShell>
      <header class="auth-header">
        <p class="auth-kicker">WELCOME BACK</p>
        <h2 class="auth-title">登录职衡</h2>
        <p class="auth-subtitle">继续管理简历、岗位与面试进度。</p>
      </header>

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

      <div class="auth-hint"><span>测试账号</span><code>demo / Demo1234</code></div>
  </AuthShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import AuthShell from '@/components/AuthShell.vue'
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
      router.push('/resume')
    } catch (error) {
      ElMessage.error((error as Error).message || '登录失败')
    }
  })
}
</script>

<style scoped>
.auth-header {
  margin-bottom: 34px;
}

.auth-kicker {
  margin: 0 0 14px;
  color: var(--color-brand-600);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.auth-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 34px;
  font-weight: 720;
  line-height: 1.2;
}

.auth-subtitle {
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 15px;
}

.auth-button {
  width: 100%;
}

.auth-footer {
  margin-top: 24px;
  color: var(--color-body);
  font-size: 14px;
}

.auth-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 28px;
  border-top: 1px solid var(--color-border);
  padding-top: 18px;
  color: var(--color-muted);
  font-size: 11px;
}

.auth-hint code {
  color: var(--color-ink);
  font-family: var(--font-mono);
  font-size: 11px;
}
</style>
