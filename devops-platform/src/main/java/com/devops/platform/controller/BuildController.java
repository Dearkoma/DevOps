package com.devops.platform.controller;

import com.devops.platform.entity.Build;
import com.devops.platform.service.BuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/builds")
@RequiredArgsConstructor
public class BuildController {

    private final BuildService buildService;

    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(
            @RequestParam Long projectId,
            @RequestParam Long pipelineId,
            @RequestParam(defaultValue = "admin") String triggeredBy,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String buildParams = null;
            String branch = null;
            if (body != null) {
                if (body.containsKey("buildParams")) {
                    Object p = body.get("buildParams");
                    buildParams = p instanceof String ? (String) p : p.toString();
                }
                if (body.containsKey("branch")) {
                    branch = (String) body.get("branch");
                }
            }
            Build build = buildService.triggerBuild(projectId, pipelineId, triggeredBy, buildParams, branch);
            return ResponseEntity.ok(build);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Build> getBuilds(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        return buildService.getBuilds(projectId, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Build> getOne(@PathVariable Long id) {
        Build b = buildService.getBuild(id);
        return b == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(b);
    }

    @GetMapping("/{id}/log")
    public ResponseEntity<String> getLog(@PathVariable Long id) {
        String log = buildService.getBuildLog(id);
        return ResponseEntity.ok(log);
    }

    @DeleteMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        buildService.cancelBuild(id);
        return ResponseEntity.ok().build();
    }
}
