"""生成开题报告架构图 — 使用 matplotlib 绘制带圆角矩形框的图"""

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.font_manager import FontProperties
import os

OUTPUT_DIR = r"E:\Dissertation\2\devops-platform\outputs\images"
FONT_HEITI = FontProperties(fname=r"C:\Windows\Fonts\simhei.ttf", size=13)
FONT_HEITI_SM = FontProperties(fname=r"C:\Windows\Fonts\simhei.ttf", size=10)
FONT_HEITI_XS = FontProperties(fname=r"C:\Windows\Fonts\simhei.ttf", size=9)
FONT_SONG = FontProperties(fname=r"C:\Windows\Fonts\simsun.ttc", size=11)
FONT_YAHEI = FontProperties(fname=r"C:\Windows\Fonts\msyh.ttc", size=10)

BOX_STYLE = dict(
    boxstyle="round,pad=0.5,rounding_size=8",
    edgecolor="#2c3e50",
    facecolor="#ecf0f1",
    linewidth=1.5,
)

def draw_box(ax, x, y, w, h, text, facecolor="#ecf0f1", edgecolor="#2c3e50", fontsize=10, font=None, text_color="#2c3e50"):
    """绘制带圆角的矩形框"""
    rect = mpatches.FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad=0.3,rounding_size=6",
        facecolor=facecolor,
        edgecolor=edgecolor,
        linewidth=1.5,
    )
    ax.add_patch(rect)
    ax.text(
        x + w / 2, y + h / 2, text,
        ha='center', va='center',
        fontsize=fontsize,
        fontproperties=font,
        color=text_color,
        fontweight='bold',
    )


def draw_arrow(ax, x1, y1, x2, y2, color="#7f8c8d"):
    """绘制箭头"""
    ax.annotate(
        '', xy=(x2, y2), xytext=(x1, y1),
        arrowprops=dict(
            arrowstyle='->',
            color=color,
            lw=1.8,
            connectionstyle='arc3,rad=0'
        )
    )


def draw_label(ax, x, y, text, font=FONT_YAHEI, fontsize=9, color="#7f8c8d"):
    """绘制标签文字"""
    ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
            fontproperties=font, color=color)


def create_architecture_diagram():
    """系统架构图 — 多层矩形框展示前后端+数据层"""
    fig, ax = plt.subplots(1, 1, figsize=(14, 9))
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 9)
    ax.axis('off')
    ax.set_facecolor('#fafafa')
    fig.patch.set_facecolor('#fafafa')

    # 标题
    ax.text(7, 8.5, '系统整体架构图', ha='center', va='center',
            fontsize=18, fontproperties=FONT_HEITI, color='#2c3e50', fontweight='bold')

    # ===== 客户端层 =====
    ax.text(0.5, 7.2, '客户端层', fontsize=11, fontproperties=FONT_HEITI, color='#2980b9', fontweight='bold')
    draw_box(ax, 0.4, 6.5, 2.8, 0.6, 'Web 浏览器', facecolor='#d4efdf', edgecolor='#27ae60', font=FONT_HEITI_SM)
    draw_box(ax, 3.6, 6.5, 2.8, 0.6, 'API 客户端', facecolor='#d4efdf', edgecolor='#27ae60', font=FONT_HEITI_SM)

    # 箭头：客户端→前端
    draw_arrow(ax, 3.2, 6.8, 3.2, 5.8, '#27ae60')
    draw_arrow(ax, 7.0, 6.8, 7.0, 5.8, '#27ae60')

    # ===== 前端展示层 =====
    ax.text(0.5, 5.2, '前端展示层', fontsize=11, fontproperties=FONT_HEITI, color='#2980b9', fontweight='bold')
    draw_box(ax, 0.4, 4.5, 3.5, 0.6, 'React 19 + Vite', facecolor='#d6eaf8', edgecolor='#2980b9', font=FONT_HEITI_SM)
    draw_box(ax, 4.3, 4.5, 3.0, 0.6, 'React Router 7', facecolor='#d6eaf8', edgecolor='#2980b9', font=FONT_HEITI_SM)
    draw_box(ax, 7.7, 4.5, 2.5, 0.6, 'Axios HTTP', facecolor='#d6eaf8', edgecolor='#2980b9', font=FONT_HEITI_SM)

    # 大框包裹前端
    rect_front = mpatches.FancyBboxPatch(
        (0.2, 4.3), 10.2, 1.5,
        boxstyle="round,pad=0.1,rounding_size=10",
        facecolor='none', edgecolor='#2980b9', linewidth=2, linestyle='--'
    )
    ax.add_patch(rect_front)
    ax.text(0.4, 5.55, 'SPA 单页应用（REST API 通信）', fontsize=9, fontproperties=FONT_YAHEI, color='#2980b9')

    # 箭头：前端→后端
    draw_arrow(ax, 5.0, 4.3, 5.0, 3.8, '#8e44ad')

    # ===== 后端业务层 =====
    ax.text(0.5, 3.3, '后端业务层', fontsize=11, fontproperties=FONT_HEITI, color='#8e44ad', fontweight='bold')
    # 核心
    draw_box(ax, 0.4, 2.0, 3.0, 1.2, 'Spring Boot 3.2\n核心引擎 (8080)', facecolor='#e8daef', edgecolor='#8e44ad', font=FONT_HEITI_SM, text_color='#6c3483')

    # Spring 组件
    draw_box(ax, 3.8, 2.6, 2.2, 0.55, 'Spring Security\nJWT 认证', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')
    draw_box(ax, 3.8, 1.95, 2.2, 0.55, 'Spring Data JPA\n数据访问', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')
    draw_box(ax, 6.4, 2.6, 2.2, 0.55, 'WebSocket\nSTOMP 推送', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')
    draw_box(ax, 6.4, 1.95, 2.2, 0.55, '@Scheduled\n定时任务', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')
    draw_box(ax, 9.0, 2.6, 2.0, 0.55, 'AOP 审计\n@Audit', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')
    draw_box(ax, 9.0, 1.95, 2.0, 0.55, 'Git Webhook\n接收器', facecolor='#f5eef8', edgecolor='#a569bd', font=FONT_HEITI_XS, text_color='#6c3483')

    # 箭号：后端→数据
    draw_arrow(ax, 5.0, 1.9, 5.0, 1.5, '#e74c3c')

    # ===== 数据持久层 =====
    ax.text(0.5, 1.05, '数据持久层', fontsize=11, fontproperties=FONT_HEITI, color='#e74c3c', fontweight='bold')
    draw_box(ax, 0.4, 0.3, 3.0, 0.6, 'MySQL 数据库', facecolor='#fadbd8', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')
    draw_box(ax, 3.8, 0.3, 3.0, 0.6, '文件存储 (制品 JAR)', facecolor='#fadbd8', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')
    draw_box(ax, 7.2, 0.3, 3.4, 0.6, 'Redis (缓存·可选)', facecolor='#fadbd8', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')

    # ===== 外部系统 (右侧) =====
    ax.text(11.5, 7.2, '外部系统', fontsize=11, fontproperties=FONT_HEITI, color='#d35400', fontweight='bold')
    draw_box(ax, 11.5, 6.4, 2.2, 0.7, 'GitHub / GitLab\nWebhook 事件', facecolor='#fdebd0', edgecolor='#d35400', font=FONT_HEITI_XS, text_color='#a04000')
    draw_box(ax, 11.5, 5.2, 2.2, 0.7, 'Docker 镜像\n仓库', facecolor='#fdebd0', edgecolor='#d35400', font=FONT_HEITI_XS, text_color='#a04000')
    draw_box(ax, 11.5, 4.0, 2.2, 0.7, 'Kubernetes\n容器编排', facecolor='#fdebd0', edgecolor='#d35400', font=FONT_HEITI_XS, text_color='#a04000')

    # Webhook 箭头
    ax.annotate('', xy=(11.2, 6.7), xytext=(8.8, 2.87),
                arrowprops=dict(arrowstyle='->', color='#d35400', lw=1.5, connectionstyle='arc3,rad=-0.25'))
    ax.text(9.5, 5.3, 'Webhook 触发', fontsize=8, fontproperties=FONT_YAHEI, color='#d35400', rotation=65)

    plt.tight_layout()
    path = os.path.join(OUTPUT_DIR, 'architecture.png')
    plt.savefig(path, dpi=200, bbox_inches='tight', facecolor='#fafafa')
    plt.close()
    print(f"架构图已保存到: {path}")
    return path


def create_module_diagram():
    """功能模块图 — 按 P0/P1/P2 分层展示 12 项特性"""
    fig, ax = plt.subplots(1, 1, figsize=(14, 8))
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 8)
    ax.axis('off')
    ax.set_facecolor('#fafafa')
    fig.patch.set_facecolor('#fafafa')

    ax.text(7, 7.6, '系统功能模块图', ha='center', va='center',
            fontsize=18, fontproperties=FONT_HEITI, color='#2c3e50', fontweight='bold')

    # P0 核心功能
    ax.text(0.5, 6.8, 'P0 — 核心基础功能', fontsize=12, fontproperties=FONT_HEITI, color='#c0392b', fontweight='bold')
    draw_box(ax, 0.35, 5.7, 2.9, 0.85, '实时构建日志\nWebSocket STOMP 推送', facecolor='#fdedec', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')
    draw_box(ax, 3.55, 5.7, 2.9, 0.85, 'Git Webhook 触发\n自动构建流水线', facecolor='#fdedec', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')
    draw_box(ax, 6.75, 5.7, 2.9, 0.85, 'Cron 定时构建\n分钟级调度引擎', facecolor='#fdedec', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')
    draw_box(ax, 9.95, 5.7, 3.55, 0.85, '部署审批与状态机\nPENDING→APPROVED→DEPLOYED', facecolor='#fdedec', edgecolor='#e74c3c', font=FONT_HEITI_SM, text_color='#c0392b')

    # P1 增强功能
    ax.text(0.5, 5.0, 'P1 — 增强功能', fontsize=12, fontproperties=FONT_HEITI, color='#d35400', fontweight='bold')
    draw_box(ax, 0.35, 3.85, 3.0, 0.85, '制品管理\nJAR/WAR 自动收集与下载', facecolor='#fef5e7', edgecolor='#f39c12', font=FONT_HEITI_SM, text_color='#d35400')
    draw_box(ax, 3.65, 3.85, 3.0, 0.85, '通知中心\n构建状态/部署审批通知', facecolor='#fef5e7', edgecolor='#f39c12', font=FONT_HEITI_SM, text_color='#d35400')
    draw_box(ax, 6.95, 3.85, 3.0, 0.85, '部署历史与回滚\n任意时间点一键回滚', facecolor='#fef5e7', edgecolor='#f39c12', font=FONT_HEITI_SM, text_color='#d35400')
    draw_box(ax, 10.25, 3.85, 3.25, 0.85, '服务实例监控\nCPU/内存/心跳 30s 检测', facecolor='#fef5e7', edgecolor='#f39c12', font=FONT_HEITI_SM, text_color='#d35400')

    # P2 锦上添花
    ax.text(0.5, 3.15, 'P2 — 扩展特性', fontsize=12, fontproperties=FONT_HEITI, color='#2471a3', fontweight='bold')
    draw_box(ax, 0.35, 2.0, 3.0, 0.85, '审计日志\nAOP 注解自动记录操作', facecolor='#ebf5fb', edgecolor='#5dade2', font=FONT_HEITI_SM, text_color='#2471a3')
    draw_box(ax, 3.65, 2.0, 3.0, 0.85, '模板管理\n8 个 Dockerfile/K8s YAML 模板', facecolor='#ebf5fb', edgecolor='#5dade2', font=FONT_HEITI_SM, text_color='#2471a3')
    draw_box(ax, 6.95, 2.0, 3.0, 0.85, '参数化构建\n版本/分支/环境参数注入', facecolor='#ebf5fb', edgecolor='#5dade2', font=FONT_HEITI_SM, text_color='#2471a3')
    draw_box(ax, 10.25, 2.0, 3.25, 0.85, '多分支流水线\nbranchPattern glob 匹配', facecolor='#ebf5fb', edgecolor='#5dade2', font=FONT_HEITI_SM, text_color='#2471a3')

    # RBAC 权限
    draw_box(ax, 3.5, 0.5, 6.5, 0.7, '四级角色权限控制: ADMIN → MANAGER → DEVELOPER → VIEWER',
             facecolor='#eaf2f8', edgecolor='#2c3e50', font=FONT_HEITI_SM, text_color='#2c3e50')

    plt.tight_layout()
    path = os.path.join(OUTPUT_DIR, 'modules.png')
    plt.savefig(path, dpi=200, bbox_inches='tight', facecolor='#fafafa')
    plt.close()
    print(f"功能模块图已保存到: {path}")
    return path


if __name__ == '__main__':
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    create_architecture_diagram()
    create_module_diagram()
    print("所有图片生成完成！")
