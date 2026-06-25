package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 构建记录实体
 */
@Data
@Entity
@Table(name = "builds")
public class Build {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column
    private Long pipelineId;  // 允许 NULL，对应 SQL FOREIGN KEY ON DELETE SET NULL

    @Column(nullable = false, length = 50)
    private String buildNumber;  // 构建编号，如 #123

    @Column(length = 20)
    private String triggerType = "MANUAL";  // MANUAL / PUSH / SCHEDULE

    @Column(length = 500)
    private String gitCommit;  // 触发的 Git commit

    @Column(length = 20)
    private String status = "PENDING";  // PENDING / RUNNING / SUCCESS / FAILED / CANCELLED

    @Column
    private Integer totalSteps = 0;

    @Column
    private Integer completedSteps = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime startTime;

    @Column
    private LocalDateTime endTime;

    @Column
    private Long durationMs;  // 构建耗时（毫秒）

    @Column(length = 50)
    private String triggeredBy = "admin";

    @Column(columnDefinition = "TEXT")
    private String resultMessage;  // 构建结果描述

    @Column(columnDefinition = "TEXT")
    private String buildParams;  // 构建参数 JSON，如 {"version":"1.2.0","env":"prod"}

    @Column(length = 500)
    private String branch;  // 实际构建的分支

    @Column
    private Boolean skipDocker = false;  // 是否跳过 Docker 构建阶段

    @Column
    private Boolean skipK8s = false;  // 是否跳过 K8s 部署阶段

    @Column(columnDefinition = "LONGTEXT")
    private String logContent;  // 构建日志内容（数据库存储兜底）
}
