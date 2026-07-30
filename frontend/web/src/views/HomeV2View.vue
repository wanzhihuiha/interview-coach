<template>
  <div class="rolefit-home">
    <a class="skip-link" href="#main-content">跳到主要内容</a>

    <header class="site-header" aria-label="职衡主导航">
      <div class="page-shell header-inner">
        <RouterLink class="brand" to="/" aria-label="职衡首页">
          <span class="brand-mark" aria-hidden="true"></span>
          <span class="brand-name">职衡</span>
          <span class="brand-en">ROLEFIT AI</span>
        </RouterLink>

        <nav class="primary-nav" aria-label="页面导航">
          <a href="#audiences">双侧能力</a>
          <a href="#hr-workflow">招聘分析</a>
          <a href="#candidate-workflow">模拟面试</a>
        </nav>

        <RouterLink class="header-entry" to="/resume">
          进入平台
          <el-icon><ArrowRight /></el-icon>
        </RouterLink>
      </div>
    </header>

    <main id="main-content">
      <section class="hero" aria-labelledby="hero-title">
        <div class="hero-index" aria-hidden="true">01 / ROLEFIT</div>
        <div class="page-shell hero-inner">
          <div class="hero-copy">
            <p class="hero-kicker">
              <span>ROLEFIT AI</span>
              AI 人岗匹配与智能面试平台
            </p>
            <h1 id="hero-title">职衡</h1>
            <p class="hero-slogan">看见能力，衡量匹配</p>
            <p class="hero-description">
              从一份简历出发，让候选人知道该如何准备，也让招聘团队知道该如何判断。
            </p>

            <div class="hero-actions" aria-label="选择使用身份">
              <RouterLink class="action action--primary" to="/resume">
                我是候选人
                <el-icon><ArrowRight /></el-icon>
              </RouterLink>
              <a class="action action--secondary" href="#hr-workflow">
                我是招聘方
                <el-icon><ArrowRight /></el-icon>
              </a>
            </div>

            <div class="hero-proof" aria-label="平台核心能力">
              <span>简历洞察</span>
              <span>岗位匹配</span>
              <span>双侧提问</span>
            </div>
          </div>

          <figure
            class="product-stage"
            role="region"
            aria-roledescription="轮播图"
            aria-label="模拟面试配置三步预览"
            @mouseenter="pauseProductCarousel('hover')"
            @mouseleave="resumeProductCarousel('hover')"
            @focusin="pauseProductCarousel('focus')"
            @focusout="handleProductFocusOut"
            @keydown.left.stop.prevent="showPreviousProductSlide"
            @keydown.right.stop.prevent="showNextProductSlide"
          >
            <figcaption>
              <span>真实产品界面 · {{ currentProductSlide.step }}</span>
              <div class="product-heading">
                <strong>{{ currentProductSlide.title }}</strong>
                <small>{{ currentProductSlide.description }}</small>
              </div>
            </figcaption>
            <div id="product-preview" class="product-frame">
              <Transition name="product-fade">
                <img
                  :key="currentProductSlide.step"
                  :src="currentProductSlide.image"
                  width="1280"
                  height="720"
                  :alt="currentProductSlide.alt"
                  draggable="false"
                />
              </Transition>

              <button
                class="product-arrow product-arrow--previous"
                type="button"
                :aria-label="`上一张：${previousProductSlide.title}`"
                :title="`上一张：${previousProductSlide.title}`"
                @click="showPreviousProductSlide"
              >
                <el-icon><ArrowLeft /></el-icon>
              </button>
              <button
                class="product-arrow product-arrow--next"
                type="button"
                :aria-label="`下一张：${nextProductSlide.title}`"
                :title="`下一张：${nextProductSlide.title}`"
                @click="showNextProductSlide"
              >
                <el-icon><ArrowRight /></el-icon>
              </button>

              <span class="product-counter" aria-hidden="true">
                {{ String(activeProductSlide + 1).padStart(2, '0') }} / 03
              </span>
            </div>

            <div class="product-tabs" role="tablist" aria-label="选择产品步骤">
              <button
                v-for="(slide, index) in productSlides"
                :key="slide.step"
                class="product-tab"
                :class="{ 'product-tab--active': activeProductSlide === index }"
                type="button"
                role="tab"
                aria-controls="product-preview"
                :aria-selected="activeProductSlide === index"
                :tabindex="activeProductSlide === index ? 0 : -1"
                @click="selectProductSlide(index)"
              >
                <span>{{ slide.step }}</span>
                <span>
                  <strong>{{ slide.title }}</strong>
                  <small>{{ slide.caption }}</small>
                </span>
              </button>
            </div>
          </figure>
        </div>
      </section>

      <section class="signal-band" aria-label="职衡分析链路">
        <div class="page-shell signal-grid">
          <strong>一份简历，两种决策视角</strong>
          <span>能力证据</span>
          <span>岗位要求</span>
          <span>匹配判断</span>
        </div>
      </section>

      <section id="audiences" class="audience-section" aria-labelledby="audience-title">
        <div class="page-shell">
          <header class="section-heading section-heading--dark">
            <p>02 / TWO PERSPECTIVES</p>
            <div>
              <h2 id="audience-title">同一份经历，<br />服务两种关键决定。</h2>
              <p>职衡不只是面试训练工具，也是招聘团队理解候选人的工作入口。</p>
            </div>
          </header>

          <div class="audience-grid">
            <article class="audience-column" aria-labelledby="candidate-title">
              <div class="audience-title">
                <span>FOR CANDIDATES</span>
                <h3 id="candidate-title">候选人</h3>
                <p>把零散经历组织成有证据、有重点的面试表达。</p>
              </div>

              <ol class="capability-list">
                <li v-for="item in candidateCapabilities" :key="item.index">
                  <span class="capability-index">{{ item.index }}</span>
                  <el-icon><component :is="item.icon" /></el-icon>
                  <div>
                    <strong>{{ item.title }}</strong>
                    <p>{{ item.description }}</p>
                  </div>
                </li>
              </ol>

              <RouterLink class="text-link" to="/interview/config">
                开始准备面试
                <el-icon><ArrowRight /></el-icon>
              </RouterLink>
            </article>

            <article class="audience-column" aria-labelledby="recruiter-title">
              <div class="audience-title">
                <span>FOR RECRUITERS</span>
                <h3 id="recruiter-title">招聘团队</h3>
                <p>先看匹配证据，再把有限的面试时间用在真正重要的问题上。</p>
              </div>

              <ol class="capability-list">
                <li v-for="item in recruiterCapabilities" :key="item.index">
                  <span class="capability-index">{{ item.index }}</span>
                  <el-icon><component :is="item.icon" /></el-icon>
                  <div>
                    <strong>{{ item.title }}</strong>
                    <p>{{ item.description }}</p>
                  </div>
                </li>
              </ol>

              <a class="text-link" href="#hr-workflow">
                查看招聘分析方式
                <el-icon><ArrowRight /></el-icon>
              </a>
            </article>
          </div>
        </div>
      </section>

      <section id="hr-workflow" class="hr-story" aria-labelledby="hr-title">
        <div class="page-shell hr-story-grid">
          <div class="hr-copy">
            <p class="section-label">03 / RECRUITMENT INTELLIGENCE</p>
            <h2 id="hr-title">不是替 HR 做决定，<br />而是让判断更有依据。</h2>
            <p>
              职衡把简历内容与岗位要求逐项对齐，标出可验证的优势、风险与信息缺口，并生成适合 HR 首轮沟通的非技术问题。
            </p>

            <dl class="hr-facts">
              <div>
                <dt>01</dt>
                <dd>识别匹配证据</dd>
              </div>
              <div>
                <dt>02</dt>
                <dd>暴露待确认风险</dd>
              </div>
              <div>
                <dt>03</dt>
                <dd>生成结构化提问</dd>
              </div>
            </dl>
          </div>

          <div class="analysis-workspace" role="img" aria-label="候选人与岗位匹配分析示例，包含匹配度、能力证据和建议提问">
            <header class="workspace-header">
              <div>
                <span class="workspace-dot" aria-hidden="true"></span>
                <strong>候选人适配分析</strong>
              </div>
              <span>分析完成</span>
            </header>

            <div class="workspace-summary">
              <div class="score-block">
                <span>岗位综合匹配度</span>
                <strong>82<small>%</small></strong>
                <p>核心经验基本匹配，建议重点确认团队协作与求职动机。</p>
              </div>
              <div class="evidence-block">
                <span class="workspace-caption">关键维度</span>
                <div v-for="metric in matchMetrics" :key="metric.label" class="metric-row">
                  <div>
                    <span>{{ metric.label }}</span>
                    <strong>{{ metric.value }}%</strong>
                  </div>
                  <span class="metric-track" aria-hidden="true">
                    <i :style="{ width: `${metric.value}%` }"></i>
                  </span>
                </div>
              </div>
            </div>

            <div class="question-panel">
              <div class="question-heading">
                <div>
                  <span class="workspace-caption">建议首轮提问</span>
                  <strong>围绕信息缺口继续确认</strong>
                </div>
                <span>3 QUESTIONS</span>
              </div>
              <ol>
                <li v-for="(question, index) in hrQuestions" :key="question">
                  <span>{{ String(index + 1).padStart(2, '0') }}</span>
                  <p>{{ question }}</p>
                </li>
              </ol>
            </div>
          </div>
        </div>
      </section>

      <section id="candidate-workflow" class="workflow-section" aria-labelledby="workflow-title">
        <div class="page-shell">
          <header class="section-heading section-heading--dark workflow-heading">
            <p>04 / INTERVIEW LOOP</p>
            <div>
              <h2 id="workflow-title">从准备到复盘，<br />每一步都有下一步。</h2>
              <RouterLink class="workflow-entry" to="/interview/config">
                进入模拟面试
                <el-icon><ArrowRight /></el-icon>
              </RouterLink>
            </div>
          </header>

          <ol class="workflow-steps">
            <li v-for="step in workflowSteps" :key="step.index">
              <span>{{ step.index }}</span>
              <el-icon><component :is="step.icon" /></el-icon>
              <strong>{{ step.title }}</strong>
              <p>{{ step.description }}</p>
            </li>
          </ol>
        </div>
      </section>

      <section class="closing-section" aria-labelledby="closing-title">
        <div class="page-shell closing-inner">
          <div>
            <p>ROLEFIT AI / 职衡</p>
            <h2 id="closing-title">看见真实能力，<br />做出更好的匹配。</h2>
          </div>
          <RouterLink class="closing-action" to="/resume">
            开始使用职衡
            <el-icon><ArrowRight /></el-icon>
          </RouterLink>
        </div>
      </section>
    </main>

    <footer class="site-footer">
      <div class="page-shell footer-inner">
        <span>职衡 · RoleFit AI</span>
        <span>AI 人岗匹配与智能面试平台</span>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowLeft,
  ArrowRight,
  Document,
  MagicStick,
  Position,
  TrendCharts,
  User,
  VideoCamera
} from '@element-plus/icons-vue'
import interviewStudioImage from '@/assets/rolefit-interview-studio.png'
import interviewPositionImage from '@/assets/rolefit-interview-position.png'
import interviewStructureImage from '@/assets/rolefit-interview-structure.png'

const productSlides = [
  {
    step: '01',
    title: '选择简历',
    caption: '锁定经历证据',
    description: '确认本次面试使用的简历与能力画像',
    image: interviewStudioImage,
    alt: '职衡模拟面试第一步，展示四份已确认能力画像的虚构简历与完整面试摘要'
  },
  {
    step: '02',
    title: '选择岗位',
    caption: '对齐目标要求',
    description: '比较公司、地点、级别与薪资，锁定目标岗位',
    image: interviewPositionImage,
    alt: '职衡模拟面试第二步，展示四个包含公司、地点、级别和薪资的虚构岗位'
  },
  {
    step: '03',
    title: '面试结构',
    caption: '编排提问流程',
    description: '组合四个面试环节与十六道针对性问题',
    image: interviewStructureImage,
    alt: '职衡模拟面试第三步，展示四个面试环节、十六道问题与自动复盘流程'
  }
]

const activeProductSlide = ref(0)
const currentProductSlide = computed(() => productSlides[activeProductSlide.value])
const previousProductSlide = computed(() => {
  return productSlides[(activeProductSlide.value - 1 + productSlides.length) % productSlides.length]
})
const nextProductSlide = computed(() => {
  return productSlides[(activeProductSlide.value + 1) % productSlides.length]
})

let productCarouselTimer: number | undefined
let reducedMotionQuery: MediaQueryList | undefined
const productCarouselPauseReasons = new Set<'hover' | 'focus'>()

function selectProductSlide(index: number) {
  activeProductSlide.value = (index + productSlides.length) % productSlides.length
  restartProductCarousel()
}

function showPreviousProductSlide() {
  selectProductSlide(activeProductSlide.value - 1)
}

function showNextProductSlide() {
  selectProductSlide(activeProductSlide.value + 1)
}

function stopProductCarousel() {
  if (productCarouselTimer === undefined) return
  window.clearInterval(productCarouselTimer)
  productCarouselTimer = undefined
}

function startProductCarousel() {
  stopProductCarousel()
  if (productCarouselPauseReasons.size > 0 || reducedMotionQuery?.matches || document.hidden) return
  productCarouselTimer = window.setInterval(() => {
    activeProductSlide.value = (activeProductSlide.value + 1) % productSlides.length
  }, 5000)
}

function restartProductCarousel() {
  if (productCarouselPauseReasons.size === 0) startProductCarousel()
}

function pauseProductCarousel(reason: 'hover' | 'focus') {
  productCarouselPauseReasons.add(reason)
  stopProductCarousel()
}

function resumeProductCarousel(reason: 'hover' | 'focus') {
  productCarouselPauseReasons.delete(reason)
  startProductCarousel()
}

function handleProductFocusOut(event: FocusEvent) {
  const carousel = event.currentTarget as HTMLElement
  const nextTarget = event.relatedTarget
  if (nextTarget instanceof Node && carousel.contains(nextTarget)) return
  resumeProductCarousel('focus')
}

function handleReducedMotionChange() {
  startProductCarousel()
}

function handlePageVisibilityChange() {
  if (document.hidden) stopProductCarousel()
  else startProductCarousel()
}

const candidateCapabilities = [
  {
    index: '01',
    icon: Document,
    title: '简历洞察',
    description: '梳理经历中的能力证据、表达缺口与可追问内容。'
  },
  {
    index: '02',
    icon: Position,
    title: '岗位匹配',
    description: '理解岗位要求，明确优势、差距与准备优先级。'
  },
  {
    index: '03',
    icon: VideoCamera,
    title: '模拟与复盘',
    description: '基于真实材料连续追问，并把反馈落到具体回答。'
  }
]

const recruiterCapabilities = [
  {
    index: '01',
    icon: User,
    title: '候选人适配分析',
    description: '把简历证据与岗位条件放在同一判断框架中。'
  },
  {
    index: '02',
    icon: TrendCharts,
    title: '亮点与风险提取',
    description: '区分已验证信息、合理推断与仍需确认的缺口。'
  },
  {
    index: '03',
    icon: MagicStick,
    title: 'HR 提问生成',
    description: '生成职业动机、协作方式和稳定性等非技术问题。'
  }
]

const matchMetrics = [
  { label: '相关经验', value: 88 },
  { label: '能力证据', value: 84 },
  { label: '岗位动机', value: 72 }
]

const hrQuestions = [
  '这次职业选择中，你最看重岗位的哪些部分？',
  '请分享一次需要协调不同角色推进工作的经历。',
  '上一段经历发生变化的原因是什么，你希望下一份工作有什么不同？'
]

const workflowSteps = [
  {
    index: '01',
    icon: Document,
    title: '理解简历',
    description: '提取经历、项目与能力证据'
  },
  {
    index: '02',
    icon: Position,
    title: '对齐岗位',
    description: '识别要求、差距与准备重点'
  },
  {
    index: '03',
    icon: VideoCamera,
    title: '模拟追问',
    description: '围绕真实材料连续深入'
  },
  {
    index: '04',
    icon: TrendCharts,
    title: '复盘改进',
    description: '逐题反馈并形成成长路径'
  }
]

let previousTitle = ''

onMounted(() => {
  previousTitle = document.title
  document.title = '职衡 RoleFit AI - AI 人岗匹配与智能面试平台'
  reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  reducedMotionQuery.addEventListener('change', handleReducedMotionChange)
  document.addEventListener('visibilitychange', handlePageVisibilityChange)
  startProductCarousel()
})

onBeforeUnmount(() => {
  stopProductCarousel()
  reducedMotionQuery?.removeEventListener('change', handleReducedMotionChange)
  document.removeEventListener('visibilitychange', handlePageVisibilityChange)
  document.title = previousTitle
})
</script>

<style scoped>
.rolefit-home {
  --rf-ink: #0d0d0c;
  --rf-ink-soft: #171715;
  --rf-paper: #f2f1ec;
  --rf-white: #faf9f5;
  --rf-text: #151514;
  --rf-muted: #74736d;
  --rf-line: #d4d2ca;
  --rf-dark-line: rgba(255, 255, 255, 0.16);
  --rf-accent: var(--color-brand-500);
  min-width: 1120px;
  overflow-x: clip;
  color: var(--rf-text);
  background: var(--rf-paper);
  font-family: var(--font-sans);
}

.rolefit-home,
.rolefit-home * {
  box-sizing: border-box;
  letter-spacing: 0;
}

.page-shell {
  width: calc(100% - 96px);
  max-width: 1740px;
  margin: 0 auto;
}

.skip-link {
  position: fixed;
  top: 12px;
  left: 12px;
  z-index: 1000;
  padding: 12px 16px;
  color: var(--rf-ink);
  background: var(--rf-white);
  transform: translateY(-160%);
  transition: transform 180ms ease;
}

.skip-link:focus {
  transform: translateY(0);
}

.site-header {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 20;
  height: 80px;
  color: var(--rf-white);
}

.header-inner {
  display: grid;
  height: 100%;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.18);
}

.brand {
  display: inline-flex;
  width: max-content;
  min-height: 48px;
  align-items: center;
  color: inherit;
  text-decoration: none;
}

.brand-mark {
  width: 4px;
  height: 30px;
  margin-right: 12px;
  background: var(--rf-accent);
}

.brand-name {
  font-size: 24px;
  font-weight: 750;
}

.brand-en {
  margin: 6px 0 0 10px;
  color: #989892;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
}

.primary-nav {
  display: flex;
  align-items: center;
  gap: 40px;
}

.primary-nav a,
.header-entry {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  color: #c7c6c0;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: color 180ms ease;
}

.primary-nav a:hover,
.primary-nav a:focus-visible,
.header-entry:hover,
.header-entry:focus-visible {
  color: #ffffff;
}

.header-entry {
  justify-self: end;
  gap: 9px;
}

.header-entry .el-icon {
  font-size: 16px;
}

.hero {
  position: relative;
  height: 100vh;
  min-height: 664px;
  overflow: hidden;
  color: var(--rf-white);
  background: var(--rf-ink);
}

.hero::before {
  position: absolute;
  top: 80px;
  right: 0;
  bottom: 0;
  width: 43%;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  content: '';
}

.hero-index {
  position: absolute;
  right: 20px;
  bottom: 24px;
  color: #595955;
  font-family: var(--font-mono);
  font-size: 10px;
  writing-mode: vertical-rl;
}

.hero-inner {
  display: grid;
  height: 100%;
  grid-template-columns: minmax(390px, 0.76fr) minmax(610px, 1.24fr);
  align-items: center;
  gap: 72px;
  padding-top: 112px;
  padding-bottom: 40px;
}

.hero-copy {
  position: relative;
  z-index: 2;
  max-width: 580px;
  animation: hero-copy-enter 360ms cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.hero-kicker,
.section-label,
.section-heading > p,
.audience-title > span,
.workspace-caption,
.closing-section > .page-shell > div > p {
  font-family: var(--font-mono);
}

.hero-kicker {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0 0 42px;
  color: #9b9b95;
  font-size: 11px;
  font-weight: 600;
}

.hero-kicker span {
  color: var(--rf-accent);
}

.hero h1 {
  margin: 0;
  color: #ffffff;
  font-size: 144px;
  font-weight: 800;
  line-height: 0.92;
}

.hero-slogan {
  margin: 26px 0 0;
  color: #efeee9;
  font-size: 28px;
  font-weight: 650;
  line-height: 1.35;
}

.hero-description {
  max-width: 500px;
  margin: 18px 0 0;
  color: #a5a49e;
  font-size: 17px;
  line-height: 1.75;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 30px;
}

.action {
  display: inline-flex;
  min-width: 154px;
  min-height: 52px;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 0 20px;
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  transition: color 180ms ease, background-color 180ms ease, border-color 180ms ease;
}

.action--primary {
  color: #111110;
  background: var(--rf-white);
}

.action--primary:hover,
.action--primary:focus-visible {
  background: var(--rf-accent);
  color: #ffffff;
}

.action--secondary {
  border-color: #555551;
  color: #efeee9;
  background: transparent;
}

.action--secondary:hover,
.action--secondary:focus-visible {
  border-color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.action:focus-visible,
.header-entry:focus-visible,
.primary-nav a:focus-visible,
.brand:focus-visible,
.text-link:focus-visible,
.workflow-entry:focus-visible,
.closing-action:focus-visible {
  outline: 3px solid var(--rf-accent);
  outline-offset: 4px;
}

.hero-proof {
  display: flex;
  align-items: center;
  gap: 0;
  margin-top: 28px;
  color: #777772;
  font-size: 12px;
}

.hero-proof span {
  display: inline-flex;
  align-items: center;
}

.hero-proof span + span::before {
  width: 3px;
  height: 3px;
  margin: 0 14px;
  content: '';
  background: var(--rf-accent);
}

.product-stage {
  position: relative;
  z-index: 2;
  width: 100%;
  max-width: 980px;
  margin: 16px 0 0;
  animation: product-enter 400ms 80ms cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.product-stage::before {
  position: absolute;
  right: -16px;
  bottom: 84px;
  width: 46%;
  height: 38%;
  content: '';
  background: var(--rf-accent);
}

.product-stage figcaption {
  display: flex;
  min-height: 46px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 14px;
}

.product-stage figcaption span {
  color: var(--rf-accent);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.product-heading {
  display: grid;
  justify-items: end;
  gap: 4px;
  text-align: right;
}

.product-heading strong {
  color: #c4c3bd;
  font-size: 13px;
  font-weight: 600;
}

.product-heading small {
  color: #74746f;
  font-size: 11px;
}

.product-frame {
  position: relative;
  z-index: 1;
  width: 100%;
  overflow: hidden;
  border: 1px solid #494944;
  border-radius: 4px;
  aspect-ratio: 16 / 9;
  background: #f5f5f5;
  box-shadow: 0 24px 52px rgba(0, 0, 0, 0.24);
}

.product-frame img {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  user-select: none;
}

.product-fade-enter-active,
.product-fade-leave-active {
  transition: opacity 320ms ease;
}

.product-fade-enter-from,
.product-fade-leave-to {
  opacity: 0;
}

.product-arrow {
  position: absolute;
  top: 50%;
  z-index: 3;
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 50%;
  color: #ffffff;
  background: rgba(13, 13, 12, 0.76);
  cursor: pointer;
  transform: translateY(-50%);
  transition: color 180ms ease, background-color 180ms ease, border-color 180ms ease;
}

.product-arrow--previous {
  left: 16px;
}

.product-arrow--next {
  right: 16px;
}

.product-arrow:hover,
.product-arrow:focus-visible {
  border-color: #ffffff;
  color: var(--rf-ink);
  outline: none;
  background: #ffffff;
}

.product-arrow:focus-visible,
.product-tab:focus-visible {
  box-shadow: 0 0 0 3px var(--rf-accent);
}

.product-counter {
  position: absolute;
  right: 14px;
  bottom: 12px;
  z-index: 2;
  border: 1px solid rgba(255, 255, 255, 0.26);
  border-radius: 2px;
  padding: 6px 8px;
  color: #ffffff;
  background: rgba(13, 13, 12, 0.72);
  font-family: var(--font-mono);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.product-tabs {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 14px;
  border-top: 1px solid #3a3a37;
}

.product-tab {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 62px;
  grid-template-columns: 24px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  border: 0;
  padding: 10px;
  color: #777772;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: color 180ms ease, background-color 180ms ease;
}

.product-tab + .product-tab {
  border-left: 1px solid #30302d;
}

.product-tab::before {
  position: absolute;
  top: -1px;
  right: 0;
  left: 0;
  height: 2px;
  content: '';
  background: transparent;
}

.product-tab:hover,
.product-tab:focus-visible,
.product-tab--active {
  color: #f4f3ee;
  outline: none;
  background: rgba(255, 255, 255, 0.035);
}

.product-tab--active::before {
  background: var(--rf-accent);
}

.product-tab > span:first-child {
  color: inherit;
  font-family: var(--font-mono);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.product-tab > span:last-child,
.product-tab strong,
.product-tab small {
  display: block;
  min-width: 0;
}

.product-tab strong,
.product-tab small {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.product-tab strong {
  color: inherit;
  font-size: 12px;
  font-weight: 650;
}

.product-tab small {
  margin-top: 4px;
  color: #666661;
  font-size: 10px;
}

.product-tab--active > span:first-child,
.product-tab--active small {
  color: var(--rf-accent);
}

.signal-band {
  color: #ffffff;
  background: var(--rf-accent);
}

.signal-grid {
  display: grid;
  min-height: 96px;
  grid-template-columns: 2fr repeat(3, 1fr);
  align-items: center;
}

.signal-grid > * {
  display: flex;
  min-height: 96px;
  align-items: center;
  border-right: 1px solid rgba(255, 255, 255, 0.28);
  padding: 0 32px;
}

.signal-grid > :first-child {
  border-left: 1px solid rgba(255, 255, 255, 0.28);
  font-size: 18px;
}

.signal-grid span {
  font-size: 14px;
  font-weight: 600;
}

.audience-section,
.workflow-section {
  padding: 128px 0 144px;
  background: var(--rf-paper);
}

.section-heading {
  display: grid;
  grid-template-columns: minmax(220px, 0.55fr) minmax(660px, 1.45fr);
  gap: 64px;
  align-items: start;
}

.section-heading > p,
.section-label {
  margin: 8px 0 0;
  color: var(--rf-accent);
  font-size: 10px;
  font-weight: 700;
}

.section-heading h2 {
  margin: 0;
  color: var(--rf-text);
  font-size: 58px;
  font-weight: 740;
  line-height: 1.18;
}

.section-heading > div > p {
  max-width: 620px;
  margin: 24px 0 0;
  color: var(--rf-muted);
  font-size: 17px;
  line-height: 1.75;
}

.audience-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 104px;
  border-top: 1px solid var(--rf-text);
  border-bottom: 1px solid var(--rf-line);
}

.audience-column {
  min-width: 0;
  padding: 48px 64px 56px 0;
}

.audience-column + .audience-column {
  border-left: 1px solid var(--rf-line);
  padding-right: 0;
  padding-left: 64px;
}

.audience-title > span {
  color: var(--rf-accent);
  font-size: 10px;
  font-weight: 700;
}

.audience-title h3 {
  margin: 18px 0 0;
  color: var(--rf-text);
  font-size: 42px;
  line-height: 1.2;
}

.audience-title p {
  max-width: 520px;
  min-height: 56px;
  margin: 16px 0 0;
  color: var(--rf-muted);
  font-size: 16px;
  line-height: 1.75;
}

.capability-list {
  margin: 48px 0 0;
  padding: 0;
  list-style: none;
}

.capability-list li {
  display: grid;
  min-height: 112px;
  grid-template-columns: 40px 32px minmax(0, 1fr);
  align-items: center;
  gap: 18px;
  border-top: 1px solid var(--rf-line);
}

.capability-index {
  color: #9b9991;
  font-family: var(--font-mono);
  font-size: 10px;
}

.capability-list .el-icon {
  color: var(--rf-accent);
  font-size: 25px;
}

.capability-list strong {
  color: var(--rf-text);
  font-size: 17px;
}

.capability-list p {
  margin: 7px 0 0;
  color: var(--rf-muted);
  font-size: 13px;
  line-height: 1.65;
}

.text-link,
.workflow-entry {
  display: inline-flex;
  min-height: 48px;
  align-items: center;
  gap: 12px;
  margin-top: 34px;
  color: var(--rf-text);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
}

.text-link .el-icon,
.workflow-entry .el-icon {
  color: var(--rf-accent);
  transition: transform 180ms ease;
}

.text-link:hover .el-icon,
.workflow-entry:hover .el-icon {
  transform: translateX(4px);
}

.hr-story {
  padding: 144px 0;
  color: var(--rf-white);
  background: var(--rf-ink-soft);
}

.hr-story-grid {
  display: grid;
  grid-template-columns: minmax(380px, 0.72fr) minmax(680px, 1.28fr);
  gap: 112px;
  align-items: center;
}

.hr-copy h2 {
  margin: 30px 0 0;
  color: #ffffff;
  font-size: 52px;
  line-height: 1.22;
}

.hr-copy > p:not(.section-label) {
  max-width: 540px;
  margin: 28px 0 0;
  color: #a5a49e;
  font-size: 16px;
  line-height: 1.85;
}

.hr-facts {
  margin: 56px 0 0;
  border-top: 1px solid var(--rf-dark-line);
}

.hr-facts > div {
  display: grid;
  min-height: 58px;
  grid-template-columns: 56px 1fr;
  align-items: center;
  border-bottom: 1px solid var(--rf-dark-line);
}

.hr-facts dt {
  color: var(--rf-accent);
  font-family: var(--font-mono);
  font-size: 10px;
}

.hr-facts dd {
  margin: 0;
  color: #d6d5cf;
  font-size: 13px;
}

.analysis-workspace {
  min-width: 0;
  border: 1px solid #41413d;
  border-radius: 4px;
  background: #1c1c1a;
}

.workspace-header {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--rf-dark-line);
  padding: 0 24px;
}

.workspace-header > div {
  display: flex;
  align-items: center;
  gap: 12px;
}

.workspace-dot {
  width: 8px;
  height: 8px;
  background: var(--rf-accent);
}

.workspace-header strong {
  font-size: 14px;
}

.workspace-header > span {
  color: #79c69b;
  font-family: var(--font-mono);
  font-size: 9px;
}

.workspace-summary {
  display: grid;
  grid-template-columns: 0.82fr 1.18fr;
  border-bottom: 1px solid var(--rf-dark-line);
}

.score-block,
.evidence-block {
  min-width: 0;
  padding: 32px;
}

.score-block {
  border-right: 1px solid var(--rf-dark-line);
}

.score-block > span,
.workspace-caption {
  color: #7f7e78;
  font-size: 10px;
}

.score-block > strong {
  display: block;
  margin-top: 18px;
  color: #ffffff;
  font-family: var(--font-mono);
  font-size: 66px;
  line-height: 1;
}

.score-block > strong small {
  color: var(--rf-accent);
  font-size: 18px;
}

.score-block p {
  margin: 20px 0 0;
  color: #96958f;
  font-size: 12px;
  line-height: 1.7;
}

.metric-row {
  margin-top: 22px;
}

.metric-row > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #c8c7c1;
  font-size: 12px;
}

.metric-row strong {
  color: #ffffff;
  font-family: var(--font-mono);
  font-size: 11px;
}

.metric-track {
  display: block;
  height: 4px;
  margin-top: 9px;
  background: #363633;
}

.metric-track i {
  display: block;
  height: 100%;
  background: var(--rf-accent);
}

.question-panel {
  padding: 28px 32px 18px;
}

.question-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}

.question-heading strong {
  display: block;
  margin-top: 8px;
  color: #e9e8e2;
  font-size: 14px;
}

.question-heading > span {
  color: var(--rf-accent);
  font-family: var(--font-mono);
  font-size: 9px;
}

.question-panel ol {
  margin: 0;
  padding: 0;
  list-style: none;
}

.question-panel li {
  display: grid;
  min-height: 68px;
  grid-template-columns: 38px minmax(0, 1fr);
  align-items: center;
  border-top: 1px solid var(--rf-dark-line);
}

.question-panel li > span {
  color: var(--rf-accent);
  font-family: var(--font-mono);
  font-size: 9px;
}

.question-panel li p {
  margin: 0;
  color: #c7c6c0;
  font-size: 12px;
  line-height: 1.6;
}

.workflow-heading > div {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 40px;
}

.workflow-entry {
  flex: 0 0 auto;
  margin: 0 0 8px;
}

.workflow-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 100px 0 0;
  padding: 0;
  border-top: 1px solid var(--rf-text);
  border-bottom: 1px solid var(--rf-line);
  list-style: none;
}

.workflow-steps li {
  position: relative;
  min-height: 290px;
  border-right: 1px solid var(--rf-line);
  padding: 34px 36px;
}

.workflow-steps li:first-child {
  border-left: 1px solid var(--rf-line);
}

.workflow-steps li > span {
  color: var(--rf-accent);
  font-family: var(--font-mono);
  font-size: 10px;
}

.workflow-steps .el-icon {
  position: absolute;
  top: 34px;
  right: 34px;
  color: #a3a198;
  font-size: 26px;
}

.workflow-steps strong {
  display: block;
  margin-top: 96px;
  color: var(--rf-text);
  font-size: 22px;
}

.workflow-steps p {
  margin: 14px 0 0;
  color: var(--rf-muted);
  font-size: 13px;
  line-height: 1.7;
}

.closing-section {
  color: #ffffff;
  background: var(--rf-accent);
}

.closing-inner {
  display: flex;
  min-height: 430px;
  align-items: center;
  justify-content: space-between;
  gap: 80px;
}

.closing-section p {
  margin: 0 0 28px;
  color: rgba(255, 255, 255, 0.78);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.closing-section h2 {
  margin: 0;
  font-size: 58px;
  line-height: 1.16;
}

.closing-action {
  display: inline-flex;
  min-width: 220px;
  min-height: 60px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 16px;
  border-radius: 4px;
  color: var(--rf-text);
  background: #ffffff;
  font-size: 15px;
  font-weight: 750;
  text-decoration: none;
  transition: color 180ms ease, background-color 180ms ease;
}

.closing-action:hover,
.closing-action:focus-visible {
  color: #ffffff;
  background: var(--rf-ink);
}

.site-footer {
  color: #8c8b85;
  background: var(--rf-ink);
}

.footer-inner {
  display: flex;
  min-height: 86px;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 11px;
}

@keyframes hero-copy-enter {
  from {
    opacity: 0;
    transform: translate3d(-18px, 0, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@keyframes product-enter {
  from {
    opacity: 0;
    transform: translate3d(22px, 0, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@media (max-width: 1500px) {
  .page-shell {
    width: calc(100% - 64px);
  }

  .primary-nav {
    gap: 28px;
  }

  .hero-inner {
    grid-template-columns: minmax(360px, 0.78fr) minmax(570px, 1.22fr);
    gap: 48px;
  }

  .hero h1 {
    font-size: 116px;
  }

  .hero-slogan {
    font-size: 25px;
  }

  .hero-description {
    font-size: 15px;
  }

  .product-stage {
    max-width: 820px;
  }

  .section-heading h2,
  .closing-section h2 {
    font-size: 50px;
  }

  .audience-column {
    padding-right: 44px;
  }

  .audience-column + .audience-column {
    padding-left: 44px;
  }

  .hr-story-grid {
    gap: 64px;
  }

  .hr-copy h2 {
    font-size: 40px;
  }
}

@media (max-width: 1240px) {
  .page-shell {
    width: calc(100% - 48px);
  }

  .primary-nav {
    gap: 20px;
  }

  .hero-inner {
    grid-template-columns: 400px minmax(0, 1fr);
    gap: 30px;
  }

  .hero h1 {
    font-size: 104px;
  }

  .hero-kicker {
    margin-bottom: 30px;
  }

  .product-stage {
    max-width: 720px;
  }

  .hr-story-grid {
    grid-template-columns: 390px minmax(0, 1fr);
    gap: 42px;
  }

  .score-block,
  .evidence-block {
    padding: 26px;
  }
}

@media (min-width: 2200px) {
  .page-shell {
    max-width: 1920px;
  }

  .hero h1 {
    font-size: 164px;
  }

  .product-stage {
    max-width: 1120px;
  }
}

@media (min-width: 3000px) {
  .page-shell {
    max-width: 2240px;
  }

  .hero {
    height: 100vh;
  }

  .hero-inner {
    grid-template-columns: minmax(560px, 0.74fr) minmax(980px, 1.26fr);
    gap: 120px;
  }

  .hero h1 {
    font-size: 188px;
  }

  .hero-slogan {
    font-size: 34px;
  }

  .hero-description {
    font-size: 20px;
  }

  .product-stage {
    max-width: 1320px;
  }

  .section-heading h2,
  .closing-section h2 {
    font-size: 68px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-copy,
  .product-stage {
    animation: none;
  }

  .rolefit-home a,
  .rolefit-home .el-icon,
  .product-arrow,
  .product-tab,
  .product-fade-enter-active,
  .product-fade-leave-active {
    transition: none;
  }
}
</style>
