package com.interviewcoach.resume.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户上传简历的主记录，对应数据库 resume 表；上传、解析、确认和面试锁定流程在此保存文件元数据与当前任务状态。
 */
@Entity
@Table(name = "resume")
@Getter
@Setter
public class Resume {

    /** 简历记录的数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 拥有该简历的用户 ID，所有用户接口均据此校验资源归属。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 上传时保留的原始文件名，用于列表和详情展示。 */
    @Column(name = "resume_name", nullable = false, length = 255)
    private String resumeName;

    /** 文件服务落盘后保存的路径；数据库删除成功后由应用层尽力删除对应文件。 */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 上传文件的扩展名类型，当前解析器据此选择 PDF 或 TXT 提取分支。 */
    @Column(name = "file_type", length = 20)
    private String fileType;

    /** 上传文件大小，单位为字节。 */
    @Column(name = "file_size")
    private Long fileSize;

    /** 当前事实解析流程的状态；该状态不代表正式画像是否存在。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 30)
    private ResumeParseStatus parseStatus = ResumeParseStatus.PENDING;

    /** 事实解析任务代次；每次重新解析递增，用于拒绝旧 Worker 和旧页面写回。 */
    @Column(name = "parse_generation", nullable = false)
    private Long parseGeneration = 0L;

    /** 当前代次从待处理状态被 Worker 认领的时间；尚未认领时为空。 */
    @Column(name = "parse_started_at")
    private LocalDateTime parseStartedAt;

    /** 最近一次事实解析失败的稳定错误码；当前无错误时为空。 */
    @Column(name = "parse_error_code", length = 50)
    private String parseErrorCode;

    /** 最近一次事实解析失败的安全说明；不得保存简历正文或模型原文。 */
    @Column(name = "parse_error_message", length = 500)
    private String parseErrorMessage;

    /** 手动解析任务通过额度准入时的日期；免费任务或无需恢复结算时为空。 */
    @Column(name = "parse_quota_date")
    private LocalDate parseQuotaDate;

    /** 手动解析任务的 Redis 额度 token；仅用于失败恢复和幂等结算。 */
    @Column(name = "parse_quota_token", length = 64)
    private String parseQuotaToken;

    /** 当前事实草稿生成或正式画像确认时同步派生的岗位分类编码；尚未解析出事实时可为空。 */
    @Column(name = "job_category", length = 30)
    private String jobCategory;

    /** 当前占用该简历的活动面试 ID；非空时禁止修改事实画像或删除简历。 */
    @Column(name = "lock_interview_id")
    private Long lockInterviewId;

    /** 简历记录首次入库时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 简历记录最近一次由 JPA 实体更新的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化时使用同一业务时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新实体前刷新更新时间；仓储批量更新语句不会触发该回调。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 判断简历是否已被锁定（进入面试流程）。
     */
    public boolean isLocked() {
        return lockInterviewId != null;
    }
}
