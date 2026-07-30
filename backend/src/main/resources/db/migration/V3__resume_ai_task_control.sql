ALTER TABLE resume ADD COLUMN parse_quota_date DATE NULL;
ALTER TABLE resume ADD COLUMN parse_quota_token VARCHAR(64) NULL;

ALTER TABLE resume_profile_analysis MODIFY COLUMN source_profile_hash VARCHAR(64) NULL;
ALTER TABLE resume_profile_analysis ADD COLUMN task_profile_hash VARCHAR(64) NULL;
ALTER TABLE resume_profile_analysis ADD COLUMN task_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE resume_profile_analysis ADD COLUMN task_mode VARCHAR(30) NULL;
ALTER TABLE resume_profile_analysis
    ADD COLUMN initial_model_call_started BIT(1) NOT NULL DEFAULT b'0';
ALTER TABLE resume_profile_analysis
    ADD COLUMN usable_for_interview BIT(1) NOT NULL DEFAULT b'0';
ALTER TABLE resume_profile_analysis ADD COLUMN task_quota_date DATE NULL;
ALTER TABLE resume_profile_analysis ADD COLUMN task_quota_token VARCHAR(64) NULL;
