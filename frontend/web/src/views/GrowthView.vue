<template>
  <AppLayout>
    <div class="page-container">
      <div class="page-header">
        <h2 class="page-title">成长方案</h2>
      </div>

      <div v-if="!plan" class="empty-growth">
        <el-empty description="暂无成长方案">
          <el-button type="primary" @click="$router.push('/history')">查看历史</el-button>
        </el-empty>
      </div>

      <template v-else>
        <div class="page-header">
          <h2 class="page-title">成长方案 · {{ plan?.positionTitle }}</h2>
          <el-button type="primary" :icon="Download" @click="downloadMd">下载 MD</el-button>
        </div>

        <el-row :gutter="24">
        <el-col :span="16">
          <el-card>
            <div class="growth-content">
              <section class="growth-section">
                <h3>一、学习路径</h3>
                <el-timeline>
                  <el-timeline-item
                    v-for="path in plan?.learningPath"
                    :key="path.phase"
                    type="primary"
                    :hollow="true"
                  >
                    <h4>{{ path.phase }}</h4>
                    <p><strong>预计时长：</strong>{{ path.duration }}</p>
                    <p><strong>阶段目标：</strong>{{ path.goal }}</p>
                    <p><strong>学习任务：</strong></p>
                    <ul>
                      <li v-for="task in path.tasks" :key="task">{{ task }}</li>
                    </ul>
                    <template v-if="path.resources && path.resources.length">
                      <p><strong>推荐资源：</strong></p>
                      <ul>
                        <li v-for="res in path.resources" :key="res.name">
                          <a :href="res.url" target="_blank" rel="noopener">{{ res.name }}</a>
                          <el-tag size="small" type="info" class="resource-tag">{{ res.type }}</el-tag>
                        </li>
                      </ul>
                    </template>
                  </el-timeline-item>
                </el-timeline>
              </section>

              <section class="growth-section">
                <h3>二、知识补全</h3>
                <el-card
                  v-for="gap in plan?.knowledgeGaps"
                  :key="gap.topic"
                  class="exercise-card"
                  shadow="hover"
                >
                  <div class="gap-header">
                    <h4>{{ gap.topic }}</h4>
                    <el-tag :type="gap.importance === '高' ? 'danger' : 'warning'" size="small">
                      重要度：{{ gap.importance }}
                    </el-tag>
                  </div>
                  <p>{{ gap.description }}</p>
                  <p class="gap-keywords">
                    <strong>关键词：</strong>
                    <el-tag
                      v-for="kw in gap.keywords"
                      :key="kw"
                      size="small"
                      class="keyword-tag"
                    >{{ kw }}</el-tag>
                  </p>
                </el-card>
              </section>

              <section class="growth-section">
                <h3>三、练习题</h3>
                <el-card
                  v-for="exercise in plan?.exercises"
                  :key="exercise.title"
                  class="exercise-card"
                  shadow="hover"
                >
                  <h4>{{ exercise.title }}</h4>
                  <p>{{ exercise.content }}</p>
                </el-card>
              </section>
            </div>
          </el-card>
        </el-col>

        <el-col :span="8">
          <el-card class="summary-card">
            <template #header>
              <span class="card-title">知识补全速览</span>
            </template>
            <ul class="quick-list">
              <li v-for="gap in plan?.knowledgeGaps" :key="gap.topic">{{ gap.topic }}</li>
            </ul>
          </el-card>
        </el-col>
      </el-row>
      </template>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getGrowthPlan } from '@/api'
import type { GrowthPlan } from '@/types'

const route = useRoute()
const interviewId = Number(route.params.id)

const plan = ref<GrowthPlan | null>(null)

onMounted(async () => {
  try {
    plan.value = await getGrowthPlan(interviewId)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载成长方案失败')
  }
})

function downloadMd() {
  if (!plan.value) return
  const blob = new Blob([plan.value.mdContent], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `成长方案-${plan.value.positionTitle}.md`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('下载成功')
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.growth-content {
  line-height: 1.8;
  color: #303133;
}

.growth-section {
  margin-bottom: 32px;
}

.growth-section h3 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.growth-section p {
  color: #606266;
}

.exercise-card {
  margin-bottom: 12px;
}

.exercise-card h4 {
  margin-bottom: 8px;
  font-size: 15px;
}

.summary-card {
  position: sticky;
  top: 24px;
}

.card-title {
  font-weight: 600;
}

.quick-list {
  padding-left: 18px;
  color: #606266;
  line-height: 2;
}

.empty-growth {
  min-height: 500px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.resource-tag {
  margin-left: 8px;
}

.gap-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.gap-header h4 {
  margin: 0;
}

.gap-keywords {
  margin-top: 12px;
}

.keyword-tag {
  margin-right: 6px;
}
</style>
