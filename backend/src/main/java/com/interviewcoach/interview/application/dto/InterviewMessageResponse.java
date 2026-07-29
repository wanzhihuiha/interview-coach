package com.interviewcoach.interview.application.dto;

import lombok.Data;

/**
 * 面试消息响应。
 */
@Data
public class InterviewMessageResponse {

    private Long messageId;
    private String phase;
    private String phaseLabel;
    private String role;
    private String content;
    private String topic;
    private Integer depth;
    private Integer seqNo;
    private String createdAt;
}
