package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.PipelineRepository;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final PipelineRepository pipelineRepository;
    private final ProjectRepository projectRepository;
    private final BuildService buildService;

    /** 每分钟检查一次是否有需要执行的定时构建 */
    @Scheduled(fixedRate = 60000)
    public void checkScheduledBuilds() {
        List<Pipeline> pipelines = pipelineRepository.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (Pipeline pipeline : pipelines) {
            if (pipeline.getCronExpression() == null || pipeline.getCronExpression().isBlank()) continue;
            if (!Boolean.TRUE.equals(pipeline.getCronEnabled())) continue;

            try {
                // 简单 Cron 检查：解析分钟级 cron 表达式
                if (shouldRunNow(pipeline.getCronExpression(), now)) {
                    Project project = projectRepository.findById(pipeline.getProjectId()).orElse(null);
                    if (project == null) continue;

                    Build build = buildService.triggerBuild(
                            project.getId(), pipeline.getId(), "SCHEDULER");
                    log.info("定时构建已触发: project={}, pipeline={}, build={}",
                            project.getName(), pipeline.getName(), build.getBuildNumber());
                }
            } catch (Exception e) {
                log.warn("定时构建检查异常: pipeline={}, error={}", pipeline.getId(), e.getMessage());
            }
        }
    }

    /** 简单 Cron 检查（支持分钟级表达式：分 时 日 月 周） */
    private boolean shouldRunNow(String cronExpression, LocalDateTime now) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length < 5) return false;

        return matches(parts[0], now.getMinute(), 0, 59)
                && matches(parts[1], now.getHour(), 0, 23)
                && matches(parts[2], now.getDayOfMonth(), 1, 31)
                && matches(parts[3], now.getMonthValue(), 1, 12)
                && matches(parts[4], now.getDayOfWeek().getValue() % 7, 0, 6);
    }

    private boolean matches(String cronField, int value, int min, int max) {
        if (cronField.equals("*")) return true;
        for (String part : cronField.split(",")) {
            if (part.contains("/")) {
                String[] stepParts = part.split("/");
                int step = Integer.parseInt(stepParts[1]);
                String range = stepParts[0];
                int start = range.equals("*") ? min : Integer.parseInt(range);
                if (value >= start && (value - start) % step == 0) return true;
            } else if (part.contains("-")) {
                String[] rangeParts = part.split("-");
                int start = Integer.parseInt(rangeParts[0]);
                int end = Integer.parseInt(rangeParts[1]);
                if (value >= start && value <= end) return true;
            } else {
                if (Integer.parseInt(part) == value) return true;
            }
        }
        return false;
    }
}
