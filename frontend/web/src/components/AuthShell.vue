<template>
  <div class="auth-shell">
    <aside class="auth-brand-panel" aria-label="职衡品牌介绍">
      <RouterLink class="auth-brand" to="/" aria-label="返回职衡首页">
        <span class="auth-brand-mark" aria-hidden="true"></span>
        <span>
          <strong>职衡</strong>
          <small>ROLEFIT AI</small>
        </span>
      </RouterLink>

      <div class="auth-brand-copy">
        <p>AI 人岗匹配与智能面试平台</p>
        <h1>看见能力，<br />衡量匹配。</h1>
        <span>从简历理解到面试判断，<br />让候选人与招聘团队基于同一份能力证据展开沟通。</span>
      </div>

      <ol class="auth-brand-flow" aria-label="职衡分析流程">
        <li><b>01</b><span>理解简历</span></li>
        <li><b>02</b><span>对齐岗位</span></li>
        <li><b>03</b><span>完成判断</span></li>
      </ol>

      <footer>ROLEFIT AI / 2026</footer>
    </aside>

    <main class="auth-form-panel">
      <div class="auth-form-shell">
        <RouterLink class="auth-back" to="/">
          <el-icon><ArrowLeft /></el-icon>
          返回首页
        </RouterLink>
        <slot />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
</script>

<style scoped>
.auth-shell {
  display: grid;
  min-width: 1120px;
  min-height: 100vh;
  grid-template-columns: minmax(520px, 1fr) minmax(600px, 1fr);
  color: var(--color-ink);
  background: var(--color-surface);
}

.auth-brand-panel {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  overflow: hidden;
  padding: 46px clamp(48px, 5vw, 88px) 38px;
  color: var(--color-on-console);
  background: var(--color-console);
}

.auth-brand-panel::after {
  position: absolute;
  right: 0;
  bottom: 16%;
  width: 6px;
  height: 34%;
  content: '';
  background: var(--color-brand-500);
}

.auth-brand {
  display: inline-flex;
  width: max-content;
  min-height: 48px;
  align-items: center;
  gap: 14px;
  color: inherit;
  text-decoration: none;
}

.auth-brand-mark {
  width: 4px;
  height: 32px;
  background: var(--color-brand-500);
}

.auth-brand > span:last-child {
  display: flex;
  flex-direction: column;
}

.auth-brand strong {
  font-size: 24px;
  line-height: 1.05;
}

.auth-brand small {
  margin-top: 6px;
  color: #8c8b85;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 650;
}

.auth-brand-copy {
  max-width: 620px;
  margin: auto 0;
  padding: 72px 0 56px;
}

.auth-brand-copy > p {
  margin: 0 0 30px;
  color: var(--color-brand-500);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
}

.auth-brand-copy h1 {
  margin: 0;
  color: #ffffff;
  font-size: clamp(52px, 4.2vw, 76px);
  font-weight: 780;
  line-height: 1.08;
}

.auth-brand-copy > span {
  display: block;
  max-width: 540px;
  margin-top: 30px;
  color: #a8a7a1;
  font-size: 16px;
  line-height: 1.8;
}

.auth-brand-flow {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.16);
  border-bottom: 1px solid rgba(255, 255, 255, 0.16);
  list-style: none;
}

.auth-brand-flow li {
  min-width: 0;
  padding: 18px 10px 18px 0;
}

.auth-brand-flow li + li {
  border-left: 1px solid rgba(255, 255, 255, 0.16);
  padding-left: 18px;
}

.auth-brand-flow b,
.auth-brand-flow span {
  display: block;
}

.auth-brand-flow b {
  color: var(--color-brand-500);
  font-family: var(--font-mono);
  font-size: 9px;
}

.auth-brand-flow span {
  margin-top: 9px;
  color: #d8d7d1;
  font-size: 12px;
}

.auth-brand-panel footer {
  margin-top: 28px;
  color: #686864;
  font-family: var(--font-mono);
  font-size: 9px;
}

.auth-form-panel {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: 56px clamp(64px, 8vw, 140px);
  background: var(--color-surface);
}

.auth-form-shell {
  width: 100%;
  max-width: 460px;
}

.auth-back {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  margin-bottom: 44px;
  color: var(--color-muted);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: color var(--motion-fast) var(--ease-standard);
}

.auth-back:hover,
.auth-back:focus-visible {
  color: var(--color-brand-600);
}

@media (min-width: 2560px) {
  .auth-shell {
    grid-template-columns: minmax(780px, 1fr) minmax(900px, 1fr);
  }

  .auth-form-shell {
    max-width: 520px;
  }
}

@media (max-height: 800px) {
  .auth-brand-panel {
    padding-top: 30px;
    padding-bottom: 24px;
  }

  .auth-brand-copy {
    padding: 34px 0 26px;
  }

  .auth-brand-copy > p {
    margin-bottom: 18px;
  }

  .auth-brand-copy h1 {
    font-size: 48px;
  }

  .auth-brand-copy > span {
    margin-top: 18px;
    font-size: 14px;
    line-height: 1.65;
  }

  .auth-brand-flow li {
    padding-top: 12px;
    padding-bottom: 12px;
  }

  .auth-brand-flow span {
    margin-top: 5px;
  }

  .auth-brand-panel footer {
    margin-top: 16px;
  }

  .auth-form-panel {
    align-items: flex-start;
    overflow-y: auto;
    padding-top: 28px;
    padding-bottom: 28px;
  }

  .auth-back {
    margin-bottom: 20px;
  }
}
</style>
