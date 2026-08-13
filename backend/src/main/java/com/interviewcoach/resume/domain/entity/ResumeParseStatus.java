package com.interviewcoach.resume.domain.entity;

/**
 * 简历当前事实解析流程的状态；接口还需结合正式画像存在性判断简历能否用于面试。
 */
public enum ResumeParseStatus {
    /** 上传或重试已登记，等待 Worker 认领当前解析代次。 */
    PENDING("PENDING", "待解析"),
    /** Worker 已认领当前代次，正在提取文件并调用模型生成事实草稿。 */
    PARSING("PARSING", "解析中"),
    /** 当前代次事实草稿已生成，等待用户修改或确认。 */
    PENDING_CONFIRM("PENDING_CONFIRM", "待确认"),
    /** 用户已确认当前草稿并保存正式事实画像。 */
    CONFIRMED("CONFIRMED", "已确认"),
    /** 当前代次解析失败；此前存在的正式画像仍可独立保留。 */
    PARSE_FAILED("PARSE_FAILED", "解析失败");

    /** 持久化和 API 使用的稳定英文编码。 */
    private final String value;
    /** 面向用户展示的中文状态名称。 */
    private final String displayName;

    /** 绑定稳定编码和中文展示名。 */
    ResumeParseStatus(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    /** 返回持久化和 API 使用的稳定英文编码。 */
    public String getValue() {
        return value;
    }

    /** 返回面向用户展示的中文状态名称。 */
    public String getDisplayName() {
        return displayName;
    }
}
