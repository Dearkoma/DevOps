package com.devops.platform.repository;

import com.devops.platform.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(String recipient);
    List<Notification> findByIsReadFalseOrderByCreatedAtDesc();
    Long countByIsReadFalse();
    Long countByRecipientAndIsReadFalse(String recipient);
}
