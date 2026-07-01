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

import jakarta.annotation.PostConstruct;

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
    private final ServiceInstanceRepository instanceRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final DatabaseProvisioningService dbProvisioningService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> logCache = new ConcurrentHashMap<>();

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    /* ---------- lazy-resolved absolute workspace root ---------- */
    private volatile Path workspaceRoot;

    /** 绝对路径的日志目录（避免 @Async 线程工作目录不一致导致的文件找不到） */
    private Path absoluteLogDir;

    @PostConstruct
    void initPaths() throws IOException {
        Path p = Paths.get(logDir).toAbsolutePath().normalize();
        Files.createDirectories(p);
        this.absoluteLogDir = p;
        log.info("日志目录: absoluteLogDir={}", absoluteLogDir);
    }

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

    @Value("${devops.pipeline.docker.skip-if-unavailable:true}")
    private boolean dockerSkipIfUnavailable;

    @Value("${devops.pipeline.docker.command:docker}")
    private String dockerCommand;

    @Value("${devops.pipeline.k8s.skip-if-unavailable:true}")
    private boolean k8sSkipIfUnavailable;

    /** 缓存 Docker/K8s 可用性检测结果，避免重复检测 */
    private Boolean dockerAvailable = null;
    private Boolean kubectlAvailable = null;

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

    /**
     * 检查数据库名是否与其他项目冲突（构建弹窗实时预览用）。
     * @return Map with keys: conflict (boolean), conflictProject, dbExists
     */
    public Map<String, Object> checkDbConflict(String dbName, Long currentProjectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String safeName = DatabaseProvisioningService.sanitizeDbName(dbName);
        result.put("dbName", safeName);
        result.put("conflict", false);
        result.put("dbExists", false);

        // db_type 为 NULL 时默认 MySQL，仅显式 H2 才跳过
        boolean useH2 = true;
        if (currentProjectId != null) {
            Project project = projectRepository.findById(currentProjectId).orElse(null);
            useH2 = project == null || "H2".equalsIgnoreCase(project.getDbType());
        }
        if (useH2) {
            result.put("skipCheck", true);
            return result;
        }

        boolean exists = dbProvisioningService.databaseExists(safeName);
        result.put("dbExists", exists);

        if (exists && currentProjectId != null) {
            List<Build> conflictBuilds = buildRepository.findByDbNameAndProjectIdNot(safeName, currentProjectId);
            if (!conflictBuilds.isEmpty()) {
                result.put("conflict", true);
                result.put("conflictProject", "Project#" + conflictBuilds.get(0).getProjectId());
                result.put("conflictProjectId", conflictBuilds.get(0).getProjectId());
            } else {
                result.put("reusedByCurrent", true); // 同项目重建
            }
        }
        return result;
    }

    /** 触发构建（简便重载） */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy) {
        return triggerBuild(projectId, pipelineId, triggeredBy, null, null, false, false, null);
    }

    /** 参数化触发构建（含独立跳过标志、数据库名称） */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy,
                              String buildParams, String branch, boolean skipDocker, boolean skipK8s,
                              String dbName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new RuntimeException("流水线不存在: " + pipelineId));

        // 构建编号原子性递增（synchronized 避免并发时编号重复）
        long count;
        synchronized (this) {
            count = buildRepository.count();
        }
        String buildNumber = "#" + (count + 1);

        // 数据库名：用户指定 > 默认规则 devops_<projectCode>_<buildNumber>
        // 每次构建生成独立数据库，确保新部署的实例是全新的（0条数据）
        String effectiveDbName = (dbName != null && !dbName.isBlank())
                ? DatabaseProvisioningService.sanitizeDbName(dbName)
                : DatabaseProvisioningService.defaultDbName(project.getCode(), buildNumber);

        // db_type 为 NULL 时默认 MySQL（大多数 Spring Boot 项目用 MySQL）
        // 仅显式声明 H2 时才跳过数据库供应
        boolean useH2 = "H2".equalsIgnoreCase(project.getDbType());
        if (!useH2) {
            // 每次构建都生成全新的独立数据库：freshIsolated=true 让已有同名库先删再建
            // 这样 O 项目每次部署都是 0 条数据，真正做到实例隔离
            DatabaseProvisioningService.CreateResult dbResult =
                    dbProvisioningService.createDatabase(effectiveDbName, projectId, buildRepository, true);
            if (!dbResult.success) {
                if (dbResult.conflictProjectId != null) {
                    throw new RuntimeException(String.format(
                            "数据库 '%s' 已被项目 %s 占用，请更换数据库名后重试",
                            dbResult.dbName, dbResult.conflictProjectName));
                }
                throw new RuntimeException("数据库 '" + effectiveDbName + "' 创建失败");
            }
        }

        Build build = new Build();
        build.setProjectId(projectId);
        build.setPipelineId(pipelineId);
        build.setBuildNumber(buildNumber);
        build.setStatus("PENDING");
        build.setTriggeredBy(triggeredBy != null ? triggeredBy : "admin");
        build.setStartTime(LocalDateTime.now());
        build.setBuildParams(buildParams);
        build.setBranch(branch != null ? branch : project.getGitBranch());
        build.setSkipDocker(skipDocker);
        build.setSkipK8s(skipK8s);
        build.setDbName(effectiveDbName);
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
        Build build = triggerBuild(projectId, matched.getId(), committer, null, branch, false, false, null);
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
            boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
            boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();
            String deployDesc = "编译+测试";
            if (!skipDocker) deployDesc += " + Docker";
            if (!skipK8s) deployDesc += " + K8s";
            logBuf.append("[INFO] 部署选项: ").append(deployDesc).append("\n");
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
                // 根据部署目标跳过不相关的阶段
                if (shouldSkipStage(stage, build)) {
                    logBuf.append("========== 阶段: ").append(stage.name)
                          .append(" 【已跳过】 ==========\n");
                    appendLog(buildId, logBuf.toString());
                    continue;
                }

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

            // 记录服务实例（如果部署了）
            recordServiceInstance(build, project, logBuf);

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

    /** 根据跳过标志记录服务实例 */
    private void recordServiceInstance(Build build, Project project, StringBuilder logBuf) {
        boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
        boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();
        if (skipDocker && skipK8s) return; // 全部跳过，不记录实例

        logBuf.append("\n========== 记录服务实例 ==========\n");

        String imageNameStr = dockerRegistry.isBlank() ? project.getCode() : dockerRegistry + "/" + project.getCode();
        String instDbName = build.getDbName();

        try {
            // Docker 实例（只要构建了 Docker 镜像就记录，无论是否跳过 K8s）
            if (!skipDocker) {
                ServiceInstance dockerInst = new ServiceInstance();
                dockerInst.setProjectId(project.getId());
                dockerInst.setProjectName(project.getName());
                dockerInst.setDeployType("DOCKER");
                dockerInst.setInstanceName(project.getCode() + "-docker-" + build.getBuildNumber().replace("#", ""));
                dockerInst.setImageName(imageNameStr);
                dockerInst.setImageTag("latest");
                dockerInst.setHost("localhost");
                dockerInst.setPort(8080);
                dockerInst.setContainerId("");
                dockerInst.setStatus("RUNNING");
                dockerInst.setHealthStatus("HEALTHY");
                dockerInst.setLastHeartbeat(LocalDateTime.now());
                dockerInst.setDbName(instDbName);
                dockerInst.setAdminUsername("admin");
                dockerInst.setAdminPassword("admin123");
                instanceRepository.save(dockerInst);
                logBuf.append("[INSTANCE] Docker 实例: ").append(dockerInst.getInstanceName()).append("\n");
                logBuf.append("[INSTANCE] 镜像: ").append(dockerInst.getImageName()).append(":").append(dockerInst.getImageTag()).append("\n");
                logBuf.append("[INSTANCE] 数据库: ").append(instDbName).append("\n");
            }

            // K8s 实例
            if (!skipK8s) {
                ServiceInstance k8sInst = new ServiceInstance();
                k8sInst.setProjectId(project.getId());
                k8sInst.setProjectName(project.getName());
                k8sInst.setDeployType("K8S");
                k8sInst.setInstanceName(project.getCode() + "-k8s-" + build.getBuildNumber().replace("#", ""));
                k8sInst.setK8sNamespace(k8sNamespace);
                k8sInst.setK8sPodName(project.getCode());
                k8sInst.setImageName(imageNameStr);
                k8sInst.setImageTag("latest");
                k8sInst.setStatus("RUNNING");
                k8sInst.setHealthStatus("HEALTHY");
                k8sInst.setLastHeartbeat(LocalDateTime.now());
                k8sInst.setDbName(instDbName);
                k8sInst.setAdminUsername("admin");
                k8sInst.setAdminPassword("admin123");
                instanceRepository.save(k8sInst);
                logBuf.append("[INSTANCE] K8s 实例: ").append(k8sInst.getInstanceName()).append("\n");
                logBuf.append("[INSTANCE] 命名空间: ").append(k8sNamespace).append("\n");
                logBuf.append("[INSTANCE] Pod: ").append(k8sInst.getK8sPodName()).append("\n");
                logBuf.append("[INSTANCE] 数据库: ").append(instDbName).append("\n");
                logBuf.append("[INSTANCE] 管理员: admin / ").append(k8sInst.getAdminPassword()).append("\n");
            }
        } catch (Exception e) {
            logBuf.append("[WARN] 记录实例失败: ").append(e.getMessage()).append("\n");
        }
    }

    /** 检测 Docker 是否可用（结果缓存） */
    private boolean isDockerAvailable() {
        if (dockerAvailable != null) return dockerAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCommand, "info");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                dockerAvailable = proc.exitValue() == 0;
            } else {
                proc.destroyForcibly();
                dockerAvailable = false;
            }
        } catch (Exception e) {
            dockerAvailable = false;
        }
        return dockerAvailable;
    }

    /** 检测 kubectl 是否可用（结果缓存） */
    private boolean isKubectlAvailable() {
        if (kubectlAvailable != null) return kubectlAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "version", "--client");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            kubectlAvailable = finished && proc.exitValue() == 0;
        } catch (Exception e) {
            kubectlAvailable = false;
        }
        return kubectlAvailable;
    }

    /** 重置工具可用性缓存（重新连接后调用） */
    public void resetToolAvailability() {
        dockerAvailable = null;
        kubectlAvailable = null;
    }

    /** 根据跳过标志判断是否跳过某个阶段 */
    private boolean shouldSkipStage(StageDef stage, Build build) {
        boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
        boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();

        boolean hasDockerStep = stage.steps.stream().anyMatch(s ->
                "DOCKER_BUILD".equalsIgnoreCase(s.type) || "DOCKER_PUSH".equalsIgnoreCase(s.type));
        boolean hasK8sStep = stage.steps.stream().anyMatch(s ->
                "K8S_DEPLOY".equalsIgnoreCase(s.type));

        if (skipDocker && hasDockerStep) return true;
        if (skipK8s && hasK8sStep) return true;
        return false;
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
        String workDir = findProjectRoot(project).toString();
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        logBuf.append("$ ").append(command).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(workDir, command, logBuf, buildId);
        if (exitCode != 0) {
            logBuf.append("[ERROR] 退出码: ").append(exitCode).append("\n");
            return false;
        }
        return true;
    }

    private boolean executeDockerBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isDockerAvailable()) {
            if (dockerSkipIfUnavailable) {
                logBuf.append("[DOCKER] Docker 不可用，跳过镜像构建\n");
                logBuf.append("[HINT] 启动 Docker Desktop 或 Rancher Desktop 后重试\n");
                return true;
            }
            logBuf.append("[ERROR] Docker 不可用，无法构建镜像\n");
            return false;
        }

        // Docker build context 应使用 workspace 根目录，以包含前端等子项目
        Path projectRoot = findProjectRoot(project);
        Path workspacePath = resolveWorkspaceRoot().resolve(project.getCode());
        Path buildContext = workspacePath;

        // 确定 Dockerfile 路径：优先级
        //   1) 项目配置的 dockerfilePath 字段（可指定子目录）
        //   2) step.command（流水线里手填的）
        //   3) 默认 "Dockerfile"
        String dockerfile = (project.getDockerfilePath() != null && !project.getDockerfilePath().isBlank())
                ? project.getDockerfilePath()
                : (step.command.isBlank() ? "Dockerfile" : step.command);
        Path dockerfilePath = projectRoot.resolve(dockerfile);
        if (!Files.exists(dockerfilePath)) {
            // 检查 workspace 根目录是否有 Dockerfile
            Path workspaceDockerfile = workspacePath.resolve(dockerfile);
            if (Files.exists(workspaceDockerfile)) {
                dockerfilePath = workspaceDockerfile;
            } else if (project.getDockerfileContent() != null && !project.getDockerfileContent().isBlank()) {
                // D 平台托管 Dockerfile：项目配置的 dockerfileContent 字段非空 → 写入项目根目录
                try {
                    Files.createDirectories(dockerfilePath.getParent() != null ? dockerfilePath.getParent() : projectRoot);
                    Files.writeString(dockerfilePath, project.getDockerfileContent());
                    logBuf.append("[DOCKER] 使用项目配置的 Dockerfile 写入: ").append(dockerfilePath).append("\n");
                } catch (IOException e) {
                    logBuf.append("[ERROR] 写入托管 Dockerfile 失败: ").append(e.getMessage()).append("\n");
                    return false;
                }
            }
        }

        // 计算 Dockerfile 相对于 build context 的路径
        String dockerfileRelPath = buildContext.relativize(dockerfilePath).toString();

        String imageName = dockerRegistry.isBlank() ? project.getCode() + ":latest"
                : dockerRegistry + "/" + project.getCode() + ":latest";
        String command = dockerCommand + " build -t " + imageName + " -f " + dockerfileRelPath + " .";
        logBuf.append("[DOCKER] 构建镜像: ").append(imageName).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(buildContext).append("\n");
        logBuf.append("[INFO] Dockerfile: ").append(dockerfilePath).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(buildContext.toString(), command, logBuf, buildId);
        if (exitCode != 0) { logBuf.append("[ERROR] Docker 构建失败\n"); return false; }
        logBuf.append("[DOCKER] 镜像构建完成\n");

        // 检查是否有前端 Dockerfile，如果有则构建前端 nginx 镜像（前后端分离部署）
        Path frontendDockerfile = projectRoot.resolve("Dockerfile.frontend");
        if (!Files.exists(frontendDockerfile)) {
            frontendDockerfile = workspacePath.resolve("Dockerfile.frontend");
        }
        if (Files.exists(frontendDockerfile)) {
            String frontendDockerfileRel = buildContext.relativize(frontendDockerfile).toString();
            String frontendImageName = dockerRegistry.isBlank() ? project.getCode() + "-frontend:latest"
                    : dockerRegistry + "/" + project.getCode() + "-frontend:latest";
            String frontendCmd = dockerCommand + " build -t " + frontendImageName
                    + " -f " + frontendDockerfileRel + " .";
            logBuf.append("[DOCKER] 构建前端镜像: ").append(frontendImageName).append("\n");
            appendLog(buildId, logBuf.toString());
            int frontendExit = runCommand(buildContext.toString(), frontendCmd, logBuf, buildId);
            if (frontendExit != 0) {
                logBuf.append("[ERROR] 前端镜像构建失败\n");
                return false;
            }
            logBuf.append("[DOCKER] 前端镜像构建完成\n");
        }

        return true;
    }

    private boolean executeDockerPushReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isDockerAvailable()) {
            logBuf.append("[DOCKER] Docker 不可用，跳过镜像推送\n");
            return true;
        }
        // No registry configured → skip push (image is available locally, no remote registry to push to)
        if (dockerRegistry.isBlank()) {
            logBuf.append("[DOCKER] 未配置镜像仓库地址，跳过推送（本地镜像可直接使用）\n");
            logBuf.append("[HINT] 如需推送远程仓库，请在 application.yml 配置 devops.pipeline.docker.registry\n");
            return true;
        }
        Path workDir = findProjectRoot(project);
        String imageName = dockerRegistry + "/" + project.getCode() + ":latest";
        String tagCmd = dockerCommand + " tag " + project.getCode() + ":latest " + imageName;
        logBuf.append("$ ").append(tagCmd).append("\n");
        if (runCommand(workDir.toString(), tagCmd, logBuf, buildId) != 0) {
            logBuf.append("[ERROR] Docker tag 失败\n"); return false;
        }
        String command = dockerCommand + " push " + imageName;
        logBuf.append("[DOCKER] 推送: ").append(imageName).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(workDir.toString(), command, logBuf, buildId);
        if (exitCode != 0) { logBuf.append("[ERROR] 推送失败\n"); return false; }
        logBuf.append("[DOCKER] 推送完成\n");
        return true;
    }

    private boolean executeK8sDeployReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isKubectlAvailable()) {
            if (k8sSkipIfUnavailable) {
                logBuf.append("[K8S] kubectl 不可用，跳过部署\n");
                logBuf.append("[HINT] 确保 kubectl 已安装且集群可访问\n");
                return true;
            }
            logBuf.append("[ERROR] kubectl 不可用，无法部署\n");
            return false;
        }
        Path workDir = findProjectRoot(project);
        String deployFile = step.command.isBlank() ? "deployment.yaml" : step.command;
        Path deployPath = workDir.resolve(deployFile);

        // 如果 deployment.yaml 不存在，自动生成默认模板
        if (!Files.exists(deployPath)) {
            logBuf.append("[K8S] deployment.yaml 不存在，自动生成默认模板\n");
            Build build = buildRepository.findById(buildId).orElse(null);
            String dbName = build != null ? build.getDbName() : null;
            String generated = generateDefaultK8sDeployment(project, dbName);
            try {
                Files.writeString(deployPath, generated);
                logBuf.append("[K8S] 已生成: ").append(deployPath.toString()).append("\n");
            } catch (IOException e) {
                logBuf.append("[ERROR] 无法写入 deployment.yaml: ").append(e.getMessage()).append("\n");
                return false;
            }
        } else {
            // deployment.yaml 已存在：在 apply 前替换/注入 SPRING_DATASOURCE_URL 强制指向本次构建的独立库
            // 避免 O 项目自带 yaml 硬编码指向 D 平台主库
            logBuf.append("[K8S] 使用已有: ").append(deployPath.toString()).append("\n");
            logBuf.append("[K8S] 注入本次构建的独立数据库配置...\n");
            // ==================== 单节点环境：固定 replicas=1 ====================
            // 单节点 K8s 集群 + hostNetwork 模式下，多个 Pod 会争抢宿主机的同一端口
            // 例如：2 个 Pod 都想占用宿主机的 8080 端口 → 第二个 Pod 永远 FailedScheduling
            // 解决：固定 1 个副本，避免端口冲突
            int randomReplicas = 1;
            logBuf.append("[K8S] 单节点环境固定 Pod 副本数: ").append(randomReplicas).append("（多副本会因 hostNetwork 端口冲突导致调度失败）\n");
            try {
                String yamlContent = Files.readString(deployPath);
                // 替换已有 replicas（list 风格或顶层 spec 形式）
                yamlContent = yamlContent.replaceAll(
                        "(?m)^(\\s*)replicas:\\s*\\d+\\s*$",
                        "$1replicas: " + randomReplicas);
                // 修正 host.docker.internal（K8s 容器不解析这个地址）
                // K8s 容器内需要用 hostNetwork 或 Node IP 访问宿主机
                // 单节点开发环境: 使用 127.0.0.1（需 hostNetwork: true）或 host.docker.internal
                // 更稳妥: 让用户配置 K8S_DB_HOST，否则默认用 127.0.0.1
                String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "127.0.0.1");
                if (yamlContent.contains("host.docker.internal")) {
                    yamlContent = yamlContent.replaceAll("host\\.docker\\.internal", k8sDbHost);
                    logBuf.append("[K8S] 已将 SPRING_DATASOURCE_URL 的 host.docker.internal 替换为 ").append(k8sDbHost).append("\n");
                }
                // 若没有 hostNetwork 字段，加上以便能访问到宿主机
                if (!yamlContent.contains("hostNetwork:") && !yamlContent.contains("hostNetwork:")) {
                    yamlContent = yamlContent.replaceFirst(
                            "(\\s+containers:\\s*\\n)",
                            "$1      hostNetwork: true\n");
                    logBuf.append("[K8S] 已自动添加 hostNetwork: true (K8s 容器访问宿主机数据库)\n");
                }
                Files.writeString(deployPath, yamlContent);
                logBuf.append("[K8S] 已更新 deployment.yaml replicas=").append(randomReplicas).append("\n");
            } catch (IOException ex) {
                logBuf.append("[WARN] 更新 replicas 失败: ").append(ex.getMessage()).append("\n");
            }
            Build build = buildRepository.findById(buildId).orElse(null);
            String dbName = build != null ? build.getDbName() : null;
            if (dbName != null && !dbName.isBlank() && !"H2".equalsIgnoreCase(project.getDbType())) {
                try {
                    String original = Files.readString(deployPath);
                    String dbHost = project.getDbHost() != null ? project.getDbHost() : "host.docker.internal";
                    int dbPort = project.getDbPort() != null ? project.getDbPort() : 3306;
                    String dbUser = project.getDbUsername() != null ? project.getDbUsername() : "root";
                    String dbPass = project.getDbPassword() != null ? project.getDbPassword() : "";
                    String jdbcUrl = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName
                            + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                            + "&createDatabaseIfNotExist=true";

                    String modified = original;

                    // 注入或替换 env 段：兼容三种情况
                    // A) 没有 env 段 → 在 container 块内追加 env
                    // B) 有 env 段但没 SPRING_DATASOURCE_URL → 在 env 末尾追加
                    // C) 有 SPRING_DATASOURCE_URL → 替换它的值

                    if (modified.contains("SPRING_DATASOURCE_URL")) {
                        // 情况 C: 替换现有值
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*[^\\s\"']+",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*[^\\s\"']+",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                    } else if (modified.matches("(?s).*\\n\\s*env:\\s*\\n.*")) {
                        // 情况 B: 有 env 段但没 URL → 在 env 段末尾追加
                        // 找到 env: 的位置，向后追加新条目到 env 块结束
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                                "(env:\\s*\\n)([\\s\\S]*?)(?=\\n\\s*-|\\n[a-zA-Z])");
                        java.util.regex.Matcher m = p.matcher(modified);
                        if (m.find()) {
                            String envBlock = m.group(2);
                            String indent = envBlock.isEmpty() ? "            " : "            ";
                            String newEnv = "            - name: SPRING_DATASOURCE_URL\n"
                                    + "              value: \"" + jdbcUrl + "\"\n"
                                    + "            - name: SPRING_DATASOURCE_USERNAME\n"
                                    + "              value: \"" + dbUser + "\"\n"
                                    + "            - name: SPRING_DATASOURCE_PASSWORD\n"
                                    + "              value: \"" + dbPass + "\"\n";
                            modified = modified.replace(m.group(0), m.group(1) + envBlock + newEnv);
                        }
                    } else {
                        // 情况 A: 没有 env 段 → 在 ports 段后插入 env 段
                        // 找到第一个 container 块（以 "- name:" 开始）的结束位置之前插入
                        // 简化: 在 "ports:" 段后插入 env
                        String envInjection = "\n          env:\n"
                                + "            - name: SPRING_DATASOURCE_URL\n"
                                + "              value: \"" + jdbcUrl + "\"\n"
                                + "            - name: SPRING_DATASOURCE_USERNAME\n"
                                + "              value: \"" + dbUser + "\"\n"
                                + "            - name: SPRING_DATASOURCE_PASSWORD\n"
                                + "              value: \"" + dbPass + "\"\n";
                        // 找到 "containerPort: 8080" 行后插入
                        modified = modified.replaceFirst(
                                "(- containerPort:[^\\n]+\\n)",
                                "$1" + envInjection);
                    }

                    // 用户名密码处理（同样的逻辑）
                    if (modified.contains("SPRING_DATASOURCE_USERNAME")) {
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_USERNAME\n              value: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME[\\s\\S]{0,30}?value\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_USERNAME\n              value: \"" + dbUser + "\"");
                    }
                    if (modified.contains("SPRING_DATASOURCE_PASSWORD")) {
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_PASSWORD\n              value: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD[\\s\\S]{0,30}?value\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_PASSWORD\n              value: \"" + dbPass + "\"");
                    }

                    // 5) 注入 env 段（如果还没有）— 用与 generateDefault 一致的 dbHost/端口
                    if (!modified.contains("SPRING_DATASOURCE_URL")) {
                        // K8s 容器需要访问宿主机 MySQL，host.docker.internal 在 K8s 中不解析
                        String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "127.0.0.1");
                        String envInjection =
                                "            - name: SPRING_DATASOURCE_URL\n" +
                                "              value: \"jdbc:mysql://" + k8sDbHost + ":" + dbPort + "/" + dbName
                                + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                                + "&createDatabaseIfNotExist=true\"\n" +
                                "            - name: SPRING_DATASOURCE_USERNAME\n" +
                                "              value: \"" + dbUser + "\"\n" +
                                "            - name: SPRING_DATASOURCE_PASSWORD\n" +
                                "              value: \"" + dbPass + "\"\n";
                        // 找到 "containerPort: 8080" 行后插入
                        modified = modified.replaceFirst(
                                "(- containerPort:[^\\n]+\\n)",
                                "$1" + envInjection);
                    } else {
                        // 已存在 SPRING_DATASOURCE_URL 时，把 host.docker.internal 替换为 K8s 可达地址
                        String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "127.0.0.1");
                        if (modified.contains("host.docker.internal")) {
                            modified = modified.replaceAll("host\\.docker\\.internal", k8sDbHost);
                        }
                    }

                    // 6) 自动添加 hostNetwork: true（K8s 容器访问宿主机 MySQL 必须）
                    // 正确位置: spec.template.spec.containers 之前（spec 的兄弟字段）
                    if (!modified.contains("hostNetwork:")) {
                        // 找到 containers: 之前的位置（在 spec.containers 同级）
                        modified = modified.replaceFirst(
                                "(\\n)([ \\t]*containers:)",
                                "$1      hostNetwork: true\n$2");
                    }

                    if (!modified.equals(original)) {
                        Files.writeString(deployPath, modified);
                        logBuf.append("[K8S] 已注入/替换 SPRING_DATASOURCE_URL 指向: ").append(jdbcUrl).append("\n");
                        logBuf.append("[K8S] 已自动添加 hostNetwork: true（K8s 访问宿主机 MySQL）\n");
                    } else {
                        logBuf.append("[K8S] 警告: 未修改 deployment.yaml，O 项目可能使用自己的 application.yml\n");
                    }
                } catch (IOException e) {
                    logBuf.append("[WARN] 修改 deployment.yaml 失败: ").append(e.getMessage()).append("\n");
                }
            }
        }

        String command = "kubectl apply -f " + deployFile + " -n " + k8sNamespace;
        logBuf.append("[K8S] 部署 namespace=").append(k8sNamespace).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        appendLog(buildId, logBuf.toString());
        if (runCommand(workDir.toString(), command, logBuf, buildId) != 0) {
            logBuf.append("[ERROR] 部署失败\n"); return false;
        }
        String rolloutCmd = "kubectl rollout status deployment/" + project.getCode()
                + " -n " + k8sNamespace + " --timeout=60s";
        logBuf.append("[K8S] $ ").append(rolloutCmd).append("\n");
        int rc = runCommand(workDir.toString(), rolloutCmd, logBuf, buildId);
        if (rc != 0) {
            logBuf.append("[WARN] rollout 在 60 秒内未完成，Pod 可能仍在拉取镜像或启动中\n");
        }
        logBuf.append("[K8S] 部署完成\n");

        // ==================== 部署后真实状态检查 ====================
        // 关键: 不能用 kubectl apply 成功 + rollout 警告就认为部署成功
        // 必须真实检查 Pod 是否就绪 (Running 且 Ready)
        // 之前的问题: Pod Pending/CrashLoopBackOff 时仍然标记"成功"
        logBuf.append("[K8S] 检查 Pod 真实状态...\n");
        appendLog(buildId, logBuf.toString());
        int deploymentNameLen = (project.getCode() != null ? project.getCode() : "devops-app").length();
        String deploymentName = (project.getCode() != null ? project.getCode() : "devops-app");
        // 用 -o name 列出 Pod（避免 jsonpath 数组越界）
        String podsOutput;
        try {
            int rc1 = runCommand(workDir.toString(),
                    "kubectl get pods -n " + k8sNamespace
                            + " -l app=" + deploymentName
                            + " -o name", logBuf, buildId);
            // 复用刚才的 rollout 输出无法重用 runCommand 的返回字符串
            // 直接用 ProcessBuilder 单独跑一次只读 Pod 列表
            podsOutput = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                            "k8s-pods-list-" + System.currentTimeMillis() + ".tmp")));
        } catch (Exception ex) {
            podsOutput = "";
        }
        // 改用 ProcessBuilder 直接拿输出
        StringBuilder podsBuf = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pods", "-n", k8sNamespace,
                    "-l", "app=" + deploymentName, "-o", "name");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) podsBuf.append(l).append("\n");
            }
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            logBuf.append("[WARN] 列出 Pod 失败: ").append(ex.getMessage()).append("\n");
        }
        podsOutput = podsBuf.toString();
        int totalPods = 0;
        int runningPods = 0;
        if (podsOutput != null && !podsOutput.trim().isEmpty()) {
            for (String line : podsOutput.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                totalPods++;
                String podName = name.startsWith("pod/") ? name.substring(4) : name;
                String phase = "";
                try {
                    ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pod", podName,
                            "-n", k8sNamespace, "-o", "jsonpath={.status.phase}");
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getInputStream()))) {
                        String l;
                        while ((l = r.readLine()) != null) phase = l;
                    }
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    phase = phase.trim();
                } catch (Exception ex) {
                    phase = "Unknown";
                }
                if ("Running".equals(phase)) runningPods++;
                else {
                    logBuf.append("[K8S] Pod ").append(podName).append(" 状态=").append(phase).append("（非 Running）\n");
                }
            }
        }
        if (totalPods == 0) {
            logBuf.append("[ERROR] 部署后未发现任何 Pod（应用可能未正确启动）\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        if (runningPods == 0) {
            logBuf.append("[ERROR] 部署后所有 Pod 都不在 Running 状态（共 ").append(totalPods).append(" 个）\n");
            logBuf.append("[HINT] 查看 Pod 状态: kubectl get pods -n ").append(k8sNamespace).append(" -l app=").append(deploymentName).append("\n");
            logBuf.append("[HINT] 查看 Pod 日志: kubectl logs -n ").append(k8sNamespace).append(" <pod-name>\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        if (runningPods < totalPods) {
            logBuf.append("[ERROR] 部署后只有 ").append(runningPods).append("/").append(totalPods).append(" Pod 处于 Running 状态\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        logBuf.append("[K8S] 所有 Pod (").append(runningPods).append("/").append(totalPods).append(") 状态正常\n");
        appendLog(buildId, logBuf.toString());
        return true;
    }

    /**
     * 自动生成默认的 K8s Deployment YAML
     * @param dbName 目标数据库名（null 时回退到 devops_platform）
     */
    private String generateDefaultK8sDeployment(Project project, String dbName) {
        String appName = project.getCode() != null ? project.getCode() : "devops-app";
        String image = appName + ":latest";
        int containerPort = 8080;
        if ("Node.js".equalsIgnoreCase(project.getLanguage())) containerPort = 3000;

        // ==================== 随机 Pod 副本数 (1-3) ====================
        // K8s 会自动调度这些副本到不同 Node（如果有的话）
        // 单节点开发环境也会自动调度到当前集群
        int replicas = 1 + new java.util.Random().nextInt(3); // 1-3 之间随机

        // 检查 workspace 是否有 frontend/ 目录（前后端分离部署）
        boolean hasFrontend = false;
        try {
            Path workspacePath = resolveWorkspaceRoot().resolve(project.getCode());
            hasFrontend = Files.exists(workspacePath.resolve("frontend"));
        } catch (IOException ignored) {}

        // 数据库名：使用构建时指定的独立库（已含构建编号），兜底也用带编号的
        String targetDb = (dbName != null && !dbName.isBlank()) ? dbName
                : DatabaseProvisioningService.defaultDbName(project.getCode(), "0");

        // 数据库连接：H2 模式零依赖；MySQL 模式注入连接信息（db_type 为 NULL 时默认 MySQL）
        boolean useH2 = "H2".equalsIgnoreCase(project.getDbType());
        // K8s 容器需要访问宿主机 MySQL，host.docker.internal 在 K8s 容器里不解析
        // 单节点 K8s: 用 127.0.0.1 + hostNetwork: true
        // 可通过环境变量 K8S_DB_HOST 自定义（如 docker-desktop 用户可写 host.docker.internal）
        String dbHost = useH2 ? (project.getDbHost() != null ? project.getDbHost() : "host.docker.internal")
                : (project.getDbHost() != null ? project.getDbHost() : System.getenv().getOrDefault("K8S_DB_HOST", "127.0.0.1"));
        // K8s 场景默认开启 hostNetwork，方便访问宿主机
        boolean needHostNetwork = !useH2;
        String envSection = "";
        String hostNetworkLine = needHostNetwork ? "          hostNetwork: true\n" : "";
        if (!useH2) {
            int dbPort = project.getDbPort() != null ? project.getDbPort() : 3306;
            String dbUser = project.getDbUsername() != null ? project.getDbUsername() : "root";
            String dbPass = project.getDbPassword() != null ? project.getDbPassword() : "";
            envSection =
                "          env:\n" +
                "            - name: SPRING_DATASOURCE_URL\n" +
                "              value: \"jdbc:mysql://" + dbHost + ":" + dbPort + "/" + targetDb + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true\"\n" +
                "            - name: SPRING_DATASOURCE_USERNAME\n" +
                "              value: \"" + dbUser + "\"\n" +
                "            - name: SPRING_DATASOURCE_PASSWORD\n" +
                "              value: \"" + dbPass + "\"\n";
        }

        // 前端 nginx 容器（如果有 frontend 目录）
        String frontendContainer = "";
        if (hasFrontend) {
            frontendContainer =
                "        - name: " + appName + "-frontend\n" +
                "          image: " + appName + "-frontend:latest\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                "          ports:\n" +
                "            - containerPort: 80\n" +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"64Mi\"\n" +
                "              cpu: \"50m\"\n" +
                "            limits:\n" +
                "              memory: \"128Mi\"\n" +
                "              cpu: \"100m\"\n";
        }

        // Service targetPort: 有前端时指向 nginx(80)，否则指向后端容器端口
        int serviceTargetPort = hasFrontend ? 80 : containerPort;

        return "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: " + appName + "\n" +
                "  namespace: " + k8sNamespace + "\n" +
                "  labels:\n" +
                "    replicas-auto: \"" + replicas + "\"\n" +
                "spec:\n" +
                "  replicas: " + replicas + "\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app: " + appName + "\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app: " + appName + "\n" +
                "    spec:\n" +
                hostNetworkLine +
                "      containers:\n" +
                "        - name: " + appName + "\n" +
                "          image: " + image + "\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                "          ports:\n" +
                "            - containerPort: " + containerPort + "\n" +
                envSection +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"256Mi\"\n" +
                "              cpu: \"250m\"\n" +
                "            limits:\n" +
                "              memory: \"512Mi\"\n" +
                "              cpu: \"500m\"\n" +
                (hasFrontend ? frontendContainer : "") +
                "---\n" +
                "apiVersion: v1\n" +
                "kind: Service\n" +
                "metadata:\n" +
                "  name: " + appName + "-svc\n" +
                "  namespace: " + k8sNamespace + "\n" +
                "spec:\n" +
                "  selector:\n" +
                "    app: " + appName + "\n" +
                "  ports:\n" +
                "    - port: 8080\n" +
                "      targetPort: " + serviceTargetPort + "\n" +
                "  type: ClusterIP\n";
    }

    private boolean executeTestReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path workDir = findProjectRoot(project);
        String cmd = project.getLanguage() != null && project.getLanguage().equalsIgnoreCase("Node.js")
                ? "npm test" : "mvn test -B";
        logBuf.append("[TEST] ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(workDir.toString(), cmd, logBuf, buildId) == 0;
    }

    private boolean executeNpmInstallReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            logBuf.append("[ERROR] 找不到 package.json: ").append(projectRoot.toAbsolutePath()).append("\n");
            return false;
        }
        String cmd = "npm install";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
    }

    private boolean executeNpmBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            logBuf.append("[ERROR] 找不到 package.json: ").append(projectRoot.toAbsolutePath()).append("\n");
            return false;
        }
        String cmd = "npm run build";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
    }

    private boolean executeMvnPackageReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path pomXml = projectRoot.resolve("pom.xml");

        if (!Files.exists(pomXml)) {
            logBuf.append("[ERROR] 找不到 pom.xml\n");
            logBuf.append("[ERROR] 查找路径: ").append(projectRoot.toAbsolutePath()).append("\n");
            logBuf.append("[HINT] 确认 pom.xml 在仓库中且未被 .gitignore 排除\n");
            logBuf.append("[HINT] 如果 pom.xml 在子目录中，可以在项目的构建命令里指定路径\n");
            // 列出目录内容帮助诊断
            logBuf.append("--- 目录内容 ---\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            logBuf.append("----------------\n");
            return false;
        }

        String cmd = "mvn clean package -DskipTests -B";
        String workDir = projectRoot.toString();
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(workDir, cmd, logBuf, buildId) == 0;
    }

    // ===================== Git 操作 =====================

    /** 确保工作区根目录已创建并返回绝对路径 */
    private Path resolveWorkspaceRoot() throws IOException {
        if (workspaceRoot != null) return workspaceRoot;
        synchronized (this) {
            if (workspaceRoot != null) return workspaceRoot;
            Path p = Paths.get(workspaceDir).toAbsolutePath().normalize();
            Files.createDirectories(p);
            workspaceRoot = p;
            return workspaceRoot;
        }
    }

    private boolean prepareWorkspace(Project project, StringBuilder logBuf, Long buildId, String branch) throws Exception {
        Path root = resolveWorkspaceRoot();
        String projectCode = project.getCode();
        if (projectCode == null || projectCode.isBlank()) {
            logBuf.append("[ERROR] 项目编码 (code) 为空，无法创建工作目录\n");
            return false;
        }
        Path workspacePath = root.resolve(projectCode);
        String workspaceAbs = workspacePath.toString();
        String gitUrl = project.getGitUrl();
        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch
                : (project.getGitBranch() != null && !project.getGitBranch().isBlank())
                ? project.getGitBranch() : "main";

        logBuf.append("========== 代码准备 ==========\n");
        logBuf.append("[INFO] 工作目录: ").append(workspaceAbs).append("\n");

        if (gitUrl == null || gitUrl.isBlank()) {
            logBuf.append("[WARN] 未配置 Git 地址，跳过代码拉取\n");
            if (!Files.exists(workspacePath)) Files.createDirectories(workspacePath);
            return true;
        }

        // --- 已有 Git 仓库 → Pull ---
        if (Files.exists(workspacePath) && Files.exists(workspacePath.resolve(".git"))) {
            logBuf.append("[GIT] 仓库已存在，拉取最新代码 (分支: ").append(effectiveBranch).append(")\n");
            String pullCmd = "git fetch origin && git checkout " + effectiveBranch
                    + " && git pull origin " + effectiveBranch;
            logBuf.append("$ ").append(pullCmd).append("\n");
            appendLog(buildId, logBuf.toString());
            int exitCode = runGitCommandWithRetry(workspaceAbs, pullCmd, logBuf, buildId, 3);
            if (exitCode != 0) {
                logBuf.append("[ERROR] Git pull 失败 (退出码: ").append(String.valueOf(exitCode)).append(")\n");
                logBuf.append("[HINT] 如果是认证失败，请检查 Git 地址是否包含 Token 或密钥\n");
                return false;
            }
            logBuf.append("[GIT] 代码更新完成\n\n");
            return verifyWorkspaceContents(project, workspacePath, logBuf);
        }

        // --- 目录存在但非 Git 仓库 → 清理后重新克隆 ---
        if (Files.exists(workspacePath)) {
            logBuf.append("[WARN] 目录已存在但不是 Git 仓库，清理后重新克隆\n");
            deleteRecursively(workspacePath);
        }

        // --- 全新克隆 ---
        logBuf.append("[GIT] 克隆: ").append(gitUrl).append(" (分支: ").append(effectiveBranch).append(")\n");
        Files.createDirectories(root);
        String cloneCmd = "git clone -b " + effectiveBranch + " " + gitUrl + " " + projectCode;
        logBuf.append("$ ").append(cloneCmd).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runGitCommandWithRetry(root.toString(), cloneCmd, logBuf, buildId, 3);
        if (exitCode != 0) {
            logBuf.append("[ERROR] Git clone 失败 (退出码: ").append(String.valueOf(exitCode)).append(")\n");
            logBuf.append("[HINT] 请确认:\n");
            logBuf.append("  1. Git 地址正确: ").append(gitUrl).append("\n");
            logBuf.append("  2. 分支存在: ").append(effectiveBranch).append("\n");
            logBuf.append("  3. 有访问权限 (公开仓库 或 个人 Token)\n");
            return false;
        }

        // 验证克隆结果
        if (!Files.exists(workspacePath)) {
            logBuf.append("[ERROR] Git clone 未报错但目标目录不存在: ").append(workspaceAbs).append("\n");
            return false;
        }
        logBuf.append("[GIT] 克隆完成\n\n");
        return verifyWorkspaceContents(project, workspacePath, logBuf);
    }

    /**
     * 验证工作目录内容 —— 列出文件结构，确认构建文件存在
     */
    private boolean verifyWorkspaceContents(Project project, Path workspacePath, StringBuilder logBuf) {
        try {
            logBuf.append("--- 工作目录内容 ---\n");
            listDirectory(workspacePath, logBuf, 0, 3);

            // 检测项目类型，验证关键构建文件
            Path pomXml = findFile(workspacePath, "pom.xml");
            Path packageJson = findFile(workspacePath, "package.json");
            Path buildGradle = findFile(workspacePath, "build.gradle");

            String language = project.getLanguage() != null ? project.getLanguage() : "Java";
            boolean hasBuildFile = false;

            if ("Java".equalsIgnoreCase(language)) {
                if (pomXml != null) {
                    logBuf.append("[OK] 找到 pom.xml: ").append(workspacePath.relativize(pomXml)).append("\n");
                    hasBuildFile = true;
                }
                if (buildGradle != null) {
                    logBuf.append("[OK] 找到 build.gradle: ").append(workspacePath.relativize(buildGradle)).append("\n");
                    hasBuildFile = true;
                }
                if (!hasBuildFile) {
                    logBuf.append("[WARN] 未找到 Maven pom.xml 或 Gradle build.gradle\n");
                    logBuf.append("[HINT] 请确认项目中有 pom.xml 文件，且已上传到 Git 仓库\n");
                }
            } else if ("Node.js".equalsIgnoreCase(language)) {
                if (packageJson != null) {
                    logBuf.append("[OK] 找到 package.json: ").append(workspacePath.relativize(packageJson)).append("\n");
                    hasBuildFile = true;
                } else {
                    logBuf.append("[WARN] 未找到 package.json\n");
                }
            }

            if (!hasBuildFile) {
                logBuf.append("[HINT] 构建文件不在根目录？可以在流水线定义中指定工作子目录\n");
            }

            logBuf.append("--------------------\n\n");
            return true;  // 目录已就绪，构建文件缺失由具体步骤报错
        } catch (Exception e) {
            logBuf.append("[WARN] 验证工作目录内容失败: ").append(e.getMessage()).append("\n");
            return true;  // 不影响构建继续
        }
    }

    /** 递归列出目录（限制深度和条目数） */
    private void listDirectory(Path dir, StringBuilder buf, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        try {
            java.util.List<Path> entries = new java.util.ArrayList<>();
            try (var stream = Files.list(dir)) { entries = stream.sorted().toList(); } catch (IOException ignored) {}
            int shown = 0;
            for (Path entry : entries) {
                if (shown >= 30) { buf.append("  ... 还有 ").append(entries.size() - 30).append(" 项\n"); break; }
                String indent = "  ".repeat(depth + 1);
                if (Files.isDirectory(entry)) {
                    buf.append(indent).append(entry.getFileName()).append("/\n");
                    if (depth < maxDepth - 1) listDirectory(entry, buf, depth + 1, maxDepth);
                } else {
                    long size = 0;
                    try { size = Files.size(entry); } catch (IOException ignored) {}
                    buf.append(indent).append(entry.getFileName());
                    buf.append(" (").append(size / 1024).append(" KB)\n");
                }
                shown++;
            }
        } catch (Exception ignored) {}
    }

    /** 在目录及一级子目录中查找文件 */
    private Path findFile(Path dir, String fileName) {
        Path direct = dir.resolve(fileName);
        if (Files.exists(direct)) return direct;
        // 搜索一级子目录
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    Path nested = child.resolve(fileName);
                    if (Files.exists(nested)) return nested;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** 在 workspace 中定位项目根目录（包含 pom.xml/package.json 等构建文件） */
    private Path findProjectRoot(Project project) throws IOException {
        Path workspacePath = resolveWorkspaceRoot().resolve(project.getCode());
        if (!Files.exists(workspacePath)) return workspacePath;

        // 先检查 workspace 本身
        if (Files.exists(workspacePath.resolve("pom.xml"))
                || Files.exists(workspacePath.resolve("package.json"))
                || Files.exists(workspacePath.resolve("build.gradle"))
                || Files.exists(workspacePath.resolve("build.gradle.kts"))) {
            return workspacePath;
        }

        // 找一级子目录中带 pom.xml 的
        try (var stream = Files.list(workspacePath)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child) && Files.exists(child.resolve("pom.xml"))) {
                    return child;
                }
            }
        }

        return workspacePath;
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
    /**
     * 带重试的 Git 命令执行 —— 网络错误自动重试，认证/分支错误不重试
     */
    private int runGitCommandWithRetry(String workDir, String command, StringBuilder logBuf,
                                        Long buildId, int maxRetries) throws Exception {
        int lastExitCode = -1;
        StringBuilder retryLog = new StringBuilder();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            StringBuilder attemptBuf = new StringBuilder();
            int exitCode = runCommand(workDir, command, attemptBuf, buildId);
            String output = attemptBuf.toString();
            logBuf.append(output);
            lastExitCode = exitCode;

            if (exitCode == 0) {
                return 0;
            }

            // 判断是否为网络类错误（可重试）
            boolean isNetworkError = output.contains("Connection was reset")
                    || output.contains("Could not connect to server")
                    || output.contains("RPC failed")
                    || output.contains("Failed to connect")
                    || output.contains("Connection timed out")
                    || output.contains("Could not resolve host")
                    || output.contains("Recv failure");

            // 判断是否为不可重试的错误（认证、分支不存在等）
            boolean isFatalError = output.contains("Authentication failed")
                    || output.contains("could not read Username")
                    || output.contains("could not read Password")
                    || output.contains("Repository not found")
                    || output.contains("couldn't find remote ref")
                    || output.contains("fatal: ambiguous argument");

            if (isFatalError || !isNetworkError) {
                // 不可重试的错误，直接返回
                return exitCode;
            }

            if (attempt < maxRetries) {
                long waitSeconds = (long) Math.pow(2, attempt + 1); // 4s, 8s, 16s
                retryLog.append("[RETRY] Git 网络错误，").append(waitSeconds)
                        .append("s 后重试 (第 ").append(attempt).append("/").append(maxRetries).append(" 次)\n");
                logBuf.append(retryLog.toString());
                appendLog(buildId, retryLog.toString());
                retryLog.setLength(0);
                Thread.sleep(waitSeconds * 1000);
            }
        }
        return lastExitCode;
    }

    private int runCommand(String workDir, String command, StringBuilder logBuf, Long buildId) throws Exception {
        Path workPath = Paths.get(workDir);
        if (!Files.exists(workPath)) Files.createDirectories(workPath);

        ProcessBuilder pb;
        if (shellCommand.contains("bash")) pb = new ProcessBuilder("bash", "-c", command);
        else if (shellCommand.contains("powershell")) pb = new ProcessBuilder("powershell", "-Command", command);
        else pb = new ProcessBuilder("cmd", "/c", command);

        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        // 强制子进程使用 UTF-8 编码，避免 Windows GBK 与 Maven UTF-8 冲突导致乱码
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
        pb.environment().put("PYTHONIOENCODING", "UTF-8");

        Process proc = pb.start();
        if (buildId != null) runningProcesses.put(buildId, proc);

        // Windows 下 cmd.exe 默认 GBK，但 Maven/javac 输出 UTF-8；设置 JAVA_TOOL_OPTIONS 后统一 UTF-8
        // docker / kubectl 输出多为 ASCII，UTF-8 读取兼容
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
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
        try {
            Path root = resolveWorkspaceRoot();
            return root.resolve(project.getCode()).toString();
        } catch (IOException e) {
            return workspaceDir + "/" + project.getCode();  // fallback
        }
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
        // 1) 写入文件系统（使用绝对路径，避免 @Async 线程的工作目录问题）
        try {
            Path logFile = absoluteLogDir.resolve("build-" + buildId + ".log");
            Files.writeString(logFile, logContent);
        } catch (IOException e) {
            log.error("保存日志文件失败 buildId={}", buildId, e);
        }

        // 2) 同时持久化到数据库，确保日志不会因为文件系统问题而丢失
        try {
            buildRepository.findById(buildId).ifPresent(build -> {
                build.setLogContent(logContent);
                buildRepository.save(build);
            });
        } catch (Exception e) {
            log.error("持久化日志到数据库失败 buildId={}", buildId, e);
        }
    }

    public String getBuildLog(Long buildId) {
        // 1) 运行中的构建 → 直接从内存缓存取（实时日志）
        StringBuilder cached = logCache.get(buildId);
        if (cached != null) return cached.toString();

        // 2) 已完成的构建 → 先尝试从文件系统读取
        try {
            Path logFile = absoluteLogDir.resolve("build-" + buildId + ".log");
            if (Files.exists(logFile)) {
                return Files.readString(logFile);
            }
        } catch (IOException e) { log.warn("读取日志文件失败 buildId={}", buildId, e); }

        // 3) 文件不存在 → 从数据库兜底
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build != null && build.getLogContent() != null && !build.getLogContent().isBlank()) {
            return build.getLogContent();
        }

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

    /** 删除构建记录（含日志缓存清理、运行中进程终止） */
    public Build deleteBuild(Long buildId) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在: " + buildId));

        // 如果正在运行则先终止
        if ("RUNNING".equals(build.getStatus())) {
            Process proc = runningProcesses.remove(buildId);
            if (proc != null) proc.destroy();
        }

        // 清理日志缓存
        logCache.remove(buildId);

        // 删除日志文件
        try {
            Files.deleteIfExists(absoluteLogDir.resolve("build-" + buildId + ".log"));
        } catch (IOException ignored) {}

        buildRepository.delete(build);
        log.info("已删除构建记录 #{}", buildId);
        return build;
    }

    /** 检查项目工作目录状态（供前端诊断用） */
    public Map<String, Object> checkWorkspace(Long projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                result.put("error", "项目不存在");
                return result;
            }
            result.put("projectId", projectId);
            result.put("projectName", project.getName());
            result.put("projectCode", project.getCode());
            result.put("gitUrl", project.getGitUrl());
            result.put("gitBranch", project.getGitBranch());

            Path workspacePath = resolveWorkspaceRoot().resolve(project.getCode());
            result.put("workspacePath", workspacePath.toString());
            result.put("workspaceExists", Files.exists(workspacePath));
            result.put("isGitRepo", Files.exists(workspacePath.resolve(".git")));

            if (Files.exists(workspacePath)) {
                // 列出一级文件
                List<String> rootFiles = new java.util.ArrayList<>();
                try (var stream = Files.list(workspacePath)) {
                    for (Path p : stream.sorted().toList()) {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p)) name += "/";
                        rootFiles.add(name);
                    }
                }
                result.put("rootFiles", rootFiles);

                // 检查关键构建文件
                Path pomXml = findFile(workspacePath, "pom.xml");
                result.put("hasPomXml", pomXml != null);
                if (pomXml != null) result.put("pomXmlPath", workspacePath.relativize(pomXml).toString());

                Path packageJson = findFile(workspacePath, "package.json");
                result.put("hasPackageJson", packageJson != null);
                if (packageJson != null) result.put("packageJsonPath", workspacePath.relativize(packageJson).toString());

                Path buildGradle = findFile(workspacePath, "build.gradle");
                result.put("hasBuildGradle", buildGradle != null);

                // Maven wrapper
                result.put("hasMvnw", Files.exists(workspacePath.resolve("mvnw"))
                        || Files.exists(workspacePath.resolve("mvnw.cmd")));
            }

            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
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
