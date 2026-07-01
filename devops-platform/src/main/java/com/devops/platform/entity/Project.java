package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 项目实体
 */
@Data
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50, unique = true)
    private String code;  // 项目编码，用于路径/标识

    @Column(length = 500)
    private String description;

    @Column(length = 500)
    private String gitUrl;

    @Column(length = 100)
    private String gitBranch = "main";

    @Column(length = 200)
    private String buildCommand;

    @Column(length = 200)
    private String startCommand;

    @Column(length = 50)
    private String language = "Java";  // Java / Node.js / Python / Go

    @Column(length = 50)
    private String framework;  // Spring Boot / Express / Django / Gin

    @Column(length = 20)
    private String dbType = "H2";  // H2 / MYSQL — O 项目数据库类型

    @Column(length = 200)
    private String dbHost;  // MySQL 主机地址

    @Column
    private Integer dbPort = 3306;  // MySQL 端口

    @Column(length = 100)
    private String dbUsername;  // MySQL 用户名

    @Column(length = 200)
    private String dbPassword;  // MySQL 密码

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 20)
    private String status = "IDLE";  // IDLE / BUILDING / DEPLOYED / FAILED

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column
    private Long ownerId;  // 项目负责人
}
