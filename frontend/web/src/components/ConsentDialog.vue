<template>
  <el-dialog
    v-model="visible"
    title="欢迎使用 Interview Coach"
    width="600px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    align-center
  >
    <div class="consent-content">
      <p class="consent-intro">
        在使用本系统前，请您阅读并同意以下条款。如您不同意，将无法使用本系统提供的 AI 面试服务。
      </p>

      <div class="consent-section">
        <h4>1. LLM 服务使用条款</h4>
        <p>
          本系统使用大语言模型（LLM）为您提供面试题目生成、简历解析、面试评估、成长方案等服务。
          您理解并同意：AI 生成的内容仅供参考，不代表最终招聘决策依据；您应对自己的回答与提交内容负责。
        </p>
      </div>

      <div class="consent-section">
        <h4>2. 隐私政策</h4>
        <p>
          为提供个性化面试辅导，系统会收集并使用您的简历、岗位信息、面试回答等数据。
          我们仅在提供服务所必需的范围内使用上述信息，并对其进行脱敏处理，不会将您的原始数据用于其他商业用途。
        </p>
      </div>

      <div class="consent-checks">
        <el-checkbox v-model="llmAgreed">
          我已阅读并同意《LLM 服务使用条款》
        </el-checkbox>
        <el-checkbox v-model="privacyAgreed">
          我已阅读并同意《隐私政策》
        </el-checkbox>
      </div>
    </div>

    <template #footer>
      <el-button @click="handleDecline">不同意并退出</el-button>
      <el-button type="primary" :disabled="!canAgree" @click="handleAgree">
        同意并继续
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'agree'): void
  (e: 'decline'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const llmAgreed = ref(false)
const privacyAgreed = ref(false)

const canAgree = computed(() => llmAgreed.value && privacyAgreed.value)

function handleAgree() {
  if (!canAgree.value) return
  emit('agree')
}

function handleDecline() {
  emit('decline')
}
</script>

<style scoped>
.consent-content {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 8px;
}

.consent-intro {
  color: #606266;
  margin-bottom: 16px;
  line-height: 1.6;
}

.consent-section {
  background-color: #f5f7fa;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.consent-section h4 {
  margin-bottom: 8px;
  color: #303133;
}

.consent-section p {
  color: #606266;
  line-height: 1.6;
  font-size: 14px;
}

.consent-checks {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 20px;
}
</style>
