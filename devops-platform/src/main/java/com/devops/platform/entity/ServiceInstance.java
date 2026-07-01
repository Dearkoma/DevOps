package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "service_instances")
public class ServiceInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column
    private Long environmentId;

    @Column
    private Long buildId;

    @Column(length = 100)
    private String projectName;

    @Column(nullable = false, length = 100)
    private String instanceName;  // Pod 名称

    @Column(length = 20)
    private String status = "RUNNING";  // RUNNING / STOPPED / UNHEALTHY / UNKNOWN

    @Column(length = 200)
    private String host;

    @Column
    private Integer port;

    @Column(length = 50)
    private String containerId;

    @Column(length = 200)
    private String imageName;

    @Column(length = 50)
    private String imageTag;

    @Column(length = 100)
    private String k8sNamespace;

    @Column(length = 100)
    private String k8sPodName;

    @Column(length = 20)
    private String deployType = "K8S";  // 部署类型: DOCKER / K8S

    @Column
    private Double cpuUsage;  // 百分比

    @Column
    private Double memoryUsage;  // MB

    @Column
    private Integer restartCount = 0;

    @Column(length = 200)
    private String healthCheckUrl;

    @Column(length = 20)
    private String healthStatus;  // HEALTHY / UNHEALTHY / UNKNOWN

    @Column(length = 128)
    private String dbName;  // 独立数据库名（部署时自动创建）

    @Column(length = 50)
    private String adminUsername;  // 部署实例的管理员账号

    @Column(length = 100)
    private String adminPassword;  // 部署实例的管理员密码

    @Column
    private LocalDateTime lastHeartbeat;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
