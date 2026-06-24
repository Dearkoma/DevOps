package com.devops.platform.repository;
import com.devops.platform.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {
    List<Pipeline> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<Pipeline> findByProjectIdAndStatus(Long projectId, String status);
}
