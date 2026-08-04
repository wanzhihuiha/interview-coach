-- 岗位解析生命周期结构升级。
-- 本迁移不回填旧岗位、旧面试或旧任务；执行前应清理无需保留的开发数据或重建开发库。

ALTER TABLE `position`
    ADD COLUMN archived_at DATETIME(6) NULL,
    ADD KEY idx_position_user_active_created (user_id, is_public, archived_at, created_at),
    ADD KEY idx_position_public_active_created (is_public, archived_at, created_at);

ALTER TABLE sys_user
    ADD COLUMN last_position_analysis_submitted_at DATETIME(6) NULL;

ALTER TABLE interview
    ADD COLUMN position_name_snapshot VARCHAR(255) NOT NULL,
    ADD COLUMN company_name_snapshot VARCHAR(255) NULL,
    ADD COLUMN job_category_snapshot VARCHAR(30) NOT NULL,
    ADD KEY idx_interview_position_status (position_id, status);

CREATE TABLE position_analysis_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    position_id BIGINT NOT NULL,
    request_user_id BIGINT NOT NULL,
    queue_owner VARCHAR(32) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'WAITING',
    candidate_profile_data TEXT NULL,
    error_code VARCHAR(50) NULL,
    error_message VARCHAR(500) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_position_analysis_task_position (position_id),
    KEY idx_position_analysis_task_status_id (status, id),
    KEY idx_position_analysis_task_owner_status_id (queue_owner, status, id),
    CONSTRAINT chk_position_analysis_task_status
        CHECK (status IN ('WAITING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
