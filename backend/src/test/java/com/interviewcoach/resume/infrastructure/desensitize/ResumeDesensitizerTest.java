package com.interviewcoach.resume.infrastructure.desensitize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumeDesensitizerTest {

    @Test
    void shouldMaskDirectIdentifiersButKeepOrganizations() {
        ResumeDesensitizer.DesensitizationResult result = new ResumeDesensitizer().desensitize("""
                姓名：张三
                手机：13800138000
                邮箱：zhangsan@example.com
                身份证：110101199001011234
                生日：1990-01-01
                地址：北京市朝阳区某路1号
                学校：示例大学
                公司：示例科技有限公司
                """);

        assertThat(result.text()).doesNotContain(
                "张三", "13800138000", "zhangsan@example.com", "110101199001011234", "1990-01-01", "某路1号");
        assertThat(result.text()).contains("示例大学", "示例科技有限公司");
        assertThat(result.maskedTypes()).contains("NAME", "PHONE", "EMAIL", "ID_CARD", "BIRTHDAY", "ADDRESS");
    }
}
