package com.interviewcoach.resume.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 简历列表中的单条摘要，由简历记录及正式画像存在性组合生成。
 */
@Data
public class ResumeListItemResponse {

    /** 简历数据库主键，用于进入详情和后续操作。 */
    private Long resumeId;
    /** 上传时保留的原始文件名。 */
    private String fileName;
    /** 当前解析状态的稳定英文编码。 */
    private String status;
    /** 当前解析状态的中文展示名。 */
    private String statusLabel;
    /** 简历主记录中由当前事实草稿或正式画像派生的岗位分类稳定编码；尚未解析出事实时为空。 */
    private String jobCategory;
    /** 当前岗位分类编码对应的中文展示名；尚未解析出事实时为空。 */
    private String jobCategoryLabel;
    /** 是否已有可供后续面试使用的正式事实画像。 */
    private Boolean hasConfirmedProfile;
    /** 最近一次解析失败说明；当前无错误时为空。 */
    private String parseErrorMessage;
    /** 简历记录创建时间。 */
    private LocalDateTime createdAt;
    /** 简历记录最后更新时间，用于列表排序和刷新展示。 */
    private LocalDateTime updatedAt;
}
