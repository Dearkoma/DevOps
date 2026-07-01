package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.PipelineRepository;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
                // 使用 Spring 内置 CronExpression 替代手写解析器
                CronExpression cron = CronExpression.parse(pipeline.getCronExpression());
                LocalDateTime next = cron.next(now.minusMinutes(1));
                if (next != null && !next.isAfter(now) && next.isAfter(now.minusMinutes(1))) {
                    Project project = projectRepository.findById(pipeline.getProjectId()).orElse(null);
                    if (project == null) continue;

                    Build build = buildService.triggerBuild(
                            project.getId(), pipeline.getId(), "SCHEDULER");
                    log.info("定时构建已触发: project={}, pipeline={}, build={}",
                            project.getName(), pipeline.getName(), build.getBuildNumber());
                }
            } catch (Exception e) {
                log.warn("定时构建检查异常: pipeline={}, cron={}, error={}",
                        pipeline.getId(), pipeline.getCronExpression(), e.getMessage());
            }
        }
    }

    /**
     * 验证 Cron 表达式是否有效
     */
    public static boolean isValidCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) return false;
        try {
            CronExpression.parse(cronExpression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
