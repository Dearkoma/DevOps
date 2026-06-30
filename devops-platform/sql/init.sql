-- ============================================
-- DevOps 持续交付平台 - MySQL 数据库初始化脚本
-- ============================================
-- 使用方式：
--   mysql -u root -p < sql/init.sql
--   或者登录 MySQL 后执行：source E:/Dissertation/2/devops-platform/sql/init.sql
--
-- 说明：
--   本脚本仅创建数据库和表结构，不包含任何演示数据。
--   首次启动应用时 Hibernate (ddl-auto=update) 会自动建表，
--   并创建一个默认管理员账户（用户名和密码见 application.yml）。
--   可使用此脚本手动创建数据库，再启动应用。
-- ============================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS devops_platform
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE devops_platform;

-- 2. 关闭外键检查（方便重复执行）
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 3. 用户表
-- ============================================
DROP TABLE IF EXISTS users;
CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)   NOT NULL UNIQUE,
    password    VARCHAR(255)  NOT NULL COMMENT 'BCrypt 加密后的密码',
    email       VARCHAR(100),
    real_name   VARCHAR(50),
    role        VARCHAR(20)   DEFAULT 'DEVELOPER' COMMENT 'ADMIN / DEVELOPER / VIEWER',
    enabled     TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 4. 项目表
-- ============================================
DROP TABLE IF EXISTS projects;
CREATE TABLE projects (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(50)   NOT NULL UNIQUE,
    name            VARCHAR(100)  NOT NULL,
    description     VARCHAR(500),
    git_url         VARCHAR(500),
    git_branch      VARCHAR(100)  DEFAULT 'main',
    build_command   VARCHAR(200),
    start_command   VARCHAR(200),
    language        VARCHAR(50),
    framework       VARCHAR(50),
    enabled         TINYINT(1)    NOT NULL DEFAULT 1,
    status          VARCHAR(20)   DEFAULT 'IDLE' COMMENT 'IDLE / BUILDING / DEPLOYED / FAILED',
    owner_id        BIGINT        COMMENT '项目负责人（关联 users.id）',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_code (code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 5. 流水线表
-- ============================================
DROP TABLE IF EXISTS pipelines;
CREATE TABLE pipelines (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT        NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    description     VARCHAR(500),
    definition      TEXT          COMMENT 'JSON 格式的流水线阶段/步骤定义',
    status          VARCHAR(20)   DEFAULT 'ACTIVE',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_project_id (project_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 6. 构建记录表
-- ============================================
DROP TABLE IF EXISTS builds;
CREATE TABLE builds (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT        NOT NULL,
    pipeline_id     BIGINT        COMMENT '允许 NULL，删除流水线时保留记录',
    build_number    VARCHAR(50)   NOT NULL COMMENT '构建编号（如 #123）',
    trigger_type    VARCHAR(20)   DEFAULT 'MANUAL' COMMENT 'MANUAL / PUSH / SCHEDULE',
    git_commit      VARCHAR(500)  COMMENT '触发的 Git Commit SHA',
    status          VARCHAR(20)   DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / CANCELLED',
    total_steps     INT           DEFAULT 0,
    completed_steps INT           DEFAULT 0,
    start_time      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    end_time        DATETIME,
    duration_ms     BIGINT        COMMENT '构建耗时（毫秒）',
    triggered_by    VARCHAR(50)   DEFAULT 'admin',
    result_message  TEXT          COMMENT '构建结果描述',
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE SET NULL,
    INDEX idx_project_id (project_id),
    INDEX idx_pipeline_id (pipeline_id),
    INDEX idx_status (status),
    INDEX idx_build_number (build_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 7. 环境表
-- ============================================
DROP TABLE IF EXISTS environments;
CREATE TABLE environments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(50)   NOT NULL UNIQUE COMMENT 'dev / test / staging / prod',
    display_name    VARCHAR(100),
    description     VARCHAR(500),
    deploy_url      VARCHAR(200)  COMMENT '部署目标 URL',
    k8s_namespace   VARCHAR(100)  COMMENT 'Kubernetes 命名空间',
    status          VARCHAR(20)   DEFAULT 'ACTIVE',
    protected_env   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否受保护（需审批才能部署）',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name (name),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 8. 服务实例表（暂无对应 Java 实体，预留）
-- ============================================
DROP TABLE IF EXISTS service_instances;
CREATE TABLE service_instances (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    build_id        BIGINT,
    project_id      BIGINT        NOT NULL,
    environment_id  BIGINT,
    instance_id     VARCHAR(100),
    status          VARCHAR(20),
    pod_ip          VARCHAR(50),
    host_ip         VARCHAR(50),
    cpu_usage       DOUBLE,
    memory_usage    DOUBLE,
    restart_count   INT           DEFAULT 0,
    started_at      DATETIME,
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (build_id) REFERENCES builds(id) ON DELETE SET NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE SET NULL,
    INDEX idx_project_id (project_id),
    INDEX idx_environment_id (environment_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. 恢复外键检查
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 初始化完成
-- ============================================
SELECT '========================================' AS '';
SELECT '  DevOps 平台数据库表创建完成（纯 DDL，无模拟数据）' AS message;
SELECT '========================================' AS '';
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'devops_platform';
