package com.interviewcoach.resume.infrastructure.desensitize;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import org.springframework.stereotype.Component;

/**
 * 简历文本脱敏器，在发送给 LLM 前对敏感信息做简单脱敏处理。
 */
@Component
public class ResumeDesensitizer {

    /**
     * 对简历文本进行脱敏。
     * MVP 阶段采用规则替换：姓名/公司名/项目名替换为通用标签。
     */
    @AgentPermission(AgentType.RESUME_ANALYSIS)
    public String desensitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        // 手机号
        result = result.replaceAll("1[3-9]\\d{9}", "[PHONE]");
        // 邮箱
        result = result.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[EMAIL]");
        // 身份证号（18位）
        result = result.replaceAll("\\b\\d{17}[\\dXx]\\b", "[ID_CARD]");
        return result;
    }
}
