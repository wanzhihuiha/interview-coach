package com.interviewcoach.common.llm;

/**
 * LLM 数据块的来源类型。
 *
 * <p>来源只用于检测、路由和审计；无论来源为何，数据块都不具备指令权限。</p>
 */
public enum LlmDataSource {
    /**
     * 服务端为当前任务补充的上下文数据；仍按不可信数据处理，不获得 system 指令权限。
     */
    SERVER_CONTEXT,

    /**
     * 候选人上传或录入的简历正文，可能包含个人敏感信息。
     */
    RESUME_TEXT,

    /**
     * 岗位方提供或系统保存的职位描述正文。
     */
    JOB_DESCRIPTION_TEXT,

    /**
     * 面试流程已生成或已保存的问题文本。
     */
    INTERVIEW_QUESTION,

    /**
     * 候选人在面试流程中提交的回答文本。
     */
    INTERVIEW_ANSWER,

    /**
     * 用户针对面试、报告或成长建议提供的反馈文本。
     */
    USER_FEEDBACK,

    /**
     * 服务端已确认并作为事实引用的数据，例如已选定的简历项目名称。
     */
    VERIFIED_FACTS,

    /**
     * 服务端已确认可作为后续输入的评估结果。
     */
    VERIFIED_EVALUATION,

    /**
     * 先前模型输出经业务流程保留后形成的派生内容，不因被保留而获得指令权限。
     */
    MODEL_DERIVED_CONTENT
}
