package com.devops.platform.repository;

import com.devops.platform.entity.DeploymentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentRequestRepository extends JpaRepository<DeploymentRequest, Long> {
    List<DeploymentRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<DeploymentRequest> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<DeploymentRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);
    List<DeploymentRequest> findByEnvironmentIdAndStatus(Long environmentId, String status);
}
