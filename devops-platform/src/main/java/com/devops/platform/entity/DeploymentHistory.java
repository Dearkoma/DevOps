package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "deployment_history")
public class DeploymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 100)
    private String projectName;

    @Column(nullable = false)
    private Long environmentId;

    @Column(length = 100)
    private String environmentName;

    @Column(nullable = false)
    private Long buildId;

    @Column(length = 50)
    private String buildNumber;

    @Column(length = 20)
    private String status = "DEPLOYED";  // DEPLOYED / ROLLED_BACK / FAILED

    @Column(length = 100)
    private String version;

    @Column(length = 200)
    private String deployUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String deployedBy;

    @Column
    private Boolean isRollbackPoint = true;  // 是否可作为回滚点

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime deployedAt;
}
