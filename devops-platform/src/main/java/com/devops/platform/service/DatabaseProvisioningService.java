package com.devops.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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
     * 为 A 项目创建独立数据库。
     *
     * @param dbName 数据库名称（建议格式: devops_{projectCode}）
     * @return 创建成功返回 true；数据库已存在记 info 日志也返回 true
     */
    public boolean createDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            log.warn("dbName 为空，跳过数据库创建");
            return false;
        }

        // 清理数据库名中的特殊字符（防止 SQL 注入和非法名称）
        String safeName = sanitizeDbName(dbName);

        // 提取 MySQL 连接基础 URL（去掉数据库名和参数部分，用于执行 DDL）
        String baseUrl = extractBaseJdbcUrl(datasourceUrl);

        try (Connection conn = DriverManager.getConnection(
                baseUrl, datasourceUsername, datasourcePassword);
             Statement stmt = conn.createStatement()) {

            String sql = String.format(
                    "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                    safeName);
            stmt.executeUpdate(sql);
            log.info("数据库 '{}' 创建成功（或已存在）", safeName);
            return true;

        } catch (Exception e) {
            log.error("创建数据库 '{}' 失败: {}", safeName, e.getMessage());
            return false;
        }
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
