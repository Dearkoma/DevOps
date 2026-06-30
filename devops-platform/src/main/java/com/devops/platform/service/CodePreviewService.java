package com.devops.platform.service;

import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * 代码预览服务 —— 纯远程，不 clone、不构建、不依赖本地工作目录。
 * 支持 GitHub / Gitee API 直读，通用 Git 走临时浅克隆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodePreviewService {

    private final ProjectRepository projectRepository;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ---- 临时克隆缓存（仅非 GitHub/Gitee 仓库使用） ----
    private final Map<Long, TempClone> cloneCache = new ConcurrentHashMap<>();
    private static final long CLONE_TTL_MS = 30 * 60 * 1000; // 30 分钟

    private static final int MAX_FILE_SIZE = 500 * 1024;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "__pycache__",
            ".idea", ".vscode", "dist", "build", ".gradle", "vendor", ".mvn");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "jar", "war", "class", "exe", "dll", "so", "dylib", "bin",
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "gz", "tar", "7z", "rar",
            "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "sqlite3");

    // ==================== 文件树 ====================

    public Map<String, Object> getFileTree(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        String gitUrl = project.getGitUrl();
        if (gitUrl == null || gitUrl.isBlank()) {
            return error("项目未配置 Git 地址");
        }

        GitInfo info = parseGitUrl(gitUrl, project.getGitBranch());
        if (info == null) {
            return error("无法解析 Git 地址格式: " + gitUrl);
        }

        try {
            return switch (info.platform) {
                case "github" -> buildTreeFromGitHub(info, "https://api.github.com");
                case "gitee"  -> buildTreeFromGitHub(info, "https://gitee.com/api/v5");
                default       -> buildTreeFromTempClone(projectId, info);
            };
        } catch (Exception e) {
            log.error("远程预览失败 projectId={} url={}", projectId, gitUrl, e);
            return error("预览失败: " + e.getMessage());
        }
    }

    // ==================== 文件内容 ====================

    public Map<String, Object> getFileContent(Long projectId, String filePath) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        String gitUrl = project.getGitUrl();
        if (gitUrl == null || gitUrl.isBlank()) {
            return error("项目未配置 Git 地址");
        }

        GitInfo info = parseGitUrl(gitUrl, project.getGitBranch());
        if (info == null) {
            return error("无法解析 Git 地址格式");
        }

        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        if (isBinaryByName(name)) {
            return error("二进制文件无法预览");
        }

        try {
            return switch (info.platform) {
                case "github" -> fetchFileFromRaw(info, filePath,
                        "https://raw.githubusercontent.com/" + info.owner + "/" + info.repo + "/" + info.branch + "/" + filePath);
                case "gitee" -> fetchFileFromRaw(info, filePath,
                        "https://gitee.com/" + info.owner + "/" + info.repo + "/raw/" + info.branch + "/" + filePath);
                default -> fetchFileFromClone(projectId, filePath);
            };
        } catch (Exception e) {
            log.error("读取远程文件失败 projectId={} path={}", projectId, filePath, e);
            return error("读取失败: " + e.getMessage());
        }
    }

    // ==================== GitHub / Gitee 共用 API 树构建 ====================

    private Map<String, Object> buildTreeFromGitHub(GitInfo info, String apiBase) throws Exception {
        // 获取仓库默认分支（如果指定分支不存在）
        String branch = resolveBranch(apiBase, info);

        String url = apiBase + "/repos/" + info.owner + "/" + info.repo
                + "/git/trees/" + branch + "?recursive=1";
        JsonNode body = apiGet(url);

        if (body.has("message")) {
            String msg = body.get("message").asText();
            if (msg.contains("empty")) {
                return successTree(info.repo, List.of());
            }
            throw new RuntimeException(msg);
        }

        JsonNode tree = body.get("tree");
        if (tree == null || !tree.isArray() || tree.size() == 0) {
            return successTree(info.repo, List.of());
        }

        // 展平 → 嵌套
        TreeNode rootNode = new TreeNode(info.repo, "directory", "");
        for (JsonNode item : tree) {
            String path = item.get("path").asText();
            String type = "tree".equals(item.get("type").asText()) ? "directory" : "file";
            long size = item.has("size") ? item.get("size").asLong() : 0;

            String[] parts = path.split("/");
            if (shouldSkip(parts)) continue;

            if ("file".equals(type) && isBinaryByName(parts[parts.length - 1])) {
                type = "binary";
            }

            rootNode.add(parts, 0, type, path, size);
        }

        rootNode.sort();
        return successTree(info.repo, rootNode.childrenAsList());
    }

    // ==================== 原始文件获取（GitHub / Gitee raw URL） ====================

    private Map<String, Object> fetchFileFromRaw(GitInfo info, String filePath, String rawUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rawUrl))
                .header("User-Agent", "DevOps-Platform")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            return error("文件不存在或无法访问 (HTTP " + resp.statusCode() + ")");
        }

        String content = resp.body();
        if (content.length() > MAX_FILE_SIZE) {
            return error("文件过大（>" + (MAX_FILE_SIZE / 1024) + "KB），无法预览");
        }

        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("path", filePath);
        result.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        result.put("name", name);
        result.put("language", guessLanguage(name));
        return result;
    }

    // ==================== 临时克隆（非 GitHub/Gitee 仓库） ====================

    private Map<String, Object> buildTreeFromTempClone(Long projectId, GitInfo info) throws Exception {
        Path dir = ensureCloned(projectId, info);

        TreeNode rootNode = new TreeNode(info.repo, "directory", "");
        walkLocal(dir, dir, rootNode, 5);
        rootNode.sort();
        return successTree(info.repo, rootNode.childrenAsList());
    }

    private Map<String, Object> fetchFileFromClone(Long projectId, String filePath) throws Exception {
        GitInfo info = getCachedInfo(projectId);
        if (info == null) {
            return error("请先展开文件树以触发远程获取");
        }
        Path dir = ensureCloned(projectId, info);
        Path target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) return error("非法的文件路径");
        if (!Files.exists(target) || Files.isDirectory(target)) return error("文件不存在");
        if (Files.size(target) > MAX_FILE_SIZE) return error("文件过大");
        if (isBinaryByLocal(target)) return error("二进制文件无法预览");

        String content = Files.readString(target, StandardCharsets.UTF_8);
        String name = target.getFileName().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("path", filePath);
        result.put("size", Files.size(target));
        result.put("name", name);
        result.put("language", guessLanguage(name));
        return result;
    }

    private Path ensureCloned(Long projectId, GitInfo info) throws Exception {
        TempClone existing = cloneCache.get(projectId);
        if (existing != null && Files.exists(existing.dir)) {
            existing.touch();
            return existing.dir;
        }

        Path tmp = Files.createTempDirectory("code-preview-");
        String url = "https://github.com".equals(info.origin)
                ? "https://github.com/" + info.owner + "/" + info.repo + ".git"
                : info.origin;

        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", "--single-branch",
                "--branch", info.branch, url, tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            deleteDir(tmp);
            throw new RuntimeException("Git 克隆失败: " + output);
        }

        cloneCache.put(projectId, new TempClone(tmp, info));
        return tmp;
    }

    private GitInfo getCachedInfo(Long projectId) {
        TempClone c = cloneCache.get(projectId);
        return c != null ? c.info : null;
    }

    // ==================== 工具方法 ====================

    /** 解析 Git URL → 平台 + owner + repo + branch */
    private GitInfo parseGitUrl(String url, String configuredBranch) {
        if (url == null) return null;
        url = url.trim();

        // 去掉末尾 .git 和 /
        url = url.replaceAll("\\.git$", "");
        url = url.replaceAll("/$", "");

        // 匹配: https://github.com/owner/repo  /  git@github.com:owner/repo
        Pattern p = Pattern.compile(
                "(?:https?://)?(?:www\\.)?(github\\.com|gitee\\.com)[/:]([^/]+)/([^/]+?)(?:/.*)?$");
        Matcher m = p.matcher(url);
        if (m.find()) {
            String host = m.group(1);
            String platform = host.contains("gitee") ? "gitee" : "github";
            String branch = configuredBranch != null && !configuredBranch.isBlank()
                    ? configuredBranch : "main";
            return new GitInfo(platform, host, m.group(2), m.group(3), branch, url);
        }

        // 通用 Git URL：尝试解析
        Pattern generic = Pattern.compile(
                "(?:https?://|git@)([^/:]+)[/:]([^/]+)/([^/]+?)(?:\\.git)?(?:/.*)?$");
        Matcher gm = generic.matcher(url);
        if (gm.find()) {
            String branch = configuredBranch != null && !configuredBranch.isBlank()
                    ? configuredBranch : "main";
            return new GitInfo("generic", gm.group(1), gm.group(2), gm.group(3), branch, url);
        }

        return null;
    }

    /** 尝试确认分支存在，失败则回退到仓库默认分支 */
    private String resolveBranch(String apiBase, GitInfo info) {
        try {
            String url = apiBase + "/repos/" + info.owner + "/" + info.repo
                    + "/branches/" + info.branch;
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "DevOps-Platform")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return info.branch;
        } catch (Exception ignored) {}
        // 回退到默认分支
        try {
            String url = apiBase + "/repos/" + info.owner + "/" + info.repo;
            JsonNode repo = apiGet(url);
            if (repo.has("default_branch")) {
                return repo.get("default_branch").asText();
            }
        } catch (Exception ignored) {}
        return info.branch; // 尽力了
    }

    private JsonNode apiGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DevOps-Platform")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            throw new RuntimeException("仓库不存在或为私有仓库（仅支持公开仓库远程预览）");
        }
        return om.readTree(resp.body());
    }

    private boolean shouldSkip(String[] pathParts) {
        for (String part : pathParts) {
            if (SKIP_DIRS.contains(part)) return true;
        }
        return false;
    }

    private boolean isBinaryByName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private boolean isBinaryByLocal(Path p) {
        if (isBinaryByName(p.getFileName().toString())) return true;
        try {
            byte[] head = Files.readAllBytes(p);
            int len = Math.min(head.length, 256);
            for (int i = 0; i < len; i++) {
                if (head[i] == 0) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private void walkLocal(Path root, Path dir, TreeNode parent, int depth) throws IOException {
        if (depth <= 0) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (SKIP_DIRS.contains(name)) continue;
                if (name.startsWith(".")) continue;

                String rel = root.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    TreeNode child = new TreeNode(name, "directory", rel);
                    walkLocal(root, p, child, depth - 1);
                    parent.children.put(name, child);
                } else {
                    String type = isBinaryByLocal(p) ? "binary" : "file";
                    long size;
                    try { size = Files.size(p); } catch (IOException e) { size = 0; }
                    parent.children.put(name, new TreeNode(name, type, rel, size));
                }
            }
        }
    }

    private void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var s = Files.walk(dir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }

    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanCloneCache() {
        long now = System.currentTimeMillis();
        cloneCache.entrySet().removeIf(e -> {
            if (now - e.getValue().createdAt > CLONE_TTL_MS) {
                deleteDir(e.getValue().dir);
                return true;
            }
            return false;
        });
    }

    // ==================== 响应构建 ====================

    private Map<String, Object> successTree(String repoName, List<Map<String, Object>> children) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", repoName);
        root.put("type", "directory");
        root.put("path", "");
        root.put("children", children);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", root);
        return result;
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", msg);
        return result;
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
        if (fileName.endsWith(".json")) return "json";
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

    // ==================== 内部类 ====================

    /** Git URL 解析结果 */
    private record GitInfo(String platform, String host, String owner, String repo, String branch, String origin) {}

    /** 临时克隆缓存条目 */
    private static class TempClone {
        final Path dir;
        final GitInfo info;
        long createdAt;

        TempClone(Path dir, GitInfo info) {
            this.dir = dir;
            this.info = info;
            this.createdAt = System.currentTimeMillis();
        }
        void touch() { this.createdAt = System.currentTimeMillis(); }
    }

    /** 文件树节点（构建嵌套结构用） */
    private static class TreeNode {
        String name, type, path;
        long size;
        Map<String, TreeNode> children = new LinkedHashMap<>();

        TreeNode(String name, String type, String path) { this(name, type, path, 0); }
        TreeNode(String name, String type, String path, long size) {
            this.name = name; this.type = type; this.path = path; this.size = size;
        }

        void add(String[] parts, int idx, String type, String fullPath, long size) {
            if (idx >= parts.length) return;
            String key = parts[idx];
            TreeNode child = children.computeIfAbsent(key,
                    k -> new TreeNode(k, idx == parts.length - 1 ? type : "directory",
                            idx == parts.length - 1 ? fullPath : ""));
            if (idx == parts.length - 1) {
                child.type = type;
                child.path = fullPath;
                child.size = size;
            } else {
                child.add(parts, idx + 1, type, fullPath, size);
            }
        }

        void sort() {
            List<Map.Entry<String, TreeNode>> entries = new ArrayList<>(children.entrySet());
            entries.sort((a, b) -> {
                boolean aDir = "directory".equals(a.getValue().type);
                boolean bDir = "directory".equals(b.getValue().type);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getKey().compareToIgnoreCase(b.getKey());
            });
            Map<String, TreeNode> sorted = new LinkedHashMap<>();
            for (var e : entries) sorted.put(e.getKey(), e.getValue());
            children = sorted;
            for (TreeNode child : children.values()) child.sort();
        }

        List<Map<String, Object>> childrenAsList() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (TreeNode child : children.values()) {
                list.add(child.toMap());
            }
            return list;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("path", path);
            if (size > 0) map.put("size", size);
            if (!children.isEmpty()) {
                map.put("children", childrenAsList());
            }
            return map;
        }
    }
}
