package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import lombok.Data;

/**
 * 简历事实草稿、正式画像及可选 AI 分析的聚合响应。
 */
@Data
public class ResumeProfileResponse {

    /** 正式画像数据库主键；尚未确认画像时为空。 */
    private Long profileId;
    /** 当前响应所属的简历数据库主键。 */
    private Long resumeId;
    /**
     * 兼容字段：有草稿时返回草稿，否则返回已确认画像。
     */
    private UserProfileData profile;
    /** 已确认的事实画像；不存在正式画像时为空。 */
    private UserProfileData confirmedProfile;
    /** 当前解析代次下可编辑的事实草稿；不存在草稿时为空。 */
    private UserProfileData draftProfile;
    /** 最近一次成功的辅助分析结果；新任务运行或失败时可继续保留旧成功结果。 */
    private ResumeProfileAnalysisData analysis;
    /** 是否存在已确认的正式事实画像。 */
    private Boolean hasConfirmedProfile;
    /** 当前事实草稿对应的解析代次，用于防止旧页面覆盖新草稿。 */
    private Long parseGeneration;
    /** 当前草稿优先、否则正式画像的经验等级稳定英文编码；两者都没有时为空。 */
    private String experienceLevel;
    /** 当前经验等级编码对应的中文展示名；没有可用画像时为空。 */
    private String experienceLevelLabel;
    /** 简历解析状态的稳定英文编码。 */
    private String status;
    /** 简历解析状态的中文展示名。 */
    private String statusLabel;
    /** 最新辅助分析任务的对外有效状态；成功记录损坏时降级为 FAILED，尚无分析记录时为空。 */
    private String analysisStatus;
    /** 最新辅助分析任务的对外错误说明；成功记录损坏时返回安全提示，其他无错误时为空。 */
    private String analysisErrorMessage;
    /** 当前保留的辅助分析结果是否与正式画像匹配并可用于面试。 */
    private Boolean analysisUsableForInterview;
    /** 当前状态是否允许基于旧成功结果和反馈继续调整。 */
    private Boolean analysisRefineAllowed;
    /** 最新辅助分析任务代次，用于区分过期 Worker 写回。 */
    private Long analysisTaskGeneration;
    /** 最新辅助分析任务模式的稳定英文编码；尚无任务时为空。 */
    private String analysisMode;
}
