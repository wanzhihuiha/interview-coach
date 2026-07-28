package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import lombok.Getter;

/**
 * 路由选中的厂商与模型信息。
 */
@Getter
public class VendorModel {

    private final String vendorKey;
    private final VendorType vendorType;
    private final LlmProperties.VendorConfig vendorConfig;
    private final String model;

    public VendorModel(String vendorKey, VendorType vendorType, LlmProperties.VendorConfig vendorConfig, String model) {
        this.vendorKey = vendorKey;
        this.vendorType = vendorType;
        this.vendorConfig = vendorConfig;
        this.model = model;
    }
}
