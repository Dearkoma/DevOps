# DevOps持续交付平台 - 项目记忆

## 项目信息
- **项目**: Spring Boot + React DevOps持续交付平台（毕业设计）
- **后端**: Java 21, Spring Boot 3.2.1, JPA/Hibernate, MySQL, WebSocket(STOMP)
- **前端**: React 19, Vite 6, React Router 7
- **端口**: 10433 (应用配置), Maven启动需用 `java -classpath ... Launcher compile/spring-boot:run` 绕过Git Bash脚本问题

## 功能模块（已全部实现代码）
### P0 核心
- WebSocket 构建日志实时推送 (WebSocketConfig + BuildService SimpMessagingTemplate, 前端polling fallback)
- Git Webhook 自动触发构建 (GitHub/GitLab/Gitee 三种格式)
- 定时构建 Cron (SchedulerService @Scheduled + 自定义Cron解析器)
- 部署审批工作流 (PENDING→APPROVED/REJECTED→DEPLOYED 状态机)

### P1 增强
- 制品管理 (Artifact 自动扫描 target/*.jar)
- 构建通知 (Notification 实体, BUILD_SUCCESS/FAILED/DEPLOY_APPROVAL)
- 部署历史与回滚 (DeploymentHistory, rollback)
- 服务实例监控 (ServiceInstance, 30s心跳检测)

### P2 锦上添花
- 审计日志 (@Audit AOP注解)
- 模板管理 (8个内置模板)
- 参数化构建 (buildParams JSON)
- 多分支流水线 (branchPattern glob匹配)

## 前端页面（10个）
监控看板、项目管理、构建记录、定时任务、部署管理、环境管理、服务实例、模板管理、审计日志(管理员)、用户管理(管理员)

## 构建运行注意事项
- 前端: `cd frontend && npm run build` (沙箱环境可能被阻止，需手动)
- 后端: `java -classpath "E:/apache-maven-3.9.6/boot/plexus-classworlds-2.7.0.jar" -Dclassworlds.conf="E:/apache-maven-3.9.6/bin/m2.conf" -Dmaven.home="E:/apache-maven-3.9.6" -Dmaven.multiModuleProjectDirectory="E:/Dissertation/2/devops-platform" org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run`
- 启动前须kill端口10433上的旧进程
