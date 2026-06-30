import { useState, useEffect, useCallback } from 'react'
import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchDashboardStats, fetchInstances } from '../api'

const allPages = [
  { path: '/dashboard', label: '监控看板', icon: '📊', minRole: 'VIEWER' },
  { path: '/projects', label: '项目管理', icon: '📁', minRole: 'MANAGER' },
  { path: '/pipelines', label: '流水线管理', icon: '🔧', minRole: 'MANAGER' },
  { path: '/builds', label: '构建记录', icon: '📋', minRole: 'DEVELOPER' },
  { path: '/schedules', label: '定时任务', icon: '⏰', minRole: 'MANAGER' },
  { path: '/artifacts', label: '制品管理', icon: '🗄️', minRole: 'DEVELOPER' },
  { path: '/deployments', label: '部署管理', icon: '🚀', minRole: 'DEVELOPER' },
  { path: '/environments', label: '环境管理', icon: '🌐', minRole: 'MANAGER' },
  { path: '/notifications', label: '通知中心', icon: '🔔', minRole: 'DEVELOPER' },
  {
    path: '/instances', label: '服务实例', icon: '🖥', minRole: 'DEVELOPER',
    children: [
      { path: '/instances/docker', label: 'Docker', icon: '🐳' },
      { path: '/instances/k8s',   label: 'K8s',   icon: '☸️' },
    ],
  },
  { path: '/templates', label: '模板管理', icon: '📄', minRole: 'MANAGER' },
  { path: '/audit', label: '审计日志', icon: '📝', minRole: 'ADMIN' },
  { path: '/users', label: '用户管理', icon: '👥', minRole: 'ADMIN' },
]

const ROLE_LEVEL = { ADMIN: 4, MANAGER: 3, DEVELOPER: 2, VIEWER: 1 }

function isParentActive(parentPath, location, children) {
  if (location.pathname.startsWith(parentPath)) return true
  if (children && children.some(c => location.pathname.startsWith(c.path))) return true
  return false
}

function CountBadge({ count }) {
  if (!count || count === 0) return null
  const display = count > 999 ? '999+' : String(count)
  return (
    <span style={{
      marginLeft: 'auto',
      minWidth: 18, height: 18, padding: '0 5px',
      borderRadius: 9,
      background: 'rgba(108,99,255,0.3)',
      color: '#a5a0ff',
      fontSize: 11, fontWeight: 600,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      lineHeight: 1,
    }}>{display}</span>
  )
}

export default function AppLayout() {
  const { user, logout, canWrite } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [expanded, setExpanded] = useState({})
  const [badgeCounts, setBadgeCounts] = useState({})

  const fetchBadgeCounts = useCallback(async () => {
    try {
      const [stats, instances] = await Promise.all([
        fetchDashboardStats(),
        fetchInstances().catch(() => []),
      ])
      const dockerCount = (instances || []).filter(i => i.deployType === 'DOCKER').length
      const k8sCount = (instances || []).filter(i => i.deployType === 'K8S').length
      setBadgeCounts({
        '/projects': stats.totalProjects ?? 0,
        '/pipelines': stats.activePipelines ?? 0,
        '/builds': stats.totalBuilds ?? 0,
        '/schedules': stats.totalSchedules ?? 0,
        '/artifacts': stats.totalArtifacts ?? 0,
        '/deployments': stats.pendingDeployments ?? 0,
        '/environments': stats.totalEnvironments ?? 0,
        '/notifications': stats.unreadNotifications ?? 0,
        '/instances': (instances || []).length,
        '/instances/docker': dockerCount,
        '/instances/k8s': k8sCount,
        '/templates': stats.totalTemplates ?? 0,
        '/audit': stats.totalAuditLogs ?? 0,
        '/users': stats.totalUsers ?? 0,
      })
    } catch (e) {
      // 静默失败，不影响导航
    }
  }, [])

  useEffect(() => {
    fetchBadgeCounts()
    const timer = setInterval(fetchBadgeCounts, 30000)
    return () => clearInterval(timer)
  }, [fetchBadgeCounts])

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const userLevel = ROLE_LEVEL[user?.role] || 0
  const visiblePages = allPages.filter(p => (ROLE_LEVEL[p.minRole] || 0) <= userLevel)

  const toggleExpand = (path) => {
    setExpanded(prev => ({ ...prev, [path]: !prev[path] }))
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">
          🚀 DevOps<span>Platform</span>
        </div>
        <nav className="sidebar-nav">
          {visiblePages.map(({ path, label, icon, children }) => {
            const hasChildren = children && children.length > 0
            const isExpanded = expanded[path] !== false // default expanded
            const active = hasChildren
              ? isParentActive(path, location, children)
              : location.pathname === path || location.pathname.startsWith(path + '/')

            return (
              <div key={path} style={{ marginBottom: 0 }}>
                {hasChildren ? (
                  <>
                    <div
                      className={`sidebar-item sidebar-parent ${active ? 'active' : ''}`}
                      onClick={() => toggleExpand(path)}
                      style={{ cursor: 'pointer' }}
                    >
                      <span className="icon">{icon}</span>
                      {label}
                      <CountBadge count={badgeCounts[path]} />
                      <span style={{ fontSize: 10, transition: 'transform 0.2s', transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)' }}>▶</span>
                    </div>
                    {isExpanded && (
                      <div className="sidebar-submenu">
                        <NavLink
                          to={path}
                          end
                          className={({ isActive }) => 'sidebar-subitem ' + (isActive ? 'active' : '')}
                        >
                          全部实例
                          <CountBadge count={badgeCounts[path]} />
                        </NavLink>
                        {children.map(c => (
                          <NavLink
                            key={c.path}
                            to={c.path}
                            className={({ isActive }) => 'sidebar-subitem ' + (isActive ? 'active' : '')}
                          >
                            <span className="icon">{c.icon}</span>
                            {c.label}
                            <CountBadge count={badgeCounts[c.path]} />
                          </NavLink>
                        ))}
                      </div>
                    )}
                  </>
                ) : (
                  <NavLink
                    to={path}
                    className={({ isActive }) => 'sidebar-item ' + (isActive ? 'active' : '')}
                  >
                    <span className="icon">{icon}</span>
                    {label}
                    <CountBadge count={badgeCounts[path]} />
                  </NavLink>
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
