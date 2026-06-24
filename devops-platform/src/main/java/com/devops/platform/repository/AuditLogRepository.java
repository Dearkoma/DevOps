package com.devops.platform.repository;

import com.devops.platform.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByUsernameOrderByCreatedAtDesc(String username);
    List<AuditLog> findByResourceAndResourceIdOrderByCreatedAtDesc(String resource, Long resourceId);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
