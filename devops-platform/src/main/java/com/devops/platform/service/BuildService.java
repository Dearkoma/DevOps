package com.devops.platform.service;

import com.devops.platform.entity.*;
import com.devops.platform.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构建执行服务
 * 支持 WebSocket 实时日志、Webhook 触发、参数化构建、制品收集、通知推送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildService {

    private final BuildRepository buildRepository;
    private final ProjectRepository projectRepository;
    private final PipelineRepository pipelineRepository;
    private final ArtifactRepository artifactRepository;
    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> logCache = new ConcurrentHashMap<>();

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    @Value("${devops.pipeline.log-dir:./data/logs}")
    private String logDir;

    @Value("${devops.pipeline.simulation-mode:true}")
    private boolean simulationMode;

    @Value("${devops.pipeline.shell.command:cmd /c}")
    private String shellCommand;

    @Value("${devops.pipeline.docker.registry:}")
    private String dockerRegistry;

    @Value("${devops.pipeline.k8s.namespace:default}")
    private String k8sNamespace;

    @Value("${devops.pipeline.timeout-ms:600000}")
    private long timeoutMs;

    /** WebSocket 日志推送 */
    private void appendLog(Long buildId, String logText) {
        if (buildId != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("buildId", buildId);
                payload.put("log", logText);
                payload.put("timestamp", System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/builds/" + buildId + "/log", payload);
            } catch (Exception e) {
                // WebSocket 推送失败不阻塞构建
            }
        }
    }

    /** 触发构建 */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy) {
        return triggerBuild(projectId, pipelineId, triggeredBy, null, null);
    }

    /** 参数化触发构建 */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy,
                              String buildParams, String branch) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new RuntimeException("流水线不存在: " + pipelineId));

        long count = buildRepository.count();
        String buildNumber = "#" + (count + 1);

        Build build = new Build();
        build.setProjectId(projectId);
        build.setPipelineId(pipelineId);
        build.setBuildNumber(buildNumber);
        build.setStatus("PENDING");
        build.setTriggeredBy(triggeredBy != null ? triggeredBy : "admin");
        build.setStartTime(LocalDateTime.now());
        build.setBuildParams(buildParams);
        build.setBranch(branch != null ? branch : project.getGitBranch());
        buildRepository.save(build);

        executeBuildAsync(build.getId(), project, pipeline);

        return build;
    }

    /** Webhook 触发构建 */
    public Build triggerBuildByWebhook(Long projectId, String branch, String committer, String commit) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        // 查找匹配分支的活跃流水线
        List<Pipeline> pipelines = pipelineRepository.findByProjectIdAndStatus(projectId, "ACTIVE");
        Pipeline matched = null;

        for (Pipeline p : pipelines) {
            if (p.getBranchPattern() == null || p.getBranchPattern().isBlank()) {
                matched = p; // 无分支限制，匹配第一个
                break;
            }
            // 检查分支是否匹配
            if (branch != null && matchesBranch(branch, p.getBranchPattern())) {
                matched = p;
                break;
            }
        }

        if (matched == null && !pipelines.isEmpty()) {
            matched = pipelines.get(0); // fallback 到第一个活跃流水线
        }
        if (matched == null) {
            throw new RuntimeException("项目没有活跃的流水线");
        }

        String triggerType = "PUSH";
        Build build = triggerBuild(projectId, matched.getId(), committer, null, branch);
        build.setTriggerType(triggerType);
        build.setGitCommit(commit);
        buildRepository.save(build);

        return build;
    }

    private boolean matchesBranch(String branch, String pattern) {
        for (String p : pattern.split(",")) {
            p = p.trim();
            if (p.equals(branch)) return true;
            if (p.endsWith("/*")) {
                String prefix = p.substring(0, p.length() - 1);
                if (branch.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    @Async
    public void executeBuildAsync(Long buildId, Project project, Pipeline pipeline) {
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build == null) return;

        StringBuilder logBuf = new StringBuilder();
        logCache.put(buildId, logBuf);

        try {
            build.setStatus("RUNNING");
            buildRepository.save(build);

            String mode = simulationMode ? "【模拟模式】" : "【真实模式】";
            String triggerInfo = build.getTriggerType() != null && !"MANUAL".equals(build.getTriggerType())
                    ? " [" + build.getTriggerType() + "]" : "";
            logBuf.append("[INFO] ").append(LocalDateTime.now())
                    .append(" 开始构建 ").append(build.getBuildNumber())
                    .append(" ").append(mode).append(triggerInfo).append("\n");
            logBuf.append("[INFO] 项目: ").append(project.getName()).append("\n");
            logBuf.append("[INFO] 流水线: ").append(pipeline.getName()).append("\n");
            logBuf.append("[INFO] 分支: ").append(build.getBranch() != null ? build.getBranch() : "N/A").append("\n");
            if (build.getBuildParams() != null) {
                logBuf.append("[INFO] 构建参数: ").append(build.getBuildParams()).append("\n");
            }
            logBuf.append("[INFO] 工作目录: ").append(getProjectWorkspace(project)).append("\n\n");
            appendLog(buildId, logBuf.toString());

            if (!simulationMode) {
                boolean prepared = prepareWorkspace(project, logBuf, buildId, build.getBranch());
                if (!prepared) {
                    throw new RuntimeException("代码准备失败");
                }
            }

            List<StageDef> stages = parsePipelineDefinition(pipeline.getDefinition());
            build.setTotalSteps(stages.stream().mapToInt(s -> s.steps.size()).sum());
            buildRepository.save(build);

            long startTime = System.currentTimeMillis();
            int completedSteps = 0;

            for (StageDef stage : stages) {
                logBuf.append("========== 阶段: ").append(stage.name).append(" ==========\n");
                appendLog(buildId, logBuf.toString());

                for (StepDef step : stage.steps) {
                    logBuf.append("[STEP] 执行: ").append(step.name).append(" (").append(step.type).append(")\n");
                    appendLog(buildId, logBuf.toString());

                    boolean success = executeStep(step, project, logBuf, buildId);
                    if (!success) {
                        throw new RuntimeException("步骤执行失败: " + step.name);
                    }

                    completedSteps++;
                    build.setCompletedSteps(completedSteps);
                    buildRepository.save(build);
                    logBuf.append("[SUCCESS] 步骤完成: ").append(step.name).append("\n\n");
                    appendLog(buildId, logBuf.toString());
                }
            }

            long endTime = System.currentTimeMillis();
            build.setStatus("SUCCESS");
            build.setEndTime(LocalDateTime.now());
            build.setDurationMs(endTime - startTime);
            build.setCompletedSteps(completedSteps);
            build.setResultMessage("构建成功");
            logBuf.append("\n[INFO] 构建完成！耗时: ").append(endTime - startTime).append("ms\n");
            appendLog(buildId, logBuf.toString());

            project.setStatus("DEPLOYED");
            projectRepository.save(project);

            // 收集制品
            collectArtifacts(build, project, logBuf);

            // 发送成功通知
            notificationService.buildSuccess(build.getBuildNumber(), build.getId(), project.getName());

        } catch (Exception e) {
            logBuf.append("\n[ERROR] 构建失败: ").append(e.getMessage()).append("\n");
            appendLog(buildId, logBuf.toString());
            build.setStatus("FAILED");
            build.setEndTime(LocalDateTime.now());
            build.setResultMessage("构建失败: " + e.getMessage());
            project.setStatus("FAILED");
            projectRepository.save(project);

            // 发送失败通知
            notificationService.buildFailed(build.getBuildNumber(), build.getId(),
                    project.getName(), e.getMessage());
        } finally {
            buildRepository.save(build);
            appendLog(buildId, logBuf.toString());
            Map<String, Object> endPayload = new HashMap<>();
            endPayload.put("buildId", buildId);
            endPayload.put("status", build.getStatus());
            endPayload.put("log", logBuf.toString());
            endPayload.put("timestamp", System.currentTimeMillis());
            endPayload.put("finished", true);
            messagingTemplate.convertAndSend("/topic/builds/" + buildId + "/log", endPayload);

            saveLogFile(buildId, logBuf.toString());
            logCache.remove(buildId);
            runningProcesses.remove(buildId);
        }
    }

    /** 收集构建制品 */
    private void collectArtifacts(Build build, Project project, StringBuilder logBuf) {
        if (simulationMode) {
            logBuf.append("[INFO] 模拟模式，跳过制品收集\n");
            return;
        }
        try {
            String workspace = getProjectWorkspace(project);
            Path targetDir = Paths.get(workspace, "target");
            if (Files.exists(targetDir)) {
                Files.list(targetDir)
                        .filter(p -> p.toString().endsWith(".jar") || p.toString().endsWith(".war"))
                        .forEach(jar -> {
                            try {
                                String version = "latest";
                                if (build.getBuildParams() != null) {
                                    try {
                                        Map<String, Object> params = objectMapper.readValue(
                                                build.getBuildParams(), new TypeReference<>() {});
                                        if (params.containsKey("version")) {
                                            version = params.get("version").toString();
                                        }
                                    } catch (Exception ignored) {}
                                }

                                Artifact artifact = new Artifact();
                                artifact.setBuildId(build.getId());
                                artifact.setFileName(jar.getFileName().toString());
                                artifact.setFilePath(jar.toAbsolutePath().toString());
                                artifact.setFileType(jar.toString().endsWith(".war") ? "WAR" : "JAR");
                                artifact.setFileSize(Files.size(jar));
                                artifact.setVersion(version);
                                artifact.setDownloadUrl("/api/artifacts/" + build.getId());
                                artifactRepository.save(artifact);
                                logBuf.append("[ARTIFACT] 收集制品: ")
                                        .append(jar.getFileName()).append(" (").append(Files.size(jar) / 1024)
                                        .append(" KB)\n");
                            } catch (IOException e) {
                                logBuf.append("[WARN] 制品收集失败: ").append(e.getMessage()).append("\n");
                            }
                        });
            }
        } catch (IOException e) {
            logBuf.append("[WARN] 制品扫描失败: ").append(e.getMessage()).append("\n");
        }
    }

    private boolean executeStep(StepDef step, Project project, StringBuilder logBuf, Long buildId) {
        try {
            if (simulationMode) return executeStepSimulated(step, project, logBuf);
            switch (step.type.toUpperCase()) {
                case "SHELL": return executeShellReal(step, project, logBuf, buildId);
                case "DOCKER_BUILD": return executeDockerBuildReal(step, project, logBuf, buildId);
                case "DOCKER_PUSH": return executeDockerPushReal(step, project, logBuf, buildId);
                case "K8S_DEPLOY": return executeK8sDeployReal(step, project, logBuf, buildId);
                case "TEST": return executeTestReal(step, project, logBuf, buildId);
                case "NPM_INSTALL": return executeNpmInstallReal(step, project, logBuf, buildId);
                case "NPM_BUILD": return executeNpmBuildReal(step, project, logBuf, buildId);
                case "MVN_PACKAGE": return executeMvnPackageReal(step, project, logBuf, buildId);
                default:
                    logBuf.append("[WARN] 未知步骤类型: ").append(step.type).append("\n");
                    return executeShellReal(step, project, logBuf, buildId);
            }
        } catch (Exception e) {
            logBuf.append("[ERROR] 步骤异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    // ===================== 模拟执行 =====================
    private boolean executeStepSimulated(StepDef step, Project project, StringBuilder logBuf) throws Exception {
        switch (step.type.toUpperCase()) {
            case "SHELL":
                logBuf.append("$ ").append(step.command).append("\n");
                simulateDelay(800, 2000);
                logBuf.append("> 模拟执行完成\n");
                return true;
            case "DOCKER_BUILD":
                logBuf.append("[DOCKER] [模拟] 构建镜像: ").append(project.getCode()).append(":latest\n");
                simulateDelay(1000, 3000);
                logBuf.append("[DOCKER] [模拟] 镜像构建完成\n");
                return true;
            case "DOCKER_PUSH":
                logBuf.append("[DOCKER] [模拟] 推送镜像...\n");
                simulateDelay(500, 1500);
                logBuf.append("[DOCKER] [模拟] 镜像推送完成\n");
                return true;
            case "K8S_DEPLOY":
                logBuf.append("[K8S] [模拟] 部署到集群 (ns: ").append(k8sNamespace).append(")...\n");
                simulateDelay(1000, 2000);
                logBuf.append("[K8S] [模拟] 部署完成\n");
                return true;
            case "TEST":
                logBuf.append("[TEST] [模拟] 执行测试...\n");
                simulateDelay(500, 1500);
                logBuf.append("[TEST] [模拟] 测试完成: 42 passed, 0 failed\n");
                return true;
            default:
                logBuf.append("$ ").append(step.command).append("\n");
                simulateDelay(500, 1500);
                logBuf.append("> 模拟完成\n");
                return true;
        }
    }

    // ===================== 真实执行 =====================
    private boolean executeShellReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String command = step.command;
        if (command == null || command.isBlank()) return true;
        logBuf.append("$ ").append(command).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(getProjectWorkspace(project), command, logBuf, buildId);
        if (exitCode != 0) {
            logBuf.append("[ERROR] 退出码: ").append(exitCode).append("\n");
            return false;
        }
        return true;
    }

    private boolean executeDockerBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String imageName = dockerRegistry.isBlank() ? project.getCode() + ":latest"
                : dockerRegistry + "/" + project.getCode() + ":latest";
        String dockerfile = step.command.isBlank() ? "Dockerfile" : step.command;
        String command = "docker build -t " + imageName + " -f " + dockerfile + " .";
        logBuf.append("[DOCKER] 构建镜像: ").append(imageName).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(getProjectWorkspace(project), command, logBuf, buildId);
        if (exitCode != 0) {
            logBuf.append("[ERROR] Docker 构建失败\n");
            return false;
        }
        logBuf.append("[DOCKER] 镜像构建完成\n");
        return true;
    }

    private boolean executeDockerPushReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String imageName = dockerRegistry.isBlank() ? project.getCode() + ":latest"
                : dockerRegistry + "/" + project.getCode() + ":latest";
        if (!dockerRegistry.isBlank()) {
            String tagCmd = "docker tag " + project.getCode() + ":latest " + imageName;
            logBuf.append("$ ").append(tagCmd).append("\n");
            if (runCommand(getProjectWorkspace(project), tagCmd, logBuf, buildId) != 0) {
                logBuf.append("[ERROR] Docker tag 失败\n");
                return false;
            }
        }
        String command = "docker push " + imageName;
        logBuf.append("[DOCKER] 推送: ").append(imageName).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(getProjectWorkspace(project), command, logBuf, buildId);
        if (exitCode != 0) { logBuf.append("[ERROR] 推送失败\n"); return false; }
        logBuf.append("[DOCKER] 推送完成\n");
        return true;
    }

    private boolean executeK8sDeployReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String deployFile = step.command.isBlank() ? "deployment.yaml" : step.command;
        String command = "kubectl apply -f " + deployFile + " -n " + k8sNamespace;
        logBuf.append("[K8S] 部署 namespace=").append(k8sNamespace).append("\n");
        appendLog(buildId, logBuf.toString());
        if (runCommand(getProjectWorkspace(project), command, logBuf, buildId) != 0) {
            logBuf.append("[ERROR] 部署失败\n"); return false;
        }
        String rolloutCmd = "kubectl rollout status deployment/" + project.getCode() + " -n " + k8sNamespace;
        logBuf.append("[K8S] $ ").append(rolloutCmd).append("\n");
        int rc = runCommand(getProjectWorkspace(project), rolloutCmd, logBuf, buildId);
        if (rc != 0) logBuf.append("[WARN] rollout 检查失败\n");
        logBuf.append("[K8S] 部署完成\n");
        return true;
    }

    private boolean executeTestReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String cmd = project.getLanguage() != null && project.getLanguage().equalsIgnoreCase("Node.js")
                ? "npm test" : "mvn test -B";
        logBuf.append("[TEST] ").append(cmd).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(getProjectWorkspace(project), cmd, logBuf, buildId) == 0;
    }

    private boolean executeNpmInstallReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        logBuf.append("$ npm install\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(getProjectWorkspace(project), "npm install", logBuf, buildId) == 0;
    }

    private boolean executeNpmBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        logBuf.append("$ npm run build\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(getProjectWorkspace(project), "npm run build", logBuf, buildId) == 0;
    }

    private boolean executeMvnPackageReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String cmd = "mvn clean package -DskipTests -B";
        logBuf.append("$ ").append(cmd).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(getProjectWorkspace(project), cmd, logBuf, buildId) == 0;
    }

    // ===================== Git 操作 =====================
    private boolean prepareWorkspace(Project project, StringBuilder logBuf, Long buildId, String branch) throws Exception {
        String workspace = getProjectWorkspace(project);
        Path workspacePath = Paths.get(workspace);
        String gitUrl = project.getGitUrl();
        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch
                : (project.getGitBranch() != null && !project.getGitBranch().isBlank())
                ? project.getGitBranch() : "main";

        logBuf.append("========== 代码准备 ==========\n");

        if (gitUrl == null || gitUrl.isBlank()) {
            logBuf.append("[WARN] 未配置 Git 地址，跳过\n");
            if (!Files.exists(workspacePath)) Files.createDirectories(workspacePath);
            return true;
        }

        if (Files.exists(workspacePath) && Files.exists(workspacePath.resolve(".git"))) {
            logBuf.append("[GIT] 仓库已存在，拉取最新代码...\n");
            String pullCmd = "git fetch origin && git checkout " + effectiveBranch
                    + " && git pull origin " + effectiveBranch;
            logBuf.append("$ ").append(pullCmd).append("\n");
            appendLog(buildId, logBuf.toString());
            int exitCode = runCommand(workspace, pullCmd, logBuf, buildId);
            if (exitCode != 0) { logBuf.append("[ERROR] Git pull 失败\n"); return false; }
            logBuf.append("[GIT] 代码更新完成\n\n");
            return true;
        }

        if (Files.exists(workspacePath)) {
            logBuf.append("[WARN] 工作目录存在但非 Git 仓库，清理后重新克隆\n");
            deleteRecursively(workspacePath);
        }

        logBuf.append("[GIT] 克隆: ").append(gitUrl).append(" (分支: ").append(effectiveBranch).append(")\n");
        Files.createDirectories(workspacePath.getParent());
        String cloneCmd = "git clone -b " + effectiveBranch + " " + gitUrl + " " + project.getCode();
        logBuf.append("$ ").append(cloneCmd).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(workspacePath.getParent().toString(), cloneCmd, logBuf, buildId);
        if (exitCode != 0) {
            logBuf.append("[ERROR] Git clone 失败\n");
            logBuf.append("[HINT] 检查 Git 地址、分支、访问权限\n");
            return false;
        }
        logBuf.append("[GIT] 克隆完成\n\n");
        return true;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    // ===================== 命令执行 =====================
    private int runCommand(String workDir, String command, StringBuilder logBuf, Long buildId) throws Exception {
        Path workPath = Paths.get(workDir);
        if (!Files.exists(workPath)) Files.createDirectories(workPath);

        ProcessBuilder pb;
        if (shellCommand.contains("bash")) pb = new ProcessBuilder("bash", "-c", command);
        else if (shellCommand.contains("powershell")) pb = new ProcessBuilder("powershell", "-Command", command);
        else pb = new ProcessBuilder("cmd", "/c", command);

        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        if (buildId != null) runningProcesses.put(buildId, proc);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuf.append("  ").append(line).append("\n");
                if (buildId != null) appendLog(buildId, logBuf.toString());
            }
        }

        boolean finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            logBuf.append("[ERROR] 命令超时 (").append(timeoutMs / 1000).append("s)\n");
            return -1;
        }
        if (buildId != null) runningProcesses.remove(buildId);
        return proc.exitValue();
    }

    // ===================== 辅助方法 =====================
    private String getProjectWorkspace(Project project) {
        return workspaceDir + "/" + project.getCode();
    }

    private void simulateDelay(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(minMs + new Random().nextInt(maxMs - minMs));
    }

    private List<StageDef> parsePipelineDefinition(String definition) {
        try {
            if (definition == null || definition.trim().isEmpty()) return getDefaultStages();
            JsonNode root = objectMapper.readTree(definition);
            List<StageDef> stages = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode stageNode : root) {
                    StageDef stage = new StageDef();
                    stage.name = stageNode.get("name").asText();
                    stage.steps = new ArrayList<>();
                    JsonNode stepsNode = stageNode.get("steps");
                    if (stepsNode != null && stepsNode.isArray()) {
                        for (JsonNode stepNode : stepsNode) {
                            StepDef step = new StepDef();
                            step.name = stepNode.get("name").asText();
                            step.type = stepNode.get("type").asText();
                            step.command = stepNode.has("command") ? stepNode.get("command").asText() : "";
                            stage.steps.add(step);
                        }
                    }
                    stages.add(stage);
                }
            }
            return stages;
        } catch (Exception e) {
            log.warn("解析流水线定义失败", e);
            return getDefaultStages();
        }
    }

    private List<StageDef> getDefaultStages() {
        List<StageDef> stages = new ArrayList<>();
        stages.add(new StageDef("编译构建", List.of(new StepDef("Maven 编译", "MVN_PACKAGE", "mvn clean package -DskipTests -B"))));
        stages.add(new StageDef("单元测试", List.of(new StepDef("执行测试", "TEST", ""))));
        stages.add(new StageDef("Docker 构建", List.of(
                new StepDef("构建镜像", "DOCKER_BUILD", "Dockerfile"),
                new StepDef("推送镜像", "DOCKER_PUSH", "")
        )));
        stages.add(new StageDef("K8s 部署", List.of(new StepDef("部署到集群", "K8S_DEPLOY", "deployment.yaml"))));
        return stages;
    }

    private void saveLogFile(Long buildId, String logContent) {
        try {
            Path logPath = Paths.get(logDir);
            if (!Files.exists(logPath)) Files.createDirectories(logPath);
            Files.writeString(logPath.resolve("build-" + buildId + ".log"), logContent);
        } catch (IOException e) {
            log.error("保存日志文件失败", e);
        }
    }

    public String getBuildLog(Long buildId) {
        StringBuilder cached = logCache.get(buildId);
        if (cached != null) return cached.toString();
        try {
            Path logFile = Paths.get(logDir, "build-" + buildId + ".log");
            if (Files.exists(logFile)) return Files.readString(logFile);
        } catch (IOException e) { log.error("读取日志失败", e); }
        return "日志不存在";
    }

    public List<Build> getBuilds(Long projectId, String status) {
        if (projectId != null && status != null)
            return buildRepository.findByProjectIdAndStatus(projectId, status);
        else if (projectId != null)
            return buildRepository.findByProjectIdOrderByStartTimeDesc(projectId);
        else if (status != null)
            return buildRepository.findByStatus(status);
        else
            return buildRepository.findAll();
    }

    public Build getBuild(Long buildId) {
        return buildRepository.findById(buildId).orElse(null);
    }

    public void cancelBuild(Long buildId) {
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build != null && "RUNNING".equals(build.getStatus())) {
            build.setStatus("CANCELLED");
            build.setEndTime(LocalDateTime.now());
            buildRepository.save(build);
            Process proc = runningProcesses.remove(buildId);
            if (proc != null) {
                proc.destroy();
                log.info("已取消构建 #{}", buildId);
            }
        }
    }

    private static class StageDef {
        String name;
        List<StepDef> steps;
        StageDef() {}
        StageDef(String name, List<StepDef> steps) { this.name = name; this.steps = steps; }
    }

    private static class StepDef {
        String name;
        String type;
        String command;
        StepDef() {}
        StepDef(String name, String type, String command) {
            this.name = name; this.type = type; this.command = command;
        }
    }
}
