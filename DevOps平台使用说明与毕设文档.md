# DevOps 持续交付平台 - 使用说明与毕业设计文档

> 本项目为软件工程毕业设计选题八的完整实现，包含系统架构、功能说明、部署指南和论文大纲。

---

## 一、系统概述

**项目名称**：基于 Docker + Kubernetes 的 DevOps 持续交付平台  
**技术栈**：Spring Boot 3.2 + JPA + H2 + Vue 3 + WebSocket  
**端口**：8080  
**H2 控制台**：http://localhost:8080/h2-console

### 核心功能
1. **项目管理**：CRUD，支持多种语言（Java/Node.js/Python/Go）
2. **流水线管理**：可视化定义 CI/CD 阶段和步骤
3. **构建执行**：模拟 CI/CD 流程，实时日志推送
4. **环境管理**：多环境部署（dev/test/staging/prod）
5. **监控看板**：项目/构建统计、成功率、最近构建

---

## 二、快速启动

### 环境要求
- Java 21+（已安装：21.0.1）
- Maven 3.9+（已配置：E:\apache-maven-3.9.6）
- 磁盘空间：至少 500MB

### 启动步骤

```bash
# 1. 进入项目目录
cd E:\Dissertation\2\devops-platform

# 2. 构建项目（首次）
java -classpath "E:/apache-maven-3.9.6/boot/plexus-classworlds-2.7.0.jar" ^
  -Dclassworlds.conf="E:/apache-maven-3.9.6/bin/m2.conf" ^
  -Dmaven.home="E:/apache-maven-3.9.6" ^
  -Dmaven.multiModuleProjectDirectory="%CD%" ^
  org.codehaus.plexus.classworlds.launcher.Launcher clean package -DskipTests

# 3. 启动应用
java -jar target\devops-platform-1.0.0.jar --server.port=8080

# 4. 访问前端
浏览器打开：http://localhost:8080/

# 5. H2 数据库控制台
浏览器打开：http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./data/devops
用户名: sa
密码: (空)
```

### 后台运行（Windows）
```bash
# 方式一：使用 nohup
nohup java -jar target\devops-platform-1.0.0.jar --server.port=8080 > data\app.log 2>&1 &

# 方式二：使用 PowerShell 后台任务
Start-Process -NoNewWindow java -ArgumentList "-jar","target\devops-platform-1.0.0.jar","--server.port=8080"
```

---

## 三、功能使用指南

### 3.1 项目管理
1. 点击顶部导航"项目管理"
2. 点击"＋ 新建项目"按钮
3. 填写项目信息（名称、Git 地址、语言、框架等）
4. 保存后项目出现在列表中
5. 点击项目名称可查看详情
6. 点击"编辑"修改项目信息
7. 点击"删除"删除项目

### 3.2 流水线管理
1. 点击顶部导航"流水线"
2. 选择项目（下拉框筛选）
3. 点击"＋ 新建流水线"
4. 填写流水线定义（JSON 格式）
   - 示例：
     ```json
     [
       {"name":"代码拉取","steps":[{"name":"Git Pull","type":"SHELL","command":"git pull"}]},
       {"name":"编译构建","steps":[{"name":"Maven 编译","type":"SHELL","command":"mvn package"}]},
       {"name":"Docker 构建","steps":[{"name":"构建镜像","type":"DOCKER_BUILD"},{"name":"推送镜像","type":"DOCKER_PUSH"}]},
       {"name":"K8s 部署","steps":[{"name":"部署到集群","type":"K8S_DEPLOY"}]}
     ]
     ```
   - 步骤类型：`SHELL` / `DOCKER_BUILD` / `DOCKER_PUSH` / `K8S_DEPLOY` / `TEST`
5. 保存后点击"▶ 触发构建"执行流水线

### 3.3 构建记录
1. 点击顶部导航"构建记录"
2. 查看所有构建记录（状态、进度、耗时）
3. 点击"📄 日志"查看实时/历史构建日志
4. 如果构建正在运行，点击"取消"中止构建

### 3.4 环境管理
1. 点击顶部导航"环境管理"
2. 创建多个部署环境（dev/test/staging/prod）
3. 标记"受保护"环境（如 prod，需要审批）

### 3.5 监控看板
1. 点击顶部导航"监控看板"
2. 查看项目总数、构建总数、成功率、运行中/失败数
3. 查看构建状态分布饼图
4. 查看最近构建记录

---

## 四、系统架构设计

### 4.1 整体架构
```
┌─────────────────────────────────────────────────────┐
│                    用户浏览器                      │
│  (Vue 3 + Element Plus + Axios + STOMP)       │
└─────────────────────┬───────────────────────────┘
                        │ HTTP / WebSocket
┌─────────────────────▼───────────────────────────┐
│              Spring Boot 应用服务器                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Controller │  │  Service  │  │ Repository│  │
│  └──────────┘  └──────────┘  └──────────┘  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ WebSocket │  │  BuildSvc │  │  Schedule │  │
│  └──────────┘  └──────────┘  └──────────┘  │
└─────────────────────┬───────────────────────────┘
                        │
┌─────────────────────▼───────────────────────────┐
│                  H2 数据库 (文件)                  │
│            ./data/devops.mv.db                     │
└───────────────────────────────────────────────────┘
```

### 4.2 数据库设计

**用户表 (users)**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| username | VARCHAR(50) | 用户名（唯一） |
| password | VARCHAR | 密码（BCrypt 加密） |
| email | VARCHAR(100) | 邮箱 |
| real_name | VARCHAR(50) | 真实姓名 |
| role | VARCHAR(20) | 角色（ADMIN/DEVELOPER/VIEWER） |
| enabled | BOOLEAN | 是否启用 |
| created_at | TIMESTAMP | 创建时间 |

**项目表 (projects)**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(100) | 项目名称 |
| code | VARCHAR(50) | 项目编码 |
| description | VARCHAR(500) | 描述 |
| git_url | VARCHAR(500) | Git 地址 |
| git_branch | VARCHAR(100) | Git 分支 |
| language | VARCHAR(50) | 语言 |
| framework | VARCHAR(50) | 框架 |
| status | VARCHAR(20) | 状态（IDLE/BUILDING/DEPLOYED/FAILED） |

**流水线表 (pipelines)**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| project_id | BIGINT | 所属项目 |
| name | VARCHAR(100) | 流水线名称 |
| definition | TEXT | 流水线定义（JSON） |
| status | VARCHAR(20) | 状态（ACTIVE/INACTIVE） |

**构建记录表 (builds)**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| project_id | BIGINT | 所属项目 |
| pipeline_id | BIGINT | 所属流水线 |
| build_number | VARCHAR(50) | 构建编号（如 #123） |
| status | VARCHAR(20) | 状态（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED） |
| total_steps | INTEGER | 总步骤数 |
| completed_steps | INTEGER | 已完成步骤数 |
| duration_ms | BIGINT | 耗时（毫秒） |
| result_message | TEXT | 结果描述 |

**环境表 (environments)**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(50) | 环境名称 |
| display_name | VARCHAR(100) | 显示名称 |
| deploy_url | VARCHAR(200) | 部署地址 |
| k8s_namespace | VARCHAR(100) | K8s 命名空间 |
| protected_env | BOOLEAN | 是否受保护环境 |

### 4.3 核心接口设计

**项目管理**
- `GET /api/projects` - 获取所有项目
- `POST /api/projects` - 创建项目
- `PUT /api/projects/{id}` - 更新项目
- `DELETE /api/projects/{id}` - 删除项目

**流水线管理**
- `GET /api/pipelines` - 获取所有流水线（可按 projectId 筛选）
- `POST /api/pipelines` - 创建流水线
- `PUT /api/pipelines/{id}` - 更新流水线
- `DELETE /api/pipelines/{id}` - 删除流水线

**构建执行**
- `POST /api/builds/trigger?projectId=1&pipelineId=1` - 触发构建
- `GET /api/builds` - 获取构建记录（可按状态筛选）
- `GET /api/builds/{id}/log` - 获取构建日志
- `DELETE /api/builds/{id}/cancel` - 取消构建

**环境管理**
- `GET /api/environments` - 获取所有环境
- `POST /api/environments` - 创建环境
- `PUT /api/environments/{id}` - 更新环境
- `DELETE /api/environments/{id}` - 删除环境

**监控看板**
- `GET /api/dashboard/stats` - 获取统计数据
- `GET /api/dashboard/recent-builds?limit=10` - 获取最近构建

---

## 五、毕业设计论文大纲

### 第一章 绪论
1.1 研究背景与意义  
1.2 国内外研究现状  
1.3 研究内容与主要贡献  
1.4 论文组织结构

### 第二章 相关技术与理论基础
2.1 DevOps 理念与发展  
2.2 持续集成/持续交付（CI/CD）  
2.3 容器化技术（Docker）  
2.4 容器编排（Kubernetes）  
2.5 Spring Boot 框架  
2.6 Vue.js 前端框架  
2.7 WebSocket 实时通信

### 第三章 系统需求分析与总体设计
3.1 需求分析  
　3.1.1 功能性需求  
　3.1.2 非功能性需求  
3.2 系统总体架构设计  
3.3 数据库设计  
3.4 接口设计

### 第四章 系统详细设计与实现
4.1 项目管理模块  
　4.1.1 功能设计  
　4.1.2 核心代码实现  
4.2 流水线管理模块  
　4.2.1 流水线定义模型  
　4.2.2 阶段与步骤设计  
4.3 构建执行引擎  
　4.3.1 异步构建机制  
　4.3.2 实时日志推送  
　4.3.3 构建状态机  
4.4 环境管理模块  
4.5 监控看板模块  
4.6 前端界面实现

### 第五章 系统测试与评估
5.1 测试环境  
5.2 功能测试  
5.3 性能测试  
5.4 用户体验评估

### 第六章 总结与展望
6.1 全文总结  
6.2 不足与展望

### 参考文献
### 致谢

---

## 六、创新点说明

1. **模拟 CI/CD 全流程**：在无真实 Docker/K8s 环境下，通过模拟执行完整展示 CI/CD 流程，适合教学演示
2. **实时日志推送**：基于 WebSocket + STOMP 实现构建日志实时推送到前端
3. **流水线可配置**：用户可通过 JSON 定义任意阶段和步骤，灵活扩展
4. **多环境管理**：支持多环境部署，受保护环境需审批（可扩展）
5. **自包含部署**：使用 H2 嵌入式数据库，无需安装 MySQL，开箱即用

---

## 七、后续扩展建议

1. **集成真实 Docker/K8s**：对接 Docker API 和 Kubernetes API，实现真实容器构建和部署
2. **Git Webhook**：监听 Git 推送事件，自动触发构建
3. **用户权限系统**：完善 RBAC 权限控制
4. **邮件/钉钉通知**：构建完成/失败时发送通知
5. **镜像仓库管理**：集成 Harbor 或 Docker Hub，管理 Docker 镜像
6. **流水线模板**：提供常用流水线模板（如 Spring Boot 项目模板）
7. **分布式构建**：支持多构建节点，提升构建并发能力

---

## 八、常见问题（FAQ）

**Q1：应用启动失败，端口被占用？**  
A：修改端口：`java -jar target\devops-platform-1.0.0.jar --server.port=8081`

**Q2：如何查看构建日志？**  
A：在"构建记录"页面，点击"📄 日志"按钮。

**Q3：如何重置数据库？**  
A：删除 `./data/` 目录，重启应用，数据初始化器会自动创建演示数据。

**Q4：Maven 构建失败？**  
A：确保 Maven 环境变量配置正确，或使用提供的完整路径命令。

**Q5：前端页面样式不正常？**  
A：检查网络，确保 CDN 资源（Vue 3、Element Plus、ECharts）能正常加载。

---

> 文档生成时间：2026-06-23  
> 作者：Dearkoma  
> 指导教师：待填写  
> 学校：待填写
