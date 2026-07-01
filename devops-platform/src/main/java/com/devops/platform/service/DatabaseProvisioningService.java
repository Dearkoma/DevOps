package com.devops.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库供应服务 — 为部署的 A 项目（下游项目）自动创建独立数据库。
 * <p>
 * 设计思路：
 * D 项目（本平台）部署 A 项目时不再强行共用 devops_platform 库，
 * 而是在 MySQL 中为每个 A 项目创建一个独立数据库，
 * 部署后的 A 项目通过 host.docker.internal 连接该库，
 * DataInitializer 会自动在库内建表并创建默认 admin 用户。
 */
@Slf4j
@Service
public class DatabaseProvisioningService {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    /**
     * 数据库创建结果。
     */
    public static class CreateResult {
        public final boolean success;
        public final String dbName;         // 净化后的数据库名
        public final boolean alreadyExisted; // 物理上已存在
        public final String conflictProjectName; // 冲突项目名（null 表示无冲突）
        public final Long conflictProjectId;     // 冲突项目ID

        private CreateResult(boolean success, String dbName, boolean alreadyExisted,
                             String conflictProjectName, Long conflictProjectId) {
            this.success = success;
            this.dbName = dbName;
            this.alreadyExisted = alreadyExisted;
            this.conflictProjectName = conflictProjectName;
            this.conflictProjectId = conflictProjectId;
        }

        public static CreateResult created(String dbName) {
            return new CreateResult(true, dbName, false, null, null);
        }

        public static CreateResult existedNoConflict(String dbName) {
            return new CreateResult(true, dbName, true, null, null);
        }

        public static CreateResult conflict(String dbName, String conflictProject, Long conflictProjectId) {
            return new CreateResult(false, dbName, true, conflictProject, conflictProjectId);
        }
    }

    /**
     * 检查指定数据库在 MySQL 中是否已物理存在。
     */
    public boolean databaseExists(String dbName) {
        String safeName = sanitizeDbName(dbName);
        String baseUrl = extractBaseJdbcUrl(datasourceUrl);
        try (Connection conn = DriverManager.getConnection(baseUrl, datasourceUsername, datasourcePassword);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '" + safeName.replace("'", "''") + "'");
            return rs.next();
        } catch (Exception e) {
            log.warn("检查数据库是否存在时出错: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 为 A 项目创建独立数据库（带冲突检测）。
     * <p>
     * 冲突判定：
     * 1. 数据库物理已存在 + 有其他项目的构建记录使用此库名 → 冲突
     * 2. 数据库物理已存在 + 只有当前项目的构建记录 → 允许（同项目重建）
     * 3. 数据库物理不存在 → 正常创建
     *
     * @param dbName          用户输入或默认的数据库名
     * @param currentProjectId 当前正在构建的项目ID
     * @param buildRepository  构建记录仓库（用于检测跨项目占用）
     * @return CreateResult，success=false 为冲突
     */
    public CreateResult createDatabase(String dbName, Long currentProjectId,
                                        com.devops.platform.repository.BuildRepository buildRepository) {
        if (dbName == null || dbName.isBlank()) {
            log.warn("dbName 为空，跳过数据库创建");
            return new CreateResult(false, "", false, null, null);
        }

        String safeName = sanitizeDbName(dbName);
        String baseUrl = extractBaseJdbcUrl(datasourceUrl);

        // 1) 检查 MySQL 中是否已存在
        boolean exists = databaseExists(safeName);

        // 2) 检查跨项目冲突
        if (exists && currentProjectId != null && buildRepository != null) {
            List<com.devops.platform.entity.Build> conflictBuilds =
                    buildRepository.findByDbNameAndProjectIdNot(safeName, currentProjectId);
            if (!conflictBuilds.isEmpty()) {
                // 数据库已被其他项目占用
                Long otherProjectId = conflictBuilds.get(0).getProjectId();
                log.warn("数据库 '{}' 已被项目 #{} 占用，当前项目 #{} 无法使用", safeName, otherProjectId, currentProjectId);
                return CreateResult.conflict(safeName, "Project#" + otherProjectId, otherProjectId);
            }
        }

        // 3) 执行创建
        try (Connection conn = DriverManager.getConnection(baseUrl, datasourceUsername, datasourcePassword);
             Statement stmt = conn.createStatement()) {

            if (!exists) {
                String sql = String.format(
                        "CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                        safeName);
                stmt.executeUpdate(sql);
                log.info("数据库 '{}' 创建成功", safeName);
                return CreateResult.created(safeName);
            } else {
                log.info("数据库 '{}' 已存在（同项目重建，无冲突）", safeName);
                return CreateResult.existedNoConflict(safeName);
            }

        } catch (Exception e) {
            log.error("创建数据库 '{}' 失败: {}", safeName, e.getMessage());
            return new CreateResult(false, safeName, exists, null, null);
        }
    }

    /**
     * 兼容旧调用（无冲突检测，建议迁移到 {@link #createDatabase(String, Long, com.devops.platform.repository.BuildRepository)}）。
     */
    public boolean createDatabase(String dbName) {
        CreateResult r = createDatabase(dbName, null, null);
        return r.success;
    }

    /**
     * 从完整 JDBC URL 提取不包含数据库名的基础 URL。
     * 例如:
     * jdbc:mysql://localhost:3306/devops_platform?params...
     * → jdbc:mysql://localhost:3306?params...（去掉 /devops_platform）
     */
    static String extractBaseJdbcUrl(String fullUrl) {
        // 格式: jdbc:mysql://host:port/dbname?params...
        // 需要去掉 /dbname 但保留查询参数
        int lastSlash = fullUrl.lastIndexOf('/');
        if (lastSlash <= 0) return fullUrl;

        String prefix = fullUrl.substring(0, lastSlash);
        String suffix = fullUrl.substring(lastSlash + 1);

        // suffix 可能包含 ?params
        int qIdx = suffix.indexOf('?');
        if (qIdx >= 0) {
            // suffix = "dbname?params"，只保留 ?params
            return prefix + suffix.substring(qIdx);
        } else {
            // suffix 就是数据库名，直接去掉
            return prefix;
        }
    }

    /**
     * 生成默认数据库名。
     */
    public static String defaultDbName(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) return "devops_app";
        // 清理特殊字符
        return "devops_" + sanitizeDbName(projectCode);
    }

    /**
     * 净化数据库名（移除非法字符，仅保留字母数字下划线和连字符）。
     * 与 createDatabase 内部净化逻辑一致，确保存储的名称与数据库实际名称匹配。
     */
    public static String sanitizeDbName(String raw) {
        if (raw == null || raw.isBlank()) return "devops_app";
        // 去首尾空白；替换连续非法字符为单个下划线
        String clean = raw.trim().replaceAll("[^a-zA-Z0-9_\\-]+", "_");
        // MySQL 数据库名不能以连字符开关
        clean = clean.replaceAll("^-+", "").replaceAll("^_+", "");
        if (clean.isEmpty()) clean = "app";
        return clean;
    }
}
