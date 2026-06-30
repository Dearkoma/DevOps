package com.devops.platform.service;

import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代码预览服务 —— 直接查看 Git 仓库代码，无需下载。
 * 复用构建工作区的 Git 克隆逻辑，提供文件树浏览和文件内容查看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodePreviewService {

    private final ProjectRepository projectRepository;
    private final BuildService buildService;

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    private static final int MAX_FILE_SIZE = 500 * 1024; // 500KB
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "__pycache__",
            ".idea", ".vscode", "dist", "build", ".gradle", "vendor", ".mvn");
    private static final Set<String> SKIP_FILES = Set.of(".DS_Store", "Thumbs.db");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "jar", "war", "class", "exe", "dll", "so", "dylib", "bin",
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "gz", "tar", "7z", "rar",
            "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "sqlite3", ".trace.db");

    // ==================== 文件树 ====================

    public Map<String, Object> getFileTree(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Path ws = resolveWorkspace(project);
            if (ws == null || !Files.exists(ws)) {
                result.put("error", "工作目录尚未创建，请先触发一次构建或确保 Git 地址可访问");
                return result;
            }

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("name", project.getCode());
            root.put("type", "directory");
            root.put("path", "");
            root.put("children", buildTree(ws, ws, 4));
            result.put("tree", root);
            result.put("workspacePath", ws.toString());
        } catch (Exception e) {
            log.error("预览文件树失败 projectId={}", projectId, e);
            result.put("error", "预览失败: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> buildTree(Path root, Path dir, int maxDepth) throws IOException {
        if (maxDepth <= 0) return List.of();

        List<Map<String, Object>> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (SKIP_DIRS.contains(name)) continue;
                if (SKIP_FILES.contains(name)) continue;
                if (name.startsWith(".") && !name.equals(".gitignore") && !name.equals(".env.example")) continue;

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                String relPath = root.relativize(p).toString().replace('\\', '/');

                if (Files.isDirectory(p)) {
                    node.put("type", "directory");
                    node.put("path", relPath);
                    node.put("children", buildTree(root, p, maxDepth - 1));
                } else {
                    node.put("type", isBinary(p) ? "binary" : "file");
                    node.put("path", relPath);
                    try {
                        node.put("size", Files.size(p));
                    } catch (IOException ignored) {}
                }
                children.add(node);
            }
        }
        // 排序：目录在前，同名排序
        children.sort((a, b) -> {
            boolean aDir = "directory".equals(a.get("type"));
            boolean bDir = "directory".equals(b.get("type"));
            if (aDir != bDir) return aDir ? -1 : 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });
        return children;
    }

    // ==================== 文件内容 ====================

    public Map<String, Object> getFileContent(Long projectId, String filePath) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Path ws = resolveWorkspace(project);
            if (ws == null || !Files.exists(ws)) {
                result.put("error", "工作目录尚未创建");
                return result;
            }

            // 安全检查：防止路径穿越
            Path target = ws.resolve(filePath).normalize();
            if (!target.startsWith(ws)) {
                result.put("error", "非法的文件路径");
                return result;
            }

            if (!Files.exists(target) || Files.isDirectory(target)) {
                result.put("error", "文件不存在");
                return result;
            }

            if (Files.size(target) > MAX_FILE_SIZE) {
                result.put("error", "文件过大（>" + (MAX_FILE_SIZE / 1024) + "KB），无法预览");
                return result;
            }

            if (isBinary(target)) {
                result.put("error", "二进制文件无法预览");
                return result;
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            result.put("content", content);
            result.put("path", filePath);
            result.put("size", Files.size(target));
            result.put("name", target.getFileName().toString());

            // 猜测语言用于前端高亮
            result.put("language", guessLanguage(target.getFileName().toString()));
        } catch (Exception e) {
            log.error("读取文件失败 projectId={} path={}", projectId, filePath, e);
            result.put("error", "读取失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private Path resolveWorkspace(Project project) {
        String code = project.getCode();
        if (code == null || code.isBlank()) return null;
        Path root = Paths.get(workspaceDir).toAbsolutePath().normalize();
        return root.resolve(code);
    }

    private boolean isBinary(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (BINARY_EXTENSIONS.contains(ext)) return true;
        }
        // 无扩展名也可能是二进制，快速检查前 256 字节
        try {
            byte[] head = new byte[256];
            try (var in = Files.newInputStream(p)) {
                int read = in.read(head);
                for (int i = 0; i < read; i++) {
                    if (head[i] == 0) return true; // null byte → binary
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    private String guessLanguage(String fileName) {
        if (fileName.endsWith(".java")) return "java";
        if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) return "javascript";
        if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) return "typescript";
        if (fileName.endsWith(".py")) return "python";
        if (fileName.endsWith(".go")) return "go";
        if (fileName.endsWith(".rs")) return "rust";
        if (fileName.endsWith(".xml") || fileName.equals("pom.xml")) return "xml";
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "html";
        if (fileName.endsWith(".css")) return "css";
        if (fileName.endsWith(".json") || fileName.equals("package.json") || fileName.equals("package-lock.json")) return "json";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "yaml";
        if (fileName.endsWith(".properties")) return "properties";
        if (fileName.endsWith(".md")) return "markdown";
        if (fileName.endsWith(".sql")) return "sql";
        if (fileName.endsWith(".sh") || fileName.equals("mvnw")) return "bash";
        if (fileName.equals("Dockerfile") || fileName.endsWith(".dockerfile")) return "dockerfile";
        if (fileName.equals("Makefile")) return "makefile";
        if (fileName.endsWith(".gradle")) return "groovy";
        if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) return "kotlin";
        if (fileName.endsWith(".c") || fileName.endsWith(".h")) return "c";
        if (fileName.endsWith(".cpp") || fileName.endsWith(".hpp") || fileName.endsWith(".cc")) return "cpp";
        return "plaintext";
    }
}
