package com.devops.platform.controller;
import com.devops.platform.entity.Project;
import com.devops.platform.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
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
}
