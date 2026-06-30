# DevOps 持续交付平台 — 项目长期记忆

## 项目概况
- **名称**：基于 Docker + Kubernetes 的 DevOps 持续交付平台（软件工程毕业设计选题八）
- **技术栈**：Spring Boot 3.2 + JPA + MySQL/H2 + WebSocket（后端）；React + Vite（前端，构建产物部署到 `devops-platform/src/main/resources/static/`）
- **端口**：8080；启动类 `com.devops.platform.DevOpsPlatformApplication`
- **代码位置**：后端 `devops-platform/src/main/java/com/devops/platform/`；前端 `frontend/src/`
- **比文档更丰富的功能**：JWT 认证、服务实例管理、K8s 部署、制品、模板、调度、审计、通知

## ⚠️ 工作约定（用户明确要求，必须遵守）
1. **"DevOps" / "D项目" = 本项目本身**。用户提到"DevOps"或"D项目"时即指这个毕业设计项目，不是泛指 DevOps 概念。
2. **代码操作必须可恢复**。通过 git 保障：修改前确保工作区干净或先提交存档；提供修改方案时附带回退方式。
3. **修改可由 AI 直接执行**（2026-06-30 更新）。AI 可以直接改代码/文件并 git 提交。用户通过 IDEA 运行后端。

## 前端运行模式（2026-06-30 切换）
- **当前模式：开发模式（npm run dev）**
  - 终端 A：IDEA 跑 `DevOpsPlatformApplication`（8080，只做 API）
  - 终端 B：`cd frontend && npm run dev`（3000，前端开发服务器 + 热更新）
  - 浏览器访问 `localhost:3000`（不再是 8080）
  - `/api` 和 `/ws` 已在 `vite.config.js` 代理到 8080，源码无需改
  - 改前端源码保存即生效，不再需要 `npm run build`
  - 首次访问 3000 需重新登录（localStorage 跨端口不共享）
- **回退到 build 模式**：`cd frontend && npm run build` → 访问 8080
- **历史坑**：用户多次踩"改前端不 build 导致修改不生效"，开发模式可根治此问题

## 可恢复机制
- 当前最新提交 `6dc26eb`（2026-06-30 21:55）。
- 改代码前：`git stash` 或先 commit；改完可 `git diff`/`git checkout -- <file>` 回退。
- 整体回退：`git reset --hard 6dc26eb`。

## 关键路径速查
- 配置：`devops-platform/src/main/resources/application.yml`（MySQL 密码在此改）
- SQL 初始化：`devops-platform/sql/init.sql`
- 前端开发：`cd frontend && npm run dev` → 访问 `localhost:3000`
- 前端构建（部署用）：`cd frontend && npm run build` → 产物自动落到后端 static 目录
- 后端运行：IDEA 运行启动类，或 `java -jar target/devops-platform-1.0.0.jar`
- **Maven 构建**（CLI）：需用 PowerShell + 设置 JAVA_HOME 后运行，因系统 Maven 损坏，可用缓存版本：
  ```
  $env:JAVA_HOME = "E:\Program Files\Java\jdk-21"
  & "C:\Users\Dearkoma\.m2\wrapper\dists\apache-maven-3.9.6-bin\3a2c146e\apache-maven-3.9.6\bin\mvn.cmd" package -DskipTests
  ```
- **MySQL 操作**（CLI）：可通过 Python venv + pymysql：
  ```
  /c/Users/Dearkoma/.workbuddy/binaries/python/envs/default/Scripts/python -c "..." 
  ```
