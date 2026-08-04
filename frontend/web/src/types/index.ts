export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface UserInfo {
  id: number
  username: string
  nickname?: string
  phone?: string
  avatar?: string
  roles?: string[]
}

export interface Resume {
  resumeId: number
  fileName: string
  fileType?: string
  fileSize?: number
  status: string
  statusLabel?: string
  jobCategory?: string
  jobCategoryLabel?: string
  experienceLevel?: string
  experienceLevelLabel?: string
  createdAt?: string
  updatedAt?: string
  parsedData?: UserProfileData
  parseProgress?: number
  hasConfirmedProfile?: boolean
  parseGeneration?: number
  analysis?: ResumeProfileAnalysisData
  analysisStatus?: string
  analysisErrorMessage?: string
}

export interface ResumeParseStatus {
  resumeId: number
  status: string
  statusLabel?: string
  parseProgress: number
  hasConfirmedProfile?: boolean
  parseGeneration?: number
  analysisStatus?: string
  analysisErrorMessage?: string
  updatedAt?: string
}

export interface UserProfileData {
  basicInfo?: BasicInfo
  skillTags?: string[]
  skillLevel?: Record<string, string>
  projectExperience?: ProjectExperience[]
  workExperience?: WorkExperience[]
}

export interface ResumeAnalysisItem {
  content: string
  evidenceRefs?: string[]
  confidence?: number
}

export interface ResumeSkillAssessment {
  skill: string
  inferredLevel: string
  evidenceRefs?: string[]
  confidence?: number
}

export interface ResumeProfileAnalysisData {
  strengths?: ResumeAnalysisItem[]
  verificationPoints?: ResumeAnalysisItem[]
  skillAssessments?: ResumeSkillAssessment[]
}

export interface BasicInfo {
  workingYears?: string
  currentPosition?: string
  education?: string
}

export interface ProjectExperience {
  name?: string
  role?: string
  techStack?: string[]
  description?: string
}

export interface WorkExperience {
  company?: string
  position?: string
  duration?: string
  highlights?: string[]
}

export interface Position {
  positionId: number
  positionName: string
  companyName?: string
  jobCategory: string
  jobCategoryLabel?: string
  level?: string
  levelLabel?: string
  location?: string
  salaryRange?: string
  jdContent?: string
  isPublic?: boolean
  userId?: number
  archived: boolean
  archivedAt?: string
  latestTaskId?: number
  latestTaskStatus?: PositionAnalysisTaskStatus
  latestTaskStatusLabel?: string
  queueAhead?: number
  profileUsable: boolean
  canConfirm: boolean
  canRetry: boolean
  analysisErrorCode?: string
  analysisErrorMessage?: string
  createdAt?: string
  updatedAt?: string
}

export type PositionAnalysisTaskStatus = 'WAITING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface PositionCreateResult {
  positionId: number
  positionName: string
  taskId: number
  latestTaskStatus: PositionAnalysisTaskStatus
}

export interface PositionAnalysisStatus {
  positionId: number
  taskId?: number
  latestTaskStatus?: PositionAnalysisTaskStatus
  latestTaskStatusLabel?: string
  queueAhead?: number
  analysisErrorCode?: string
  analysisErrorMessage?: string
  profileUsable: boolean
  canConfirm: boolean
  canRetry: boolean
  archived: boolean
}

export interface PositionProfileResponse {
  profileId?: number
  positionId: number
  profile?: PositionProfileData
  taskId?: number
  latestTaskStatus?: PositionAnalysisTaskStatus
  latestTaskStatusLabel?: string
  candidateProfile?: PositionProfileData
  analysisErrorCode?: string
  analysisErrorMessage?: string
  profileUsable: boolean
  canConfirm: boolean
  canRetry: boolean
  archived: boolean
}

export interface PositionListResponse {
  content: Position[]
  totalElements: number
  totalPages: number
  currentPage: number
}

export interface PositionProfileData {
  basicInfo?: PositionBasicInfo
  requiredSkills?: PositionSkillItem[]
  preferredSkills?: PositionSkillItem[]
  probingDirections?: ProbingDirection[]
  interviewFocus?: string[]
  confidenceLevel?: number
}

export interface PositionBasicInfo {
  title?: string
  company?: string
  location?: string
  level?: string
  salaryRange?: string
}

export interface PositionSkillItem {
  skill: string
  importance: string
  depth: string
}

export interface ProbingDirection {
  direction: string
  priority: number
  depthRange: string
  sampleQuestions: string[]
}

export interface InterviewSession {
  id: number
  positionTitle: string
  company: string
  jobCategory?: string
  status: 'ongoing' | 'completed' | 'interrupted'
  score?: number
  level?: string
  statusLabel?: string
  startTime: string
  phases: InterviewPhase[]
  currentPhase?: string
  currentPhaseLabel?: string
}

export interface InterviewPhase {
  key: string
  name: string
  description: string
  questionCount: number
  completed: boolean
  current: boolean
}

export interface InterviewQuestion {
  id: string
  content: string
  phase: string
  topic: string
  depth: number
}

/** 后端原始创建面试响应 */
export interface CreateInterviewResponse {
  interviewId: number
  status: string
  statusLabel?: string
  currentPhase: string
  currentPhaseLabel?: string
  selectedPhases: string[]
  firstQuestion: string
  phaseOrder: string[]
  phaseLabels?: Record<string, string>
}

/** 后端原始面试详情 */
export interface InterviewDetail {
  interviewId: number
  resumeId: number
  positionId: number
  status: string
  statusLabel?: string
  currentPhase: string
  currentPhaseLabel?: string
  currentTopic?: string
  currentDepth?: number
  totalQuestionCount?: number
  selectedPhases: string[]
  phaseLabels?: Record<string, string>
  pendingQuestion?: string
  positionTitle: string
  companyName: string
  jobCategory: string
  overallScore?: number
  grade?: string
  startedAt?: string
  endedAt?: string
}

/** 后端消息记录 */
export interface InterviewMessage {
  messageId: number
  phase: string
  phaseLabel?: string
  role: 'interviewer' | 'candidate'
  content: string
  topic?: string
  depth?: number
  seqNo: number
  createdAt: string
}

/** 后端原始报告 */
export interface InterviewReportDto {
  interviewId: number
  overallScore: number
  grade: string
  phases: Record<string, { phaseLabel?: string; completed: boolean; questionCount: number }>
  dimensions: {
    technicalDepth: number
    technicalBreadth: number
    practicalExperience: number
    expression: number
    learningAbility: number
  }
  strengths: string[]
  weaknesses: string[]
  conclusion: string
  mdContent: string
}

export interface ScoreItem {
  name: string
  score: number
}

export interface InterviewReport {
  id: number
  positionTitle: string
  totalScore: number
  level: string
  conclusion?: string
  scores: ScoreItem[]
  phaseSummary: string[]
  weakPoints: string[]
  strongPoints: string[]
  mdContent: string
}

export interface GrowthPlan {
  id: number
  positionTitle: string
  learningPath: {
    phase: string
    duration?: string
    goal?: string
    tasks: string[]
    resources?: {
      name: string
      type: string
      url: string
    }[]
  }[]
  exercises: {
    title: string
    content: string
  }[]
  knowledgeGaps: {
    topic: string
    description: string
    importance: string
    keywords: string[]
  }[]
  mdContent: string
}
