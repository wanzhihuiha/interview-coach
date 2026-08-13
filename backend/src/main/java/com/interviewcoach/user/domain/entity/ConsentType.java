package com.interviewcoach.user.domain.entity;

/**
 * 用户协议同意记录的稳定类型码。
 *
 * <p>前端提交枚举名称，同意服务解析后以名称写入数据库，并在 AI 处理前按类型检查历史记录。
 * 当前枚举没有中文 {@code displayName}，API 也没有返回对应中文 Label；该展示结构缺口不由注释解决。</p>
 */
public enum ConsentType {
    /**
     * 用户同意使用 LLM 服务；简历 AI 处理前会要求存在该类型记录。
     */
    LLM_SERVICE,

    /**
     * 用户同意隐私政策；简历 AI 处理前会与 LLM 服务同意一并检查。
     */
    PRIVACY_POLICY
}
