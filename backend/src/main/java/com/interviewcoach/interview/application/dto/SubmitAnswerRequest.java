package com.interviewcoach.interview.application.dto;

import lombok.Data;

/**
 * 提交单轮面试回答的 HTTP 请求体。
 *
 * <p>回答可能包含个人经历等敏感文本。应用服务校验后会先预留当前轮次，再把回答交给评估与
 * 出题流程，并在同一短事务中与下一题一起持久化；处理失败时只释放本轮预留。</p>
 */
@Data
public class SubmitAnswerRequest {

    /** 候选人回答原文；空白或超过当前服务长度上限时会被拒绝。 */
    private String answer;
}
