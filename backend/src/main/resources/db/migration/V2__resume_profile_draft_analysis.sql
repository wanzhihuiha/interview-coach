ALTER TABLE resume ADD COLUMN parse_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE resume ADD COLUMN parse_started_at DATETIME(6) NULL;
ALTER TABLE resume ADD COLUMN parse_error_code VARCHAR(50) NULL;
ALTER TABLE resume ADD COLUMN parse_error_message VARCHAR(500) NULL;

ALTER TABLE resume_profile ADD COLUMN profile_hash VARCHAR(64) NULL;
ALTER TABLE resume_profile ADD COLUMN schema_version INT NOT NULL DEFAULT 2;
ALTER TABLE resume_profile ADD COLUMN confirmed_at DATETIME(6) NULL;

CREATE TABLE resume_profile_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    profile_data TEXT NOT NULL,
    experience_level VARCHAR(20) NULL,
    parse_generation BIGINT NOT NULL,
    schema_version INT NOT NULL DEFAULT 2,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_profile_draft_resume (resume_id),
    KEY idx_resume_profile_draft_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE resume_profile_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_profile_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    analysis_data TEXT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    prompt_version VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NULL,
    error_code VARCHAR(50) NULL,
    error_message VARCHAR(500) NULL,
    generated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_profile_analysis_resume (resume_id),
    KEY idx_resume_profile_analysis_user (user_id),
    KEY idx_resume_profile_analysis_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE interview ADD COLUMN user_profile_analysis TEXT NULL;
