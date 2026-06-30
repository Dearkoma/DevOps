package com.devops.platform.service;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    private Path workspaceRoot;

    @PostConstruct
    public void init() {
        try {
            workspaceRoot = Paths.get(workspaceDir).toAbsolutePath().normalize();
            Files.createDirectories(workspaceRoot);
            log.info("项目工作区根目录: {}", workspaceRoot);
        } catch (IOException e) {
            log.error("初始化工作区根目录失败: {}", workspaceDir, e);
        }
    }

    public List<Project> getAllProjects() { return projectRepository.findAll(); }
    public Project getProject(Long id) { return projectRepository.findById(id).orElse(null); }

    public Project createProject(Project project) {
        if (project.getCode() == null || project.getCode().isEmpty()) {
            project.setCode("proj-" + System.currentTimeMillis());
        }
        Project saved = projectRepository.save(project);
        // 新建项目时同步创建工作目录, 让用户立刻能点"查看文件"看到目录内容
        createProjectWorkspace(saved);
        return saved;
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

    public void deleteProject(Long id) {
        Project existing = getProject(id);
        if (existing == null) return;
        // 删数据库前先删工作目录(避免脏数据残留)
        deleteProjectWorkspace(existing);
        projectRepository.deleteById(id);
    }

    public List<Project> findByStatus(String status) { return projectRepository.findByStatus(status); }

    /* ---------- 工作目录管理 ---------- */

    private Path getProjectWorkspacePath(Project project) {
        if (workspaceRoot == null) {
            workspaceRoot = Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return workspaceRoot.resolve(project.getCode());
    }

    private void createProjectWorkspace(Project project) {
        try {
            Path projectDir = getProjectWorkspacePath(project);
            Files.createDirectories(projectDir);

            // 写入 README 占位文件, 让用户点"查看文件"时有内容可看
            Path readme = projectDir.resolve("README.md");
            if (!Files.exists(readme)) {
                String content = String.format(
                    "# %s%n%n" +
                    "> 项目工作区 (Workspace)%n%n" +
                    "- 项目编码: `%s`%n" +
                    "- 创建时间: %s%n" +
                    "- Git 仓库: %s%n" +
                    "- 分支: %s%n%n" +
                    "## 说明%n%n" +
                    "此目录是平台为该项目自动创建的工作区. " +
                    "首次触发构建时, 平台会从 Git 仓库克隆代码到此目录, " +
                    "后续的构建/部署都会基于此目录进行.%n%n" +
                    "在触发构建之前, 你可以在此目录中手动上传或创建文件.%n",
                    project.getName() == null ? project.getCode() : project.getName(),
                    project.getCode(),
                    java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    project.getGitUrl() == null ? "(未配置)" : project.getGitUrl(),
                    project.getGitBranch() == null ? "(未配置)" : project.getGitBranch()
                );
                Files.writeString(readme, content);
            }

            log.info("已创建项目工作目录: {}", projectDir);
        } catch (IOException e) {
            log.error("创建项目工作目录失败: project={}", project.getCode(), e);
            // 不抛异常, 工作目录创建失败不影响项目创建本身
        }
    }

    private void deleteProjectWorkspace(Project project) {
        try {
            Path projectDir = getProjectWorkspacePath(project);
            if (Files.exists(projectDir)) {
                deleteRecursively(projectDir);
                log.info("已删除项目工作目录: {}", projectDir);
            }
        } catch (IOException e) {
            log.error("删除项目工作目录失败: project={}", project.getCode(), e);
            // 不抛异常, 工作目录删除失败不影响项目删除本身
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
