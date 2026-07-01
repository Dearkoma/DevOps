package com.devops.platform.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.VersionApi;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * Kubernetes 集群连接服务
 * 支持通过 kubeconfig 文件连接 Docker Desktop / Rancher Desktop / 原生 K8s 集群
 */
@Slf4j
@Service
public class K8sClientService {

    @Value("${devops.pipeline.k8s.namespace:devops}")
    private String defaultNamespace;

    @Value("${devops.pipeline.k8s.kubeconfig-path:}")
    private String kubeconfigPath;

    @Value("${devops.pipeline.k8s.enabled:true}")
    private boolean k8sEnabled;

    private ApiClient apiClient;
    private CoreV1Api coreV1Api;
    private AppsV1Api appsV1Api;
    private volatile boolean connected = false;
    private volatile long lastCheckTime = 0;
    private static final long STALE_THRESHOLD_MS = 10_000;  // 缓存有效期 10 秒
    private String connectionError = null;
    private String serverVersion = null;
    private String clusterName = null;

    @PostConstruct
    public void init() {
        if (!k8sEnabled) {
            log.info("K8s 集成已禁用 (devops.pipeline.k8s.enabled=false)");
            return;
        }
        tryConnect();
    }

    /**
     * 尝试连接 K8s 集群
     */
    public synchronized void tryConnect() {
        connected = false;
        connectionError = null;
        serverVersion = null;

        try {
            File kubeConfigFile = resolveKubeconfig();
            if (kubeConfigFile == null || !kubeConfigFile.exists()) {
                connectionError = "未找到 kubeconfig 文件，请确认已在 Docker Desktop / Rancher Desktop 中启用了 Kubernetes";

                log.warn(connectionError);
                return;
            }

            // 加载 kubeconfig
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(new FileReader(kubeConfigFile));

            // 提取集群/上下文名称
            try {
                // KubeConfig.currentContext 返回的是当前 context 名称字符串
                this.clusterName = kubeConfig.getCurrentContext();
                if (clusterName == null || clusterName.isBlank()) {
                    // contexts 返回 ArrayList<Object>，通过反射获取 name
                    var ctxList = kubeConfig.getContexts();
                    if (ctxList != null && !ctxList.isEmpty()) {
                        Object firstCtx = ctxList.get(0);
                        try {
                            clusterName = (String) firstCtx.getClass().getMethod("getName").invoke(firstCtx);
                        } catch (Exception ignore) {
                            clusterName = firstCtx.toString();
                        }
                    } else {
                        clusterName = "";
                    }
                }
            } catch (Exception ignored) {
                clusterName = "";
            }

            this.apiClient = ClientBuilder.kubeconfig(kubeConfig).build();

            // 设置超时
            apiClient.setConnectTimeout(10_000);
            apiClient.setReadTimeout(15_000);

            // 测试连接 — 用命名空间查询做连通性检测（比 VersionApi 更可靠，兼容 k3s 额外字段）
            Configuration.setDefaultApiClient(apiClient);
            this.coreV1Api = new CoreV1Api(apiClient);
            this.appsV1Api = new AppsV1Api(apiClient);

            // 连通性检测：列出命名空间（k3s 不会有 JSON 反序列化兼容问题）
            coreV1Api.listNamespace().pretty("false").execute();
            this.connected = true;
            this.connectionError = null;
            this.lastCheckTime = System.currentTimeMillis();

            // 获取版本号（k3s 会返回额外字段如 emulationMajor，Java Client 可能反序列化失败）
            try {
                VersionApi versionApi = new VersionApi(apiClient);
                VersionInfo versionInfo = versionApi.getCode().execute();
                this.serverVersion = (versionInfo.getGitVersion() != null && !versionInfo.getGitVersion().isBlank())
                        ? versionInfo.getGitVersion()
                        : versionInfo.getMajor() + "." + versionInfo.getMinor();
            } catch (Exception ve) {
                // k3s / Rancher Desktop 在 /version 响应中增加了 emulationMajor 等非标准字段，
                // 导致 io.kubernetes:client-java 的 VersionInfo 反序列化失败。
                // 此时连通性已验证通过，版本号标记为 "k3s" 即可。
                this.serverVersion = "k3s (兼容)";
                log.info("✅ Kubernetes 集群连接成功，版本获取降级: {}", ve.getMessage());
            }

            log.info("✅ Kubernetes 集群连接成功，版本: {}", serverVersion);

        } catch (Exception e) {
            this.connected = false;
            this.connectionError = analyzeError(e);
            this.lastCheckTime = System.currentTimeMillis();
            log.warn("❌ Kubernetes 集群连接失败: {}", connectionError);
        }
    }

    /**
     * 定位 kubeconfig 文件路径
     * 优先级: 配置路径 > KUBECONFIG 环境变量 > ~/.kube/config > Rancher 桌面默认路径
     */
    private File resolveKubeconfig() {
        // 1. 配置文件中指定的路径
        if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
            File f = new File(kubeconfigPath);
            if (f.exists()) {
                log.info("使用配置的 kubeconfig: {}", f.getAbsolutePath());
                return f;
            }
        }

        // 2. KUBECONFIG 环境变量
        String envKubeconfig = System.getenv("KUBECONFIG");
        if (envKubeconfig != null && !envKubeconfig.isBlank()) {
            File f = new File(envKubeconfig);
            if (f.exists()) {
                log.info("使用环境变量 KUBECONFIG: {}", f.getAbsolutePath());
                return f;
            }
        }

        // 3. 默认路径 ~/.kube/config
        String homeDir = System.getProperty("user.home");
        File defaultConfig = new File(homeDir, ".kube/config");
        if (defaultConfig.exists()) {
            log.info("使用默认 kubeconfig: {}", defaultConfig.getAbsolutePath());
            return defaultConfig;
        }

        // 4. Windows: Rancher Desktop 默认路径
        File rancherConfig = new File(homeDir, ".rancher/kube/config");
        if (rancherConfig.exists()) {
            log.info("使用 Rancher Desktop kubeconfig: {}", rancherConfig.getAbsolutePath());
            return rancherConfig;
        }

        return defaultConfig; // 返回默认路径让外层检查文件存在性
    }

    /**
     * 分析连接错误，给用户可读的提示
     */
    private String analyzeError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        if (msg.contains("java.net.ConnectException") || msg.contains("Connection refused")) {
            return "无法连接 Kubernetes API Server，请确认 Docker Desktop / Rancher Desktop 正在运行且 Kubernetes 已启用";

        }
        if (msg.contains("SSLHandshakeException") || msg.contains("PKIX")) {
            return "SSL 证书验证失败，kubeconfig 中的证书可能已过期";
        }
        if (msg.contains("Unauthorized") || msg.contains("401") || msg.contains("403")) {
            return "认证失败，kubeconfig 凭据可能已过期，请重启 Docker Desktop / Rancher Desktop 刷新凭据";

        }
        if (msg.contains("SocketTimeoutException") || msg.contains("connect timed out")) {
            return "连接超时，请确认 Rancher Desktop 正在运行且 K8s 集群已启动";
        }
        if (msg.contains("UnknownHostException") || msg.contains("No route to host")) {
            return "无法解析集群地址，请检查网络连接";
        }
        if (e instanceof ApiException apiEx) {
            return "K8s API 错误 (HTTP " + apiEx.getCode() + "): " + apiEx.getResponseBody();
        }

        return "连接失败: " + msg;
    }

    /**
     * 获取连接状态（智能缓存 + 实时检查）
     * 缓存 10 秒内直接返回；超时后做 2 秒快速 Ping 确认真实状态
     */
    public boolean isConnected() {
        // 快速路径：已知断开，直接返回
        if (!connected) return false;
        // 缓存新鲜，直接返回
        if (System.currentTimeMillis() - lastCheckTime < STALE_THRESHOLD_MS) return true;
        // 缓存过期，做一次快速健康检查
        return quickHealthCheck();
    }

    /**
     * 快速健康检查（短超时 HTTP Ping，2 秒内判定）
     * 成功则更新缓存时间，失败则标记 disconnected
     */
    private synchronized boolean quickHealthCheck() {
        if (apiClient == null || !k8sEnabled) {
            connected = false;
            return false;
        }
        try {
            okhttp3.OkHttpClient fastClient = apiClient.getHttpClient().newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .readTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(apiClient.getBasePath() + "/api/v1/namespaces?limit=1&timeoutSeconds=2")
                    .get()
                    .build();
            okhttp3.Response resp = fastClient.newCall(req).execute();
            int code = resp.code();
            resp.close();
            if (code >= 200 && code < 300) {
                connected = true;
                lastCheckTime = System.currentTimeMillis();
                connectionError = null;
                return true;
            } else {
                connected = false;
                connectionError = "K8s API 返回 HTTP " + code;
                lastCheckTime = System.currentTimeMillis();
                return false;
            }
        } catch (Exception e) {
            connected = false;
            connectionError = analyzeError(e);
            lastCheckTime = System.currentTimeMillis();
            return false;
        }
    }

    /**
     * 定时健康检查（每 30 秒自动刷新连接状态）
     */
    @Scheduled(fixedDelay = 30_000)
    public void scheduledHealthCheck() {
        if (!k8sEnabled) return;
        if (apiClient == null) {
            tryConnect();
            return;
        }
        boolean wasConnected = connected;
        boolean nowConnected = quickHealthCheck();
        if (wasConnected != nowConnected) {
            log.info("🔄 K8s 连接状态变化: {} → {}", wasConnected ? "已连接" : "已断开", nowConnected ? "已连接" : "已断开");
        }
    }

    /**
     * 获取完整状态信息（含 Pod 统计）
     */
    public Map<String, Object> getStatus() {
        boolean isConn = isConnected();  // 实时检查
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", isConn);
        result.put("serverVersion", serverVersion);
        result.put("clusterName", clusterName != null ? clusterName : "");
        result.put("namespace", defaultNamespace);
        result.put("error", connectionError);

        if (isConn) {
            // 统计目标命名空间的 Pod
            List<Map<String, String>> pods = listPods(defaultNamespace);
            result.put("podCount", pods.size());

            long runningPods = pods.stream().filter(p -> "Running".equals(p.get("status"))).count();
            long pendingPods = pods.stream().filter(p -> "Pending".equals(p.get("status"))).count();
            long failedPods = pods.stream()
                    .filter(p -> !"Running".equals(p.get("status")) && !"Pending".equals(p.get("status")) && !"Succeeded".equals(p.get("status")))
                    .count();
            result.put("runningPods", runningPods);
            result.put("pendingPods", pendingPods);
            result.put("failedPods", failedPods);
            result.put("pods", pods);

            // 构建详细消息
            StringBuilder msg = new StringBuilder("已连接到集群");
            if (clusterName != null && !clusterName.isBlank()) msg.append(" ").append(clusterName);
            msg.append("，命名空间: ").append(defaultNamespace);

            if (pods.isEmpty()) {
                msg.append("，但该命名空间中没有任何 Pod");
            } else {
                msg.append("，").append(pods.size()).append(" 个 Pod（")
                   .append(runningPods).append(" 运行中");
                if (pendingPods > 0) msg.append(", ").append(pendingPods).append(" 等待中");
                if (failedPods > 0) msg.append(", ").append(failedPods).append(" 异常");
                msg.append("）");
            }
            result.put("message", msg.toString());
        } else {
            result.put("podCount", 0);
            result.put("runningPods", 0);
            result.put("pendingPods", 0);
            result.put("failedPods", 0);
            result.put("pods", Collections.emptyList());
            result.put("message", "Kubernetes 集群未连接："
                    + (connectionError != null ? connectionError : "请检查配置"));
        }
        return result;
    }

    /**
     * 列出指定命名空间的 Pod
     * <p>
     * 使用 CoreV1Api.buildCall() 获取带正确认证头的 HTTP 请求，
     * 然后手动用 JsonParser 解析响应，避免 k3s 返回的非标准字段
     * (如 observedGeneration) 导致 V1PodList 模型 Gson 反序列化失败。
     */
    public List<Map<String, String>> listPods(String namespace) {
        List<Map<String, String>> pods = new ArrayList<>();
        if (!connected || coreV1Api == null || apiClient == null) {
            return pods;
        }

        String targetNs = (namespace != null && !namespace.isBlank())
                ? namespace : defaultNamespace;

        try {
            // 通过 CoreV1Api.buildCall 发起请求（自动注入 kubeconfig 认证 token）
            okhttp3.Call call = coreV1Api.listNamespacedPod(targetNs)
                    .pretty("false")
                    .buildCall(null);

            okhttp3.Response response = call.execute();
            if (!response.isSuccessful()) {
                log.warn("列出 Pod HTTP {}: {}", response.code(), response.message());
                response.close();
                return pods;
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            response.close();

            // 用 Gson 的 JsonParser 手动解析（不做模型类反序列化，不校验未知字段）
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root == null || !root.has("items")) {
                return pods;
            }

            for (JsonElement item : root.getAsJsonArray("items")) {
                JsonObject pod = item.getAsJsonObject();
                Map<String, String> podInfo = new LinkedHashMap<>();

                // metadata
                JsonObject metadata = pod.has("metadata") ? pod.getAsJsonObject("metadata") : null;
                podInfo.put("name", jsonStr(metadata, "name"));
                podInfo.put("namespace", jsonStr(metadata, "namespace"));

                // labels.app
                if (metadata != null && metadata.has("labels")
                        && metadata.getAsJsonObject("labels").has("app")) {
                    podInfo.put("app", metadata.getAsJsonObject("labels").get("app").getAsString());
                } else {
                    podInfo.put("app", "-");
                }

                // status
                JsonObject status = pod.has("status") ? pod.getAsJsonObject("status") : null;
                podInfo.put("status", jsonStr(status, "phase"));
                podInfo.put("nodeName", jsonStr(status, "hostIP"));

                // 容器就绪数
                if (status != null && status.has("containerStatuses")) {
                    var containers = status.getAsJsonArray("containerStatuses");
                    long ready = 0;
                    for (JsonElement c : containers) {
                        var cs = c.getAsJsonObject();
                        if (cs.has("ready") && cs.get("ready").getAsBoolean()) ready++;
                    }
                    podInfo.put("containers", ready + "/" + containers.size());
                }

                pods.add(podInfo);
            }
            log.debug("命名空间 {} 中找到 {} 个 Pod", targetNs, pods.size());
        } catch (Exception e) {
            log.warn("列出 Pod 失败: {}", e.getMessage());
        }
        return pods;
    }

    /**
     * 安全地从 JsonObject 中取字符串字段，不存在时返回 "-"
     */
    private static String jsonStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "-";
        }
        return obj.get(key).getAsString();
    }

    /**
     * 列出所有命名空间
     */
    public List<String> listNamespaces() {
        List<String> namespaces = new ArrayList<>();
        if (!connected || coreV1Api == null) {
            return namespaces;
        }

        try {
            V1NamespaceList nsList = coreV1Api.listNamespace()
                    .pretty("false")
                    .execute();
            for (V1Namespace ns : nsList.getItems()) {
                if (ns.getMetadata() != null) {
                    namespaces.add(ns.getMetadata().getName());
                }
            }
        } catch (Exception e) {
            log.warn("列出命名空间失败: {}", e.getMessage());
        }
        return namespaces;
    }

    /**
     * 获取完整状态（含 Pod 列表）
     */
    public Map<String, Object> getFullStatus() {
        Map<String, Object> result = getStatus();
        if (Boolean.TRUE.equals(result.get("connected"))) {
            List<Map<String, String>> pods = listPods(defaultNamespace);
            result.put("pods", pods);
            result.put("podCount", pods.size());
            if (pods.isEmpty()) {
                result.put("message", result.get("message")
                        + "，但命名空间 " + defaultNamespace + " 中没有运行的 Pod");
            }
        }
        return result;
    }

    // ============ Deployment 管理 ============

    /**
     * 列出指定命名空间的 Deployment
     */
    public List<Map<String, Object>> listDeployments(String namespace) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (!connected || apiClient == null || appsV1Api == null) return results;

        String targetNs = (namespace != null && !namespace.isBlank()) ? namespace : defaultNamespace;
        try {
            // 通过 AppsV1Api.buildCall 发起请求（自动注入 kubeconfig 认证 token），手动 JSON 解析避免 Gson 兼容问题
            okhttp3.Call call = appsV1Api.listNamespacedDeployment(targetNs)
                    .pretty("false")
                    .buildCall(null);
            okhttp3.Response response = call.execute();
            if (!response.isSuccessful()) {
                log.warn("列出 Deployment HTTP {}: {}", response.code(), response.message());
                response.close();
                return results;
            }
            String body = response.body() != null ? response.body().string() : "{}";
            response.close();

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root == null || !root.has("items")) return results;

            for (JsonElement item : root.getAsJsonArray("items")) {
                JsonObject dep = item.getAsJsonObject();
                Map<String, Object> info = new LinkedHashMap<>();
                JsonObject meta = dep.has("metadata") ? dep.getAsJsonObject("metadata") : null;
                info.put("name", jsonStr(meta, "name"));
                info.put("namespace", jsonStr(meta, "namespace"));
                info.put("createdAt", jsonStr(meta, "creationTimestamp"));

                // spec.replicas
                JsonObject spec = dep.has("spec") ? dep.getAsJsonObject("spec") : null;
                int desired = spec != null && spec.has("replicas") ? spec.get("replicas").getAsInt() : 1;
                info.put("replicas", desired);

                // status
                JsonObject status = dep.has("status") ? dep.getAsJsonObject("status") : null;
                int ready = status != null && status.has("readyReplicas") ? status.get("readyReplicas").getAsInt() : 0;
                int available = status != null && status.has("availableReplicas") ? status.get("availableReplicas").getAsInt() : 0;
                info.put("readyReplicas", ready);
                info.put("availableReplicas", available);

                // labels
                if (meta != null && meta.has("labels")) {
                    JsonObject labels = meta.getAsJsonObject("labels");
                    if (labels.has("app")) info.put("app", labels.get("app").getAsString());
                }
                if (!info.containsKey("app")) info.put("app", info.get("name"));

                // image
                if (spec != null && spec.has("template")) {
                    try {
                        JsonObject tmpl = spec.getAsJsonObject("template");
                        JsonObject podSpec = tmpl.has("spec") ? tmpl.getAsJsonObject("spec") : null;
                        if (podSpec != null && podSpec.has("containers")) {
                            var containers = podSpec.getAsJsonArray("containers");
                            if (!containers.isEmpty()) {
                                JsonObject first = containers.get(0).getAsJsonObject();
                                info.put("image", jsonStr(first, "image"));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (!info.containsKey("image")) info.put("image", "-");

                results.add(info);
            }
            log.debug("命名空间 {} 中找到 {} 个 Deployment", targetNs, results.size());
        } catch (Exception e) {
            log.warn("列出 Deployment 失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 获取单个 Deployment 详情（含 Pod 列表）
     */
    public Map<String, Object> getDeployment(String name, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!connected || apiClient == null) {
            result.put("error", "K8s 未连接");
            return result;
        }
        String targetNs = (namespace != null && !namespace.isBlank()) ? namespace : defaultNamespace;

        try {
            // 获取 Deployment 详情
            okhttp3.Call call = appsV1Api.readNamespacedDeployment(name, targetNs)
                    .pretty("false")
                    .buildCall(null);
            okhttp3.Response response = call.execute();
            if (!response.isSuccessful()) {
                log.warn("获取 Deployment 详情 HTTP {}: {}", response.code(), response.message());
                result.put("error", "获取失败 HTTP " + response.code());
                response.close();
                return result;
            }
            String body = response.body() != null ? response.body().string() : "{}";
            response.close();

            JsonObject dep = JsonParser.parseString(body).getAsJsonObject();
            JsonObject meta = dep.has("metadata") ? dep.getAsJsonObject("metadata") : null;
            result.put("name", jsonStr(meta, "name"));
            result.put("namespace", jsonStr(meta, "namespace"));

            // 关联 Pod 列表（用标签选择器匹配）
            List<Map<String, String>> allPods = listPods(targetNs);
            String appLabel = null;
            if (meta != null && meta.has("labels")) {
                JsonObject labels = meta.getAsJsonObject("labels");
                if (labels.has("app")) appLabel = labels.get("app").getAsString();
            }
            if (appLabel == null) appLabel = name;

            List<Map<String, String>> matchedPods = new ArrayList<>();
            for (Map<String, String> p : allPods) {
                if (appLabel.equals(p.get("app")) || name.equals(p.get("app"))) {
                    matchedPods.add(p);
                }
            }
            result.put("pods", matchedPods);
            result.put("podCount", matchedPods.size());

        } catch (Exception e) {
            log.warn("获取 Deployment 详情失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 创建/更新 Deployment（应用 YAML）
     * 使用 kubectl apply 命令，兼容性最好
     */
    public Map<String, Object> applyDeployment(String yaml, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 写入临时文件
            java.nio.file.Path tmpFile = java.nio.file.Files.createTempFile("k8s-deploy-", ".yaml");
            java.nio.file.Files.writeString(tmpFile, yaml);

            ProcessBuilder pb = new ProcessBuilder("kubectl", "apply", "-f", tmpFile.toAbsolutePath().toString(), "-n",
                    (namespace != null && !namespace.isBlank()) ? namespace : defaultNamespace);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();

            // 清理临时文件
            try { java.nio.file.Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}

            result.put("success", code == 0);
            result.put("output", output.trim());
            result.put("exitCode", code);

            if (code == 0) {
                log.info("Deployment apply 成功");
            } else {
                log.warn("Deployment apply 失败: {}", output);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("output", e.getMessage());
            result.put("exitCode", -1);
            log.warn("applyDeployment 异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 删除 Deployment
     */
    public Map<String, Object> deleteDeployment(String name, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        String targetNs = (namespace != null && !namespace.isBlank()) ? namespace : defaultNamespace;
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "delete", "deployment", name, "-n", targetNs);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            result.put("success", code == 0);
            result.put("output", output.trim());
            if (code == 0) {
                log.info("Deployment {} 删除成功", name);
            } else {
                log.warn("Deployment {} 删除失败: {}", name, output);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("output", e.getMessage());
            log.warn("删除 Deployment 异常: {}", e.getMessage());
        }
        return result;
    }
}
