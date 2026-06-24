package com.devops.platform.controller;

import com.devops.platform.entity.Build;
import com.devops.platform.service.BuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final BuildService buildService;

    /** GitHub Webhook */
    @PostMapping("/github/{projectId}")
    public ResponseEntity<?> githubWebhook(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String event) {
        return handleWebhook(projectId, "GitHub", event, payload);
    }

    /** GitLab Webhook */
    @PostMapping("/gitlab/{projectId}")
    public ResponseEntity<?> gitlabWebhook(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "Push Hook") String event) {
        return handleWebhook(projectId, "GitLab", event, payload);
    }

    /** Gitee Webhook */
    @PostMapping("/gitee/{projectId}")
    public ResponseEntity<?> giteeWebhook(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload) {
        return handleWebhook(projectId, "Gitee", "Push Hook", payload);
    }

    /** 通用 Webhook */
    @PostMapping("/{projectId}")
    public ResponseEntity<?> genericWebhook(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload) {
        return handleWebhook(projectId, "Generic", "push", payload);
    }

    private ResponseEntity<?> handleWebhook(Long projectId, String source, String event, Map<String, Object> payload) {
        try {
            String branch = extractBranch(source, payload);
            String commit = extractCommit(source, payload);
            String committer = extractCommitter(source, payload);

            log.info("收到 {} Webhook: project={}, branch={}, commit={}", source, projectId, branch, commit);

            // 查找项目下匹配分支的活跃流水线
            Build build = buildService.triggerBuildByWebhook(
                    projectId, branch, committer, commit);

            log.info("Webhook 触发构建成功: build={}", build.getBuildNumber());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "source", source,
                    "buildId", build.getId(),
                    "buildNumber", build.getBuildNumber(),
                    "message", "构建已触发: " + build.getBuildNumber()
            ));
        } catch (Exception e) {
            log.error("Webhook 处理失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private String extractBranch(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            } else if ("GitLab".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            } else if ("Gitee".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            }
        } catch (Exception ignored) {}
        return "main";
    }

    @SuppressWarnings("unchecked")
    private String extractCommit(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                Map<String, Object> head = (Map<String, Object>) payload.get("head_commit");
                if (head != null) return (String) head.getOrDefault("id", "");
                return (String) payload.getOrDefault("after", "");
            } else if ("GitLab".equals(source)) {
                Map<String, Object> commits = (Map<String, Object>) payload.get("commits");
                if (commits != null) {
                    java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) commits.get("object");
                    return "";
                }
                return (String) payload.getOrDefault("checkout_sha", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractCommitter(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                Map<String, Object> head = (Map<String, Object>) payload.get("head_commit");
                if (head != null) {
                    Map<String, Object> author = (Map<String, Object>) head.get("author");
                    if (author != null) return (String) author.getOrDefault("name", "webhook");
                }
                Map<String, Object> pusher = (Map<String, Object>) payload.get("pusher");
                if (pusher != null) return (String) pusher.getOrDefault("name", "webhook");
            } else if ("GitLab".equals(source)) {
                return (String) payload.getOrDefault("user_username", "webhook");
            } else if ("Gitee".equals(source)) {
                Map<String, Object> sender = (Map<String, Object>) payload.get("sender");
                if (sender != null) return (String) sender.getOrDefault("name", "webhook");
            }
        } catch (Exception ignored) {}
        return "webhook";
    }
}
