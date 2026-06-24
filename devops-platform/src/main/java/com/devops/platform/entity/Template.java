package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "templates")
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String type;  // DOCKERFILE / K8S_DEPLOYMENT / K8S_SERVICE / K8S_INGRESS / DOCKER_COMPOSE / PIPELINE

    @Column(length = 50)
    private String category;  // Java / Node.js / Python / Go / Generic

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;  // 模板内容

    @Column(length = 50)
    private String language;  // 适用语言，null 表示通用

    @Column(length = 50)
    private String framework;  // 适用框架，null 表示通用

    @Column(nullable = false)
    private Boolean isBuiltin = false;  // 系统内置模板

    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
