package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * 提交用户同意请求。
 */
@Data
public class ConsentSubmitRequest {

    /**
     * 同意类型列表，可选值：LLM_SERVICE、PRIVACY_POLICY。
     */
    @NotEmpty(message = "同意类型不能为空")
    private List<String> consentTypes;

    /**
     * 同意版本号，为空时默认使用 1.0。
     */
    private String version;
}
