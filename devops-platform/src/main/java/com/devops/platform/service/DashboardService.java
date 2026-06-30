package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProjectRepository projectRepository;
    private final BuildRepository buildRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentRepository environmentRepository;
    private final DeploymentRequestRepository deploymentRequestRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final NotificationRepository notificationRepository;
    private final ArtifactRepository artifactRepository;
    private final TemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProjects", projectRepository.count());
        stats.put("totalBuilds", buildRepository.count());
        stats.put("successBuilds", buildRepository.countByStatus("SUCCESS"));
        stats.put("failedBuilds", buildRepository.countByStatus("FAILED"));
        stats.put("runningBuilds", buildRepository.countByStatus("RUNNING"));
        stats.put("activePipelines", pipelineRepository.count());
        stats.put("totalEnvironments", environmentRepository.count());
        stats.put("successRate", calculateSuccessRate());
        stats.put("avgBuildTimeMs", calculateAvgBuildTime());

        // 新功能统计
        stats.put("pendingDeployments", deploymentRequestRepository.findByStatusOrderByCreatedAtDesc("PENDING").size());
        stats.put("totalDeployments", deploymentHistoryRepository.count());
        stats.put("runningInstances", instanceRepository.findByStatus("RUNNING").size());
        stats.put("unreadNotifications", notificationRepository.countByIsReadFalse());

        // 侧边栏计数
        stats.put("totalInstances", instanceRepository.count());
        long dockerCount = instanceRepository.findAll().stream()
                .filter(i -> "DOCKER".equals(i.getDeployType())).count();
        stats.put("dockerInstances", dockerCount);
        stats.put("k8sInstances", instanceRepository.count() - dockerCount);
        stats.put("totalArtifacts", artifactRepository.count());
        stats.put("totalTemplates", templateRepository.count());
        stats.put("totalUsers", userRepository.count());
        stats.put("totalAuditLogs", auditLogRepository.count());
        long scheduledPipelines = pipelineRepository.findAll().stream()
                .filter(p -> p.getCronExpression() != null && !p.getCronExpression().isBlank()
                        && Boolean.TRUE.equals(p.getCronEnabled())).count();
        stats.put("totalSchedules", scheduledPipelines);

        return stats;
    }

    public List<Map<String, Object>> getRecentBuilds(int limit) {
        List<Build> builds = buildRepository.findAll();
        builds.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, builds.size()); i++) {
            Build b = builds.get(i);
            Map<String, Object> map = new HashMap<>();
            map.put("id", b.getId());
            map.put("buildNumber", b.getBuildNumber());
            map.put("projectId", b.getProjectId());
            map.put("pipelineId", b.getPipelineId());
            map.put("status", b.getStatus());
            map.put("startTime", b.getStartTime());
            map.put("durationMs", b.getDurationMs());
            map.put("completedSteps", b.getCompletedSteps());
            map.put("totalSteps", b.getTotalSteps());
            result.add(map);
        }
        return result;
    }

    /** 最近 7 天每日构建成功/失败趋势 */
    public List<Map<String, Object>> getBuildTrends() {
        List<Map<String, Object>> trends = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            List<Build> dayBuilds = buildRepository.findByStartTimeBetween(start, end);
            long success = dayBuilds.stream().filter(b -> "SUCCESS".equals(b.getStatus())).count();
            long failed  = dayBuilds.stream().filter(b -> "FAILED".equals(b.getStatus())).count();
            long running = dayBuilds.stream().filter(b -> "RUNNING".equals(b.getStatus())).count();
            Map<String, Object> m = new HashMap<>();
            m.put("date", date.format(fmt));
            m.put("success", success);
            m.put("failed", failed);
            m.put("running", running);
            m.put("total", dayBuilds.size());
            trends.add(m);
        }
        return trends;
    }

    private double calculateSuccessRate() {
        long total = buildRepository.count();
        if (total == 0) return 0.0;
        long success = buildRepository.countByStatus("SUCCESS");
        return Math.round(success * 1000.0 / total) / 10.0;
    }

    private long calculateAvgBuildTime() {
        List<Build> all = buildRepository.findByStatus("SUCCESS");
        if (all.isEmpty()) return 0;
        long total = all.stream()
                .filter(b -> b.getDurationMs() != null)
                .mapToLong(Build::getDurationMs)
                .sum();
        long count = all.stream().filter(b -> b.getDurationMs() != null).count();
        return count > 0 ? total / count : 0;
    }
}
