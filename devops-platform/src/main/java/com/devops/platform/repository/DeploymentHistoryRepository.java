package com.devops.platform.repository;

import com.devops.platform.entity.DeploymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentHistoryRepository extends JpaRepository<DeploymentHistory, Long> {
    List<DeploymentHistory> findByProjectIdAndEnvironmentIdOrderByDeployedAtDesc(Long projectId, Long environmentId);
    List<DeploymentHistory> findByProjectIdOrderByDeployedAtDesc(Long projectId);
    List<DeploymentHistory> findByEnvironmentIdOrderByDeployedAtDesc(Long environmentId);
    List<DeploymentHistory> findByProjectIdAndEnvironmentIdAndIsRollbackPointTrueOrderByDeployedAtDesc(Long projectId, Long environmentId);
}
