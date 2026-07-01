# DevOps Platform 项目约定

## Git 工作流
- **自动提交**: 每次代码改动自动 git add + git commit，不询问用户
- **不自动 push**: push 操作需要用户明确指示
- **Commit 风格**: conventional commits (feat:/fix:/refactor:/chore:)
- **远程仓库**: GitHub (DevOps) + Gitee (Devops)，分支 main

## 项目架构
- 后端: Spring Boot 3.2.1 + Java 21, devops-platform/
- 前端: React 19 + Vite 6, frontend/
- 构建: Maven Wrapper (mvnw), Node.js

## 开发注意事项
- 前端开发端口 3000，后端 8080
- 数据库: MySQL 8.x (devops_platform)，JPA ddl-auto=update
- 默认管理员: admin / admin123
- 角色体系: ADMIN > MANAGER > DEVELOPER > VIEWER

## 待修复的问题 (低优先级)
- 配置文件密码明文 (application.yml)
- Entity 层缺少外键约束 (@ManyToOne)
- 生产环境 ddl-auto: update 危险
- WebSocket 端点无认证保护
- BuildController triggeredBy 默认值问题
- Controller 响应格式不统一
- Layout.jsx 是死代码，可删除
- 多个组件的 console.log 需要清理
- 全项目使用 alert() 作为错误提示，可统一为 toast
