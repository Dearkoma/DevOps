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
            if (inst.getLastHeartbeat() != null &&
                    inst.getLastHeartbeat().plusMinutes(2).isBefore(LocalDateTime.now())) {
                inst.setHealthStatus("UNKNOWN");
                inst.setStatus("UNKNOWN");
                instanceRepository.save(inst);
            }
        }
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
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
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
}
