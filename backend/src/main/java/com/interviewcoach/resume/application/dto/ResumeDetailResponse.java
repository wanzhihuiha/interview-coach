package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 简历详情响应，由应用服务聚合简历记录、当前草稿和正式画像生成。
 */
@Data
public class ResumeDetailResponse {

    /** 简历数据库主键。 */
    private Long resumeId;
    /** 上传时保留的原始文件名。 */
    private String fileName;
    /** 上传文件的扩展名类型。 */
    private String fileType;
    /** 上传文件大小，单位为字节。 */
    private Long fileSize;
    /** 当前解析状态的稳定英文编码。 */
    private String status;
    /** 当前解析状态的中文展示名。 */
    private String statusLabel;
    /** 简历主记录中由当前事实草稿或正式画像派生的岗位分类稳定编码；尚未解析出事实时为空。 */
    private String jobCategory;
    /** 当前岗位分类编码对应的中文展示名；尚未解析出事实时为空。 */
    private String jobCategoryLabel;
    /** 有当前草稿时返回草稿事实，否则返回正式画像；两者都不存在时为空。 */
    private UserProfileData parsedData;
    /** 是否已经存在可供后续面试使用的正式事实画像。 */
    private Boolean hasConfirmedProfile;
    /** 最近一次解析失败的稳定错误码；当前记录无错误时为空。 */
    private String parseErrorCode;
    /** 最近一次解析失败的用户可见说明；当前记录无错误时为空。 */
    private String parseErrorMessage;
    /** 简历记录创建时间。 */
    private LocalDateTime createdAt;
    /** 正式画像最近一次确认时间；未确认时为空。 */
    private LocalDateTime confirmedAt;
}
