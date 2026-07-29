-- Interview Coach MySQL 8 初始数据库基线。
-- 仅包含当前 JPA 实体对应的表、业务唯一约束和 Repository 查询所需索引；
-- 实体之间使用显式 ID，不额外引入会改变现有删除语义的外键约束。
-- 本脚本不会创建或切换数据库；执行前必须在客户端确认已选中正确的空数据库。

CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    email VARCHAR(100) NULL,
    openid VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    roles VARCHAR(100) NOT NULL DEFAULT 'USER',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    UNIQUE KEY uk_sys_user_phone (phone),
    UNIQUE KEY uk_sys_user_email (email),
    UNIQUE KEY uk_sys_user_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_user_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(50) NULL,
    avatar VARCHAR(500) NULL,
    gender VARCHAR(10) NULL,
    birthday DATE NULL,
    bio VARCHAR(500) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_user_consent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    consent_type VARCHAR(50) NOT NULL,
    consent_version VARCHAR(20) NOT NULL,
    consent_time DATETIME(6) NOT NULL,
    ip_address VARCHAR(50) NULL,
    user_agent VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY idx_sys_user_consent_user_type_time (user_id, consent_type, consent_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE resume (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NULL,
    file_type VARCHAR(20) NULL,
    file_size BIGINT NULL,
    parse_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    job_category VARCHAR(30) NULL,
    lock_interview_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_resume_user_created (user_id, created_at),
    KEY idx_resume_lock_interview (lock_interview_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE resume_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    profile_data TEXT NOT NULL,
    experience_level VARCHAR(20) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_profile_resume (resume_id),
    KEY idx_resume_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `position` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    position_name VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NULL,
    location VARCHAR(100) NULL,
    salary_range VARCHAR(50) NULL,
    job_category VARCHAR(30) NOT NULL,
    level VARCHAR(20) NULL,
    jd_content TEXT NULL,
    parse_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    audit_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    audit_remark VARCHAR(500) NULL,
    auditor_id BIGINT NULL,
    audited_at DATETIME(6) NULL,
    is_public BIT(1) NOT NULL DEFAULT b'0',
    lock_interview_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_position_user_created (user_id, created_at),
    KEY idx_position_user_parse_audit (user_id, parse_status, audit_status),
    KEY idx_position_user_audit (user_id, audit_status),
    KEY idx_position_public_audit (is_public, audit_status),
    KEY idx_position_audit (audit_status),
    KEY idx_position_lock_interview (lock_interview_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE position_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    position_id BIGINT NOT NULL,
    user_id BIGINT NULL,
    profile_data TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_position_profile_position (position_id),
    KEY idx_position_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE interview (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    user_profile TEXT NULL,
    position_profile TEXT NULL,
    selected_phases VARCHAR(500) NOT NULL,
    current_phase VARCHAR(30) NOT NULL,
    current_topic_id VARCHAR(100) NULL,
    current_topic_name VARCHAR(100) NULL,
    current_depth INT NULL DEFAULT 1,
    current_topic_follow_up_count INT NULL DEFAULT 0,
    consecutive_failures INT NULL DEFAULT 0,
    consecutive_excellence INT NULL DEFAULT 0,
    last_evaluation_seq INT NULL DEFAULT 0,
    pending_question TEXT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    total_question_count INT NULL DEFAULT 0,
    current_phase_question_count INT NULL DEFAULT 0,
    self_intro_question_count INT NULL DEFAULT 0,
    current_project_index INT NULL DEFAULT 0,
    current_behavioral_index INT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_interview_user_created (user_id, created_at),
    KEY idx_interview_status (status),
    KEY idx_interview_resume (resume_id),
    KEY idx_interview_position (position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE interview_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    interview_id BIGINT NOT NULL,
    phase VARCHAR(30) NOT NULL,
    role VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    topic_id VARCHAR(100) NULL,
    topic_name VARCHAR(100) NULL,
    depth INT NULL,
    seq_no INT NULL,
    token_count INT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_interview_message_interview_seq (interview_id, seq_no),
    KEY idx_interview_message_interview_phase_seq (interview_id, phase, seq_no),
    KEY idx_interview_message_interview_role (interview_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE interview_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    interview_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    overall_score INT NULL,
    grade VARCHAR(20) NULL,
    phases TEXT NULL,
    dimensions TEXT NULL,
    strengths TEXT NULL,
    weaknesses TEXT NULL,
    key_events TEXT NULL,
    conclusion TEXT NULL,
    md_content TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_report_interview (interview_id),
    KEY idx_interview_report_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE theme_evaluation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    interview_id BIGINT NOT NULL,
    topic_id VARCHAR(100) NOT NULL,
    topic_name VARCHAR(100) NULL,
    score INT NULL,
    depth_reached INT NULL,
    strength_list TEXT NULL,
    weakness_list TEXT NULL,
    key_events TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_theme_evaluation_interview (interview_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE temporary_question_bank (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_category VARCHAR(30) NOT NULL,
    phase VARCHAR(30) NOT NULL,
    topic_id VARCHAR(50) NULL,
    topic_name VARCHAR(100) NULL,
    content TEXT NOT NULL,
    expected_answer TEXT NULL,
    source_interview_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    difficulty_level INT NULL DEFAULT 3,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_temporary_question_filter (job_category, phase, status),
    KEY idx_temporary_question_status (status),
    KEY idx_temporary_question_source_interview (source_interview_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE permanent_question_bank (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_category VARCHAR(30) NOT NULL,
    phase VARCHAR(30) NOT NULL,
    topic_id VARCHAR(50) NULL,
    topic_name VARCHAR(100) NULL,
    content TEXT NOT NULL,
    expected_answer TEXT NULL,
    source_temporary_id BIGINT NULL,
    usage_count INT NULL DEFAULT 0,
    difficulty_level INT NULL DEFAULT 3,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_permanent_question_usage (job_category, phase, usage_count),
    KEY idx_permanent_question_topic_usage (job_category, phase, topic_id, usage_count),
    KEY idx_permanent_question_source_temporary (source_temporary_id),
    KEY idx_permanent_question_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    interview_id BIGINT NOT NULL,
    position_title VARCHAR(200) NULL,
    overall_score INT NULL,
    grade VARCHAR(50) NULL,
    content TEXT NULL,
    structured_data TEXT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'GENERATING',
    generated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_plan_interview (interview_id),
    KEY idx_growth_plan_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    caller VARCHAR(50) NOT NULL,
    operation VARCHAR(200) NULL,
    method_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    duration_ms BIGINT NOT NULL,
    args_summary VARCHAR(2000) NULL,
    result_summary VARCHAR(500) NULL,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_audit_caller_status (caller, status),
    KEY idx_agent_audit_status (status),
    KEY idx_agent_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
