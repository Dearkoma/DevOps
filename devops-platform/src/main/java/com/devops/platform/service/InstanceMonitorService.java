package com.devops.platform.service;

import com.devops.platform.entity.Project;
import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.ProjectRepository;
import com.devops.platform.repository.ServiceInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceMonitorService {

    private final ServiceInstanceRepository instanceRepository;
    private final ProjectRepository projectRepository;

    @Value("${devops.pipeline.docker.command:docker}")
    private String dockerCommand;

    /** 跟踪 K8s 端口转发进程：instanceId → Process */
    private final Map<Long, Process> portForwardProcesses = new ConcurrentHashMap<>();
    /** 跟踪 K8s 端口转发使用的本地端口：instanceId → localPort */
    private final Map<Long, Integer> portForwardPorts = new ConcurrentHashMap<>();

    /**
     * 应用关闭时：停止所有 RUNNING 的 O 项目实例 + 清理端口转发进程
     * - K8s:  kubectl scale deployment/<depName> --replicas=0
     * - Docker: docker stop <containerId>
     */
    @PreDestroy
    public void cleanupAllPortForwards() {
        // 1. 停止所有 RUNNING 的 O 项目实例
        try {
            List<ServiceInstance> runningInstances = instanceRepository.findAll().stream()
                    .filter(inst -> "RUNNING".equals(inst.getStatus()))
                    .collect(Collectors.toList());
            if (!runningInstances.isEmpty()) {
                log.info("D 项目关闭：自动停止 {} 个运行中的 O 项目实例", runningInstances.size());
                for (ServiceInstance inst : runningInstances) {
                    try {
                        if ("K8S".equals(inst.getDeployType())) {
                            stopK8sInstance(inst);
                            log.info("  ✓ K8s 实例已缩容到 0: {}", inst.getInstanceName());
                        } else {
                            stopDockerInstance(inst);
                            log.info("  ✓ Docker 容器已停止: {}", inst.getInstanceName());
                        }
                    } catch (Exception e) {
                        log.warn("  ✗ 停止实例失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("关闭时停止 O 项目实例异常: {}", e.getMessage());
        }

        // 2. 清理所有端口转发进程
        log.info("清理所有端口转发进程，共 {} 个", portForwardProcesses.size());
        for (Map.Entry<Long, Process> entry : portForwardProcesses.entrySet()) {
            try {
                Process p = entry.getValue();
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                    log.info("已停止端口转发: instanceId={}", entry.getKey());
                }
            } catch (Exception e) {
                log.warn("停止端口转发失败: instanceId={}", entry.getKey(), e);
            }
        }
        portForwardProcesses.clear();
        portForwardPorts.clear();
    }

    public List<ServiceInstance> getInstancesByProject(Long projectId) {
        return instanceRepository.findByProjectId(projectId);
    }

    public List<ServiceInstance> getAllInstances() {
        return instanceRepository.findAll();
    }

    public ServiceInstance saveInstance(ServiceInstance instance) {
        return instanceRepository.save(instance);
    }

    public ServiceInstance updateHealth(Long id, String healthStatus, Double cpuUsage, Double memoryUsage) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst != null) {
            inst.setHealthStatus(healthStatus);
            if (cpuUsage != null) inst.setCpuUsage(cpuUsage);
            if (memoryUsage != null) inst.setMemoryUsage(memoryUsage);
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
        }
        return inst;
    }

    /** 定期检查所有实例健康状态（每 30 秒） */
    @Scheduled(fixedRate = 30000)
    public void checkHealth() {
        List<ServiceInstance> instances = instanceRepository.findAll();
        for (ServiceInstance inst : instances) {
            // 只对原本标记为 RUNNING 但心跳超时的实例降级
            if ("RUNNING".equals(inst.getStatus())
                    && inst.getLastHeartbeat() != null
                    && inst.getLastHeartbeat().plusMinutes(2).isBefore(LocalDateTime.now())) {
                inst.setHealthStatus("UNKNOWN");
                instanceRepository.save(inst);
            }
        }
    }

    /** 定期采集所有实例的 CPU/内存指标（每 15 秒），不限制状态 */
    @Scheduled(fixedRate = 15000)
    public void collectMetrics() {
        List<ServiceInstance> instances = instanceRepository.findAll();
        for (ServiceInstance inst : instances) {
            // 跳过已确认停止的实例
            if ("STOPPED".equals(inst.getStatus())) continue;
            try {
                if ("K8S".equals(inst.getDeployType())) {
                    collectK8sMetrics(inst);
                } else {
                    collectDockerMetrics(inst);
                }
            } catch (Exception e) {
                log.debug("采集指标失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            }
        }
    }

    /** 通过 docker stats 采集 Docker 容器的 CPU/内存 */
    private void collectDockerMetrics(ServiceInstance inst) {
        String containerId = resolveDockerContainerId(inst);

        // 找不到独立 Docker 容器 → 标记为 STOPPED（排除 K8s 容器干扰）
        if (containerId == null || containerId.isEmpty()) {
            if (!"STOPPED".equals(inst.getStatus())) {
                inst.setStatus("STOPPED");
                inst.setHealthStatus("UNKNOWN");
                inst.setLastHeartbeat(LocalDateTime.now());
                instanceRepository.save(inst);
            }
            return;
        }

        // 只要找到容器就更新心跳
        inst.setStatus("RUNNING");
        inst.setHealthStatus("HEALTHY");
        inst.setLastHeartbeat(LocalDateTime.now());
        instanceRepository.save(inst);

        ProcessBuilder pb = new ProcessBuilder(dockerCommand, "stats", "--no-stream",
                "--format", "{{.CPUPerc}}|{{.MemUsage}}", containerId);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return;
            }
            if (proc.exitValue() != 0) return;

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
            }

            String raw = output.toString().trim();
            if (raw.isEmpty()) return;

            // 格式: "0.05%|15.26MiB / 1.94GiB"
            String[] parts = raw.split("\\|", 2);
            if (parts.length < 2) return;

            // 解析 CPU 百分比
            String cpuStr = parts[0].replace("%", "").trim();
            try {
                inst.setCpuUsage(Double.parseDouble(cpuStr));
            } catch (NumberFormatException e) {
                log.trace("CPU 解析失败: {}", cpuStr);
            }

            // 解析内存使用量
            String memUsed = parts[1].trim().split("/")[0].trim();
            double memMB = parseDockerMemToMB(memUsed);
            if (memMB >= 0) {
                inst.setMemoryUsage(memMB);
            }

            instanceRepository.save(inst);
        } catch (Exception e) {
            log.debug("Docker 指标采集异常 [{}]: {}", inst.getInstanceName(), e.getMessage());
        }
    }

    /** 通过 kubectl top 采集 K8s Pod 的 CPU/内存 */
    private void collectK8sMetrics(ServiceInstance inst) {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";

        // 先解析实际运行的 Pod 名（DB 存的可能是 Deployment 名而非完整 Pod 名）
        String podName = resolveK8sPodName(ns, inst.getK8sPodName(), inst.getInstanceName(), inst.getProjectName());

        // 无论 kubectl top 是否可用，只要 Pod 在运行就更新心跳（防止状态跳 UNKNOWN）
        if (podName != null && !podName.isEmpty()) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
        }

        if (podName == null || podName.isEmpty()) return;

        String cmd = String.format("kubectl top pod %s -n %s --no-headers", podName, ns);
        ProcessResult r = runCommand(cmd);
        if (!r.success || r.output.isBlank()) return;  // top 失败不影响状态，心跳已更新

        // 输出格式: "pod-name   15m   128Mi"
        String[] parts = r.output.trim().split("\\s+");
        if (parts.length < 3) return;

        try {
            // CPU: "15m" → millicores, "1" → cores
            String cpuStr = parts[1];
            double millicpu;
            if (cpuStr.endsWith("m")) {
                millicpu = Double.parseDouble(cpuStr.replace("m", ""));
            } else {
                millicpu = Double.parseDouble(cpuStr) * 1000;
            }
            // 转换为百分比 (1000m ≈ 1 核 → 以此为基准)
            inst.setCpuUsage(Math.round(millicpu / 10.0 * 10.0) / 10.0);
        } catch (NumberFormatException e) {
            log.trace("K8s CPU 解析失败: {}", parts[1]);
        }

        // 内存: "128Mi"
        double memMB = parseK8sMemToMB(parts[2]);
        if (memMB >= 0) {
            inst.setMemoryUsage(memMB);
        }

        // 采集成功 → 标为运行中（不覆盖 k8sPodName，保持为 projectCode/Deployment 名）
        inst.setStatus("RUNNING");
        inst.setHealthStatus("HEALTHY");
        inst.setLastHeartbeat(LocalDateTime.now());
        instanceRepository.save(inst);
    }

    /**
     * 解析 K8s Deployment 名称
     * 优先用 instanceName 去掉 -k8s/-docker 后缀；
     * 回退：从 Pod 名中去掉最后两段 hash（<depName>-<rsHash>-<podHash>）
     */
    private String resolveK8sDeploymentName(ServiceInstance inst) {
        // k8sPodName 存的是 projectCode = Deployment 名
        if (inst.getK8sPodName() != null && !inst.getK8sPodName().isBlank()) {
            return inst.getK8sPodName();
        }
        // 兜底：从实例名推导，兼容旧格式 xxx-k8s / 新格式 xxx-k8s-3
        String depName = inst.getInstanceName();
        if (depName != null) {
            depName = depName.replaceAll("-(k8s|docker)(-\\d+)?$", "");
        }
        return (depName != null && !depName.isBlank()) ? depName : null;
    }

    /** 解析 K8s 实际运行的 Pod 名称 */
    private String resolveK8sPodName(String namespace, String podNameHint, String instanceName, String projectName) {
        String searchTerm = podNameHint;
        if (searchTerm == null || searchTerm.isEmpty()) {
            searchTerm = (projectName != null) ? projectName : instanceName;
        }
        if (searchTerm == null || searchTerm.isEmpty()) return null;

        // 先尝试直接 kubectl get pods 查找匹配的 Running Pod
        // 优先精确匹配，否则前缀匹配
        String cmd = String.format("kubectl get pods -n %s --field-selector=status.phase=Running "
                + "-o custom-columns=NAME:.metadata.name --no-headers", namespace);
        ProcessResult r = runCommand(cmd);
        if (!r.success || r.output.isBlank()) return null;

        for (String line : r.output.split("\\R")) {
            String name = line.trim();
            if (name.isEmpty()) continue;
            if (name.equals(searchTerm)) return name;
            if (name.startsWith(searchTerm + "-")) return name;
        }

        // 再试宽松匹配：name 包含 searchTerm
        for (String line : r.output.split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty() && name.contains(searchTerm)) return name;
        }

        // 兜底：用 Deployment 名前缀匹配（Pod 重启后 hash 会变，旧 Pod 名匹配不到）
        // 从 instanceName 推导 Deployment 名：去掉 -k8s-<数字> / -k8s / -docker-<数字> / -docker
        String depName = instanceName;
        if (depName != null) {
            depName = depName.replaceAll("-(k8s|docker)(-\\d+)?$", "");
            if (!depName.isEmpty() && !depName.equals(searchTerm)) {
                for (String line : r.output.split("\\R")) {
                    String name = line.trim();
                    if (!name.isEmpty() && name.startsWith(depName + "-")) return name;
                }
            }
        }

        // 最后用 projectName 前缀匹配
        if (projectName != null && !projectName.isEmpty() && !projectName.equals(searchTerm)) {
            for (String line : r.output.split("\\R")) {
                String name = line.trim();
                if (!name.isEmpty() && name.startsWith(projectName + "-")) return name;
            }
        }

        return null;
    }

    /**
     * 解析 K8s Pod 名称（不限状态，包括 CrashLoopBackOff / Failed 等非 Running Pod）
     * 用于日志查看场景——需要能看到崩溃 Pod 的日志
     */
    private String resolveK8sPodNameAllPhases(String namespace, String searchTerm) {
        if (searchTerm == null || searchTerm.isEmpty()) return null;

        ProcessResult r = kubectl("get", "pods", "-n", namespace,
                "-o", "custom-columns=NAME:.metadata.name", "--no-headers");
        if (!r.success || r.output == null || r.output.isBlank()) return null;

        // 优先前缀匹配（Deployment 创建的 Pod 名 = searchTerm-<replicaset-hash>-<pod-hash>）
        for (String line : r.output.split("\\R")) {
            String name = line.trim();
            if (name.isEmpty()) continue;
            if (name.startsWith(searchTerm + "-")) return name;
        }

        // 宽松匹配
        for (String line : r.output.split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty() && name.contains(searchTerm)) return name;
        }

        return null;
    }

    /** 解析 Docker stats 返回的内存字符串 (KiB/MiB/GiB/KB/MB/GB/B → MB) */
    private double parseDockerMemToMB(String memStr) {
        if (memStr == null || memStr.isEmpty() || "0B".equals(memStr)) return 0;
        try {
            memStr = memStr.trim();
            double value;
            if (memStr.endsWith("KiB")) {
                value = Double.parseDouble(memStr.replace("KiB", "").trim()) / 1024.0;
            } else if (memStr.endsWith("MiB")) {
                value = Double.parseDouble(memStr.replace("MiB", "").trim());
            } else if (memStr.endsWith("GiB")) {
                value = Double.parseDouble(memStr.replace("GiB", "").trim()) * 1024.0;
            } else if (memStr.endsWith("kB")) {
                value = Double.parseDouble(memStr.replace("kB", "").trim()) / 1000.0;
            } else if (memStr.endsWith("MB")) {
                value = Double.parseDouble(memStr.replace("MB", "").trim());
            } else if (memStr.endsWith("GB")) {
                value = Double.parseDouble(memStr.replace("GB", "").trim()) * 1000.0;
            } else if (memStr.endsWith("B")) {
                value = Double.parseDouble(memStr.replace("B", "").trim()) / (1024.0 * 1024.0);
            } else {
                return -1;
            }
            return Math.round(value * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析 K8s kubectl top 返回的内存字符串 (Ki/Mi/Gi → MB) */
    private double parseK8sMemToMB(String memStr) {
        if (memStr == null || memStr.isEmpty()) return -1;
        return parseDockerMemToMB(memStr
                .replace("Ki", "KiB")
                .replace("Mi", "MiB")
                .replace("Gi", "GiB")
                .replace("Ti", "TiB"));
    }

    /** 获取按部署类型分组的实例统计 */
    public Map<String, Object> getStatsByType() {
        List<ServiceInstance> all = instanceRepository.findAll();

        List<ServiceInstance> dockerList = all.stream()
                .filter(i -> "DOCKER".equals(i.getDeployType()))
                .collect(Collectors.toList());
        List<ServiceInstance> k8sList = all.stream()
                .filter(i -> "K8S".equals(i.getDeployType()))
                .collect(Collectors.toList());

        return Map.of(
                "docker", buildTypeStats(dockerList),
                "k8s", buildTypeStats(k8sList),
                "total", all.size()
        );
    }

    private Map<String, Object> buildTypeStats(List<ServiceInstance> list) {
        long running = list.stream().filter(i -> "RUNNING".equals(i.getStatus())).count();
        long stopped = list.stream().filter(i -> "STOPPED".equals(i.getStatus())).count();
        long healthy = list.stream().filter(i -> "HEALTHY".equals(i.getHealthStatus())).count();
        long unhealthy = list.stream().filter(i -> "UNHEALTHY".equals(i.getHealthStatus())).count();
        long unknown = list.stream().filter(i -> "UNKNOWN".equals(i.getHealthStatus()) || i.getHealthStatus() == null).count();
        return Map.of(
                "total", list.size(),
                "running", running,
                "stopped", stopped,
                "healthy", healthy,
                "unhealthy", unhealthy,
                "unknown", unknown
        );
    }

    /** 获取实例统计 */
    public Map<String, Object> getStats() {
        List<ServiceInstance> all = instanceRepository.findAll();
        long running = all.stream().filter(i -> "RUNNING".equals(i.getStatus())).count();
        long healthy = all.stream().filter(i -> "HEALTHY".equals(i.getHealthStatus())).count();
        long unhealthy = all.stream().filter(i -> "UNHEALTHY".equals(i.getHealthStatus())).count();

        return Map.of(
                "total", all.size(),
                "running", running,
                "healthy", healthy,
                "unhealthy", unhealthy,
                "instances", all
        );
    }

    /**
     * 检测 Docker 是否可用（含容器数统计）
     * 返回 connected / name / version / containers / error 信息
     */
    public Map<String, Object> checkDockerAvailability() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 一次调用同时获取 Name + ServerVersion + RunningContainers + StoppedContainers
            ProcessBuilder pb = new ProcessBuilder(dockerCommand, "info", "--format",
                    "{{.Name}}|{{.ServerVersion}}|{{.ContainersRunning}}|{{.ContainersStopped}}|{{.Images}}");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (finished && proc.exitValue() == 0) {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }
                String raw = output.toString().trim();
                String[] parts = raw.split("\\|", -1);
                String name = getPart(parts, 0);
                String version = getPart(parts, 1);
                int runningContainers = parseIntSafe(getPart(parts, 2));
                int stoppedContainers = parseIntSafe(getPart(parts, 3));
                int images = parseIntSafe(getPart(parts, 4));

                result.put("connected", true);
                result.put("name", name);
                result.put("version", version);
                result.put("containersRunning", runningContainers);
                result.put("containersStopped", stoppedContainers);
                result.put("totalContainers", runningContainers + stoppedContainers);
                result.put("images", images);

                // 构建详细消息
                StringBuilder msg = new StringBuilder("已连接到 ").append(name.isEmpty() ? "Docker" : name);
                msg.append("，版本 ").append(version);
                msg.append("，").append(runningContainers).append(" 个容器运行中");
                if (stoppedContainers > 0) msg.append("，").append(stoppedContainers).append(" 个已停止");
                if (runningContainers == 0 && stoppedContainers == 0) msg.append("，但无运行容器");
                result.put("message", msg.toString());
                result.put("error", null);
            } else {
                if (!finished) {
                    proc.destroyForcibly();
                    result.put("connected", false);
                    result.put("name", null);
                    result.put("version", null);
                    result.put("containersRunning", 0);
                    result.put("containersStopped", 0);
                    result.put("totalContainers", 0);
                    result.put("images", 0);
                    result.put("message", "Docker 无响应（10s 超时）");
                    result.put("error", "Docker 命令超时，请确认 Docker Desktop 已启动");
                } else {
                    result.put("connected", false);
                    result.put("name", null);
                    result.put("version", null);
                    result.put("containersRunning", 0);
                    result.put("containersStopped", 0);
                    result.put("totalContainers", 0);
                    result.put("images", 0);
                    result.put("message", "Docker 不可用");
                    result.put("error", "docker info 返回非零退出码: " + proc.exitValue());
                }
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            result.put("connected", false);
            result.put("name", null);
            result.put("version", null);
            result.put("containersRunning", 0);
            result.put("containersStopped", 0);
            result.put("totalContainers", 0);
            result.put("images", 0);
            if (errMsg != null && errMsg.contains("Cannot run program")) {
                result.put("message", "Docker 未安装或未在 PATH 中");
                result.put("error", errMsg);
            } else {
                result.put("message", "Docker 检测异常");
                result.put("error", errMsg != null ? errMsg : "未知错误");
            }
        }
        return result;
    }

    private String getPart(String[] parts, int index) {
        return parts.length > index && !parts[index].isBlank() ? parts[index].trim() : "";
    }

    private int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ==================== 实例访问信息与外部部署 ====================

    /**
     * 获取实例的访问信息（内部链接 + 外部访问状态）
     * - K8s:  内部 ClusterIP Service URL；外部 NodePort（如有）
     * - Docker: 内部 localhost:端口；外部端口映射信息
     */
    public Map<String, Object> getAccessInfo(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("success", true);
        info.put("instanceName", inst.getInstanceName());
        info.put("deployType", inst.getDeployType());
        info.put("status", inst.getStatus());

        // 停止状态不返回访问链接（没有运行中的服务）
        if ("STOPPED".equals(inst.getStatus())) {
            info.put("stopped", true);
            info.put("message", "实例已停止，无可用访问链接。请先启动实例。");
            // 停止状态也返回管理员凭据（如果有存储）
            addAdminCredentials(inst, info);
            return info;
        }

        if ("K8S".equals(inst.getDeployType())) {
            buildK8sAccessInfo(inst, info);
        } else {
            buildDockerAccessInfo(inst, info);
        }

        // 附加管理员凭据
        addAdminCredentials(inst, info);
        return info;
    }

    /** 附加管理员凭据信息到访问信息中 */
    private void addAdminCredentials(ServiceInstance inst, Map<String, Object> info) {
        // adminUsername 可能为 null（旧实例），统一使用默认值 admin/admin123
        String username = (inst.getAdminUsername() != null && !inst.getAdminUsername().isBlank())
                ? inst.getAdminUsername() : "admin";
        String password = (inst.getAdminPassword() != null && !inst.getAdminPassword().isBlank())
                ? inst.getAdminPassword() : "admin123";
        info.put("hasAdminCredentials", true);
        info.put("adminUsername", username);
        info.put("adminPassword", password);
        if (inst.getDbName() != null && !inst.getDbName().isBlank()) {
            info.put("dbName", inst.getDbName());
        }
    }

    /** 从实例名推导 K8s Service 名 */
    /**
     * 自适应查找 K8s Service 名：
     * 1. 查询命名空间下所有 Service，通过 selector 匹配 Deployment 的 label
     * 2. 如果没找到，尝试用 projectCode-svc 约定名
     * 3. 都不行就返回约定名让调用方报错
     */
    private String resolveK8sServiceName(ServiceInstance inst) {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String deploymentName = inst.getK8sPodName(); // k8sPodName 存的是 projectCode = Deployment 名

        // 优先：查 K8s 集群实际存在的 Service
        if (deploymentName != null && !deploymentName.isBlank()) {
            // 方式1: 通过 Deployment 的 selector label 查找匹配的 Service
            ProcessResult svcR = kubectl("get", "svc", "-n", ns, "-o",
                    "jsonpath={range .items[*]}{.metadata.name}{\"\\t\"}{.spec.selector}{\"\\n\"}{end}");
            if (svcR.success && !svcR.output.isBlank()) {
                for (String line : svcR.output.split("\n")) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length < 2) continue;
                    String svcName = parts[0].trim();
                    String selector = parts[1].trim();
                    // selector 里包含 app: deploymentName 就认为是匹配的
                    // 格式: map[app:proj-xxx] 或 {"app":"proj-xxx"}
                    if (selector.contains("\"app\":\"" + deploymentName + "\"")
                            || selector.contains("app:" + deploymentName)
                            || selector.contains("app: " + deploymentName)) {
                        return svcName;
                    }
                }
            }

            // 方式2: 约定名 projectCode-svc
            String conventionName = deploymentName + "-svc";
            ProcessResult checkR = kubectl("get", "svc", conventionName, "-n", ns, "-o", "name");
            if (checkR.success && !checkR.output.contains("not found") && !checkR.output.contains("NotFound")) {
                return conventionName;
            }

            // 方式3: 集群里只有一个 Service，直接用它
            ProcessResult allSvcR = kubectl("get", "svc", "-n", ns, "-o",
                    "jsonpath={.items[*].metadata.name}");
            if (allSvcR.success && !allSvcR.output.isBlank()) {
                String[] names = allSvcR.output.trim().split("\\s+");
                // 排除 kubernetes 这个默认 Service
                java.util.List<String> candidates = new java.util.ArrayList<>();
                for (String n : names) {
                    if (!n.equals("kubernetes")) candidates.add(n);
                }
                if (candidates.size() == 1) {
                    return candidates.get(0);
                }
            }
        }

        // 兜底：从实例名推导，兼容旧格式
        String base = inst.getInstanceName();
        int k8sIdx = base.indexOf("-k8s");
        if (k8sIdx > 0) {
            base = base.substring(0, k8sIdx);
        } else if (base.endsWith("-docker")) {
            base = base.substring(0, base.length() - 7);
        }
        return base + "-svc";
    }

    private void buildK8sAccessInfo(ServiceInstance inst, Map<String, Object> info) {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String svcName = resolveK8sServiceName(inst);
        int port = inst.getPort() != null ? inst.getPort() : 8080;

        // 内部链接：ClusterIP 服务地址
        String internalUrl = String.format("http://%s.%s.svc.cluster.local:%d", svcName, ns, port);
        info.put("internalUrl", internalUrl);
        info.put("internalLabel", "集群内部访问");

        // 检查是否有活跃的端口转发
        Process existingProcess = portForwardProcesses.get(inst.getId());
        if (existingProcess != null && existingProcess.isAlive()) {
            Integer fwdPort = portForwardPorts.get(inst.getId());
            if (fwdPort != null) {
                info.put("forwarded", true);
                info.put("externalUrl", String.format("http://localhost:%d", fwdPort));
                info.put("externalPort", fwdPort);
                info.put("externalLabel", "已转发 — 本地端口 " + fwdPort + "（kubectl port-forward）");
                return;
            }
        }
        // 端口转发进程已死，清理残留状态
        if (existingProcess != null) {
            portForwardProcesses.remove(inst.getId());
            portForwardPorts.remove(inst.getId());
        }

        info.put("forwarded", false);
        info.put("externalLabel", "未转发（仅集群内部可访问，点击「一键转发」建立本地隧道）");
    }


    private void buildDockerAccessInfo(ServiceInstance inst, Map<String, Object> info) {
        String containerId = resolveDockerContainerId(inst);
        int port = inst.getPort() != null ? inst.getPort() : 8080;

        if (containerId == null) {
            // 容器未运行，但镜像已构建
            info.put("internalUrl", "容器未运行（镜像: " + (inst.getImageName() != null ? inst.getImageName() : "未知") + "）");
            info.put("internalLabel", "点击「一键转发」可自动启动容器并分配端口");
            info.put("forwarded", false);
            info.put("externalLabel", "未暴露 — 点击下方按钮自动启动并分配端口");
            return;
        }

        // 内部：容器可直接通过 localhost:port 访问（在宿主机上）
        info.put("internalUrl", String.format("http://localhost:%d", port));
        info.put("internalLabel", "宿主机本地访问");

        // 检查所有端口映射
        ProcessResult r = docker("port", containerId);
        if (r.success && !r.output.isBlank()) {
            // 输出格式: "8080/tcp -> 0.0.0.0:30080" 或多行
            String[] lines = r.output.trim().split("\\R");
            for (String line : lines) {
                if (line.contains(":")) {
                    String mappedPort = line.substring(line.lastIndexOf(":") + 1).trim();
                    try {
                        int extPort = Integer.parseInt(mappedPort);
                        info.put("forwarded", true);
                        info.put("externalUrl", String.format("http://localhost:%d", extPort));
                        info.put("externalPort", extPort);
                        info.put("externalLabel", "已映射到宿主机端口（系统自动分配）");
                        return;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        info.put("forwarded", false);
        info.put("externalLabel", "未映射端口 — 点击「一键转发」系统自动分配端口");
    }

    /**
     * 一键转发（建立本地隧道访问 K8s 服务）
     * - K8s: kubectl port-forward 建立本地隧道，不修改 Service 配置
     * - Docker: 系统自动分配空闲端口，停止旧容器并带 -p 端口映射重新启动
     */
    public Map<String, Object> exposeToExternal(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        try {
            if ("K8S".equals(inst.getDeployType())) {
                return exposeK8sToExternal(inst);
            } else {
                return exposeDockerToExternal(inst);
            }
        } catch (Exception e) {
            log.error("外部部署失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            return Map.of("success", false, "error", "外部部署失败: " + e.getMessage());
        }
    }

    /**
     * 停止指定实例的端口转发
     * 分三层尝试：
     * 1. 内存中的 Process 引用（最快）
     * 2. 通过记录的端口查 PID 并杀掉
     * 3. 通过 wmic / ps 搜索孤立的 kubectl port-forward 进程（兜底）
     * @return Map: success, message
     */
    public Map<String, Object> stopForward(Long id) {
        Process p = portForwardProcesses.remove(id);
        Integer port = portForwardPorts.remove(id);
        boolean killed = false;

        // 第 1 层：通过内存中的 Process 引用杀进程
        if (p != null && p.isAlive()) {
            try {
                p.destroyForcibly();
                killed = true;
                log.info("已停止端口转发（内存引用）: instanceId={}, port={}", id, port);
            } catch (Exception e) {
                log.warn("通过内存引用停止端口转发失败: instanceId={}", id, e);
            }
        }

        // 第 2 层：通过记录的端口杀掉监听该端口的 kubectl 进程
        if (!killed && port != null) {
            killed = killProcessOnPort(port);
            if (killed) {
                log.info("已停止端口转发（端口查找）: instanceId={}, port={}", id, port);
            }
        }

        // 第 3 层：通过 service 名搜索孤立的 kubectl port-forward 进程
        if (!killed) {
            ServiceInstance inst = instanceRepository.findById(id).orElse(null);
            if (inst != null && "K8S".equals(inst.getDeployType())) {
                String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
                String svcName = resolveK8sServiceName(inst);
                killed = killOrphanPortForward(svcName, ns);
                if (killed) {
                    log.info("已停止孤立的端口转发: svc/{}.{} -> instanceId={}", svcName, ns, id);
                }
            }
        }

        if (killed) {
            return Map.of("success", true, "message",
                    "端口转发已停止" + (port != null ? "（端口 " + port + " 已释放）" : ""));
        }
        return Map.of("success", true, "message", "没有活跃的端口转发（可能已自动断开）");
    }

    /** 通过端口号查找并杀掉监听该端口的进程 */
    private boolean killProcessOnPort(int port) {
        try {
            if (isWindows()) {
                // netstat -ano | findstr ":PORT "  → 获取 PID
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                        "netstat -ano | findstr \":" + port + " \" | findstr LISTENING");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(5, TimeUnit.SECONDS);

                for (String line : output.split("\\R")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 5) {
                        String pid = parts[parts.length - 1];
                        // 验证该 PID 确实是 kubectl 进程
                        if (isKubectlProcess(pid)) {
                            new ProcessBuilder("taskkill", "/F", "/PID", pid)
                                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                            return true;
                        }
                    }
                }
            } else {
                // Linux/Mac: lsof -ti :PORT → kill
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        "lsof -ti :" + port + " 2>/dev/null");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor(5, TimeUnit.SECONDS);
                if (!output.isEmpty()) {
                    new ProcessBuilder("kill", "-9", output)
                            .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("通过端口 {} 杀进程失败: {}", port, e.getMessage());
        }
        return false;
    }

    /** 搜索孤立的 kubectl port-forward 进程并按 service 名杀掉 */
    private boolean killOrphanPortForward(String svcName, String namespace) {
        try {
            if (isWindows()) {
                // wmic process where "name='kubectl.exe'" get processid,commandline
                ProcessBuilder pb = new ProcessBuilder("wmic", "process",
                        "where", "name='kubectl.exe'", "get", "processid,commandline", "/format:csv");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(10, TimeUnit.SECONDS);

                for (String line : output.split("\\R")) {
                    if (!line.contains("port-forward") || !line.contains("svc/" + svcName)) continue;
                    // 格式: Node,ProcessId,CommandLine
                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        String pid = parts[1].trim();
                        try {
                            Long.parseLong(pid); // 验证是数字
                            new ProcessBuilder("taskkill", "/F", "/PID", pid)
                                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                            return true;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else {
                // Linux/Mac: ps aux | grep "port-forward.*svc/SVCNAME" | grep -v grep
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        "ps aux | grep 'port-forward.*svc/" + svcName + "' | grep -v grep | awk '{print $2}'");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor(5, TimeUnit.SECONDS);
                if (!output.isEmpty()) {
                    for (String pid : output.split("\\R")) {
                        pid = pid.trim();
                        if (!pid.isEmpty()) {
                            new ProcessBuilder("kill", "-9", pid)
                                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                        }
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("搜索孤立的端口转发进程失败: {}", e.getMessage());
        }
        return false;
    }

    /** 验证指定 PID 是否是 kubectl 进程 */
    private boolean isKubectlProcess(String pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(5, TimeUnit.SECONDS);
            return output.toLowerCase().contains("kubectl");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    // ==================== 容器日志 ====================

    /**
     * 获取实例的容器日志（Docker / K8s）
     * @param id     实例 ID
     * @param tail   获取最后 N 行日志
     * @return Map: success, deployType, logs, source, tailLines
     */
    public Map<String, Object> getContainerLogs(Long id, int tail, String container) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("instanceName", inst.getInstanceName());
        result.put("deployType", inst.getDeployType());
        result.put("tailLines", tail);
        result.put("container", container);
        result.put("status", inst.getStatus());

        // K8s 停止后 Pod 被回收，日志不可获取——返回 stopped 标记，让前端显示缓存
        if ("K8S".equals(inst.getDeployType()) && "STOPPED".equals(inst.getStatus())) {
            result.put("success", false);
            result.put("stopped", true);
            result.put("error", "实例已停止，K8s Pod 已被回收。以下为停止前最后一次获取的日志缓存。");
            result.put("logs", "");
            return result;
        }

        if ("K8S".equals(inst.getDeployType())) {
            collectK8sLogs(inst, tail, container, result);
        } else {
            collectDockerLogs(inst, tail, result);
        }
        return result;
    }

    /**
     * 获取实例的容器列表（用于判断有无前台/后台服务）
     * K8s: 解析 Pod 的 containers；Docker: 单容器
     * @return Map: success, containers[{name,image,role}], hasFrontend, hasBackend
     */
    public Map<String, Object> getInstanceContainers(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("instanceName", inst.getInstanceName());
        result.put("deployType", inst.getDeployType());
        result.put("status", inst.getStatus());

        java.util.List<Map<String, String>> containers = new java.util.ArrayList<>();

        if ("K8S".equals(inst.getDeployType())) {
            String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
            String searchTerm = inst.getInstanceName();
            if (searchTerm != null) searchTerm = searchTerm.replaceAll("-(k8s|docker)$", "");

            System.out.println("[CONTAINERS] 实例=" + inst.getInstanceName() + " ns=" + ns + " searchTerm=" + searchTerm);

            String podName = null;
            if (searchTerm != null && !searchTerm.isBlank()) {
                podName = resolveK8sPodNameAllPhases(ns, searchTerm);
            }

            System.out.println("[CONTAINERS] 解析到 podName=" + podName);

            if (podName != null && !podName.isBlank()) {
                ProcessResult r = kubectl("get", "pod", podName, "-n", ns, "-o",
                        "jsonpath={range .spec.containers[*]}{.name}|{.image}{\"\\n\"}{end}");
                System.out.println("[CONTAINERS] kubectl get pod success=" + r.success + " output=" + r.output);
                if (r.success && r.output != null) {
                    for (String line : r.output.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("\\|", 2);
                        String name = parts[0];
                        String image = parts.length > 1 ? parts[1] : "";
                        Map<String, String> c = new LinkedHashMap<>();
                        c.put("name", name);
                        c.put("image", image);
                        c.put("role", inferContainerRole(name, image));
                        containers.add(c);
                    }
                }
            }
            if (containers.isEmpty()) {
                // 兜底：找不到容器时返回默认 backend，避免前端无按钮
                Map<String, String> c = new LinkedHashMap<>();
                c.put("name", searchTerm != null ? searchTerm : inst.getInstanceName());
                c.put("image", "");
                c.put("role", "backend");
                containers.add(c);
                result.put("warning", "未能获取容器列表，已使用默认值");
            }
        } else {
            // Docker：单容器，视为后台
            Map<String, String> c = new LinkedHashMap<>();
            c.put("name", inst.getInstanceName());
            c.put("image", (inst.getImageName() != null ? inst.getImageName() : "")
                    + ":" + (inst.getImageTag() != null ? inst.getImageTag() : ""));
            c.put("role", "backend");
            containers.add(c);
        }

        result.put("containers", containers);
        result.put("hasFrontend", containers.stream().anyMatch(c -> "frontend".equals(c.get("role"))));
        result.put("hasBackend", containers.stream().anyMatch(c -> "backend".equals(c.get("role"))));
        System.out.println("[CONTAINERS] 最终结果: containers=" + containers.size() + " hasFrontend=" + result.get("hasFrontend") + " hasBackend=" + result.get("hasBackend"));
        return result;
    }

    /** 根据容器名/镜像推断角色：front/nginx/web/ui/static → 前台，其余 → 后台 */
    private String inferContainerRole(String name, String image) {
        String lower = ((name != null ? name : "") + " " + (image != null ? image : "")).toLowerCase();
        if (lower.contains("front") || lower.contains("nginx") || lower.contains("web")
                || lower.contains("ui") || lower.contains("static")) {
            return "frontend";
        }
        return "backend";
    }

    /** 获取 K8s Pod 日志 */
    private void collectK8sLogs(ServiceInstance inst, int tail, String container, Map<String, Object> result) {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";

        // 搜索词：用 instanceName 去掉 -k8s/-docker 后缀（Pod 名是 proj-xxx-<rs-hash>-<pod-hash>）
        String searchTerm = inst.getInstanceName();
        if (searchTerm != null) {
            searchTerm = searchTerm.replaceAll("-(k8s|docker)$", "");
        }

        // 始终实时解析当前 Pod 名（存储的 k8sPodName 可能已过期——Pod 重建后名字会变）
        // 重试机制：重启/启动后 Pod 可能需要几秒才创建
        String podName = null;
        if (searchTerm != null && !searchTerm.isBlank()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                podName = resolveK8sPodNameAllPhases(ns, searchTerm);
                if (podName != null && !podName.isBlank()) break;
                if (attempt < 2) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        }

        // 实时解析失败 = 没有 Pod（可能 Deployment replicas=0 已停止，或 Pod 已被删除）
        if (podName == null || podName.isBlank()) {
            result.put("success", false);
            result.put("error", "未找到运行中的 Pod——实例可能已停止（Deployment replicas=0）。请先重启实例再查看日志。");
            result.put("logs", "");
            result.put("source", "namespace: " + ns + "，搜索: " + searchTerm);
            return;
        }

        // kubectl logs <pod> [-c container] -n <ns> --tail=<tail>
        java.util.List<String> logArgs = new java.util.ArrayList<>(java.util.List.of("logs", podName));
        if (container != null && !container.isBlank()) { logArgs.add("-c"); logArgs.add(container); }
        logArgs.add("-n"); logArgs.add(ns); logArgs.add("--tail=" + tail);
        ProcessResult r = kubectl(logArgs.toArray(new String[0]));
        result.put("source", "kubectl logs " + podName + (container != null && !container.isBlank() ? " -c " + container : "") + " -n " + ns);

        if (r.success) {
            String logs = r.output != null ? r.output.trim() : "";
            if (logs.isEmpty()) logs = "(暂无日志输出)";
            result.put("logs", logs);
            result.put("podName", podName);
            result.put("namespace", ns);
            // 不再覆盖 k8sPodName，保持为 projectCode（Deployment 名）
        } else {
            // Pod 可能已停止，尝试获取上一次运行的日志
            java.util.List<String> prevArgs = new java.util.ArrayList<>(java.util.List.of("logs", podName));
            if (container != null && !container.isBlank()) { prevArgs.add("-c"); prevArgs.add(container); }
            prevArgs.add("-n"); prevArgs.add(ns); prevArgs.add("--previous"); prevArgs.add("--tail=" + tail);
            ProcessResult prev = kubectl(prevArgs.toArray(new String[0]));
            if (prev.success && prev.output != null && !prev.output.isBlank()) {
                result.put("logs", "⚠️ Pod 当前无日志，以下是上一次运行的日志：\n\n" + prev.output);
                result.put("podName", podName);
                result.put("namespace", ns);
            } else {
                result.put("success", false);
                result.put("error", "获取 K8s 日志失败: " + (r.output != null ? r.output : "未知错误"));
                result.put("logs", "");
            }
        }
    }

    /** 获取 Docker 容器日志 */
    private void collectDockerLogs(ServiceInstance inst, int tail, Map<String, Object> result) {
        String containerId = resolveDockerContainerId(inst);

        if (containerId == null || containerId.isBlank()) {
            result.put("success", false);
            result.put("error", "未找到运行中的 Docker 容器");
            result.put("logs", "");
            return;
        }

        // docker logs --tail <tail> <containerId>
        ProcessResult r = docker("logs", "--tail", String.valueOf(tail), containerId);
        result.put("source", "docker logs --tail " + tail + " " + containerId.substring(0, Math.min(12, containerId.length())));

        if (r.success) {
            String logs = r.output != null ? r.output.trim() : "";
            if (logs.isEmpty()) logs = "(暂无日志输出)";
            result.put("logs", logs);
            result.put("containerId", containerId);
        } else {
            result.put("success", false);
            result.put("error", "获取 Docker 日志失败: " + (r.output != null ? r.output : "未知错误"));
            result.put("logs", "");
        }
    }

    /** 安全执行 kubectl 命令（ProcessBuilder 传参数组，避免 shell 引号解析问题） */
    private ProcessResult kubectl(String... args) {
        return runWithProcessBuilder("kubectl", args);
    }

    /** 安全执行 docker 命令（ProcessBuilder 传参数组，避免 cmd.exe 引号/特殊字符问题） */
    private ProcessResult docker(String... args) {
        return runWithProcessBuilder(dockerCommand, args);
    }

    /** 通用 ProcessBuilder 命令执行（避免 shell 引号解析） */
    private ProcessResult runWithProcessBuilder(String executable, String... args) {
        ProcessResult result = new ProcessResult();
        try {
            List<String> cmdList = new java.util.ArrayList<>();
            cmdList.add(executable);
            for (String a : args) cmdList.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append("\n");
            }
            boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); result.success = false; result.output = "timeout"; return result; }
            result.output = out.toString().trim();
            result.success = proc.exitValue() == 0;
        } catch (Exception e) {
            result.success = false;
            result.output = e.getMessage();
        }
        return result;
    }

    private Map<String, Object> exposeK8sToExternal(ServiceInstance inst) {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String svcName = resolveK8sServiceName(inst);
        int svcPort = inst.getPort() != null ? inst.getPort() : 8080;

        // 检查是否已有活跃的端口转发
        Process existingProcess = portForwardProcesses.get(inst.getId());
        if (existingProcess != null && existingProcess.isAlive()) {
            Integer fwdPort = portForwardPorts.get(inst.getId());
            if (fwdPort != null) {
                return Map.of("success", true, "alreadyForwarded", true,
                        "message", "端口转发已建立（本地端口 " + fwdPort + "）",
                        "externalUrl", String.format("http://localhost:%d", fwdPort),
                        "externalPort", fwdPort);
            }
        }
        // 清理死进程
        if (existingProcess != null) {
            portForwardProcesses.remove(inst.getId());
            portForwardPorts.remove(inst.getId());
        }

        // 检查 Service 是否存在
        ProcessResult existsR = kubectl("get", "svc", svcName, "-n", ns, "-o", "name");
        if (!existsR.success || existsR.output.contains("not found") || existsR.output.contains("NotFound")) {
            return Map.of("success", false, "error",
                    "K8s Service \"" + svcName + "\" 不存在于命名空间 \"" + ns + "\"，请确认部署已完成");
        }

        // ==================== 前置检查：确保有可用 Pod（Pod 可能 Succeeded/Completed 已退出） ====================
        // 列出 Service selector 匹配的所有 Pod，逐个检查 phase
        // 优先选择 Running 状态的 Pod；若无任何 Running，则启动 port-forward 也无法工作
        // 注意: 不用 jsonpath (jsonpath 解析器不允许 | 字符),改用 custom-columns + sed 解析
        ProcessResult podsR = kubectl("get", "pods", "-n", ns,
                "-l", "app=" + svcName.replace("-svc", ""),
                "--no-headers",
                "-o", "custom-columns=NAME:.metadata.name,PHASE:.status.phase");
        String runningPod = null;
        String firstPod = null;
        StringBuilder phases = new StringBuilder();
        if (podsR.success && podsR.output != null && !podsR.output.trim().isEmpty()) {
            for (String line : podsR.output.split("\\R")) {
                if (line.trim().isEmpty()) continue;
                // custom-columns 输出: "<name>\t<phase>" 或 "<name> <phase>" (用空格分隔)
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length < 2) continue;
                String podName = parts[0].trim();
                String phase = parts[1].trim();
                if (firstPod == null) firstPod = podName;
                phases.append(podName).append("=").append(phase).append(", ");
                if ("Running".equals(phase) && runningPod == null) {
                    runningPod = podName;
                }
            }
        }
        log.info("K8s Service {} 的 Pod 状态: {}", svcName, phases);
        if (runningPod == null) {
            // 没有 Running 状态的 Pod — 给出明确错误
            String detail = firstPod == null
                    ? "没有找到匹配 app=" + svcName.replace("-svc", "") + " 的 Pod"
                    : "所有 Pod 都未运行（当前状态: " + phases.toString().replaceAll(", $", "") + "）";
            return Map.of("success", false, "error",
                    "无法启动端口转发: " + detail + "。"
                            + "Pod 可能已 Succeeded/Completed（O 项目是 Job 类型/单次任务）"
                            + "或 CrashLoopBackOff。\n"
                            + "建议: 1) 在服务实例列表查看 Pod 实时状态; "
                            + "2) 若 O 项目是 Spring Boot 等长驻服务，deployment.yaml 的 restartPolicy 应为 Always; "
                            + "3) 若 Pod 已退出但 Service 还在，删除实例重建。");
        }

        // 查找空闲本地端口
        int freePort = findFreePort(30000, 32767);
        if (freePort == -1) {
            return Map.of("success", false, "error", "系统无法找到可用的本地端口（30000-32767 范围均已占用）");
        }

        // 启动 kubectl port-forward（后台进程）
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "kubectl", "port-forward",
                    "svc/" + svcName, "-n", ns,
                    freePort + ":" + svcPort
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 等待 1.5 秒让隧道建立
            Thread.sleep(1500);

            if (!process.isAlive()) {
                // 进程已退出，读取错误信息
                String errOut = new String(process.getInputStream().readAllBytes());
                return Map.of("success", false, "error",
                        "端口转发进程启动后立即退出: " + errOut.trim()
                                + "（Pod " + runningPod + " 当前 Running，但 port-forward 仍启动失败）");
            }

            // 记录转发进程和端口
            portForwardProcesses.put(inst.getId(), process);
            portForwardPorts.put(inst.getId(), freePort);

            log.info("K8s 端口转发已建立: svc/{}.{}:{} → localhost:{}  [instanceId={}]",
                    svcName, ns, svcPort, freePort, inst.getId());

            return Map.of("success", true, "alreadyForwarded", false,
                    "message", "本地隧道已建立！转发端口 " + freePort + "，可通过 localhost:" + freePort + " 访问",
                    "externalUrl", String.format("http://localhost:%d", freePort),
                    "externalPort", freePort);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("success", false, "error", "端口转发被中断");
        } catch (Exception e) {
            log.error("启动端口转发失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            return Map.of("success", false, "error", "启动端口转发失败: " + e.getMessage());
        }
    }

    private Map<String, Object> exposeDockerToExternal(ServiceInstance inst) {
        int containerPort = inst.getPort() != null ? inst.getPort() : 8080;
        String containerName = inst.getInstanceName();

        // 1. 尝试查找已有容器
        String containerId = resolveDockerContainerId(inst);

        // 2. 如果容器存在，检查是否已有端口映射
        if (containerId != null) {
            ProcessResult portCheck = docker("port", containerId, String.valueOf(containerPort));
            if (portCheck.success && !portCheck.output.isBlank()) {
                String mapping = portCheck.output.trim().split("\\R")[0];
                String mappedPort = mapping.contains(":") ? mapping.substring(mapping.lastIndexOf(":") + 1) : "";
                if (!mappedPort.isEmpty()) {
                    return Map.of("success", true, "alreadyExposed", true,
                            "message", "容器已映射端口到宿主机（端口 " + mappedPort + "）",
                            "externalUrl", String.format("http://localhost:%s", mappedPort),
                            "externalPort", Integer.parseInt(mappedPort));
                }
            }
        }

        // 3. 系统自动分配一个空闲端口（避免端口冲突）
        int freePort = findFreePort(30000, 32767);
        if (freePort == -1) {
            return Map.of("success", false, "error", "系统无法找到可用的宿主机端口（30000-32767 范围均已占用）");
        }

        // 4. 获取镜像名
        String imageName = inst.getImageName() + ":" + (inst.getImageTag() != null ? inst.getImageTag() : "latest");

        // 5. 如果容器已存在但无端口映射 → 停止旧容器并带端口重新启动
        if (containerId != null) {
            log.info("Docker 容器 [{}] 无端口映射，停止后重新启动并映射端口", containerName);
            docker("stop", containerId);
            docker("rm", containerId);
        }

        // 6. 启动新容器（带端口映射），使用 ProcessBuilder 避免 cmd.exe 引号问题
        List<String> runArgs = new java.util.ArrayList<>();
        runArgs.add("run");
        runArgs.add("-d");
        runArgs.add("--name");
        runArgs.add(containerName);
        runArgs.add("-p");
        runArgs.add(freePort + ":" + containerPort);

        // db_type 为 NULL 时默认 MySQL（大多数 Spring Boot 项目用 MySQL）
        // 仅显式声明 H2 时才跳过数据库环境变量注入
        Project project = inst.getProjectId() != null
                ? projectRepository.findById(inst.getProjectId()).orElse(null) : null;
        boolean useH2 = project == null || "H2".equalsIgnoreCase(project.getDbType());
        if (!useH2) {
            String targetDb = (inst.getDbName() != null && !inst.getDbName().isBlank())
                    ? inst.getDbName() : DatabaseProvisioningService.defaultDbName(
                            project.getCode() != null ? project.getCode() : "app", "0");
            String dbHost = project != null && project.getDbHost() != null ? project.getDbHost() : "host.docker.internal";
            int dbPort = project != null && project.getDbPort() != null ? project.getDbPort() : 3306;
            String dbUser = project != null && project.getDbUsername() != null ? project.getDbUsername() : "root";
            String dbPass = project != null && project.getDbPassword() != null ? project.getDbPassword() : "";
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_URL=jdbc:mysql://" + dbHost + ":" + dbPort + "/" + targetDb + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true");
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_USERNAME=" + dbUser);
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_PASSWORD=" + dbPass);
        }

        runArgs.add(imageName);
        ProcessResult runR = docker(runArgs.toArray(new String[0]));

        if (!runR.success) {
            return Map.of("success", false, "error",
                    "启动容器失败: " + runR.output);
        }

        // 7. 更新实例的 containerId
        String newContainerId = runR.output.trim();
        inst.setContainerId(newContainerId);
        inst.setStatus("RUNNING");
        inst.setHealthStatus("HEALTHY");
        inst.setLastHeartbeat(LocalDateTime.now());
        instanceRepository.save(inst);

        log.info("Docker 容器 [{}] 已启动，端口映射 {}:{} → 宿主机:{}", containerName, containerName, containerPort, freePort);

        return Map.of("success", true, "alreadyExposed", false,
                "message", "服务已暴露到外部，系统自动分配端口 " + freePort + "，可通过 localhost:" + freePort + " 访问",
                "externalUrl", String.format("http://localhost:%d", freePort),
                "externalPort", freePort);
    }

    /**
     * 在指定范围内查找一个空闲端口（系统自动分配，避免端口冲突）
     * 使用 Java ServerSocket 尝试绑定，能绑定说明端口空闲
     */
    private int findFreePort(int minPort, int maxPort) {
        // 优先让 OS 自动分配一个端口（最可靠）
        try (ServerSocket socket = new ServerSocket(0)) {
            int osPort = socket.getLocalPort();
            if (osPort >= minPort && osPort <= maxPort) {
                return osPort;
            }
        } catch (Exception e) {
            log.debug("OS 自动分配端口失败: {}", e.getMessage());
        }

        // 在指定范围内扫描
        for (int port = minPort; port <= maxPort; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (Exception e) {
                // 端口被占用，继续尝试下一个
            }
        }
        return -1;
    }

    // ==================== 实例操作：重启 / 停止 / 删除 ====================

    /**
     * 重启服务实例
     * - Docker: docker restart <containerId>
     * - K8s:    kubectl rollout restart deployment/<podName> -n <namespace>
     */
    public Map<String, Object> restartInstance(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        try {
            if ("K8S".equals(inst.getDeployType())) {
                return restartK8sInstance(inst);
            } else {
                return restartDockerInstance(inst);
            }
        } catch (Exception e) {
            log.error("重启实例失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            return Map.of("success", false, "error", "重启失败: " + e.getMessage());
        }
    }

    private Map<String, Object> restartDockerInstance(ServiceInstance inst) throws Exception {
        String containerId = resolveDockerContainerId(inst);
        if (containerId == null || containerId.isEmpty()) {
            return Map.of("success", false, "error", "未找到运行中的容器，请确认容器已启动");
        }
        String cmd = dockerCommand + " restart " + containerId;
        ProcessResult r = runCommand(cmd);
        if (r.success) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "Docker 容器已重启", "output", r.output);
        }
        return Map.of("success", false, "error", "重启失败", "output", r.output);
    }

    private Map<String, Object> restartK8sInstance(ServiceInstance inst) throws Exception {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String depName = resolveK8sDeploymentName(inst);
        if (depName == null || depName.isEmpty()) {
            return Map.of("success", false, "error", "无法解析 Deployment 名称");
        }
        ProcessResult r = kubectl("rollout", "restart", "deployment/" + depName, "-n", ns);
        if (r.success) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "K8s Deployment 正在滚动重启", "output", r.output, "deployment", depName);
        }
        return Map.of("success", false, "error", "重启失败", "output", r.output);
    }

    /**
     * 启动服务实例（从 STOPPED 状态恢复）
     * - Docker: docker start <containerId>
     * - K8s:    kubectl scale deployment/<depName> --replicas=1 -n <namespace>
     */
    public Map<String, Object> startInstance(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        try {
            if ("K8S".equals(inst.getDeployType())) {
                return startK8sInstance(inst);
            } else {
                return startDockerInstance(inst);
            }
        } catch (Exception e) {
            log.error("启动实例失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            return Map.of("success", false, "error", "启动失败: " + e.getMessage());
        }
    }

    private Map<String, Object> startK8sInstance(ServiceInstance inst) throws Exception {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String depName = resolveK8sDeploymentName(inst);
        if (depName == null || depName.isEmpty()) {
            return Map.of("success", false, "error", "无法解析 Deployment 名称");
        }
        ProcessResult r = kubectl("scale", "deployment/" + depName, "--replicas=1", "-n", ns);
        if (r.success) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "K8s Deployment 已扩容到 1（Pod 正在启动）", "output", r.output, "deployment", depName);
        }
        return Map.of("success", false, "error", "启动失败", "output", r.output);
    }

    private Map<String, Object> startDockerInstance(ServiceInstance inst) throws Exception {
        String containerId = resolveDockerContainerId(inst);
        if (containerId == null || containerId.isEmpty()) {
            return Map.of("success", false, "error", "未找到关联的容器（可能已被删除，请重新部署）");
        }
        ProcessResult r = docker("start", containerId);
        if (r.success) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "Docker 容器已启动", "output", r.output);
        }
        return Map.of("success", false, "error", "启动失败", "output", r.output);
    }

    /**
     * 停止服务实例
     * - Docker: docker stop <containerId>
     * - K8s:    kubectl scale deployment/<podName> --replicas=0 -n <namespace>
     */
    public Map<String, Object> stopInstance(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        try {
            if ("K8S".equals(inst.getDeployType())) {
                return stopK8sInstance(inst);
            } else {
                return stopDockerInstance(inst);
            }
        } catch (Exception e) {
            log.error("停止实例失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            return Map.of("success", false, "error", "停止失败: " + e.getMessage());
        }
    }

    private Map<String, Object> stopDockerInstance(ServiceInstance inst) throws Exception {
        String containerId = resolveDockerContainerId(inst);
        if (containerId == null || containerId.isEmpty()) {
            return Map.of("success", false, "error", "未找到运行中的容器");
        }
        String cmd = dockerCommand + " stop " + containerId;
        ProcessResult r = runCommand(cmd);
        if (r.success) {
            inst.setStatus("STOPPED");
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "Docker 容器已停止", "output", r.output);
        }
        return Map.of("success", false, "error", "停止失败", "output", r.output);
    }

    private Map<String, Object> stopK8sInstance(ServiceInstance inst) throws Exception {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String depName = resolveK8sDeploymentName(inst);
        if (depName == null || depName.isEmpty()) {
            return Map.of("success", false, "error", "无法解析 Deployment 名称");
        }
        // 停止端口转发（如果有）
        stopForward(inst.getId());
        ProcessResult r = kubectl("scale", "deployment/" + depName, "--replicas=0", "-n", ns);
        if (r.success) {
            inst.setStatus("STOPPED");
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "K8s Deployment 已缩容到 0（所有 Pod 已停止）", "output", r.output, "deployment", depName);
        }
        return Map.of("success", false, "error", "停止失败", "output", r.output);
    }

    /**
     * 删除服务实例（同时停止容器/Pod）
     * - Docker: docker stop <id> && docker rm <id> → 删除 DB 记录
     * - K8s:    kubectl delete deployment/<name> -n <ns> → 删除 DB 记录
     */
    public Map<String, Object> deleteInstanceAndCleanup(Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) return Map.of("success", false, "error", "实例不存在");

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            // 先停止端口转发（如果有）
            if ("K8S".equals(inst.getDeployType())) {
                stopForward(id);
            }

            String deployType = inst.getDeployType();
            if ("K8S".equals(deployType)) {
                result = deleteK8sResources(inst);
            } else {
                result = deleteDockerResources(inst);
            }
        } catch (Exception e) {
            log.warn("删除实例时清理资源失败 [{}]: {}", inst.getInstanceName(), e.getMessage());
            result.put("cleanupWarning", "资源清理异常: " + e.getMessage());
        }

        // 无论清理是否成功，都删除 DB 记录
        instanceRepository.deleteById(id);
        result.put("deleted", true);
        result.put("message", result.getOrDefault("message", "实例记录已删除").toString());
        return result;
    }

    private Map<String, Object> deleteDockerResources(ServiceInstance inst) throws Exception {
        String containerId = resolveDockerContainerId(inst);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (containerId != null && !containerId.isEmpty()) {
            // 停止容器（忽略已停止的错误）
            runCommand(dockerCommand + " stop " + containerId + " 2>/dev/null");
            // 删除容器
            ProcessResult r = runCommand(dockerCommand + " rm " + containerId);
            result.put("message", r.success ? "Docker 容器已删除" : "Docker 容器删除警告: " + r.errorOutput);
            result.put("output", r.output);
        } else {
            result.put("message", "未找到关联的 Docker 容器（可能未运行）");
        }
        return result;
    }

    private Map<String, Object> deleteK8sResources(ServiceInstance inst) throws Exception {
        String ns = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : "devops";
        String depName = resolveK8sDeploymentName(inst);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (depName == null || depName.isEmpty()) {
            result.put("message", "无法解析 Deployment 名称，仅删除数据库记录");
            return result;
        }
        // 删除 Deployment
        ProcessResult r = kubectl("delete", "deployment", depName, "-n", ns, "--ignore-not-found=true");
        // 同时删除关联的 Service（约定名 + 自适应查找）
        String svcName = resolveK8sServiceName(inst);
        kubectl("delete", "svc", svcName, "-n", ns, "--ignore-not-found=true");
        result.put("message", "K8s Deployment + Service 已删除: " + depName);
        result.put("output", r.output);
        return result;
    }

    /**
     * 根据实例信息解析实际运行中的 Docker 容器 ID
     * 优先使用记录的 containerId，否则通过镜像名查找（排除 K8s 容器）
     */
    private String resolveDockerContainerId(ServiceInstance inst) {
        // 优先使用记录的 containerId
        if (inst.getContainerId() != null && !inst.getContainerId().isBlank()) {
            return inst.getContainerId().trim();
        }
        // 通过镜像名查找，排除 K8s 管理的容器（名称以 k8s_ 开头）
        if (inst.getImageName() != null && !inst.getImageName().isBlank()) {
            String image = inst.getImageName() + ":" + (inst.getImageTag() != null ? inst.getImageTag() : "latest");
            try {
                ProcessResult r = runCommand(dockerCommand + " ps --format \"{{.ID}} {{.Names}}\" --filter ancestor=" + image);
                if (r.success && r.output != null && !r.output.isBlank()) {
                    for (String line : r.output.split("\\R")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        String[] parts = trimmed.split(" ", 2);
                        if (parts.length < 2) continue;
                        // 跳过 K8s 管理的容器
                        if (parts[1].startsWith("k8s_")) continue;
                        return parts[0]; // 返回第一个独立 Docker 容器
                    }
                }
            } catch (Exception e) {
                log.debug("通过镜像查找容器失败: {}", e.getMessage());
            }
        }
        return null;
    }

    // ==================== 命令执行工具 ====================

    private static class ProcessResult {
        boolean success;
        String output = "";
        String errorOutput = "";
    }

    private ProcessResult runCommand(String command) {
        ProcessResult result = new ProcessResult();
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            // 读取 stdout
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append("\n");
            }
            // 读取 stderr
            StringBuilder err = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) err.append(line).append("\n");
            }

            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            result.success = proc.exitValue() == 0;
            result.output = out.toString().trim();
            result.errorOutput = err.toString().trim();
        } catch (Exception e) {
            result.success = false;
            result.errorOutput = e.getMessage();
        }
        return result;
    }

    /**
     * 获取本机所有 Docker 容器列表（docker ps -a），含 K8s 管理的容器
     */
    public List<Map<String, Object>> getDockerContainers() {
        try {
            // 先尝试 --format json（Docker 24+）
            ProcessResult r = runCommand(dockerCommand + " ps -a --format json");
            if (r.success && r.output != null && !r.output.isBlank()) {
                return parseDockerPsJson(r.output);
            }
            // 回退：使用管道分隔符解析
            r = runCommand(dockerCommand + " ps -a --format \"{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}\\t{{.Status}}\\t{{.Ports}}\\t{{.CreatedAt}}\\t{{.RunningFor}}\"");
            if (r.success && r.output != null && !r.output.isBlank()) {
                return parseDockerPsTab(r.output);
            }
            return List.of();
        } catch (Exception e) {
            log.warn("获取 Docker 容器列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> parseDockerPsJson(String output) {
        java.util.List<Map<String, Object>> containers = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = mapper.readValue(trimmed, Map.class);
                String name = String.valueOf(raw.getOrDefault("Names", ""));
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", raw.getOrDefault("ID", ""));
                info.put("name", name);
                info.put("image", raw.getOrDefault("Image", ""));
                info.put("state", raw.getOrDefault("State", ""));
                info.put("status", raw.getOrDefault("Status", ""));
                info.put("ports", raw.getOrDefault("Ports", ""));
                info.put("createdAt", raw.getOrDefault("CreatedAt", ""));
                info.put("runningFor", raw.getOrDefault("RunningFor", ""));
                containers.add(info);
            } catch (Exception e) {
                log.debug("解析 Docker 容器 JSON 失败: {}", e.getMessage());
            }
        }
        return containers;
    }

    private List<Map<String, Object>> parseDockerPsTab(String output) {
        java.util.List<Map<String, Object>> containers = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\t", -1);
            if (parts.length < 8) continue;
            String containerName = parts[1].trim();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", parts[0].trim());
            info.put("name", containerName);
            info.put("image", parts[2].trim());
            info.put("state", parts[3].trim());
            info.put("status", parts[4].trim());
            info.put("ports", parts[5].trim());
            info.put("createdAt", parts[6].trim());
            info.put("runningFor", parts[7].trim());
            containers.add(info);
        }
        return containers;
    }
}
