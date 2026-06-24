package com.devops.platform.repository;

import com.devops.platform.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArtifactRepository extends JpaRepository<Artifact, Long> {
    List<Artifact> findByBuildIdOrderByCreatedAtDesc(Long buildId);
    List<Artifact> findByFileType(String fileType);
}
