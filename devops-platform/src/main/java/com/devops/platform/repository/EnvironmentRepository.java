package com.devops.platform.repository;
import com.devops.platform.entity.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface EnvironmentRepository extends JpaRepository<Environment, Long> {
    List<Environment> findByStatusOrderByCreatedAtAsc(String status);
    Environment findByName(String name);
}
