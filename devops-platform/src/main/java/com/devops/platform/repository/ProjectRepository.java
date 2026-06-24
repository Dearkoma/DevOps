package com.devops.platform.repository;
import com.devops.platform.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByNameContainingIgnoreCase(String name);
    List<Project> findByStatus(String status);
    Page<Project> findAll(Pageable pageable);
}
