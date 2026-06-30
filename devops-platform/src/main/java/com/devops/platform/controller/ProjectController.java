package com.devops.platform.controller;
import com.devops.platform.entity.Project;
import com.devops.platform.service.BuildService;
import com.devops.platform.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final BuildService buildService;
    @GetMapping
    public List<Project> getAll() { return projectService.getAllProjects(); }
    @GetMapping("/{id}")
    public ResponseEntity<Project> getOne(@PathVariable Long id) {
        Project p = projectService.getProject(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @PostMapping
    public Project create(@RequestBody Project project) { return projectService.createProject(project); }
    @PutMapping("/{id}")
    public ResponseEntity<Project> update(@PathVariable Long id, @RequestBody Project project) {
        Project p = projectService.updateProject(id, project);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ResponseEntity.ok().build();
    }
    @GetMapping("/by-status")
    public List<Project> getByStatus(@RequestParam String status) {
        return projectService.findByStatus(status);
    }

    /**
     * 列出项目文件 —— 支持 workspace / docker / k8s 三个环境
     * @param context 环境标识，默认 "workspace"
     */
    @GetMapping("/{id}/files")
    public ResponseEntity<?> listFiles(@PathVariable Long id,
                                        @RequestParam(defaultValue = "") String path,
                                        @RequestParam(defaultValue = "workspace") String context) {
        try {
            List<Map<String, Object>> files = buildService.listProjectFiles(id, path, context);
            return ResponseEntity.ok(files);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 读取项目文件内容 —— 支持 workspace / docker / k8s
     */
    @GetMapping("/{id}/files/content")
    public ResponseEntity<?> readFileContent(@PathVariable Long id,
                                              @RequestParam String path,
                                              @RequestParam(defaultValue = "workspace") String context) {
        try {
            Map<String, Object> result = buildService.readProjectFile(id, path, context);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 写入/编辑文件内容 —— 仅 Docker/K8s 环境下可用
     */
    @PutMapping("/{id}/files/content")
    public ResponseEntity<?> writeFileContent(@PathVariable Long id,
                                               @RequestParam String path,
                                               @RequestParam String context,
                                               @RequestBody Map<String, String> body) {
        try {
            String content = body.get("content");
            if (content == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 content 字段"));
            }
            Map<String, Object> result = buildService.writeProjectFile(id, path, content, context);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
