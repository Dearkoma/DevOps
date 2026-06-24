package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 环境实体
 * 定义不同的部署环境（开发/测试/预发布/生产）
 */
@Data
@Entity
@Table(name = "environments")
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;  // dev / test / staging / prod

    @Column(length = 100)
    private String displayName;  // 显示名称

    @Column(length = 500)
    private String description;

    @Column(length = 20)
    private String status = "ACTIVE";  // ACTIVE / INACTIVE

    @Column(length = 200)
    private String deployUrl;  // 部署目标 URL（模拟）

    @Column(length = 100)
    private String k8sNamespace;  // K8s 命名空间（模拟）

    @Column(nullable = false)
    private Boolean protectedEnv = false;  // 是否受保护环境（需要审批）

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
