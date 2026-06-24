import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

const allPages = [
  { path: '/dashboard', label: '监控看板', icon: '📊', minRole: 'VIEWER' },
  { path: '/projects', label: '项目管理', icon: '📁', minRole: 'VIEWER' },
  { path: '/builds', label: '构建记录', icon: '📋', minRole: 'VIEWER' },
  { path: '/schedules', label: '定时任务', icon: '⏰', minRole: 'MANAGER' },
  { path: '/artifacts', label: '制品管理', icon: '🗄️', minRole: 'VIEWER' },
  { path: '/deployments', label: '部署管理', icon: '🚀', minRole: 'VIEWER' },
  { path: '/environments', label: '环境管理', icon: '🌐', minRole: 'VIEWER' },
  { path: '/notifications', label: '通知中心', icon: '🔔', minRole: 'VIEWER' },
  { path: '/instances', label: '服务实例', icon: '🖥', minRole: 'VIEWER' },
  { path: '/templates', label: '模板管理', icon: '📄', minRole: 'VIEWER' },
  { path: '/audit', label: '审计日志', icon: '📝', minRole: 'ADMIN' },
  { path: '/users', label: '用户管理', icon: '👥', minRole: 'ADMIN' },
]

const ROLE_LEVEL = { ADMIN: 4, MANAGER: 3, DEVELOPER: 2, VIEWER: 1 }

export default function AppLayout() {
  const { user, logout, canWrite } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const userLevel = ROLE_LEVEL[user?.role] || 0
  const visiblePages = allPages.filter(p => (ROLE_LEVEL[p.minRole] || 0) <= userLevel)

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">
          🚀 DevOps<span>Platform</span>
        </div>
        <nav className="sidebar-nav">
          {visiblePages.map(({ path, label, icon }) => (
            <NavLink
              key={path}
              to={path}
              className={({ isActive }) => isActive ? 'active' : ''}
            >
              <span className="icon">{icon}</span>
              {label}
            </NavLink>
          ))}
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
