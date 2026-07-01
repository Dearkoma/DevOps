package com.devops.platform.service;

import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.ServiceInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceMonitorService {

    private final ServiceInstanceRepository instanceRepository;

    @Value("${devops.pipeline.docker.command:docker}")
    private String dockerCommand;

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

        // 只要找到容器就更新心跳（防止 docker stats 失败导致状态跳 UNKNOWN）
        if (containerId != null && !containerId.isEmpty()) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
        } else {
            return;
        }

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

        // 采集成功 → 更新 Pod 名（完整名）、标为运行中
        inst.setK8sPodName(podName);
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
        String depName = inst.getInstanceName();
        if (depName != null) {
            depName = depName.replaceAll("-(k8s|docker)$", "");
        }
        if (depName == null || depName.isBlank()) {
            String pod = inst.getK8sPodName();
            if (pod != null && !pod.isBlank()) {
                String[] parts = pod.split("-");
                if (parts.length >= 3) {
                    StringBuilder sb = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length - 2; i++) sb.append("-").append(parts[i]);
                    depName = sb.toString();
                } else {
                    depName = pod;
                }
            }
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
        // 从 instanceName 推导 Deployment 名：去掉 -k8s/-docker 后缀 + "-"
        String depName = instanceName;
        if (depName != null) {
            depName = depName.replaceAll("-(k8s|docker)$", "");
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
    private String resolveK8sServiceName(ServiceInstance inst) {
        // 实例名格式: proj-xxx-k8s → Service 名: proj-xxx-svc
        String base = inst.getInstanceName();
        if (base.endsWith("-k8s")) base = base.substring(0, base.length() - 4);
        else if (base.endsWith("-docker")) base = base.substring(0, base.length() - 7);
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

        // 检查当前 Service 状态（是否已有 NodePort）
        String cmd = String.format("kubectl get svc %s -n %s -o json 2>&1", svcName, ns);
        ProcessResult r = runCommand(cmd);

        if (r.success && !r.output.contains("NotFound") && !r.output.contains("Error")) {
            try {
                // 简单解析 type 和 nodePort
                if (r.output.contains("\"NodePort\"") || r.output.contains("\"LoadBalancer\"")) {
                    // 已暴露到外部
                    info.put("externalExposed", true);

                    // 尝试获取 nodePort
                    int nodePort = extractNodePort(r.output);
                    if (nodePort > 0) {
                        String externalUrl = String.format("http://localhost:%d", nodePort);
                        info.put("externalUrl", externalUrl);
                        info.put("externalPort", nodePort);
                    info.put("externalLabel", "已暴露 — NodePort " + nodePort + "（K8s 自动分配）");
                    }
                } else {
                    info.put("externalExposed", false);
                    info.put("externalLabel", "未暴露（仅集群内部可访问）");
                }
            } catch (Exception e) {
                info.put("externalExposed", false);
                info.put("externalLabel", "状态获取失败");
            }
        } else {
            info.put("externalExposed", false);
            info.put("externalLabel", "Service 未找到");
        }
    }

    private int extractNodePort(String jsonOutput) {
        // 从 kubectl 输出中提取 nodePort 数字
        int idx = jsonOutput.indexOf("\"nodePort\"");
        if (idx < 0) return -1;
        // nodePort 后跟 : 一个数字
        int colon = jsonOutput.indexOf(":", idx);
        if (colon < 0) return -1;
        String sub = jsonOutput.substring(colon + 1).trim();
        // 跳过空格和引号
        StringBuilder num = new StringBuilder();
        for (char c : sub.toCharArray()) {
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        try { return Integer.parseInt(num.toString()); } catch (NumberFormatException e) { return -1; }
    }

    private void buildDockerAccessInfo(ServiceInstance inst, Map<String, Object> info) {
        String containerId = resolveDockerContainerId(inst);
        int port = inst.getPort() != null ? inst.getPort() : 8080;

        if (containerId == null) {
            // 容器未运行，但镜像已构建
            info.put("internalUrl", "容器未运行（镜像: " + (inst.getImageName() != null ? inst.getImageName() : "未知") + "）");
            info.put("internalLabel", "点击「一键部署到外部」可自动启动容器并分配端口");
            info.put("externalExposed", false);
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
                        info.put("externalExposed", true);
                        info.put("externalUrl", String.format("http://localhost:%d", extPort));
                        info.put("externalPort", extPort);
                        info.put("externalLabel", "已映射到宿主机端口（系统自动分配）");
                        return;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        info.put("externalExposed", false);
        info.put("externalLabel", "未映射端口 — 点击「一键部署到外部」系统自动分配端口");
    }

    /**
     * 一键部署到外部（暴露服务为外部可访问）
     * - K8s: 将 ClusterIP Service 改为 NodePort 类型，K8s 自动分配端口
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
            // 顺便更新存储的 Pod 名
            if (!podName.equals(inst.getK8sPodName())) {
                inst.setK8sPodName(podName);
                instanceRepository.save(inst);
            }
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

        // 先检查 Service 是否存在
        ProcessResult existsR = kubectl("get", "svc", svcName, "-n", ns, "-o", "name");
        if (!existsR.success || existsR.output.contains("not found") || existsR.output.contains("NotFound")) {
            return Map.of("success", false, "error",
                    "K8s Service \"" + svcName + "\" 不存在于命名空间 \"" + ns + "\"，请确认部署已完成");
        }

        // 检查是否已经是 NodePort（用 -o jsonpath 直接取 .spec.type）
        ProcessResult check = kubectl("get", "svc", svcName, "-n", ns, "-o", "jsonpath={.spec.type}");
        if (check.success && "NodePort".equals(check.output.trim())) {
            ProcessResult portR = kubectl("get", "svc", svcName, "-n", ns, "-o", "jsonpath={.spec.ports[0].nodePort}");
            int nodePort = -1;
            try { nodePort = Integer.parseInt(portR.output.trim()); }
            catch (NumberFormatException e) { /* ignore */ }
            return Map.of("success", true, "alreadyExposed", true,
                    "message", "服务已暴露到外部（K8s 自动分配端口 " + nodePort + "）",
                    "externalUrl", String.format("http://localhost:%d", nodePort),
                    "nodePort", nodePort);
        }

        // 将 ClusterIP 改为 NodePort
        //   写 JSON patch 到临时文件，用 --patch-file 传递，彻底避免命令行引号问题
        java.nio.file.Path patchFile = null;
        try {
            patchFile = java.nio.file.Files.createTempFile("k8s-patch-", ".json");
            java.nio.file.Files.writeString(patchFile, "{\"spec\":{\"type\":\"NodePort\"}}");
            ProcessResult patchR = kubectl("patch", "svc", svcName, "-n", ns,
                    "--type", "merge", "--patch-file", patchFile.toString());
            if (!patchR.success) {
                return Map.of("success", false, "error",
                        "修改 Service 类型失败: " + patchR.output, "svcName", svcName);
            }
        } catch (Exception e) {
            return Map.of("success", false, "error",
                    "写入 patch 文件失败: " + e.getMessage(), "svcName", svcName);
        } finally {
            if (patchFile != null) {
                try { java.nio.file.Files.deleteIfExists(patchFile); } catch (Exception ignored) {}
            }
        }

        // 获取分配的 NodePort
        ProcessResult portR = kubectl("get", "svc", svcName, "-n", ns, "-o", "jsonpath={.spec.ports[0].nodePort}");
        int nodePort = -1;
        try { nodePort = Integer.parseInt(portR.output.trim()); }
        catch (NumberFormatException e) { /* ignore */ }

        return Map.of("success", true, "alreadyExposed", false,
                "message", "服务已暴露到外部，K8s 自动分配端口 " + nodePort + "，可通过 localhost:" + nodePort + " 访问",
                "externalUrl", String.format("http://localhost:%d", nodePort),
                "nodePort", nodePort);
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

        // Java 项目注入 MySQL 环境变量（使用实例独立的数据库名）
        if (inst.getDeployType() != null && !"Node.js".equalsIgnoreCase(inst.getDeployType())) {
            String targetDb = (inst.getDbName() != null && !inst.getDbName().isBlank())
                    ? inst.getDbName() : "devops_platform";
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/" + targetDb + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true");
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_USERNAME=root");
            runArgs.add("-e");
            runArgs.add("SPRING_DATASOURCE_PASSWORD=Dearkoma.962464");
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
        ProcessResult r = kubectl("delete", "deployment", depName, "-n", ns, "--ignore-not-found=true");
        result.put("message", "K8s Deployment 已删除: " + depName);
        result.put("output", r.output);
        return result;
    }

    /**
     * 根据实例信息解析实际运行中的 Docker 容器 ID
     * 优先使用记录的 containerId，否则通过镜像名查找
     */
    private String resolveDockerContainerId(ServiceInstance inst) {
        // 优先使用记录的 containerId
        if (inst.getContainerId() != null && !inst.getContainerId().isBlank()) {
            return inst.getContainerId().trim();
        }
        // 通过镜像名查找
        if (inst.getImageName() != null && !inst.getImageName().isBlank()) {
            String image = inst.getImageName() + ":" + (inst.getImageTag() != null ? inst.getImageTag() : "latest");
            try {
                ProcessResult r = runCommand(dockerCommand + " ps -q --filter ancestor=" + image);
                if (r.success && r.output != null && !r.output.isBlank()) {
                    return r.output.trim().split("\\R")[0]; // 取第一个匹配的容器
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
}
