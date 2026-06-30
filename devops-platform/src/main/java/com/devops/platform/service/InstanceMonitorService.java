package com.devops.platform.service;

import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.ServiceInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceMonitorService {

    private final ServiceInstanceRepository instanceRepository;
    private final K8sClientService k8sClientService;

    @Value("${devops.pipeline.docker.command:docker}")
    private String dockerCommand;

    @Value("${devops.k8s.namespace:devops}")
    private String k8sNamespace;

    /** 活跃的 port-forward 进程: instanceId -> { process, localPort, namespace, svcName } */
    private final ConcurrentHashMap<Long, Map<String, Object>> activePortForwards = new ConcurrentHashMap<>();

    // ============ 启动时刷新 ============

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        log.info("启动时刷新服务实例状态...");
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {} // 等 Docker/K8s 初始化
            List<ServiceInstance> instances = instanceRepository.findAll();
            if (instances.isEmpty()) {
                log.info("没有需要刷新的服务实例");
                return;
            }
            int refreshed = 0;
            for (ServiceInstance inst : instances) {
                try {
                    boolean changed = refreshInstanceStatus(inst);
                    if (changed) refreshed++;
                } catch (Exception e) {
                    log.debug("刷新实例 {} (id={}) 状态失败: {}", inst.getInstanceName(), inst.getId(), e.getMessage());
                }
            }
            log.info("启动刷新完成: {}/{} 个实例状态已更新", refreshed, instances.size());
        }, "instance-refresh-startup").start();
    }

    /**
     * 主动检查单个实例的实际状态（Docker inspect / K8s API）
     * 返回 true 表示状态有变化并已持久化
     */
    private boolean refreshInstanceStatus(ServiceInstance inst) {
        boolean changed = false;

        if ("DOCKER".equals(inst.getDeployType())) {
            Map<String, String> info = inspectDockerContainer(inst);
            if (info != null) {
                String containerId = info.get("containerId");
                String status = info.get("status");

                if (containerId != null && !containerId.isEmpty() && !containerId.equals(inst.getContainerId())) {
                    inst.setContainerId(containerId);
                    changed = true;
                }

                String newStatus = mapDockerStatus(status);
                if (!newStatus.equals(inst.getStatus())) {
                    inst.setStatus(newStatus);
                    inst.setHealthStatus("RUNNING".equals(newStatus) ? "HEALTHY" : "UNKNOWN");
                    changed = true;
                }

                // 刷新心跳
                inst.setLastHeartbeat(LocalDateTime.now());
                changed = true;
            }
        } else if ("K8S".equals(inst.getDeployType())) {
            Map<String, String> podInfo = findK8sPod(inst);
            if (podInfo != null) {
                String podStatus = podInfo.get("status");
                String newStatus = mapK8sStatus(podStatus);
                if (!newStatus.equals(inst.getStatus())) {
                    inst.setStatus(newStatus);
                    inst.setHealthStatus("RUNNING".equals(newStatus) ? "HEALTHY" : "UNKNOWN");
                    changed = true;
                }
                inst.setLastHeartbeat(LocalDateTime.now());
                changed = true;
            }
        }

        if (changed) {
            try {
                instanceRepository.save(inst);
            } catch (Exception e) {
                log.warn("保存实例状态失败: {}", e.getMessage());
            }
        }
        return changed;
    }

    // ============ 停止 / 启动 ============

    /**
     * 停止服务实例
     * @return 操作结果 Map，含 success / message
     */
    public Map<String, Object> stopInstance(Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) {
            result.put("success", false);
            result.put("message", "实例不存在");
            return result;
        }

        if ("DOCKER".equals(inst.getDeployType())) {
            String containerId = resolveDockerContainerId(inst);
            if (containerId == null || containerId.isEmpty()) {
                result.put("success", false);
                result.put("message", "未找到运行中的 Docker 容器（镜像: " + inst.getImageName() + ":" + inst.getImageTag() + "）");
                return result;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(dockerCommand, "stop", containerId);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code == 0) {
                    inst.setStatus("STOPPED");
                    inst.setLastHeartbeat(LocalDateTime.now());
                    instanceRepository.save(inst);
                    result.put("success", true);
                    result.put("message", "Docker 容器已停止: " + containerId.substring(0, Math.min(12, containerId.length())));
                } else {
                    result.put("success", false);
                    result.put("message", "停止容器失败: " + output.trim());
                }
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "执行 docker stop 失败: " + e.getMessage());
            }
        } else if ("K8S".equals(inst.getDeployType())) {
            String namespace = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : k8sNamespace;
            String deploymentName = inst.getK8sPodName() != null ? inst.getK8sPodName() : inst.getInstanceName().replace("-k8s", "");
            Map<String, Object> scaleResult = k8sClientService.scaleDeployment(deploymentName, namespace, 0);
            if (Boolean.TRUE.equals(scaleResult.get("success"))) {
                inst.setStatus("STOPPED");
                inst.setLastHeartbeat(LocalDateTime.now());
                instanceRepository.save(inst);
                result.put("success", true);
                result.put("message", "K8s Deployment 已缩容至 0: " + deploymentName);
            } else {
                result.put("success", false);
                result.put("message", "K8s 缩容失败: " + scaleResult.getOrDefault("output", "未知错误"));
            }
        } else {
            result.put("success", false);
            result.put("message", "不支持的部署类型: " + inst.getDeployType());
        }
        return result;
    }

    /**
     * 启动服务实例
     */
    public Map<String, Object> startInstance(Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) {
            result.put("success", false);
            result.put("message", "实例不存在");
            return result;
        }

        if ("DOCKER".equals(inst.getDeployType())) {
            String containerId = resolveDockerContainerId(inst);
            if (containerId == null || containerId.isEmpty()) {
                result.put("success", false);
                result.put("message", "未找到容器（镜像: " + inst.getImageName() + ":" + inst.getImageTag() + "），请重新构建部署");
                return result;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(dockerCommand, "start", containerId);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code == 0) {
                    inst.setStatus("RUNNING");
                    inst.setHealthStatus("HEALTHY");
                    inst.setLastHeartbeat(LocalDateTime.now());
                    instanceRepository.save(inst);
                    result.put("success", true);
                    result.put("message", "Docker 容器已启动: " + containerId.substring(0, Math.min(12, containerId.length())));
                } else {
                    result.put("success", false);
                    result.put("message", "启动容器失败: " + output.trim());
                }
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "执行 docker start 失败: " + e.getMessage());
            }
        } else if ("K8S".equals(inst.getDeployType())) {
            String namespace = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : k8sNamespace;
            String deploymentName = inst.getK8sPodName() != null ? inst.getK8sPodName() : inst.getInstanceName().replace("-k8s", "");
            Map<String, Object> scaleResult = k8sClientService.scaleDeployment(deploymentName, namespace, 1);
            if (Boolean.TRUE.equals(scaleResult.get("success"))) {
                inst.setStatus("RUNNING");
                inst.setHealthStatus("HEALTHY");
                inst.setLastHeartbeat(LocalDateTime.now());
                instanceRepository.save(inst);
                result.put("success", true);
                result.put("message", "K8s Deployment 已扩容至 1: " + deploymentName);
            } else {
                result.put("success", false);
                result.put("message", "K8s 扩容失败: " + scaleResult.getOrDefault("output", "未知错误"));
            }
        } else {
            result.put("success", false);
            result.put("message", "不支持的部署类型: " + inst.getDeployType());
        }
        return result;
    }

    // ============ 辅助方法 ============

    /**
     * 获取实例的访问信息（端口映射、访问 URL）
     * Docker: docker port 获取端口映射
     * K8s: kubectl get svc 获取 Service 端口
     */
    public Map<String, Object> getAccessInfo(Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) {
            result.put("success", false);
            result.put("message", "实例不存在");
            return result;
        }

        result.put("instanceName", inst.getInstanceName());
        result.put("deployType", inst.getDeployType());
        result.put("status", inst.getStatus());
        result.put("imageName", inst.getImageName());
        result.put("imageTag", inst.getImageTag() != null ? inst.getImageTag() : "latest");
        result.put("host", inst.getHost());
        result.put("port", inst.getPort());
        result.put("healthCheckUrl", inst.getHealthCheckUrl());

        List<Map<String, String>> ports = new ArrayList<>();
        List<String> internalUrls = new ArrayList<>();  // 集群内部访问
        List<String> externalUrls = new ArrayList<>();  // 外部访问（宿主机 / NodePort / port-forward）

        if ("DOCKER".equals(inst.getDeployType())) {
            String containerId = resolveDockerContainerId(inst);
            String containerName = inst.getInstanceName();
            if (containerId != null && !containerId.isEmpty()) {
                // 1) docker port: 获取端口映射 → 外部访问
                try {
                    ProcessBuilder pb = new ProcessBuilder(dockerCommand, "port", containerId);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0) {
                        String output = new String(p.getInputStream().readAllBytes()).trim();
                        if (!output.isEmpty()) {
                            for (String line : output.split("\\R")) {
                                line = line.trim();
                                if (line.isEmpty()) continue;
                                // 格式: "8080/tcp -> 0.0.0.0:32768" 或 "8080/tcp -> :::32768"
                                String[] parts = line.split("->");
                                if (parts.length >= 2) {
                                    String containerPort = parts[0].trim();
                                    String hostBinding = parts[1].trim();
                                    Map<String, String> mapping = new LinkedHashMap<>();
                                    mapping.put("containerPort", containerPort);
                                    mapping.put("hostBinding", hostBinding);
                                    ports.add(mapping);

                                    // 外部 URL: 通过宿主机映射端口访问
                                    String[] addrParts = hostBinding.split(":");
                                    if (addrParts.length >= 2) {
                                        String host = addrParts[0].isEmpty() || "0.0.0.0".equals(addrParts[0])
                                                ? "localhost" : addrParts[0];
                                        if (host.startsWith("[")) host = "localhost"; // IPv6
                                        String hPort = addrParts[addrParts.length - 1];
                                        String proto = containerPort.contains("443") ? "https" : "http";
                                        externalUrls.add(proto + "://" + host + ":" + hPort);
                                    }
                                }
                            }
                        }
                    } else {
                        if (!finished) p.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.debug("docker port 查询失败 ({}): {}", inst.getInstanceName(), e.getMessage());
                }

                // 2) docker inspect: 获取容器内部 IP → 集群内部访问
                try {
                    ProcessBuilder pb = new ProcessBuilder(dockerCommand, "inspect",
                            "--format", "{{.Name}}|{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                            containerId);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0) {
                        String line = new String(p.getInputStream().readAllBytes()).trim();
                        if (!line.isEmpty()) {
                            String[] inspectParts = line.split("\\|");
                            if (inspectParts.length >= 2 && !inspectParts[1].isEmpty()) {
                                containerName = inspectParts[0].replace("/", "");
                                String containerIP = inspectParts[1];
                                // 内部 URL: Docker 网络内部 IP
                                if (inst.getPort() != null) {
                                    String proto = inst.getPort() == 443 ? "https" : "http";
                                    internalUrls.add(proto + "://" + containerIP + ":" + inst.getPort());
                                }
                            }
                            if (inspectParts.length >= 1) {
                                containerName = inspectParts[0].replace("/", "");
                            }
                        }
                    } else {
                        if (!finished) p.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.debug("docker inspect 查询失败 ({}): {}", inst.getInstanceName(), e.getMessage());
                }
            }

            // 兜底
            if (ports.isEmpty() && inst.getPort() != null) {
                String host = inst.getHost() != null ? inst.getHost() : "localhost";
                Map<String, String> fallback = new LinkedHashMap<>();
                fallback.put("containerPort", inst.getPort() + "/tcp");
                fallback.put("hostBinding", host + ":" + inst.getPort());
                ports.add(fallback);
                externalUrls.add("http://" + host + ":" + inst.getPort());
            }
            // 若没有内部地址，用容器名构造
            if (internalUrls.isEmpty() && inst.getPort() != null) {
                String proto = inst.getPort() == 443 ? "https" : "http";
                internalUrls.add(proto + "://" + containerName + ":" + inst.getPort());
            }

            result.put("containerName", containerName);
        } else if ("K8S".equals(inst.getDeployType())) {
            String namespace = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : k8sNamespace;
            String deploymentName = inst.getK8sPodName() != null ? inst.getK8sPodName()
                    : inst.getInstanceName().replace("-k8s", "");
            String nodeIP = null;

            // 先获取节点 IP（用于 NodePort 外部访问）
            try {
                ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "nodes", "-o",
                        "jsonpath={.items[0].status.addresses[?(@.type==\"InternalIP\")].address}");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    nodeIP = new String(p.getInputStream().readAllBytes()).trim();
                    if (nodeIP.isEmpty()) nodeIP = null;
                }
            } catch (Exception e) {
                log.debug("K8s node IP 查询失败: {}", e.getMessage());
            }

            // 通过 kubectl get svc 查找 Service
            try {
                ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "svc",
                        "-n", namespace, "-o", "json");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                if (finished && p.exitValue() == 0) {
                    String output = new String(p.getInputStream().readAllBytes());
                    com.google.gson.JsonObject root = com.google.gson.JsonParser
                            .parseString(output).getAsJsonObject();
                    if (root.has("items")) {
                        for (com.google.gson.JsonElement item : root.getAsJsonArray("items")) {
                            com.google.gson.JsonObject svc = item.getAsJsonObject();
                            com.google.gson.JsonObject meta = svc.has("metadata")
                                    ? svc.getAsJsonObject("metadata") : null;
                            com.google.gson.JsonObject spec = svc.has("spec")
                                    ? svc.getAsJsonObject("spec") : null;

                            if (meta == null || spec == null) continue;

                            String svcName = meta.has("name") ? meta.get("name").getAsString() : "";
                            boolean matches = svcName.contains(deploymentName)
                                    || deploymentName.contains(svcName.replace("-svc", "").replace("-service", ""));

                            if (!matches && meta.has("labels")) {
                                com.google.gson.JsonObject labels = meta.getAsJsonObject("labels");
                                if (labels.has("app")) {
                                    String appLabel = labels.get("app").getAsString();
                                    matches = deploymentName.contains(appLabel) || appLabel.contains(deploymentName);
                                }
                            }

                            if (matches && spec.has("ports")) {
                                String svcType = spec.has("type") ? spec.get("type").getAsString() : "ClusterIP";
                                String clusterIP = spec.has("clusterIP") ? spec.get("clusterIP").getAsString() : "";

                                for (com.google.gson.JsonElement portEl : spec.getAsJsonArray("ports")) {
                                    com.google.gson.JsonObject portObj = portEl.getAsJsonObject();
                                    int svcPort = portObj.has("port") ? portObj.get("port").getAsInt() : 0;
                                    String protocol = portObj.has("protocol")
                                            ? portObj.get("protocol").getAsString() : "TCP";

                                    Map<String, String> mapping = new LinkedHashMap<>();
                                    mapping.put("serviceName", svcName);
                                    mapping.put("serviceType", svcType);
                                    mapping.put("port", svcPort + "/" + protocol.toLowerCase());
                                    mapping.put("clusterIP", clusterIP);
                                    ports.add(mapping);

                                    // ---- 内部访问 ----
                                    String proto = svcPort == 443 ? "https" : "http";
                                    internalUrls.add(proto + "://" + svcName + "." + namespace
                                            + ".svc.cluster.local:" + svcPort);

                                    // ---- 外部访问 ----
                                    if ("NodePort".equals(svcType) && portObj.has("nodePort")) {
                                        int nodePort = portObj.get("nodePort").getAsInt();
                                        if (nodeIP != null) {
                                            externalUrls.add(proto + "://" + nodeIP + ":" + nodePort);
                                        } else {
                                            externalUrls.add(proto + "://<节点IP>:" + nodePort);
                                        }
                                    } else if ("LoadBalancer".equals(svcType)) {
                                        // 尝试获取 EXTERNAL-IP
                                        String externalIP = null;
                                        if (svc.has("status")) {
                                            com.google.gson.JsonObject status = svc.getAsJsonObject("status");
                                            if (status.has("loadBalancer") && status.getAsJsonObject("loadBalancer").has("ingress")) {
                                                com.google.gson.JsonArray ingress = status.getAsJsonObject("loadBalancer")
                                                        .getAsJsonArray("ingress");
                                                if (!ingress.isEmpty()) {
                                                    com.google.gson.JsonObject first = ingress.get(0).getAsJsonObject();
                                                    externalIP = first.has("ip") ? first.get("ip").getAsString()
                                                            : first.has("hostname") ? first.get("hostname").getAsString()
                                                            : null;
                                                }
                                            }
                                        }
                                        if (externalIP != null) {
                                            externalUrls.add(proto + "://" + externalIP + ":" + svcPort);
                                        } else {
                                            externalUrls.add(proto + "://<EXTERNAL-IP>:" + svcPort);
                                        }
                                    }
                                    // ClusterIP: 无直接外部访问，添加 port-forward 提示
                                    if (externalUrls.isEmpty()) {
                                        externalUrls.add("kubectl port-forward -n " + namespace
                                                + " svc/" + svcName + " <本地端口>:" + svcPort
                                                + "  → 然后访问 http://localhost:<本地端口>");
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (!finished) p.destroyForcibly();
                }
            } catch (Exception e) {
                log.debug("K8s Service 查询失败 ({}): {}", inst.getInstanceName(), e.getMessage());
            }

            // 兜底
            if (ports.isEmpty() && inst.getPort() != null) {
                Map<String, String> fallback = new LinkedHashMap<>();
                fallback.put("port", inst.getPort() + "/tcp");
                fallback.put("serviceType", "—");
                ports.add(fallback);
                internalUrls.add("http://" + deploymentName + ":" + inst.getPort());
                externalUrls.add("kubectl port-forward -n " + namespace + " deploy/" + deploymentName + " <本地端口>:" + inst.getPort());
            }
        }

        result.put("ports", ports);
        result.put("internalUrls", internalUrls);

        // 检查是否有活跃的 port-forward
        Map<String, Object> activeForward = getActivePortForward(id);
        if (activeForward != null) {
            // 替换掉文本命令为真实 URL
            List<String> realExternalUrls = new ArrayList<>();
            for (String url : externalUrls) {
                if (url.startsWith("kubectl port-forward")) {
                    realExternalUrls.add((String) activeForward.get("localUrl"));
                } else {
                    realExternalUrls.add(url);
                }
            }
            result.put("externalUrls", realExternalUrls);
            result.put("portForwardActive", true);
            result.put("portForwardUrl", activeForward.get("localUrl"));
        } else {
            result.put("externalUrls", externalUrls);
            result.put("portForwardActive", false);
            // 添加可以启动 port-forward 的提示
            if ("K8S".equals(inst.getDeployType()) && !ports.isEmpty()) {
                result.put("canPortForward", true);
            }
        }

        result.put("success", true);
        result.put("message", ports.isEmpty() ? "未找到端口映射，请检查容器/Service 配置" : "已获取访问信息");
        return result;
    }

    /**
     * 通过镜像名查找 Docker 容器 ID（优先运行中的，其次是已停止的）
     * 先查已缓存的 containerId，若无则通过 docker ps -a 搜索
     */
    private String resolveDockerContainerId(ServiceInstance inst) {
        // 优先用缓存的 containerId
        if (inst.getContainerId() != null && !inst.getContainerId().isEmpty()) {
            return inst.getContainerId();
        }
        // 通过镜像名搜索
        Map<String, String> info = inspectDockerContainer(inst);
        return info != null ? info.get("containerId") : null;
    }

    /**
     * 通过镜像名检查 Docker 容器状态
     * 返回 { containerId, status } 或 null（未找到）
     */
    private Map<String, String> inspectDockerContainer(ServiceInstance inst) {
        String imageName = inst.getImageName();
        String imageTag = inst.getImageTag() != null ? inst.getImageTag() : "latest";
        if (imageName == null || imageName.isEmpty()) return null;

        try {
            // docker ps -a --filter ancestor=<image>:<tag> --format "{{.ID}}|{{.Status}}"
            ProcessBuilder pb = new ProcessBuilder(dockerCommand, "ps", "-a",
                    "--filter", "ancestor=" + imageName + ":" + imageTag,
                    "--format", "{{.ID}}|{{.Status}}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;

            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (output.isEmpty()) return null;

            // 取第一行（可能多个容器匹配同一镜像）
            String firstLine = output.split("\\R")[0].trim();
            String[] parts = firstLine.split("\\|", 2);
            if (parts.length < 2) return null;

            Map<String, String> result = new LinkedHashMap<>();
            result.put("containerId", parts[0].trim());
            result.put("status", parts[1].trim());
            return result;
        } catch (Exception e) {
            log.debug("Docker inspect 失败 ({}): {}", inst.getInstanceName(), e.getMessage());
            return null;
        }
    }

    /**
     * 通过 K8s Pod 名查找 Pod 状态
     * 返回 { status } 或 null（未找到）
     */
    private Map<String, String> findK8sPod(ServiceInstance inst) {
        if (!k8sClientService.isConnected()) return null;

        String namespace = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : k8sNamespace;
        String podName = inst.getK8sPodName();

        try {
            List<Map<String, String>> pods = k8sClientService.listPods(namespace);
            for (Map<String, String> pod : pods) {
                String name = pod.get("name");
                if (name != null && name.contains(podName != null ? podName : inst.getInstanceName().replace("-k8s", ""))) {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("status", pod.getOrDefault("status", "Unknown"));
                    return result;
                }
            }
        } catch (Exception e) {
            log.debug("K8s Pod 查询失败 ({}): {}", inst.getInstanceName(), e.getMessage());
        }
        return null;
    }

    private String mapDockerStatus(String dockerStatus) {
        if (dockerStatus == null) return "UNKNOWN";
        String lower = dockerStatus.toLowerCase();
        if (lower.startsWith("up ")) return "RUNNING";
        if (lower.startsWith("exited")) return "STOPPED";
        return "UNKNOWN";
    }

    private String mapK8sStatus(String podStatus) {
        if (podStatus == null) return "UNKNOWN";
        return switch (podStatus) {
            case "Running" -> "RUNNING";
            case "Pending" -> "RUNNING"; // 启动中视为运行
            case "Succeeded" -> "STOPPED";
            case "Failed" -> "STOPPED";
            default -> "UNKNOWN";
        };
    }

    // ============ 原有方法 ============

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
            // 主动检查（尝试通过 Docker/K8s API 获取真实状态）
            try {
                refreshInstanceStatus(inst);
            } catch (Exception ignored) {}

            // 回退：心跳超时兜底
            if (inst.getLastHeartbeat() != null &&
                    inst.getLastHeartbeat().plusMinutes(5).isBefore(LocalDateTime.now())) {
                if (!"UNKNOWN".equals(inst.getStatus())) {
                    inst.setHealthStatus("UNKNOWN");
                    inst.setStatus("UNKNOWN");
                    instanceRepository.save(inst);
                }
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
     */
    public Map<String, Object> checkDockerAvailability() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
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
                    result.put("name", null); result.put("version", null);
                    result.put("containersRunning", 0); result.put("containersStopped", 0);
                    result.put("totalContainers", 0); result.put("images", 0);
                    result.put("message", "Docker 无响应（10s 超时）");
                    result.put("error", "Docker 命令超时，请确认 Docker Desktop 已启动");
                } else {
                    result.put("connected", false);
                    result.put("name", null); result.put("version", null);
                    result.put("containersRunning", 0); result.put("containersStopped", 0);
                    result.put("totalContainers", 0); result.put("images", 0);
                    result.put("message", "Docker 不可用");
                    result.put("error", "docker info 返回非零退出码: " + proc.exitValue());
                }
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            result.put("connected", false);
            result.put("name", null); result.put("version", null);
            result.put("containersRunning", 0); result.put("containersStopped", 0);
            result.put("totalContainers", 0); result.put("images", 0);
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

    /**
     * 为 K8s ClusterIP 服务启动端口转发
     * 返回 { success, localUrl, localPort, namespace, svcName, alreadyRunning }
     */
    public Map<String, Object> startPortForward(Long instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        ServiceInstance inst = instanceRepository.findById(instanceId).orElse(null);
        if (inst == null) {
            result.put("success", false);
            result.put("message", "实例不存在");
            return result;
        }

        // 检查是否已经有活跃的 port-forward
        Map<String, Object> existing = activePortForwards.get(instanceId);
        if (existing != null) {
            Process existingProc = (Process) existing.get("process");
            if (existingProc != null && existingProc.isAlive()) {
                int localPort = (int) existing.get("localPort");
                result.put("success", true);
                result.put("alreadyRunning", true);
                result.put("localUrl", "http://localhost:" + localPort);
                result.put("localPort", localPort);
                result.put("namespace", existing.get("namespace"));
                result.put("svcName", existing.get("svcName"));
                result.put("message", "端口转发已在运行: " + localPort);
                return result;
            } else {
                // 已死，清理
                activePortForwards.remove(instanceId);
            }
        }

        // 获取 Service 信息
        String namespace = inst.getK8sNamespace() != null ? inst.getK8sNamespace() : k8sNamespace;
        String deploymentName = inst.getK8sPodName() != null ? inst.getK8sPodName()
                : inst.getInstanceName().replace("-k8s", "");

        // 查找 Service
        Map<String, Object> svcInfo = findK8sService(namespace, deploymentName);
        if (svcInfo == null) {
            result.put("success", false);
            result.put("message", "未找到该实例对应的 K8s Service");
            return result;
        }

        String svcName = (String) svcInfo.get("svcName");
        int svcPort = (int) svcInfo.get("svcPort");

        // 找一个空闲本地端口
        int localPort = findFreePort();
        if (localPort == -1) {
            result.put("success", false);
            result.put("message", "无法分配本地端口");
            return result;
        }

        // 启动 kubectl port-forward
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "kubectl", "port-forward",
                    "-n", namespace,
                    "svc/" + svcName,
                    localPort + ":" + svcPort);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // 等待一下确认启动成功 (port-forward 会输出 "Forwarding from...")
            Thread.sleep(1500);
            if (!proc.isAlive()) {
                String errorOut = new String(proc.getInputStream().readAllBytes());
                proc.destroyForcibly();
                result.put("success", false);
                result.put("message", "端口转发启动失败: " + errorOut);
                return result;
            }

            Map<String, Object> forwardInfo = new LinkedHashMap<>();
            forwardInfo.put("process", proc);
            forwardInfo.put("localPort", localPort);
            forwardInfo.put("namespace", namespace);
            forwardInfo.put("svcName", svcName);
            forwardInfo.put("svcPort", svcPort);
            activePortForwards.put(instanceId, forwardInfo);

            result.put("success", true);
            result.put("alreadyRunning", false);
            result.put("localUrl", "http://localhost:" + localPort);
            result.put("localPort", localPort);
            result.put("namespace", namespace);
            result.put("svcName", svcName);
            result.put("message", "端口转发已启动: " + localPort);
            log.info("K8s 端口转发已启动: {}:{} → localhost:{}", namespace, svcName, localPort);
        } catch (Exception e) {
            log.error("启动端口转发失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "启动端口转发异常: " + e.getMessage());
        }

        return result;
    }

    /**
     * 停止端口转发
     */
    public Map<String, Object> stopPortForward(Long instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> existing = activePortForwards.remove(instanceId);
        if (existing != null) {
            Process proc = (Process) existing.get("process");
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
                try { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            int localPort = (int) existing.get("localPort");
            result.put("success", true);
            result.put("message", "端口转发已停止: " + localPort);
            log.info("K8s 端口转发已停止: {} port {}", instanceId, localPort);
        } else {
            result.put("success", true);
            result.put("message", "该实例没有活跃的端口转发");
        }
        return result;
    }

    /**
     * 获取活跃的 port-forward 信息（供 getAccessInfo 使用）
     */
    public Map<String, Object> getActivePortForward(Long instanceId) {
        Map<String, Object> existing = activePortForwards.get(instanceId);
        if (existing == null) return null;
        Process proc = (Process) existing.get("process");
        if (proc == null || !proc.isAlive()) {
            activePortForwards.remove(instanceId);
            return null;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("localPort", existing.get("localPort"));
        info.put("localUrl", "http://localhost:" + existing.get("localPort"));
        info.put("namespace", existing.get("namespace"));
        info.put("svcName", existing.get("svcName"));
        return info;
    }

    /** 查找 K8s Service: { svcName, svcPort, svcType } */
    private Map<String, Object> findK8sService(String namespace, String deploymentName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "svc", "-n", namespace, "-o", "json");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            String output = new String(p.getInputStream().readAllBytes());
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(output).getAsJsonObject();
            if (!root.has("items")) return null;
            for (com.google.gson.JsonElement item : root.getAsJsonArray("items")) {
                com.google.gson.JsonObject svc = item.getAsJsonObject();
                com.google.gson.JsonObject meta = svc.getAsJsonObject("metadata");
                String svcName = meta.get("name").getAsString();
                boolean matches = svcName.contains(deploymentName)
                        || deploymentName.contains(svcName.replace("-svc", "").replace("-service", ""));
                if (matches) {
                    com.google.gson.JsonObject spec = svc.getAsJsonObject("spec");
                    int svcPort = spec.getAsJsonArray("ports").get(0).getAsJsonObject().get("port").getAsInt();
                    String svcType = spec.has("type") ? spec.get("type").getAsString() : "ClusterIP";
                    return Map.of("svcName", svcName, "svcPort", svcPort, "svcType", svcType);
                }
            }
        } catch (Exception e) {
            log.debug("查找 K8s Service 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 找一个空闲 TCP 端口 */
    private int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            return -1;
        }
    }

    private int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
