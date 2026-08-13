package com.interviewcoach.interview.application.dto;

import lombok.Data;

/**
 * 按消息序号升序返回的单条面试消息。
 *
 * <p>环节和角色保留持久化编码；创建时间转换为无时区信息的固定格式字符串。</p>
 */
@Data
public class InterviewMessageResponse {

    /** 消息数据库 ID。 */
    private Long messageId;

    /** 消息产生时所属的面试环节编码。 */
    private String phase;

    /** 已知环节编码对应的中文名称；未知历史编码保持原值。 */
    private String phaseLabel;

    /** 消息发送方编码，当前主流程写入 {@code interviewer} 或 {@code candidate}。 */
    private String role;

    /** 面试官问题、结束语或候选人回答的原始消息正文。 */
    private String content;

    /** 消息产生时的主题名称，可为空。 */
    private String topic;

    /** 消息产生时的题目深度，可为空。 */
    private Integer depth;

    /** 同一面试内由应用服务分配的连续消息序号。 */
    private Integer seqNo;

    /** 格式为 {@code yyyy-MM-dd HH:mm:ss} 的本地日期时间字符串，不携带时区或偏移量。 */
    private String createdAt;
}
