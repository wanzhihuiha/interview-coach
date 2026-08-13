package com.interviewcoach.resume.application.dto;

/**
 * 手动辅助分析请求。
 *
 * @param mode 公开的分析模式编码，由应用服务解析并限制为 REGENERATE 或 REFINE；为空或非法时拒绝请求
 * @param feedback REFINE 模式必填的用户反馈，REGENERATE 模式会忽略；属于敏感输入且只在当前调用链内存中存在
 */
public record ResumeProfileAnalysisRetryRequest(String mode, String feedback) {

    /**
     * feedback 属于只在当前调用链使用的敏感输入，禁止进入请求诊断日志。
     */
    @Override
    public String toString() {
        return "ResumeProfileAnalysisRetryRequest[mode=" + mode + ", feedback=<redacted>]";
    }
}
