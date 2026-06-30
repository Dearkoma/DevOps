package com.devops.platform.controller;
import com.devops.platform.entity.Project;
import com.devops.platform.service.CodePreviewService;
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
    private final CodePreviewService codePreviewService;
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

    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewCode(@PathVariable Long id) {
        return ResponseEntity.ok(codePreviewService.getFileTree(id));
    }

    @GetMapping("/{id}/preview/file")
    public ResponseEntity<Map<String, Object>> previewFile(@PathVariable Long id, @RequestParam String path) {
        return ResponseEntity.ok(codePreviewService.getFileContent(id, path));
    }
}
