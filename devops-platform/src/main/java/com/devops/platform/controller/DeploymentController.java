package com.devops.platform.controller;

import com.devops.platform.entity.DeploymentHistory;
import com.devops.platform.entity.DeploymentRequest;
import com.devops.platform.service.DeploymentService;
import com.devops.platform.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final NotificationService notificationService;

    /** 申请部署 */
    @PostMapping("/request")
    public ResponseEntity<DeploymentRequest> requestDeploy(@RequestBody Map<String, Object> body) {
        Long buildId = Long.valueOf(body.get("buildId").toString());
        Long environmentId = Long.valueOf(body.get("environmentId").toString());
        String requestedBy = (String) body.getOrDefault("requestedBy", "admin");
        String reason = (String) body.getOrDefault("reason", "");

        DeploymentRequest req = deploymentService.requestDeploy(buildId, environmentId, requestedBy, reason);
        if ("PENDING".equals(req.getStatus())) {
            notificationService.deployApprovalRequest(req.getId(), req.getProjectName(), req.getEnvironmentName());
        }
        return ResponseEntity.ok(req);
    }

    /** 审批通过 */
    @PostMapping("/approve/{id}")
    public ResponseEntity<?> approve(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String approvedBy = body.getOrDefault("approvedBy", "admin");
        try {
            DeploymentRequest req = deploymentService.approve(id, approvedBy);
            return ResponseEntity.ok(Map.of("success", true, "request", req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 审批拒绝 */
    @PostMapping("/reject/{id}")
    public ResponseEntity<?> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String approvedBy = body.getOrDefault("approvedBy", "admin");
        String reason = body.getOrDefault("reason", "无");
        try {
            DeploymentRequest req = deploymentService.reject(id, approvedBy, reason);
            return ResponseEntity.ok(Map.of("success", true, "request", req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 获取待审批列表 */
    @GetMapping("/pending")
    public List<DeploymentRequest> getPending() {
        return deploymentService.getPendingRequests();
    }

    /** 获取项目的部署申请 */
    @GetMapping("/requests")
    public List<DeploymentRequest> getRequests(@RequestParam(required = false) Long projectId) {
        return deploymentService.getRequestsByProject(projectId);
    }

    /** 获取部署历史 */
    @GetMapping("/history")
    public List<DeploymentHistory> getHistory(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long environmentId) {
        return deploymentService.getHistory(projectId, environmentId);
    }

    /** 获取可回滚的部署点 */
    @GetMapping("/rollback-candidates")
    public List<DeploymentHistory> getRollbackCandidates(
            @RequestParam Long projectId,
            @RequestParam Long environmentId) {
        return deploymentService.getRollbackCandidates(projectId, environmentId);
    }

    /** 执行回滚 */
    @PostMapping("/rollback/{historyId}")
    public ResponseEntity<?> rollback(
            @PathVariable Long historyId,
            @RequestBody Map<String, String> body) {
        String triggeredBy = body.getOrDefault("triggeredBy", "admin");
        try {
            DeploymentHistory result = deploymentService.rollback(historyId, triggeredBy);
            return ResponseEntity.ok(Map.of("success", true, "rollback", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
