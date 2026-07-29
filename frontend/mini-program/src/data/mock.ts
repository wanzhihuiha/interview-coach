import type { Resume } from '@/api/resume';
import type { Position } from '@/api/position';
import type { InterviewSession, InterviewMessage, InterviewReport, GrowthPlan } from '@/api/interview';

export const mockResumes: Resume[] = [
  {
    resumeId: 1,
    fileName: '张三-Java开发工程师.pdf',
    fileType: 'PDF',
    fileSize: 1024 * 1024 * 1.2,
    status: 'CONFIRMED',
    jobCategory: '后端开发',
    createdAt: '2026-07-20T10:00:00',
    updatedAt: '2026-07-20T10:00:00',
  },
  {
    resumeId: 2,
    fileName: '张三-高级后端工程师.docx',
    fileType: 'DOCX',
    fileSize: 1024 * 512,
    status: 'PENDING',
    jobCategory: '后端开发',
    createdAt: '2026-07-25T14:30:00',
    updatedAt: '2026-07-25T14:30:00',
  },
];

export const mockPositions: Position[] = [
  {
    positionId: 1,
    positionName: '高级 Java 开发工程师',
    companyName: '某知名互联网公司',
    jobCategory: '后端开发',
    level: 'P6/P7',
    location: '北京·海淀',
    salaryRange: '35K-55K',
    parseStatus: 'DONE',
    auditStatus: 'APPROVED',
    createdAt: '2026-07-18T09:00:00',
    updatedAt: '2026-07-18T09:00:00',
  },
  {
    positionId: 2,
    positionName: '后端架构师',
    companyName: '某一线大厂',
    jobCategory: '后端开发',
    level: 'P8',
    location: '上海·浦东',
    salaryRange: '50K-80K',
    parseStatus: 'DONE',
    auditStatus: 'APPROVED',
    createdAt: '2026-07-22T11:00:00',
    updatedAt: '2026-07-22T11:00:00',
  },
  {
    positionId: 3,
    positionName: 'Go 开发工程师',
    companyName: '某独角兽企业',
    jobCategory: '后端开发',
    level: 'P5/P6',
    location: '杭州·余杭',
    salaryRange: '25K-40K',
    parseStatus: 'DONE',
    auditStatus: 'APPROVED',
    createdAt: '2026-07-26T16:00:00',
    updatedAt: '2026-07-26T16:00:00',
  },
];

export const mockInterviewDetail = {
  interviewId: 101,
  resumeId: 1,
  positionId: 1,
  status: 'ONGOING',
  currentPhase: 'PROFESSIONAL',
  currentTopic: 'Java 并发编程',
  currentDepth: 2,
  totalQuestionCount: 8,
  selectedPhases: ['SELF_INTRO', 'PROFESSIONAL', 'RESUME_DISCUSSION', 'BEHAVIORAL', 'ENDING'],
  pendingQuestion: '请谈谈你对线程池核心参数的理解，以及在实际项目中如何调优？',
  positionTitle: '高级 Java 开发工程师',
  companyName: '某知名互联网公司',
  overallScore: 82,
  grade: 'B+',
  startedAt: '2026-07-27T10:00:00',
};

export const mockInterviews: InterviewSession[] = [
  {
    id: 101,
    positionTitle: '高级 Java 开发工程师',
    company: '某知名互联网公司',
    status: 'ongoing',
    score: 82,
    level: 'B+',
    startTime: '2026-07-27 10:00',
    currentPhase: 'PROFESSIONAL',
    phases: [
      { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1, completed: true, current: false },
      { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8, completed: false, current: true },
      { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3, completed: false, current: false },
      { key: 'behavior', name: '行为面试', description: 'STAR问题，考察软技能', questionCount: 4, completed: false, current: false },
      { key: 'ending', name: '结束', description: '面试结束，生成报告', questionCount: 0, completed: false, current: false },
    ],
  },
  {
    id: 102,
    positionTitle: '后端架构师',
    company: '某一线大厂',
    status: 'completed',
    score: 88,
    level: 'A',
    startTime: '2026-07-25 14:30',
    currentPhase: 'ENDING',
    phases: [
      { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1, completed: true, current: false },
      { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8, completed: true, current: false },
      { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3, completed: true, current: false },
      { key: 'behavior', name: '行为面试', description: 'STAR问题，考察软技能', questionCount: 4, completed: true, current: false },
      { key: 'ending', name: '结束', description: '面试结束，生成报告', questionCount: 0, completed: true, current: false },
    ],
  },
  {
    id: 103,
    positionTitle: 'Go 开发工程师',
    company: '某独角兽企业',
    status: 'interrupted',
    score: undefined,
    level: undefined,
    startTime: '2026-07-24 09:15',
    currentPhase: 'RESUME_DISCUSSION',
    phases: [
      { key: 'intro', name: '自我介绍', description: '热身放松，了解基本信息', questionCount: 1, completed: true, current: false },
      { key: 'professional', name: '专业面试', description: '深度提问，考察专业技能', questionCount: 8, completed: true, current: false },
      { key: 'resume', name: '简历探讨', description: '项目追问，深入了解项目经历', questionCount: 3, completed: false, current: true },
      { key: 'behavior', name: '行为面试', description: 'STAR问题，考察软技能', questionCount: 4, completed: false, current: false },
      { key: 'ending', name: '结束', description: '面试结束，生成报告', questionCount: 0, completed: false, current: false },
    ],
  },
];

export const mockInterviewMessages: InterviewMessage[] = [
  {
    messageId: 1,
    phase: 'SELF_INTRO',
    role: 'interviewer',
    content: '请你先做一个简单的自我介绍。',
    seqNo: 1,
    createdAt: '2026-07-27T10:00:00',
  },
  {
    messageId: 2,
    phase: 'SELF_INTRO',
    role: 'candidate',
    content: '您好，我叫张三，有 5 年 Java 后端开发经验，熟悉高并发、微服务架构……',
    seqNo: 2,
    createdAt: '2026-07-27T10:01:00',
  },
  {
    messageId: 3,
    phase: 'PROFESSIONAL',
    role: 'interviewer',
    content: '请谈谈你对线程池核心参数的理解，以及在实际项目中如何调优？',
    topic: 'Java 并发编程',
    depth: 2,
    seqNo: 3,
    createdAt: '2026-07-27T10:03:00',
  },
];

export const mockInterviewReport: InterviewReport = {
  id: 102,
  positionTitle: '后端架构师',
  totalScore: 88,
  level: 'A',
  scores: [
    { name: '技术深度', score: 90 },
    { name: '技术广度', score: 85 },
    { name: '实践经验', score: 88 },
    { name: '表达能力', score: 86 },
    { name: '学习能力', score: 89 },
  ],
  phaseSummary: [
    '自我介绍 (1题) · 已完成',
    '专业面试 (8题) · 已完成',
    '简历探讨 (3题) · 已完成',
    '行为面试 (4题) · 已完成',
    '结束 (0题) · 已完成',
  ],
  weakPoints: [
    '对分布式事务的实践经验描述不够具体，建议补充 Seata 或 TCC 的实际案例。',
    '在回答高可用架构设计时，对降级策略的触发条件阐述不够清晰。',
  ],
  strongPoints: [
    '对 JVM 内存模型和 GC 调优有深入理解，能结合生产案例说明。',
    '微服务拆分思路清晰，能权衡业务边界与性能。',
  ],
  mdContent: '# 面试评估报告\n\n## 总评\n得分：88 分，等级：A\n\n## 优势\n- 对 JVM 内存模型和 GC 调优有深入理解\n- 微服务拆分思路清晰\n\n## 待提升\n- 分布式事务实践经验需补充\n- 高可用降级策略需更具体\n',
};

export const mockGrowthPlan: GrowthPlan = {
  id: 102,
  positionTitle: '后端架构师',
  learningPath: [
    {
      phase: '分布式事务',
      duration: '2 周',
      goal: '掌握 Seata AT/TCC 模式，能结合业务场景选型',
      tasks: [
        '阅读 Seata 官方文档并完成一个 AT 模式 Demo',
        '整理过去项目中遇到的一致性问题，输出案例',
        '学习 TCC 与 Saga 的适用场景',
      ],
      resources: [
        { name: 'Seata 官方文档', type: 'doc', url: 'https://seata.apache.org/zh-cn/docs/overview/what-is-seata' },
      ],
    },
    {
      phase: '高可用架构',
      duration: '1 周',
      goal: '能清晰阐述降级、熔断、限流的触发条件与实现',
      tasks: [
        '复习 Sentinel 熔断降级策略',
        '设计一个电商大促场景的降级方案',
      ],
    },
  ],
  exercises: [
    {
      title: '线程池调优实战',
      content: '给定一个 QPS 3000、平均耗时 50ms 的接口，计算合适的核心线程数、最大线程数和队列长度，并说明理由。',
    },
    {
      title: '分布式锁设计',
      content: '基于 Redis 实现一个可重入分布式锁，讨论过期时间、续期与主从切换问题。',
    },
  ],
  knowledgeGaps: [
    {
      topic: '分布式事务',
      description: '对 Seata 的使用细节和 TCC 模式的理解有待加深',
      importance: '高',
      keywords: ['Seata', 'TCC', 'Saga', '最终一致性'],
    },
    {
      topic: '高可用降级',
      description: '降级策略触发条件与执行细节描述不够清晰',
      importance: '中',
      keywords: ['降级', '熔断', '限流', 'Sentinel'],
    },
  ],
  mdContent: '# 成长计划\n\n## 学习路径\n1. 分布式事务（2 周）\n2. 高可用架构（1 周）\n\n## 练习\n- 线程池调优实战\n- 分布式锁设计\n',
};
