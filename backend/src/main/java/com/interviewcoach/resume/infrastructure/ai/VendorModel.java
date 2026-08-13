package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import lombok.Getter;

/**
 * 路由器输出、客户端工厂消费的一次模型选择，保留厂商键、协议、敏感配置引用和最终模型名。
 */
@Getter
public class VendorModel {

    /** 路由配置引用的厂商键，用于定位配置和非敏感日志。 */
    private final String vendorKey;
    /** 客户端工厂选择协议适配分支所需的厂商类型。 */
    private final VendorType vendorType;
    /** 厂商连接配置引用，可能含 API Key，不得整体记录。 */
    private final LlmProperties.VendorConfig vendorConfig;
    /** 本次路由最终选择的模型名。 */
    private final String model;

    /** 创建经路由器校验后的不可变模型选择。 */
    public VendorModel(String vendorKey, VendorType vendorType, LlmProperties.VendorConfig vendorConfig, String model) {
        this.vendorKey = vendorKey;
        this.vendorType = vendorType;
        this.vendorConfig = vendorConfig;
        this.model = model;
    }
}
