package com.devops.platform.config;

import com.devops.platform.entity.Template;
import com.devops.platform.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateDataInitializer implements CommandLineRunner {

    private final TemplateRepository templateRepository;

    @Override
    public void run(String... args) {
        if (templateRepository.count() > 0) {
            log.info("模板数据已存在，跳过初始化");
            return;
        }
        log.info("初始化内置模板...");

        // Dockerfile - Java Spring Boot
        Template javaDockerfile = new Template();
        javaDockerfile.setName("Spring Boot Dockerfile");
        javaDockerfile.setType("DOCKERFILE");
        javaDockerfile.setCategory("Java");
        javaDockerfile.setLanguage("Java");
        javaDockerfile.setFramework("Spring Boot");
        javaDockerfile.setDescription("适用于 Spring Boot 项目的多阶段 Dockerfile");
        javaDockerfile.setContent(
            "# Multi-stage build for Spring Boot\n" +
            "FROM eclipse-temurin:21-jdk-alpine AS builder\n" +
            "WORKDIR /app\n" +
            "COPY pom.xml mvnw ./\n" +
            "COPY .mvn .mvn\n" +
            "RUN chmod +x mvnw && ./mvnw dependency:go-offline -B\n" +
            "COPY src ./src\n" +
            "RUN ./mvnw package -DskipTests -B\n" +
            "\n" +
            "FROM eclipse-temurin:21-jre-alpine\n" +
            "WORKDIR /app\n" +
            "COPY --from=builder /app/target/*.jar app.jar\n" +
            "EXPOSE 8080\n" +
            "HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O- http://localhost:8080/actuator/health || exit 1\n" +
            "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n");
        javaDockerfile.setIsBuiltin(true);
        templateRepository.save(javaDockerfile);

        // Dockerfile - Node.js
        Template nodeDockerfile = new Template();
        nodeDockerfile.setName("Node.js Dockerfile");
        nodeDockerfile.setType("DOCKERFILE");
        nodeDockerfile.setCategory("Node.js");
        nodeDockerfile.setLanguage("Node.js");
        nodeDockerfile.setFramework("Express");
        nodeDockerfile.setDescription("适用于 Node.js 项目的多阶段 Dockerfile");
        nodeDockerfile.setContent(
            "# Multi-stage build for Node.js\n" +
            "FROM node:20-alpine AS builder\n" +
            "WORKDIR /app\n" +
            "COPY package*.json ./\n" +
            "RUN npm ci\n" +
            "COPY . .\n" +
            "RUN npm run build\n" +
            "\n" +
            "FROM node:20-alpine\n" +
            "WORKDIR /app\n" +
            "COPY --from=builder /app/dist ./dist\n" +
            "COPY --from=builder /app/node_modules ./node_modules\n" +
            "COPY --from=builder /app/package*.json ./\n" +
            "EXPOSE 3000\n" +
            "CMD [\"node\", \"dist/main.js\"]\n");
        nodeDockerfile.setIsBuiltin(true);
        templateRepository.save(nodeDockerfile);

        // Dockerfile - Python
        Template pythonDockerfile = new Template();
        pythonDockerfile.setName("Python Dockerfile");
        pythonDockerfile.setType("DOCKERFILE");
        pythonDockerfile.setCategory("Python");
        pythonDockerfile.setLanguage("Python");
        pythonDockerfile.setDescription("适用于 Python 项目的 Dockerfile");
        pythonDockerfile.setContent(
            "FROM python:3.12-slim\n" +
            "WORKDIR /app\n" +
            "COPY requirements.txt .\n" +
            "RUN pip install --no-cache-dir -r requirements.txt\n" +
            "COPY . .\n" +
            "EXPOSE 8000\n" +
            "CMD [\"uvicorn\", \"main:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]\n");
        pythonDockerfile.setIsBuiltin(true);
        templateRepository.save(pythonDockerfile);

        // K8s Deployment
        Template k8sDeployment = new Template();
        k8sDeployment.setName("Kubernetes Deployment");
        k8sDeployment.setType("K8S_DEPLOYMENT");
        k8sDeployment.setCategory("Generic");
        k8sDeployment.setDescription("标准 Kubernetes Deployment 配置");
        k8sDeployment.setContent(
            "apiVersion: apps/v1\n" +
            "kind: Deployment\n" +
            "metadata:\n" +
            "  name: {{APP_NAME}}\n" +
            "  namespace: {{NAMESPACE}}\n" +
            "  labels:\n" +
            "    app: {{APP_NAME}}\n" +
            "spec:\n" +
            "  replicas: 2\n" +
            "  selector:\n" +
            "    matchLabels:\n" +
            "      app: {{APP_NAME}}\n" +
            "  template:\n" +
            "    metadata:\n" +
            "      labels:\n" +
            "        app: {{APP_NAME}}\n" +
            "    spec:\n" +
            "      containers:\n" +
            "      - name: {{APP_NAME}}\n" +
            "        image: {{IMAGE_NAME}}:{{IMAGE_TAG}}\n" +
            "        ports:\n" +
            "        - containerPort: {{CONTAINER_PORT}}\n" +
            "        env:\n" +
            "        - name: SPRING_PROFILES_ACTIVE\n" +
            "          value: {{PROFILE}}\n" +
            "        resources:\n" +
            "          requests:\n" +
            "            memory: \"256Mi\"\n" +
            "            cpu: \"250m\"\n" +
            "          limits:\n" +
            "            memory: \"512Mi\"\n" +
            "            cpu: \"500m\"\n" +
            "        livenessProbe:\n" +
            "          httpGet:\n" +
            "            path: /actuator/health\n" +
            "            port: {{CONTAINER_PORT}}\n" +
            "          initialDelaySeconds: 30\n" +
            "          periodSeconds: 10\n" +
            "        readinessProbe:\n" +
            "          httpGet:\n" +
            "            path: /actuator/health\n" +
            "            port: {{CONTAINER_PORT}}\n" +
            "          initialDelaySeconds: 5\n" +
            "          periodSeconds: 5\n");
        k8sDeployment.setIsBuiltin(true);
        templateRepository.save(k8sDeployment);

        // K8s Service
        Template k8sService = new Template();
        k8sService.setName("Kubernetes Service");
        k8sService.setType("K8S_SERVICE");
        k8sService.setCategory("Generic");
        k8sService.setDescription("标准 Kubernetes Service 配置");
        k8sService.setContent(
            "apiVersion: v1\n" +
            "kind: Service\n" +
            "metadata:\n" +
            "  name: {{APP_NAME}}-svc\n" +
            "  namespace: {{NAMESPACE}}\n" +
            "  labels:\n" +
            "    app: {{APP_NAME}}\n" +
            "spec:\n" +
            "  type: ClusterIP\n" +
            "  selector:\n" +
            "    app: {{APP_NAME}}\n" +
            "  ports:\n" +
            "  - port: 80\n" +
            "    targetPort: {{CONTAINER_PORT}}\n" +
            "    protocol: TCP\n" +
            "    name: http\n");
        k8sService.setIsBuiltin(true);
        templateRepository.save(k8sService);

        // K8s Ingress
        Template k8sIngress = new Template();
        k8sIngress.setName("Kubernetes Ingress");
        k8sIngress.setType("K8S_INGRESS");
        k8sIngress.setCategory("Generic");
        k8sIngress.setDescription("Kubernetes Ingress 配置");
        k8sIngress.setContent(
            "apiVersion: networking.k8s.io/v1\n" +
            "kind: Ingress\n" +
            "metadata:\n" +
            "  name: {{APP_NAME}}-ingress\n" +
            "  namespace: {{NAMESPACE}}\n" +
            "  annotations:\n" +
            "    nginx.ingress.kubernetes.io/rewrite-target: /\n" +
            "spec:\n" +
            "  ingressClassName: nginx\n" +
            "  rules:\n" +
            "  - host: {{HOST}}\n" +
            "    http:\n" +
            "      paths:\n" +
            "      - path: /\n" +
            "        pathType: Prefix\n" +
            "        backend:\n" +
            "          service:\n" +
            "            name: {{APP_NAME}}-svc\n" +
            "            port:\n" +
            "              number: 80\n");
        k8sIngress.setIsBuiltin(true);
        templateRepository.save(k8sIngress);

        // Docker Compose
        Template dockerCompose = new Template();
        dockerCompose.setName("Docker Compose");
        dockerCompose.setType("DOCKER_COMPOSE");
        dockerCompose.setCategory("Generic");
        dockerCompose.setDescription("Docker Compose 编排配置");
        dockerCompose.setContent(
            "version: '3.8'\n" +
            "services:\n" +
            "  app:\n" +
            "    build: .\n" +
            "    container_name: {{APP_NAME}}\n" +
            "    ports:\n" +
            "      - \"{{HOST_PORT}}:{{CONTAINER_PORT}}\"\n" +
            "    environment:\n" +
            "      - SPRING_PROFILES_ACTIVE=docker\n" +
            "    depends_on:\n" +
            "      - mysql\n" +
            "      - redis\n" +
            "    restart: unless-stopped\n" +
            "\n" +
            "  mysql:\n" +
            "    image: mysql:8.0\n" +
            "    container_name: {{APP_NAME}}-mysql\n" +
            "    ports:\n" +
            "      - \"3306:3306\"\n" +
            "    environment:\n" +
            "      MYSQL_ROOT_PASSWORD: root123\n" +
            "      MYSQL_DATABASE: {{APP_NAME}}\n" +
            "    volumes:\n" +
            "      - mysql_data:/var/lib/mysql\n" +
            "    restart: unless-stopped\n" +
            "\n" +
            "  redis:\n" +
            "    image: redis:7-alpine\n" +
            "    container_name: {{APP_NAME}}-redis\n" +
            "    ports:\n" +
            "      - \"6379:6379\"\n" +
            "    restart: unless-stopped\n" +
            "\n" +
            "volumes:\n" +
            "  mysql_data:\n");
        dockerCompose.setIsBuiltin(true);
        templateRepository.save(dockerCompose);

        log.info("已初始化 {} 个内置模板", templateRepository.count());
    }
}
