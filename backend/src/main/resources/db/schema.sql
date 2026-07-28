-- 用户模块表结构

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    openid VARCHAR(100) COMMENT '微信openid',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE正常 DISABLED禁用',
    roles VARCHAR(100) NOT NULL DEFAULT 'USER' COMMENT '用户角色，多个以逗号分隔',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    nickname VARCHAR(50) COMMENT '昵称',
    avatar VARCHAR(500) COMMENT '头像URL',
    gender VARCHAR(10) COMMENT '性别：MALE男 FEMALE女 UNKNOWN未知',
    birthday DATE COMMENT '生日',
    bio VARCHAR(500) COMMENT '个人简介',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户资料表';

CREATE TABLE IF NOT EXISTS sys_user_consent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    consent_type VARCHAR(50) NOT NULL COMMENT '同意类型',
    consent_version VARCHAR(20) NOT NULL COMMENT '同意版本号',
    consent_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同意时间',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '浏览器信息',
    KEY idx_user_id (user_id),
    KEY idx_consent_type (consent_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户同意记录表';

-- 简历模块表结构

CREATE TABLE IF NOT EXISTS resume (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    resume_name VARCHAR(255) NOT NULL COMMENT '简历名称（文件名）',
    file_path VARCHAR(500) COMMENT '文件存储路径',
    file_type VARCHAR(20) COMMENT '文件类型：PDF/TXT/DOC',
    file_size BIGINT COMMENT '文件大小（字节）',
    parse_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING/PARSING/PENDING_CONFIRM/CONFIRMED/PARSE_FAILED',
    job_category VARCHAR(30) COMMENT '岗位大类：TECH/PRODUCT/DESIGN/OPERATIONS/MARKETING/FUNCTION',
    lock_interview_id BIGINT COMMENT '锁定的面试ID，不为空表示已被面试锁定',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历表';

CREATE TABLE IF NOT EXISTS resume_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id BIGINT NOT NULL COMMENT '简历ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    profile_data JSON NOT NULL COMMENT '画像数据（JSON）',
    experience_level VARCHAR(20) COMMENT '经验水平：JUNIOR/MID/SENIOR',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resume_id (resume_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历画像表';
