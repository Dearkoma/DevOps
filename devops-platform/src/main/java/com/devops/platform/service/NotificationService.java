package com.devops.platform.service;

import com.devops.platform.entity.Notification;
import com.devops.platform.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 发送通知 */
    public Notification send(String type, String title, String message, String recipient, Long relatedId, String relatedType) {
        Notification n = new Notification();
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setRecipient(recipient);
        n.setRelatedId(relatedId);
        n.setRelatedType(relatedType);
        return notificationRepository.save(n);
    }

    /** 构建成功通知 */
    public void buildSuccess(String buildNumber, Long buildId, String projectName) {
        send("BUILD_SUCCESS",
                "构建成功: " + buildNumber,
                "项目 " + projectName + " 的构建 " + buildNumber + " 已成功完成",
                null, buildId, "BUILD");
    }

    /** 构建失败通知 */
    public void buildFailed(String buildNumber, Long buildId, String projectName, String error) {
        send("BUILD_FAILED",
                "构建失败: " + buildNumber,
                "项目 " + projectName + " 的构建 " + buildNumber + " 失败: " + error,
                null, buildId, "BUILD");
    }

    /** 部署审批通知 */
    public void deployApprovalRequest(Long requestId, String projectName, String envName) {
        send("DEPLOY_APPROVAL",
                "部署审批: " + projectName,
                "项目 " + projectName + " 申请部署到 " + envName + " 环境，等待审批",
                null, requestId, "DEPLOYMENT");
    }

    public List<Notification> getUserNotifications(String username) {
        return notificationRepository.findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
    }

    public Long getUnreadCount(String username) {
        Long personal = notificationRepository.countByRecipientAndIsReadFalse(username);
        return personal + notificationRepository.countByIsReadFalse();
    }

    public void markAsRead(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    public void markAllAsRead(String username) {
        List<Notification> all = notificationRepository.findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
        all.forEach(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    /** 删除单条通知 */
    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
        log.info("已删除通知 #{}", id);
    }

    /** 清空全部通知 */
    public int deleteAllNotifications(String username) {
        List<Notification> all = notificationRepository
                .findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
        notificationRepository.deleteAll(all);
        log.info("已清空 {} 条通知", all.size());
        return all.size();
    }
}
