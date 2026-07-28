package com.interviewcoach.interview.application.dto;

import lombok.Data;

/**
 * 题库题目项，用于临时 RAG 和永久 RAG 的读写传递。
 */
@Data
public class QuestionBankItem {

    private Long id;
    private String jobCategory;
    private String phase;
    private String topicId;
    private String topicName;
    private String content;
    private String expectedAnswer;
    private Integer usageCount;
    private Integer difficultyLevel;
}
