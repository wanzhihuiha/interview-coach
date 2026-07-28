package com.interviewcoach.resume.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 模型路由器单元测试。
 */
class ModelRouterTest {

    @Test
    void shouldResolvePrimaryVendorAndModel() {
        LlmProperties properties = createProperties();
        ModelRouter router = new ModelRouter(properties);

        VendorModel primary = router.selectPrimary(ModelTier.L2);

        assertThat(primary.getVendorKey()).isEqualTo("dashscope");
        assertThat(primary.getVendorType()).isEqualTo(VendorType.DASHSCOPE);
        assertThat(primary.getModel()).isEqualTo("qwen-plus");
    }

    @Test
    void shouldResolveFallbackVendorAndOverrideModel() {
        LlmProperties properties = createProperties();
        properties.getTiers().getL2().setFallback("openai:gpt-4o");
        ModelRouter router = new ModelRouter(properties);

        VendorModel fallback = router.selectFallback(ModelTier.L2);

        assertThat(fallback.getVendorKey()).isEqualTo("openai");
        assertThat(fallback.getVendorType()).isEqualTo(VendorType.OPENAI_COMPATIBLE);
        assertThat(fallback.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void shouldThrowExceptionWhenVendorDisabled() {
        LlmProperties properties = createProperties();
        properties.getVendors().get("openai").setEnabled(false);
        properties.getTiers().getL2().setFallback("openai");
        ModelRouter router = new ModelRouter(properties);

        assertThatThrownBy(() -> router.selectFallback(ModelTier.L2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("厂商未启用");
    }

    private LlmProperties createProperties() {
        LlmProperties properties = new LlmProperties();

        LlmProperties.VendorConfig dashscope = new LlmProperties.VendorConfig();
        dashscope.setType("DASHSCOPE");
        dashscope.setEnabled(true);
        dashscope.setApiKey("dashscope-key");
        dashscope.setModel("qwen-plus");

        LlmProperties.VendorConfig openai = new LlmProperties.VendorConfig();
        openai.setType("OPENAI_COMPATIBLE");
        openai.setEnabled(true);
        openai.setApiKey("openai-key");
        openai.setBaseUrl("https://api.openai.com");
        openai.setModel("gpt-3.5-turbo");

        properties.setVendors(Map.of("dashscope", dashscope, "openai", openai));
        properties.getTiers().getL2().setPrimary("dashscope");
        properties.getTiers().getL2().setFallback("openai");
        return properties;
    }
}
