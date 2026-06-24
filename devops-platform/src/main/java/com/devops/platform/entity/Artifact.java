package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "artifacts")
public class Artifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buildId;

    @Column(nullable = false, length = 200)
    private String fileName;

    @Column(nullable = false, length = 500)
    private String filePath;

    @Column(length = 50)
    private String fileType;  // JAR / WAR / DOCKER_IMAGE / NPM_PACKAGE / OTHER

    @Column
    private Long fileSize;  // bytes

    @Column(length = 500)
    private String downloadUrl;

    @Column(length = 100)
    private String version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
