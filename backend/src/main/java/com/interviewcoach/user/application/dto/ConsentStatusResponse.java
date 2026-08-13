package com.interviewcoach.user.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前认证用户的协议同意状态响应。
 *
 * <p>同意服务根据数据库中是否存在对应类型的历史记录构造本对象，Controller 再将它返回给前端，
 * 供前端判断是否需要展示协议确认流程。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsentStatusResponse {

    /**
     * LLM 服务条款状态；{@code true} 表示存在该类型的同意记录，{@code false} 表示不存在。
     */
    private boolean llmService;

    /**
     * 隐私政策状态；{@code true} 表示存在该类型的同意记录，{@code false} 表示不存在。
     */
    private boolean privacyPolicy;
}
