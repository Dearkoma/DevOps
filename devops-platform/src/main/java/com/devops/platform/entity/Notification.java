package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String type;  // BUILD_SUCCESS / BUILD_FAILED / DEPLOY_APPROVAL / DEPLOY_SUCCESS / DEPLOY_FAILED / SYSTEM

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 50)
    private String recipient;  // 目标用户，null 表示全局通知

    @Column(nullable = false)
    private Boolean isRead = false;

    @Column
    private Long relatedId;  // 关联的构建/部署 ID

    @Column(length = 50)
    private String relatedType;  // BUILD / DEPLOYMENT / SYSTEM

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
