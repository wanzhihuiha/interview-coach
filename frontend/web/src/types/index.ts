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
  jobCategory?: string
  createdAt?: string
  updatedAt?: string
  parsedData?: UserProfileData
}

export interface UserProfileData {
  basicInfo?: BasicInfo
  skillTags?: string[]
  skillLevel?: Record<string, string>
  projectExperience?: ProjectExperience[]
  workExperience?: WorkExperience[]
  strengths?: string[]
  weaknesses?: string[]
  confidenceLevel?: number
}

export interface BasicInfo {
  name?: string
  age?: string
  gender?: string
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
  level?: string
  location?: string
  salaryRange?: string
  jdContent?: string
  parseStatus: string
  auditStatus: string
  createdAt?: string
  updatedAt?: string
  profile?: PositionProfileData
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
  status: 'ongoing' | 'completed' | 'interrupted'
  score?: number
  level?: string
  startTime: string
  phases: InterviewPhase[]
  currentPhase?: string
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
  currentPhase: string
  selectedPhases: string[]
  firstQuestion: string
  phaseOrder: string[]
}

/** 后端原始面试详情 */
export interface InterviewDetail {
  interviewId: number
  resumeId: number
  positionId: number
  status: string
  currentPhase: string
  currentTopic?: string
  currentDepth?: number
  totalQuestionCount?: number
  selectedPhases: string[]
    pendingQuestion?: string
    positionTitle: string
    companyName: string
    overallScore?: number
    grade?: string
    startedAt?: string
    endedAt?: string
}

/** 后端消息记录 */
export interface InterviewMessage {
  messageId: number
  phase: string
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
  phases: Record<string, { completed: boolean; questionCount: number }>
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
