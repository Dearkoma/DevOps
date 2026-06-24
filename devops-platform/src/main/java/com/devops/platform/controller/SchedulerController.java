package com.devops.platform.controller;

import com.devops.platform.entity.Pipeline;
import com.devops.platform.repository.PipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final PipelineRepository pipelineRepository;

    /** 获取所有定时构建配置 */
    @GetMapping
    public List<Map<String, Object>> getSchedules() {
        return pipelineRepository.findAll().stream()
                .filter(p -> p.getCronExpression() != null && !p.getCronExpression().isBlank())
                .map(p -> Map.<String, Object>of(
                        "pipelineId", p.getId(),
                        "pipelineName", p.getName(),
                        "projectId", p.getProjectId(),
                        "cronExpression", p.getCronExpression(),
                        "cronEnabled", p.getCronEnabled()
                ))
                .toList();
    }

    /** 更新流水线 Cron */
    @PutMapping("/pipeline/{pipelineId}")
    public ResponseEntity<?> updateCron(
            @PathVariable Long pipelineId,
            @RequestBody Map<String, Object> body) {
        Pipeline p = pipelineRepository.findById(pipelineId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();

        if (body.containsKey("cronExpression")) {
            p.setCronExpression((String) body.get("cronExpression"));
        }
        if (body.containsKey("cronEnabled")) {
            p.setCronEnabled((Boolean) body.get("cronEnabled"));
        }
        pipelineRepository.save(p);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
