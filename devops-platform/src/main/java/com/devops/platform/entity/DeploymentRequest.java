package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "deployment_requests")
public class DeploymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buildId;

    @Column(nullable = false)
    private Long environmentId;

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 100)
    private String projectName;

    @Column(length = 100)
    private String environmentName;

    @Column(length = 20)
    private String status = "PENDING";  // PENDING / APPROVED / REJECTED / DEPLOYED / FAILED

    @Column(length = 50)
    private String requestedBy;

    @Column(length = 50)
    private String approvedBy;

    @Column(columnDefinition = "TEXT")
    private String reason;  // 申请原因

    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    @Column(length = 200)
    private String deployUrl;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime approvedAt;

    @Column
    private LocalDateTime deployedAt;
}
