<template>
  <AuthShell>
      <header class="auth-header">
        <p class="auth-kicker">CREATE ACCOUNT</p>
        <h2 class="auth-title">创建职衡账号</h2>
        <p class="auth-subtitle">建立你的简历档案与面试记录。</p>
      </header>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleRegister"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" :prefix-icon="User" />
        </el-form-item>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="选填" size="large" :prefix-icon="Phone" />
        </el-form-item>

        <el-form-item label="验证码" prop="smsCode">
          <el-input v-model="form.smsCode" placeholder="选填" size="large">
            <template #append>
              <el-button :disabled="countdown > 0" @click="handleSendCode">
                {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="设置密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="auth-button"
          :loading="userStore.loading"
          @click="handleRegister"
        >
          注册
        </el-button>
      </el-form>

      <div class="auth-footer">
        <span>已有账号？</span>
        <el-button link type="primary" @click="$router.push('/login')">立即登录</el-button>
      </div>
  </AuthShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Phone, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { sendSmsCode } from '@/api'
import AuthShell from '@/components/AuthShell.vue'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const countdown = ref(0)

const form = reactive({
  username: '',
  phone: '',
  smsCode: '',
  password: '',
  confirmPassword: ''
})

const validatePass = (rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== form.password) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  smsCode: [],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*?&]{8,20}$/, message: '密码应为 8-20 位且包含字母和数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validatePass, trigger: 'blur' }
  ]
}

async function handleSendCode() {
  if (!form.phone) {
    ElMessage.warning('请先输入手机号')
    return
  }
  try {
    await sendSmsCode(form.phone)
    ElMessage.success('验证码已发送')
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (error) {
    ElMessage.error((error as Error).message || '发送失败')
  }
}

async function handleRegister() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await userStore.registerAccount({
        username: form.username,
        phone: form.phone,
        password: form.password,
        confirmPassword: form.confirmPassword,
        smsCode: form.smsCode
      })
      ElMessage.success('注册成功')
      router.push('/resume')
    } catch (error) {
      ElMessage.error((error as Error).message || '注册失败')
    }
  })
}
</script>

<style scoped>
.auth-header {
  margin-bottom: 30px;
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
  margin-top: 8px;
}

.auth-footer {
  margin-top: 24px;
  color: var(--color-body);
  font-size: 14px;
}
</style>
