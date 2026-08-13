package com.interviewcoach.interview.application.dto;

import lombok.Data;

/**
 * Agent 题库工具与管理端题库 API 共同使用的题目传输对象。
 *
 * <p>管理端返回时 {@link #id} 是临时或永久题目 ID；评估 Agent 请求保存临时题时，
 * 该字段临时承载来源面试 ID，并由工具映射到临时题目的 {@code sourceInterviewId}。</p>
 */
@Data
public class QuestionBankItem {

    /** 题目 ID；评估 Agent 新建临时题时例外承载来源面试 ID。 */
    private Long id;

    /** 岗位类别稳定编码，用于题库分层和精确筛选。 */
    private String jobCategory;

    /** 岗位类别中文展示名称，主要由管理端映射填充。 */
    private String jobCategoryLabel;

    /** 面试环节稳定编码。 */
    private String phase;

    /** 面试环节中文展示名称，主要由管理端映射填充。 */
    private String phaseLabel;

    /** 专业主题标识，可为空。 */
    private String topicId;

    /** 专业主题名称，可为空。 */
    private String topicName;

    /** 题目正文；临时入库和管理端编辑时必须为非空白文本。 */
    private String content;

    /** 可选参考答案。 */
    private String expectedAnswer;

    /** 永久题目的累计使用次数字段；临时题目返回时为空，当前出题流程不会递增该值。 */
    private Integer usageCount;

    /** 当前持久化和出题使用的难度值；空值会在部分映射入口回退为 3，其取值依据仍未确认。 */
    private Integer difficultyLevel;
}
