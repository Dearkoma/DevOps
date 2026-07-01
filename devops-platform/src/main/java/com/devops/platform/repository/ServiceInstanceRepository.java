package com.devops.platform.repository;

import com.devops.platform.entity.ServiceInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, Long> {
    List<ServiceInstance> findByProjectId(Long projectId);
    List<ServiceInstance> findByProjectIdAndDeployType(Long projectId, String deployType);
    List<ServiceInstance> findByStatus(String status);
    List<ServiceInstance> findByK8sNamespace(String namespace);
    List<ServiceInstance> findByHealthStatus(String healthStatus);
}
