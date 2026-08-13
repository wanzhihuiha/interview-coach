package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把任务层级的主备表达式解析为可建客户端的厂商和模型；生产路由取决于运行时配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    /** 提供层级路由和厂商连接配置。 */
    private final LlmProperties llmProperties;

    /**
     * 根据层级选择主用厂商和模型。
     */
    public VendorModel selectPrimary(ModelTier tier) {
        // 先定位层级槽位，再校验主用表达式、厂商启用状态和最终模型名。
        LlmProperties.VendorModelConfig config = getTierConfig(tier);
        return resolveVendorModel(config.getPrimary());
    }

    /**
     * 根据层级选择备用厂商和模型。
     */
    public VendorModel selectFallback(ModelTier tier) {
        // 备用表达式使用与主用相同的解析规则；非法配置会在实际降级前失败。
        LlmProperties.VendorModelConfig config = getTierConfig(tier);
        return resolveVendorModel(config.getFallback());
    }

    /**
     * 记录模型切换日志；只保留异常类型，避免供应商错误正文携带提示词或响应内容。
     */
    public void recordFallback(ModelTier tier, VendorModel from, VendorModel to, Throwable cause) {
        log.warn("[ModelRouter] 模型降级: tier={}, from={}/{}, to={}/{}, errorType={}",
                tier, from.getVendorKey(), from.getModel(), to.getVendorKey(), to.getModel(),
                cause.getClass().getSimpleName());
    }

    /** 返回任务层级对应的路由槽位。 */
    private LlmProperties.VendorModelConfig getTierConfig(ModelTier tier) {
        return switch (tier) {
            case L1 -> llmProperties.getTiers().getL1();
            case L2 -> llmProperties.getTiers().getL2();
            case L3 -> llmProperties.getTiers().getL3();
        };
    }

    /**
     * 解析 vendorKey 或 vendorKey:model 格式的配置。
     */
    private VendorModel resolveVendorModel(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("模型路由配置不能为空");
        }
        String[] parts = expression.split(":", 2);
        String vendorKey = parts[0].trim();
        LlmProperties.VendorConfig vendorConfig = llmProperties.getVendors().get(vendorKey);
        if (vendorConfig == null) {
            throw new IllegalArgumentException("未找到厂商配置: " + vendorKey);
        }
        if (!vendorConfig.isEnabled()) {
            throw new IllegalArgumentException("厂商未启用: " + vendorKey);
        }
        VendorType vendorType = parseVendorType(vendorConfig.getType());
        String model = parts.length > 1 ? parts[1].trim() : vendorConfig.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空: " + vendorKey);
        }
        return new VendorModel(vendorKey, vendorType, vendorConfig, model);
    }

    /** 将配置文本转换为受支持协议类型，空值或未知类型直接拒绝。 */
    private VendorType parseVendorType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("厂商类型不能为空");
        }
        try {
            return VendorType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的厂商类型: " + type);
        }
    }
}
