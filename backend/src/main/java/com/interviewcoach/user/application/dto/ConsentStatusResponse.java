package com.interviewcoach.user.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户同意状态响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsentStatusResponse {

    /**
     * 是否已同意 LLM 服务使用条款。
     */
    private boolean llmService;

    /**
     * 是否已同意隐私政策。
     */
    private boolean privacyPolicy;
}
