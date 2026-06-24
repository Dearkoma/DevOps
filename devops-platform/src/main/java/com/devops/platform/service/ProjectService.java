package com.devops.platform.service;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;
    public List<Project> getAllProjects() { return projectRepository.findAll(); }
    public Project getProject(Long id) { return projectRepository.findById(id).orElse(null); }
    public Project createProject(Project project) {
        if (project.getCode() == null || project.getCode().isEmpty()) {
            project.setCode("proj-" + System.currentTimeMillis());
        }
        return projectRepository.save(project);
    }
    public Project updateProject(Long id, Project project) {
        Project existing = getProject(id);
        if (existing == null) return null;
        existing.setName(project.getName());
        existing.setDescription(project.getDescription());
        existing.setGitUrl(project.getGitUrl());
        existing.setGitBranch(project.getGitBranch());
        existing.setBuildCommand(project.getBuildCommand());
        existing.setStartCommand(project.getStartCommand());
        existing.setLanguage(project.getLanguage());
        existing.setFramework(project.getFramework());
        existing.setEnabled(project.getEnabled());
        return projectRepository.save(existing);
    }
    public void deleteProject(Long id) { projectRepository.deleteById(id); }
    public List<Project> findByStatus(String status) { return projectRepository.findByStatus(status); }
}
