package com.devops.platform.controller;

import com.devops.platform.entity.Build;
import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.BuildRepository;
import com.devops.platform.repository.ServiceInstanceRepository;
import com.devops.platform.service.BuildService;
import com.devops.platform.service.InstanceMonitorService;
import com.devops.platform.service.K8sClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/instances")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceMonitorService monitorService;
    private final ServiceInstanceRepository instanceRepository;
    private final K8sClientService k8sClientService;
    private final BuildService buildService;
    private final BuildRepository buildRepository;

    @GetMapping
    public List<ServiceInstance> getAll() {
        return monitorService.getAllInstances();
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return monitorService.getStats();
    }

    /** 按部署类型分组的统计（Docker / K8s） */
    @GetMapping("/stats-by-type")
    public Map<String, Object> getStatsByType() {
        return monitorService.getStatsByType();
    }

    @GetMapping("/project/{projectId}")
    public List<ServiceInstance> getByProject(@PathVariable Long projectId) {
        return monitorService.getInstancesByProject(projectId);
    }

    @PostMapping
    public ServiceInstance register(@RequestBody ServiceInstance instance) {
        return monitorService.saveInstance(instance);
    }

    @PutMapping("/{id}/health")
    public ResponseEntity<ServiceInstance> updateHealth(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String healthStatus = (String) body.getOrDefault("healthStatus", "HEALTHY");
        Double cpu = body.containsKey("cpuUsage") ? ((Number) body.get("cpuUsage")).doubleValue() : null;
        Double mem = body.containsKey("memoryUsage") ? ((Number) body.get("memoryUsage")).doubleValue() : null;
        ServiceInstance inst = monitorService.updateHealth(id, healthStatus, cpu, mem);
        return inst == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(inst);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> result = monitorService.deleteInstanceAndCleanup(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 重启服务实例
     * - Docker: docker restart
     * - K8s:    kubectl rollout restart
     */
    @PostMapping("/{id}/restart")
    public ResponseEntity<Map<String, Object>> restart(@PathVariable Long id) {
        Map<String, Object> result = monitorService.restartInstance(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 停止服务实例
     * - Docker: docker stop
     * - K8s:    kubectl scale --replicas=0
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long id) {
        Map<String, Object> result = monitorService.stopInstance(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 启动服务实例（从 STOPPED 状态恢复）
     * - Docker: docker start
     * - K8s:    kubectl scale --replicas=1
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id) {
        Map<String, Object> result = monitorService.startInstance(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取实例访问信息（内部链接 + 外部部署状态）
     */
    @GetMapping("/{id}/access-info")
    public ResponseEntity<Map<String, Object>> getAccessInfo(@PathVariable Long id) {
        Map<String, Object> result = monitorService.getAccessInfo(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 一键部署到外部（K8s → NodePort，Docker → 端口映射检查）
     */
    @PostMapping("/{id}/expose")
    public ResponseEntity<Map<String, Object>> exposeToExternal(@PathVariable Long id) {
        Map<String, Object> result = monitorService.exposeToExternal(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取实例容器日志（Docker / K8s）— 后台运行时日志
     * @param tail  获取最后 N 行日志（默认 200）
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<Map<String, Object>> getLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "200") int tail) {
        Map<String, Object> result = monitorService.getContainerLogs(id, tail);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取实例的构建日志（前台构建日志）— 取该项目最新一次构建的完整日志
     * 包含 npm install、npm run build（前端）、mvn package（后端）、docker build、k8s deploy 等步骤
     */
    @GetMapping("/{id}/build-logs")
    public ResponseEntity<Map<String, Object>> getBuildLogs(@PathVariable Long id) {
        ServiceInstance inst = instanceRepository.findById(id).orElse(null);
        if (inst == null) {
            return ResponseEntity.ok(Map.of("success", false, "error", "实例不存在"));
        }

        List<Build> builds = buildRepository.findByProjectIdOrderByStartTimeDesc(inst.getProjectId());
        if (builds == null || builds.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "error", "该项目暂无构建记录"));
        }

        Build latest = builds.get(0);
        String log = buildService.getBuildLog(latest.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("buildId", latest.getId());
        result.put("buildNumber", latest.getBuildNumber());
        result.put("buildStatus", latest.getStatus());
        result.put("startTime", latest.getStartTime());
        result.put("endTime", latest.getEndTime());
        result.put("triggeredBy", latest.getTriggeredBy());
        result.put("branch", latest.getBranch());
        result.put("logs", log != null ? log : "(暂无日志)");
        result.put("source", "构建 " + latest.getBuildNumber() + " · " + latest.getStatus());
        return ResponseEntity.ok(result);
    }

    /**
     * K8s 连通性检测
     * 使用 Kubernetes Java Client 连接集群，检查是否可用，并拉取 Pod 列表
     */
    @GetMapping("/k8s-status")
    public ResponseEntity<Map<String, Object>> checkK8sStatus() {
        return ResponseEntity.ok(k8sClientService.getFullStatus());
    }

    /**
     * 强制重新连接 K8s（刷新 kubeconfig）
     */
    @PostMapping("/k8s-reconnect")
    public ResponseEntity<Map<String, Object>> reconnect() {
        k8sClientService.tryConnect();
        return ResponseEntity.ok(k8sClientService.getFullStatus());
    }

    /**
     * 列出 K8s 命名空间
     */
    @GetMapping("/k8s-namespaces")
    public ResponseEntity<List<String>> listNamespaces() {
        return ResponseEntity.ok(k8sClientService.listNamespaces());
    }

    /**
     * Docker + K8s 联合可用性检测（角色感知，权限隔离）
     *  - ADMIN / DEVELOPER: 返回完整信息（连接状态、版本、Pod数、容器数、错误原因）
     *  - VIEWER / MANAGER:    仅返回名称，不暴露状态和错误
     */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> checkAvailability() {
        Map<String, Object> dockerRaw = monitorService.checkDockerAvailability();
        Map<String, Object> k8sRaw = k8sClientService.getStatus();

        // 判断当前用户角色
        boolean isPrivileged = isPrivilegedUser();

        Map<String, Object> result = new LinkedHashMap<>();

        if (isPrivileged) {
            // 管理员/开发者：返回全部详情
            result.put("docker", dockerRaw);
            result.put("k8s", k8sRaw);
        } else {
            // Viewer/Manager：仅返回名称，隐藏状态敏感信息
            result.put("docker", maskAvailability(dockerRaw, "name"));
            result.put("k8s", maskAvailability(k8sRaw, "clusterName"));
        }

        result.put("roleLevel", isPrivileged ? "FULL" : "BASIC");
        return ResponseEntity.ok(result);
    }

    // ============ K8s Deployment 管理 ============

    /**
     * 列出 K8s Deployment
     */
    @GetMapping("/k8s/deployments")
    public ResponseEntity<?> listDeployments(@RequestParam(defaultValue = "devops") String namespace) {
        if (!k8sClientService.isConnected()) {
            return ResponseEntity.ok(Map.of("connected", false, "deployments", List.of()));
        }
        List<Map<String, Object>> deps = k8sClientService.listDeployments(namespace);
        return ResponseEntity.ok(Map.of("connected", true, "deployments", deps));
    }

    /**
     * 获取单个 K8s Deployment 详情（含关联 Pod）
     */
    @GetMapping("/k8s/deployments/{name}")
    public ResponseEntity<Map<String, Object>> getDeployment(
            @PathVariable String name,
            @RequestParam(defaultValue = "devops") String namespace) {
        if (!k8sClientService.isConnected()) {
            return ResponseEntity.ok(Map.of("connected", false, "error", "K8s 未连接"));
        }
        Map<String, Object> detail = k8sClientService.getDeployment(name, namespace);
        detail.put("connected", true);
        return ResponseEntity.ok(detail);
    }

    /**
     * 创建 / 更新 K8s Deployment（接收 YAML 文本）
     */
    @PostMapping("/k8s/deployments")
    public ResponseEntity<Map<String, Object>> createDeployment(@RequestBody Map<String, String> body) {
        String yaml = body.get("yaml");
        String namespace = body.getOrDefault("namespace", "devops");
        if (yaml == null || yaml.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "output", "YAML 内容不能为空"));
        }
        Map<String, Object> result = k8sClientService.applyDeployment(yaml, namespace);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 K8s Deployment
     */
    @DeleteMapping("/k8s/deployments/{name}")
    public ResponseEntity<Map<String, Object>> deleteDeployment(
            @PathVariable String name,
            @RequestParam(defaultValue = "devops") String namespace) {
        Map<String, Object> result = k8sClientService.deleteDeployment(name, namespace);
        return ResponseEntity.ok(result);
    }

    /**
     * 检查当前用户是否为 ADMIN 或 DEVELOPER
     */
    private boolean isPrivilegedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_DEVELOPER"));
    }

    /**
     * 对 VIEWER/MANAGER 屏蔽详细状态，仅保留名称
     */
    private Map<String, Object> maskAvailability(Map<String, Object> raw, String nameField) {
        Map<String, Object> masked = new LinkedHashMap<>();
        String name = raw.get(nameField) != null ? raw.get(nameField).toString() : "";
        masked.put("name", name);
        masked.put("masked", true);
        masked.put("message", name.isEmpty() ? "（未检测到连接）" : name);
        return masked;
    }
}
