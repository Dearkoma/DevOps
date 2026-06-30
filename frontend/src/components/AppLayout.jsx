import { useState, useEffect, useCallback } from 'react'
import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchDashboardStats } from '../api'

// ── 分组菜单结构 ──
// 每个分组可折叠展开，子项统一使用 sidebar-subitem 缩进样式
const menuGroups = [
  {
    key: 'dev',
    label: '项目配置',
    icon: '⚙️',
    defaultOpen: true,
    items: [
      { path: '/projects',    label: '项目管理',     icon: '📁', minRole: 'MANAGER' },
      { path: '/pipelines',   label: '流水线管理',   icon: '🔧', minRole: 'MANAGER' },
      { path: '/templates',   label: '模板管理',     icon: '📄', minRole: 'MANAGER' },
    ],
  },
  {
    key: 'cicd',
    label: '构建 & 制品',
    icon: '🔨',
    defaultOpen: true,
    items: [
      { path: '/builds',      label: '构建记录',     icon: '📋', minRole: 'DEVELOPER' },
      { path: '/schedules',   label: '定时任务',     icon: '⏰', minRole: 'MANAGER' },
      { path: '/artifacts',   label: '制品管理',     icon: '🗄️', minRole: 'DEVELOPER' },
    ],
  },
  {
    key: 'deploy',
    label: '部署 & 环境',
    icon: '🚀',
    defaultOpen: true,
    items: [
      { path: '/deployments',   label: '部署管理',   icon: '🚀', minRole: 'DEVELOPER' },
      { path: '/environments',  label: '环境管理',   icon: '🌐', minRole: 'MANAGER' },
    ],
  },
  {
    key: 'instances',
    label: '服务实例',
    icon: '🖥',
    defaultOpen: false,
    // 服务实例子菜单样式：父级可点展开，子项带缩进
    items: [
      { path: '/instances',         label: '全部实例', icon: '',   minRole: 'DEVELOPER' },
      { path: '/instances/docker',  label: 'Docker',    icon: '🐳', minRole: 'DEVELOPER' },
      { path: '/instances/k8s',     label: 'K8s',        icon: '☸️', minRole: 'DEVELOPER' },
    ],
  },
  {
    key: 'monitor',
    label: '监控 & 审计',
    icon: '📊',
    defaultOpen: false,
    items: [
      { path: '/dashboard',       label: '监控看板',   icon: '📊', minRole: 'VIEWER' },
      { path: '/notifications',   label: '通知中心',   icon: '🔔', minRole: 'DEVELOPER' },
      { path: '/audit',           label: '审计日志',   icon: '📝', minRole: 'ADMIN' },
    ],
  },
  {
    key: 'admin',
    label: '系统管理',
    icon: '🛠️',
    defaultOpen: false,
    items: [
      { path: '/users', label: '用户管理', icon: '👥', minRole: 'ADMIN' },
    ],
  },
]

const ROLE_LEVEL = { ADMIN: 4, MANAGER: 3, DEVELOPER: 2, VIEWER: 1 }

export default function AppLayout() {
  const { user, logout, canWrite } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [expanded, setExpanded] = useState({})
  const [sidebarStats, setSidebarStats] = useState({
    totalProjects: 0,
    totalBuilds: 0,
    activePipelines: 0,
    totalEnvironments: 0,
    totalDeployments: 0,
    runningInstances: 0,
    totalUsers: 0,
    totalAuditLogs: 0,
    runningBuilds: 0,
    pendingDeployments: 0,
    unreadNotifications: 0,
  })

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  // 加载侧边栏统计数字
  const loadStats = useCallback(async () => {
    try {
      const data = await fetchDashboardStats()
      setSidebarStats({
        totalProjects:       data.totalProjects || 0,
        totalBuilds:         data.totalBuilds || 0,
        activePipelines:     data.activePipelines || 0,
        totalEnvironments:    data.totalEnvironments || 0,
        totalDeployments:     data.totalDeployments || 0,
        runningInstances:     data.runningInstances || 0,
        totalUsers:           data.totalUsers || 0,
        totalAuditLogs:       data.totalAuditLogs || 0,
        runningBuilds:       data.runningBuilds || 0,
        pendingDeployments:   data.pendingDeployments || 0,
        unreadNotifications:  data.unreadNotifications || 0,
      })
    } catch (e) {
      // 静默失败，侧边栏 badge 不显示而已
    }
  }, [])

  // 初始加载 + 每 60 秒刷新一次
  useEffect(() => {
    loadStats()
    const timer = setInterval(loadStats, 60000)
    return () => clearInterval(timer)
  }, [loadStats])

  // 返回菜单项对应的记录总数（用于灰色气泡，始终显示）
  const getTotalCount = (path) => {
    switch (path) {
      case '/projects':     return sidebarStats.totalProjects
      case '/pipelines':   return sidebarStats.activePipelines
      case '/builds':       return sidebarStats.totalBuilds
      case '/deployments': return sidebarStats.totalDeployments
      case '/environments': return sidebarStats.totalEnvironments
      case '/instances':   return sidebarStats.runningInstances
      case '/notifications': return sidebarStats.unreadNotifications
      case '/audit':        return sidebarStats.totalAuditLogs
      case '/users':        return sidebarStats.totalUsers
      default: return 0
    }
  }

  // 返回红色待办气泡数量（仅 >0 时显示）
  const getAlertCount = (path) => {
    switch (path) {
      case '/builds':      return sidebarStats.runningBuilds
      case '/deployments': return sidebarStats.pendingDeployments
      default: return 0
    }
  }

  // 已登录但 role 无效（localStorage 旧数据/脏数据）时，降级到 DEVELOPER 避免菜单全空
  const userLevel = ROLE_LEVEL[user?.role] ?? (user ? ROLE_LEVEL.DEVELOPER : 0)

  // 过滤不可见分组（组内至少有一项可见）
  const visibleGroups = menuGroups.map(group => {
    const visibleItems = group.items.filter(
      item => (ROLE_LEVEL[item.minRole] || 0) <= userLevel
    )
    return { ...group, items: visibleItems }
  }).filter(group => group.items.length > 0)

  const toggleExpand = (key) => {
    setExpanded(prev => ({ ...prev, [key]: !prev[key] }))
  }

  // 判断分组当前是否活跃（有子项匹配当前路由）
  const isGroupActive = (items) => {
    return items.some(item =>
      location.pathname === item.path || location.pathname.startsWith(item.path + '/')
    )
  }

  // 展开状态：手动点击优先；未手动设置时，活跃分组默认展开，否则用 defaultOpen
  const getExpanded = (group) => {
    if (expanded[group.key] !== undefined) return expanded[group.key]
    return isGroupActive(group.items) ? true : group.defaultOpen
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">
          🚀 DevOps<span>Platform</span>
        </div>
        <nav className="sidebar-nav">
          {visibleGroups.map(group => {
            const isOpen = getExpanded(group)
            const active = isGroupActive(group.items)

            return (
              <div key={group.key} className="sidebar-group">
                {/* 分组头 */}
                <div
                  className={`sidebar-group-header ${active ? 'active' : ''}`}
                  onClick={() => toggleExpand(group.key)}
                >
                  <span className="icon">{group.icon}</span>
                  <span className="sidebar-group-label">{group.label}</span>
                  <span className={`sidebar-group-arrow ${isOpen ? 'open' : ''}`}>▶</span>
                </div>

                {/* 子项列表 */}
                {isOpen && (
                  <div className="sidebar-submenu">
                    {group.items.map(item => {
                      const totalCount  = getTotalCount(item.path)
                      const alertCount  = getAlertCount(item.path)
                      return (
                      <NavLink
                        key={item.path}
                        to={item.path}
                        end={item.path === '/instances'}
                        className={({ isActive }) =>
                          'sidebar-subitem ' + (isActive ? 'active' : '')
                        }
                      >
                        {item.icon && <span className="icon">{item.icon}</span>}
                        {item.label}
                        {/* 灰色：记录总数，始终显示 */}
                        {totalCount > 0 && (
                          <span className="sidebar-badge sidebar-badge-total">
                            {totalCount > 999 ? '999+' : totalCount}
                          </span>
                        )}
                        {/* 红色：待办/运行中的数量，仅 >0 时显示 */}
                        {alertCount > 0 && (
                          <span className="sidebar-badge sidebar-badge-alert">
                            {alertCount > 99 ? '99+' : alertCount}
                          </span>
                        )}
                      </NavLink>
                      )
                    })}
                  </div>
                )}
              </div>
            )
          })}
        </nav>
        <div className="sidebar-user">
          <div className="sidebar-user-info">
            <div className="sidebar-user-avatar">
              {(user?.realName || user?.username || '?')[0].toUpperCase()}
            </div>
            <div>
              <div className="sidebar-user-name">{user?.realName || user?.username}</div>
              <div className="sidebar-user-role">
                {user?.role === 'ADMIN' ? '管理员' :
                 user?.role === 'MANAGER' ? '项目经理' :
                 user?.role === 'DEVELOPER' ? '开发者' :
                 user?.role === 'VIEWER' ? '观察者' : user?.role}
                {!canWrite && <span style={{ marginLeft: 4, fontSize: 10, color: '#ef4444' }}>只读</span>}
              </div>
            </div>
          </div>
          <button className="sidebar-logout" onClick={handleLogout} title="退出登录">
            ⇥
          </button>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
