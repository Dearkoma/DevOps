import { createContext, useContext, useState, useEffect, useCallback } from 'react'

const AuthContext = createContext(null)
const TOKEN_KEY = 'devops_token'
const USER_KEY = 'devops_user'
// 会话版本号：改代码后递增此数字，旧 localStorage 自动失效，强制用户重新登录
// 用于清理脏数据 / 旧账号残留 token
const SESSION_VERSION_KEY = 'devops_session_version'
const SESSION_VERSION = 2

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try {
      const saved = localStorage.getItem(USER_KEY)
      return saved ? JSON.parse(saved) : null
    } catch { return null }
  })
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY))
  const [loading, setLoading] = useState(true)

  // 启动时检查会话版本，版本不匹配则清掉所有脏数据
  useEffect(() => {
    const savedVersion = Number(localStorage.getItem(SESSION_VERSION_KEY) || 0)
    if (savedVersion !== SESSION_VERSION) {
      // 旧版会话数据，全部清掉
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      localStorage.setItem(SESSION_VERSION_KEY, String(SESSION_VERSION))
      setToken(null)
      setUser(null)
      setLoading(false)
      return
    }
  }, [])

  // 启动时验证 token 有效性
  useEffect(() => {
    if (token) {
      fetch('/api/auth/me', {
        headers: { Authorization: `Bearer ${token}` },
      })
        .then(res => {
          if (res.ok) return res.json()
          throw new Error('token_invalid')
        })
        .then(data => {
          setUser(data)
          localStorage.setItem(USER_KEY, JSON.stringify(data))
        })
        .catch(() => {
          // Token 无效，清除状态
          setToken(null)
          setUser(null)
          localStorage.removeItem(TOKEN_KEY)
          localStorage.removeItem(USER_KEY)
        })
        .finally(() => setLoading(false))
    } else {
      setLoading(false)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const login = useCallback((authResponse) => {
    setToken(authResponse.token)
    setUser(authResponse)
    localStorage.setItem(TOKEN_KEY, authResponse.token)
    localStorage.setItem(USER_KEY, JSON.stringify(authResponse))
    localStorage.setItem(SESSION_VERSION_KEY, String(SESSION_VERSION))
  }, [])

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }, [])

  const role = user?.role || ''

  const isAdmin = role === 'ADMIN'
  const isManager = role === 'MANAGER'
  const isDeveloper = role === 'DEVELOPER'
  const isViewer = role === 'VIEWER'
  const canWrite = !isViewer                          // VIEWER 只读
  const canManage = isAdmin || isManager              // 管理资源 CRUD
  const canTrigger = isAdmin || isManager || isDeveloper  // 触发构建/部署申请

  const value = { user, token, loading, login, logout, isAuthenticated: !!token,
    isAdmin, isManager, isDeveloper, isViewer, canWrite, canManage, canTrigger }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
