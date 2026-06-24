package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 20)
    private String action;  // CREATE / UPDATE / DELETE / TRIGGER / CANCEL / APPROVE / REJECT / LOGIN / LOGOUT

    @Column(nullable = false, length = 100)
    private String resource;  // 操作资源：PROJECT / PIPELINE / BUILD / ENVIRONMENT / USER / DEPLOYMENT

    @Column
    private Long resourceId;  // 资源 ID

    @Column(length = 200)
    private String resourceName;  // 资源名称

    @Column(columnDefinition = "TEXT")
    private String detail;  // 操作详情（JSON）

    @Column(length = 50)
    private String ipAddress;

    @Column(nullable = false)
    private Boolean success = true;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
