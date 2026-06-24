# DevOps 持续交付平台 — IDEA + MySQL 运行指南

## 一、环境准备

### 1. 安装并启动 MySQL 8.0+
- 下载：https://dev.mysql.com/downloads/mysql/
- 安装完成后，记住 **root 密码**

### 2. 创建数据库并导入演示数据
打开 **MySQL 命令行** 或 **Navicat / Workbench**，执行：

```sql
-- 方法一：直接复制 sql/init.sql 里的语句执行
-- 方法二：在命令行执行
source E:/Dissertation/2/devops-platform/sql/init.sql
```

确认 5 张表都创建成功：
```sql
USE devops_platform;
SHOW TABLES;
-- 应看到：builds / environments / projects / pipelines / users / service_instances
```

### 3. 确认 MySQL 用户密码
打开 `src/main/resources/application.yml`，确认这行是你的 MySQL 密码：
```yaml
spring.datasource.password: 123456   # ← 改成你的 MySQL root 密码
```

---

## 二、IDEA 导入并运行（关键步骤）

### 步骤 1：用 IDEA 打开项目
1. 打开 **IntelliJ IDEA**
2. **File → Open**
3. 选择 `E:\Dissertation\2\devops-platform` 文件夹
4. 点击 **OK**

### 步骤 2：以 Maven 项目导入（最重要！）
IDEA 打开后：
- 如果右下角弹出 **"Maven projects need to be imported"** → 点击 **Import Changes**
- 如果没有弹出：
  1. 在左侧项目树右键点击 `pom.xml`
  2. 选择 **Add as Maven Project**

等待 IDEA 索引完成（右下角进度条消失）。

### 步骤 3：配置 JDK 21
1. **File → Project Structure**
2. 左侧选 **Project**
3. **Project SDK** → 选择 **JDK 21**
   - 如果没有 JDK 21：点击 **Add SDK → Download JDK** → 选择版本 21 → 下载
4. **Project language level** → 选择 **"21 - Record patterns, ..."**
5. 点击 **OK**

### 步骤 4：下载 Maven 依赖
1. 打开右侧 **Maven** 工具栏（右边竖条）
2. 点击 **刷新图标**（Reload All Maven Projects）
3. 等待依赖下载完成（第一次约 2-5 分钟）

### 步骤 5：运行项目
**方法一（推荐）**：
1. 在左侧项目树打开：
   `src/main/java/com/devops/platform/DevOpsPlatformApplication.java`
2. 在文件编辑区内 **右键**
3. 选择 **Run 'DevOpsPlatformApplication.main()'**

**方法二**：
1. 点击右上角 **运行配置下拉框**
2. 选择 **DevOpsPlatformApplication**
3. 点击绿色 **▶ 运行按钮**

---

## 三、验证运行成功

### 1. 查看控制台输出
启动成功后，控制台最后几行应显示：
```
Tomcat started on port(s): 8080 (http)
Started DevOpsPlatformApplication in X.XXX seconds
========== DevOps 平台数据初始化完成 ==========
```

### 2. 测试接口
打开浏览器，访问：

| 功能 | 地址 | 说明 |
|---|---|---|
| 首页 | http://localhost:8080/ | 前端界面 |
| 项目列表 API | http://localhost:8080/api/projects | JSON 数据 |
| 流水线列表 API | http://localhost:8080/api/pipelines | JSON 数据 |
| 环境列表 API | http://localhost:8080/api/environments | JSON 数据 |
| 监控看板 API | http://localhost:8080/api/dashboard/stats | 统计数据 |

---

## 四、常见问题解决

### ❌ 问题 1：`java: error: invalid source release: 21`
**原因**：项目 JDK 版本不对
**解决**：
1. **File → Project Structure → Project**
2. **Project SDK** 选择 JDK 21
3. **Project language level** 选择 21

---

### ❌ 问题 2：`Cannot resolve symbol 'SpringBootApplication'`
**原因**：Maven 依赖未下载
**解决**：
1. 右侧 **Maven** 工具栏 → 点击刷新按钮
2. 等待下载完成（看底部状态栏）

---

### ❌ 问题 3：`Access denied for user 'root'@'localhost'`
**原因**：MySQL 密码错误
**解决**：
1. 打开 `src/main/resources/application.yml`
2. 修改 `spring.datasource.password` 为你的 MySQL root 密码
3. 重新运行

---

### ❌ 问题 4：`Table 'devops_platform.xxx' doesn't exist`
**原因**：数据库未初始化
**解决**：执行 `sql/init.sql` 脚本（见本文档第一节）

---

### ❌ 问题 5：`Port 8080 was already in use`
**原因**：8080 端口被占用
**解决（二选一）**：
- 方法 A：结束占用 8080 的进程
- 方法 B：修改 `application.yml` 中的 `server.port` 为 `8081`（或其他端口）

---

### ❌ 问题 6：IDEA 找不到主类，无法运行
**解决**：
1. 右键点击 `DevOpsPlatformApplication.java`
2. 选择 **Run 'DevOpsPlatformApplication.main()'**
3. 不要右键整个项目运行

---

## 五、功能演示

### 触发一次构建（模拟 CI/CD）
在浏览器访问：
```
http://localhost:8080/api/builds/trigger?projectId=1&pipelineId=1
```
返回构建信息即表示成功。

### 查看构建日志
```
http://localhost:8080/api/builds/1/log
```

---

## 六、项目结构说明

```
devops-platform/
├── sql/
│   └── init.sql              ← MySQL 初始化脚本（先执行这个！）
├── src/main/java/com/devops/platform/
│   ├── DevOpsPlatformApplication.java   ← 启动类
│   ├── config/                         ← 配置类
│   ├── entity/                         ← 实体类（对应数据库表）
│   ├── repository/                      ← 数据访问层
│   ├── service/                        ← 业务逻辑层
│   └── controller/                     ← REST API 控制器
├── src/main/resources/
│   ├── application.yml                 ← 配置文件（修改 MySQL 密码这里！）
│   └── static/                        ← 前端页面
│       ├── index.html
│       ├── css/style.css
│       └── js/app.js
└── pom.xml                             ← Maven 依赖配置
```

---

## 七、MySQL 密码修改提醒

如果你修改了 MySQL 密码，只需要改一个地方：

**文件**：`src/main/resources/application.yml`

```yaml
spring:
  datasource:
    password: 你的新密码   ← 改这里
```

然后重新运行项目即可。
