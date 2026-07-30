package com.interviewcoach.resume.application.dto;

/**
 * 手动辅助分析请求。mode 由应用服务转换为公开模式，feedback 只在当前调用链内存中使用。
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
