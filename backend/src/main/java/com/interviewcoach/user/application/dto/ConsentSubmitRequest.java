package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * 前端提交的协议同意请求。
 *
 * <p>Controller 将本对象与安全上下文中的用户身份、当前 HTTP 请求元数据一并交给同意服务；
 * 服务把每个有效类型追加保存为独立同意记录。</p>
 */
@Data
public class ConsentSubmitRequest {

    /**
     * 要同意的稳定英文类型码列表，当前支持 {@code LLM_SERVICE} 和 {@code PRIVACY_POLICY}；
     * 服务解析后会去除重复类型，再逐类追加记录。
     */
    @NotEmpty(message = "同意类型不能为空")
    private List<String> consentTypes;

    /**
     * 客户端确认的协议版本；为 {@code null}、空串或纯空白时使用当前固定值 {@code 1.0}。
     * 该默认版本的治理依据缺失，目前没有可靠项目文档说明其版本演进规则。
     */
    private String version;
}
