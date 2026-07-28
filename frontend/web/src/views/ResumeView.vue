<template>
  <AppLayout>
    <div class="page-container">
      <div class="section-header">
        <h2 class="page-title">我的简历</h2>
        <el-upload
          accept=".pdf,.doc,.docx,.txt"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="handleFileChange"
        >
          <el-button type="primary" :icon="Upload">上传简历</el-button>
        </el-upload>
      </div>

      <el-row :gutter="24">
        <el-col :span="16">
          <el-card>
            <el-table :data="resumes" stripe v-loading="loading">
              <el-table-column prop="fileName" label="简历名称" />
              <el-table-column prop="jobCategory" label="岗位类型" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.status === 'CONFIRMED'" type="success">已确认</el-tag>
                  <el-tag v-else-if="row.status === 'PENDING_CONFIRM'" type="warning">待确认</el-tag>
                  <el-tag v-else-if="row.status === 'PARSING'" type="info">解析中</el-tag>
                  <el-tag v-else-if="row.status === 'PARSE_FAILED'" type="danger">解析失败</el-tag>
                  <el-tag v-else type="info">待解析</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="上传时间" />
              <el-table-column label="操作" width="220">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openDetail(row)">查看</el-button>
                  <el-button link type="warning" @click="reparseResume(row.resumeId)">重新解析</el-button>
                  <el-button v-if="row.status === 'PENDING_CONFIRM'" link type="success" @click="confirmResume(row)">确认</el-button>
                  <el-button link type="danger" @click="removeResume(row.resumeId)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>

        <el-col :span="8">
          <el-card v-if="selectedResume" class="detail-card">
            <template #header>
              <span class="detail-title">简历详情预览</span>
            </template>
            <div v-if="selectedResume.parsedData">
              <h4 class="detail-section-title">基本信息</h4>
              <p class="detail-text">
                姓名：{{ selectedResume.parsedData.basicInfo?.name || '未识别' }}（脱敏）<br>
                工作年限：{{ selectedResume.parsedData.basicInfo?.workingYears || '-' }}<br>
                学历：{{ selectedResume.parsedData.basicInfo?.education || '-' }}<br>
                当前职位：{{ selectedResume.parsedData.basicInfo?.currentPosition || '-' }}
              </p>

              <h4 class="detail-section-title">技能清单</h4>
              <div class="skill-tags">
                <el-tag
                  v-for="skill in selectedResume.parsedData.skillTags"
                  :key="skill"
                  class="skill-tag"
                >
                  {{ skill }}
                </el-tag>
              </div>

              <h4 v-if="selectedResume.parsedData.workExperience?.length" class="detail-section-title">工作经历</h4>
              <div v-for="(work, idx) in selectedResume.parsedData.workExperience" :key="idx" class="experience-item">
                <p class="detail-text">
                  <strong>{{ work.company }}</strong> · {{ work.position }}<br>
                  <span>{{ work.duration }}</span>
                </p>
                <ul class="project-list">
                  <li v-for="(h, hIdx) in work.highlights" :key="hIdx">{{ h }}</li>
                </ul>
              </div>

              <h4 v-if="selectedResume.parsedData.projectExperience?.length" class="detail-section-title">项目经历</h4>
              <div v-for="(proj, idx) in selectedResume.parsedData.projectExperience" :key="idx" class="experience-item">
                <p class="detail-text">
                  <strong>{{ proj.name }}</strong> · {{ proj.role }}<br>
                  <el-tag v-for="tech in proj.techStack" :key="tech" size="small" class="skill-tag">{{ tech }}</el-tag>
                </p>
                <p class="detail-text">{{ proj.description }}</p>
              </div>
            </div>
            <el-empty v-else description="暂无解析详情" />
          </el-card>
          <el-card v-else class="detail-card">
            <el-empty description="选择左侧简历查看详情" />
          </el-card>
        </el-col>
      </el-row>

      <!-- 简历详情弹窗 -->
      <el-dialog
        v-model="detailDialogVisible"
        title="简历画像详情"
        width="700px"
        destroy-on-close
      >
        <div v-if="detailLoading" v-loading="detailLoading" class="dialog-loading" />
        <div v-else-if="detailResume?.parsedData" class="detail-dialog-body">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="简历名称">{{ detailResume.fileName }}</el-descriptions-item>
            <el-descriptions-item label="岗位类型">{{ detailResume.jobCategory || '-' }}</el-descriptions-item>
            <el-descriptions-item label="经验等级">{{ detailResume.experienceLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="置信度">{{ detailResume.parsedData.confidenceLevel }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="dialog-section-title">基本信息</h4>
          <p class="dialog-text">
            姓名：{{ detailResume.parsedData.basicInfo?.name || '未识别' }}<br>
            性别：{{ detailResume.parsedData.basicInfo?.gender || '-' }}<br>
            年龄：{{ detailResume.parsedData.basicInfo?.age || '-' }}<br>
            工作年限：{{ detailResume.parsedData.basicInfo?.workingYears || '-' }}<br>
            当前职位：{{ detailResume.parsedData.basicInfo?.currentPosition || '-' }}<br>
            学历：{{ detailResume.parsedData.basicInfo?.education || '-' }}
          </p>

          <h4 class="dialog-section-title">技能标签</h4>
          <div class="skill-tags">
            <el-tag v-for="skill in detailResume.parsedData.skillTags" :key="skill">{{ skill }}</el-tag>
          </div>

          <h4 v-if="detailResume.parsedData.skillLevel && Object.keys(detailResume.parsedData.skillLevel).length" class="dialog-section-title">技能水平</h4>
          <el-descriptions v-if="detailResume.parsedData.skillLevel" :column="3" border>
            <el-descriptions-item v-for="(level, skill) in detailResume.parsedData.skillLevel" :key="skill" :label="skill">{{ level }}</el-descriptions-item>
          </el-descriptions>

          <h4 v-if="detailResume.parsedData.workExperience?.length" class="dialog-section-title">工作经历</h4>
          <div v-for="(work, idx) in detailResume.parsedData.workExperience" :key="idx" class="dialog-experience">
            <p class="dialog-text">
              <strong>{{ work.company }}</strong> · {{ work.position }}<br>
              <span>{{ work.duration }}</span>
            </p>
            <ul class="dialog-list">
              <li v-for="(h, hIdx) in work.highlights" :key="hIdx">{{ h }}</li>
            </ul>
          </div>

          <h4 v-if="detailResume.parsedData.projectExperience?.length" class="dialog-section-title">项目经历</h4>
          <div v-for="(proj, idx) in detailResume.parsedData.projectExperience" :key="idx" class="dialog-experience">
            <p class="dialog-text">
              <strong>{{ proj.name }}</strong> · {{ proj.role }}<br>
              <el-tag v-for="tech in proj.techStack" :key="tech" size="small" class="skill-tag">{{ tech }}</el-tag>
            </p>
            <p class="dialog-text">{{ proj.description }}</p>
          </div>

          <h4 v-if="detailResume.parsedData.strengths?.length" class="dialog-section-title">优势</h4>
          <ul class="dialog-list">
            <li v-for="(s, idx) in detailResume.parsedData.strengths" :key="idx">{{ s }}</li>
          </ul>

          <h4 v-if="detailResume.parsedData.weaknesses?.length" class="dialog-section-title">薄弱点</h4>
          <ul class="dialog-list">
            <li v-for="(w, idx) in detailResume.parsedData.weaknesses" :key="idx">{{ w }}</li>
          </ul>
        </div>
        <el-empty v-else description="暂无解析详情" />
        <template #footer>
          <el-button @click="detailDialogVisible = false">关闭</el-button>
          <el-button v-if="detailResume && detailResume.status === 'PENDING_CONFIRM'" type="primary" @click="confirmFromDialog">确认解析结果</el-button>
        </template>
      </el-dialog>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import AppLayout from '@/components/AppLayout.vue'
import { getResumeList, uploadResume, getResumeDetail, getResumeProfile, confirmResume as apiConfirmResume, deleteResume, reparseResume as apiReparseResume } from '@/api'
import type { Resume, UserProfileData } from '@/types'
import type { UploadFile } from 'element-plus'

const resumes = ref<Resume[]>([])
const selectedResume = ref<Resume | null>(null)
const loading = ref(false)

const detailDialogVisible = ref(false)
const detailLoading = ref(false)
const detailResume = ref<Resume & { parsedData?: UserProfileData; experienceLevel?: string } | null>(null)

onMounted(loadResumes)

async function loadResumes() {
  loading.value = true
  try {
    resumes.value = await getResumeList()
    if (resumes.value.length > 0 && !selectedResume.value) {
      selectedResume.value = resumes.value[0]
      await loadDetail(resumes.value[0])
    }
  } finally {
    loading.value = false
  }
}

async function loadDetail(resume: Resume) {
  try {
    const detail = await getResumeDetail(resume.resumeId)
    const profile = await getResumeProfile(resume.resumeId)
    const merged = { ...detail, parsedData: profile.profile, experienceLevel: profile.experienceLevel }
    const idx = resumes.value.findIndex(r => r.resumeId === resume.resumeId)
    if (idx >= 0) {
      resumes.value[idx] = merged
    }
    if (selectedResume.value?.resumeId === resume.resumeId) {
      selectedResume.value = merged
    }
    return merged
  } catch (error) {
    ElMessage.error((error as Error).message || '获取简历详情失败')
    return resume
  }
}

async function openDetail(resume: Resume) {
  detailDialogVisible.value = true
  detailLoading.value = true
  detailResume.value = null
  try {
    const merged = await loadDetail(resume)
    detailResume.value = merged
  } finally {
    detailLoading.value = false
  }
}

async function handleFileChange(uploadFile: UploadFile) {
  if (!uploadFile.raw) return
  try {
    const resume = await uploadResume(uploadFile.raw)
    ElMessage.success('上传成功')
    resumes.value.unshift(resume)
    selectedResume.value = resume
    await loadDetail(resume)
  } catch (error) {
    ElMessage.error((error as Error).message || '上传失败')
  }
}

async function confirmResume(resume: Resume) {
  if (!resume.parsedData) {
    ElMessage.warning('暂无解析结果，无法确认')
    return
  }
  try {
    await ElMessageBox.confirm('确认后该简历将用于后续面试，是否继续？', '确认简历', { type: 'warning' })
    await apiConfirmResume(resume.resumeId, resume.parsedData)
    ElMessage.success('确认成功')
    await loadDetail(resume)
  } catch (error) {
    if ((error as Error).message !== 'cancel') {
      ElMessage.error((error as Error).message || '确认失败')
    }
  }
}

async function confirmFromDialog() {
  if (!detailResume.value) return
  await confirmResume(detailResume.value)
  detailDialogVisible.value = false
}

async function reparseResume(resumeId: number) {
  try {
    await ElMessageBox.confirm('重新解析将调用 LLM 再次分析简历，是否继续？', '重新解析', { type: 'warning' })
    const reparseResult = await apiReparseResume(resumeId)
    const idx = resumes.value.findIndex(r => r.resumeId === resumeId)
    if (idx >= 0) {
      resumes.value[idx] = { ...resumes.value[idx], status: reparseResult.status }
    }
    ElMessage.success('重新解析已提交')
    const resume = resumes.value.find(r => r.resumeId === resumeId)
    if (resume) {
      await loadDetail(resume)
    }
  } catch (error) {
    if ((error as Error).message !== 'cancel') {
      ElMessage.error((error as Error).message || '重新解析失败')
    }
  }
}

async function removeResume(resumeId: number) {
  try {
    await ElMessageBox.confirm('确定删除该简历吗？', '提示', { type: 'warning' })
    await deleteResume(resumeId)
    resumes.value = resumes.value.filter(r => r.resumeId !== resumeId)
    if (selectedResume.value?.resumeId === resumeId) {
      selectedResume.value = resumes.value[0] || null
    }
    ElMessage.success('删除成功')
  } catch {
    // 取消删除
  }
}
</script>

<style scoped>
.section-header {
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

.detail-card {
  min-height: 400px;
}

.detail-title {
  font-weight: 600;
}

.detail-section-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.detail-text {
  font-size: 14px;
  color: #606266;
  line-height: 1.8;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.skill-tag {
  margin: 0;
}

.experience-item {
  margin-bottom: 12px;
}

.project-list {
  padding-left: 18px;
  color: #606266;
  font-size: 14px;
  line-height: 1.8;
}

.dialog-loading {
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dialog-section-title {
  margin: 20px 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.dialog-text {
  font-size: 14px;
  color: #606266;
  line-height: 1.8;
}

.dialog-list {
  padding-left: 18px;
  color: #606266;
  font-size: 14px;
  line-height: 1.8;
}

.dialog-experience {
  margin-bottom: 16px;
}
</style>
