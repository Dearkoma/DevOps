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
            boolean skipDocker = false;
            boolean skipK8s = false;
            String dbName = null;
            if (body != null) {
                if (body.containsKey("buildParams")) {
                    Object p = body.get("buildParams");
                    buildParams = p == null ? null : (p instanceof String ? (String) p : p.toString());
                }
                if (body.containsKey("branch")) {
                    Object b = body.get("branch");
                    branch = b == null ? null : (b instanceof String ? (String) b : b.toString());
                }
                // 接收独立的跳过标志（向后兼容旧的 deployTarget 字段）
                if (body.containsKey("skipDocker")) {
                    skipDocker = Boolean.TRUE.equals(body.get("skipDocker"));
                }
                if (body.containsKey("skipK8s")) {
                    skipK8s = Boolean.TRUE.equals(body.get("skipK8s"));
                }
                // 兼容旧版 deployTarget 参数
                if (body.containsKey("deployTarget") && !body.containsKey("skipDocker") && !body.containsKey("skipK8s")) {
                    String dt = body.get("deployTarget").toString().toUpperCase();
                    switch (dt) {
                        case "NONE":  skipDocker = true;  skipK8s = true;  break;
                        case "DOCKER": skipDocker = false; skipK8s = true;  break;
                        case "K8S":    skipDocker = false; skipK8s = false; break;
                    }
                }
                // 数据库名（用于数据库隔离）
                if (body.containsKey("dbName")) {
                    Object d = body.get("dbName");
                    dbName = d == null ? null : d.toString();
                }
            }
            Build build = buildService.triggerBuild(projectId, pipelineId, triggeredBy,
                    buildParams, branch, skipDocker, skipK8s, dbName);
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

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBuild(@PathVariable Long id) {
        try {
            buildService.deleteBuild(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 检查项目工作目录状态 — 验证 Git 克隆情况和构建文件 */
    @GetMapping("/workspace-check")
    public ResponseEntity<?> workspaceCheck(@RequestParam Long projectId) {
        return ResponseEntity.ok(buildService.checkWorkspace(projectId));
    }

    /** 检查数据库名是否与已有项目冲突 */
    @GetMapping("/check-db-conflict")
    public ResponseEntity<?> checkDbConflict(@RequestParam String dbName, @RequestParam Long projectId) {
        try {
            var result = buildService.checkDbConflict(dbName, projectId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
