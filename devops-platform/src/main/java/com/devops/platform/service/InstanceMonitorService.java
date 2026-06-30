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
        if (containerId == null || containerId.isEmpty()) return;

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

            // 采集成功 → 标为运行中
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
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
        if (podName == null || podName.isEmpty()) return;

        String cmd = String.format("kubectl top pod %s -n %s --no-headers", podName, ns);
        ProcessResult r = runCommand(cmd);
        if (!r.success || r.output.isBlank()) return;

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
        String podName = inst.getK8sPodName();
        if (podName == null || podName.isEmpty()) {
            return Map.of("success", false, "error", "K8s Pod 名称缺失");
        }
        String cmd = String.format("kubectl rollout restart deployment/%s -n %s", podName, ns);
        ProcessResult r = runCommand(cmd);
        if (r.success) {
            inst.setStatus("RUNNING");
            inst.setHealthStatus("HEALTHY");
            inst.setLastHeartbeat(LocalDateTime.now());
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "K8s Deployment 正在滚动重启", "output", r.output);
        }
        return Map.of("success", false, "error", "重启失败", "output", r.errorOutput);
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
        String podName = inst.getK8sPodName();
        if (podName == null || podName.isEmpty()) {
            return Map.of("success", false, "error", "K8s Pod 名称缺失");
        }
        String cmd = String.format("kubectl scale deployment/%s --replicas=0 -n %s", podName, ns);
        ProcessResult r = runCommand(cmd);
        if (r.success) {
            inst.setStatus("STOPPED");
            instanceRepository.save(inst);
            return Map.of("success", true, "message", "K8s Deployment 已缩容到 0（所有 Pod 已停止）", "output", r.output);
        }
        return Map.of("success", false, "error", "停止失败", "output", r.errorOutput);
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
        String podName = inst.getK8sPodName();
        if (podName == null || podName.isEmpty()) {
            return Map.of("message", "K8s Pod 名称缺失，仅删除数据库记录");
        }
        String cmd = String.format("kubectl delete deployment %s -n %s --ignore-not-found=true", podName, ns);
        ProcessResult r = runCommand(cmd);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("message", "K8s Deployment 已删除: " + podName);
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
