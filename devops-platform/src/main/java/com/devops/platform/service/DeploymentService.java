package com.devops.platform.service;

import com.devops.platform.entity.*;
import com.devops.platform.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentHistoryRepository historyRepository;
    private final EnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final BuildRepository buildRepository;
    private final BuildService buildService;

    /** 申请部署（受保护环境自动进入审批） */
    public DeploymentRequest requestDeploy(Long buildId, Long environmentId, String requestedBy, String reason) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在"));
        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new RuntimeException("环境不存在"));
        Project project = projectRepository.findById(build.getProjectId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        DeploymentRequest req = new DeploymentRequest();
        req.setBuildId(buildId);
        req.setEnvironmentId(environmentId);
        req.setProjectId(build.getProjectId());
        req.setProjectName(project.getName());
        req.setEnvironmentName(env.getDisplayName() != null ? env.getDisplayName() : env.getName());
        req.setRequestedBy(requestedBy);
        req.setReason(reason);
        req.setDeployUrl(env.getDeployUrl());

        // 受保护环境需要审批
        if (Boolean.TRUE.equals(env.getProtectedEnv())) {
            req.setStatus("PENDING");
        } else {
            req.setStatus("APPROVED");
            // 非保护环境直接执行部署
            deployToHistory(req);
        }

        return requestRepository.save(req);
    }

    /** 审批通过 */
    @Transactional
    public DeploymentRequest approve(Long requestId, String approvedBy) {
        DeploymentRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("该申请已被处理");
        }
        req.setStatus("APPROVED");
        req.setApprovedBy(approvedBy);
        req.setApprovedAt(LocalDateTime.now());
        requestRepository.save(req);

        // 执行部署
        deployToHistory(req);
        return req;
    }

    /** 审批拒绝 */
    public DeploymentRequest reject(Long requestId, String approvedBy, String rejectReason) {
        DeploymentRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("该申请已被处理");
        }
        req.setStatus("REJECTED");
        req.setApprovedBy(approvedBy);
        req.setRejectReason(rejectReason);
        req.setApprovedAt(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /** 执行部署并记录历史 */
    private void deployToHistory(DeploymentRequest req) {
        Build build = buildRepository.findById(req.getBuildId()).orElse(null);
        if (build == null) return;

        DeploymentHistory history = new DeploymentHistory();
        history.setProjectId(req.getProjectId());
        history.setProjectName(req.getProjectName());
        history.setEnvironmentId(req.getEnvironmentId());
        history.setEnvironmentName(req.getEnvironmentName());
        history.setBuildId(req.getBuildId());
        history.setBuildNumber(build.getBuildNumber());
        history.setStatus("DEPLOYED");
        history.setDeployUrl(req.getDeployUrl());
        history.setDescription("部署申请 #" + req.getId() + " 通过");
        history.setDeployedBy(req.getApprovedBy() != null ? req.getApprovedBy() : req.getRequestedBy());
        historyRepository.save(history);

        req.setStatus("DEPLOYED");
        req.setDeployedAt(LocalDateTime.now());
        requestRepository.save(req);
    }

    /** 回滚到指定部署历史 */
    @Transactional
    public DeploymentHistory rollback(Long historyId, String triggeredBy) {
        DeploymentHistory prev = historyRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("部署历史不存在"));

        // 创建回滚记录
        DeploymentHistory rollback = new DeploymentHistory();
        rollback.setProjectId(prev.getProjectId());
        rollback.setProjectName(prev.getProjectName());
        rollback.setEnvironmentId(prev.getEnvironmentId());
        rollback.setEnvironmentName(prev.getEnvironmentName());
        rollback.setBuildId(prev.getBuildId());
        rollback.setBuildNumber(prev.getBuildNumber());
        rollback.setStatus("DEPLOYED");
        rollback.setVersion(prev.getVersion());
        rollback.setDeployUrl(prev.getDeployUrl());
        rollback.setDescription("回滚到构建 #" + prev.getBuildNumber() + " (历史记录 #" + prev.getId() + ")");
        rollback.setDeployedBy(triggeredBy);
        rollback.setIsRollbackPoint(false);
        return historyRepository.save(rollback);
    }

    /** 获取可回滚的部署历史 */
    public List<DeploymentHistory> getRollbackCandidates(Long projectId, Long environmentId) {
        return historyRepository.findByProjectIdAndEnvironmentIdAndIsRollbackPointTrueOrderByDeployedAtDesc(projectId, environmentId);
    }

    public List<DeploymentRequest> getPendingRequests() {
        return requestRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public List<DeploymentRequest> getRequestsByProject(Long projectId) {
        return requestRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<DeploymentHistory> getHistory(Long projectId, Long environmentId) {
        if (projectId != null && environmentId != null) {
            return historyRepository.findByProjectIdAndEnvironmentIdOrderByDeployedAtDesc(projectId, environmentId);
        } else if (projectId != null) {
            return historyRepository.findByProjectIdOrderByDeployedAtDesc(projectId);
        } else if (environmentId != null) {
            return historyRepository.findByEnvironmentIdOrderByDeployedAtDesc(environmentId);
        } else {
            return historyRepository.findAll(); // 无筛选条件时返回全部
        }
    }

    /** 删除部署历史记录 */
    public void deleteDeploymentHistory(Long id) {
        historyRepository.deleteById(id);
        log.info("已删除部署历史 #{}", id);
    }

    /** 删除部署申请（仅允许删除 REJECTED / DEPLOYED 状态） */
    public void deleteDeploymentRequest(Long id) {
        DeploymentRequest req = requestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if ("PENDING".equals(req.getStatus())) {
            throw new RuntimeException("待审批的申请不能直接删除，请先拒绝");
        }
        requestRepository.delete(req);
        log.info("已删除部署申请 #{} (status={})", id, req.getStatus());
    }
}
