package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 流水线实体
 * 定义 CI/CD 流水线的阶段和步骤
 */
@Data
@Entity
@Table(name = "pipelines")
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * 流水线定义（JSON 格式）
     * 包含多个阶段(stage)，每个阶段包含多个步骤(step)
     * 示例：
     * [
     *   {"name":"构建","steps":[{"name":"Maven编译","type":"SHELL","command":"mvn clean package"}]},
     *   {"name":"测试","steps":[{"name":"单元测试","type":"SHELL","command":"mvn test"}]},
     *   {"name":"部署","steps":[{"name":"Docker构建","type":"SHELL","command":"docker build..."}]}
     * ]
     */
    @Column(columnDefinition = "TEXT")
    private String definition;  // JSON

    @Column(length = 20)
    private String status = "ACTIVE";  // ACTIVE / INACTIVE

    @Column(length = 100)
    private String cronExpression;  // Cron 表达式，用于定时构建

    @Column(length = 200)
    private String branchPattern;  // 分支匹配模式，如 "feature/*" 或 "main,develop"

    @Column(nullable = false)
    private Boolean cronEnabled = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
