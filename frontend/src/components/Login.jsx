import { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

// 后端状态：unknown / online / offline / error
export default function Login() {
  const { login, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', password: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [backendStatus, setBackendStatus] = useState('checking') // checking | online | offline | error

  // 检测后端状态
  useEffect(() => {
    let cancelled = false
    fetch('/api/auth/me')
      .then(res => {
        if (!cancelled) setBackendStatus('online')
      })
      .catch(() => {
        // 尝试连接后端健康检查
        return fetch('/api')
          .then(() => { if (!cancelled) setBackendStatus('online') })
          .catch(() => { if (!cancelled) setBackendStatus('offline') })
      })
    return () => { cancelled = true }
  }, [])

  // 已登录直接跳转
  useEffect(() => {
    if (isAuthenticated) navigate('/dashboard', { replace: true })
  }, [isAuthenticated, navigate])

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (backendStatus !== 'online') {
      setError('后端服务未启动，请先启动 Spring Boot 应用（端口 8080）')
      return
    }

    if (!form.username.trim()) { setError('请输入用户名'); return }
    if (!form.password) { setError('请输入密码'); return }

    setLoading(true)
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      })

      const data = await res.json()

      if (!res.ok) {
        if (res.status === 403) {
          setError('账号已被禁用，请联系管理员')
        } else if (res.status === 500) {
          setError('服务器内部错误（500），请检查后端日志')
        } else {
          setError(data.error || '用户名或密码错误')
        }
        return
      }

      login(data)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      if (err.message.includes('Failed to fetch') || err.name === 'TypeError') {
        setError('无法连接后端服务（ECONNREFUSED），请确保 Spring Boot 已在 8080 端口启动')
        setBackendStatus('offline')
      } else {
        setError('网络错误：' + err.message)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-logo">🚀 DevOps<span>Platform</span></div>
          <p>持续交付平台 · 请登录</p>
        </div>

        {/* 后端状态提示 */}
        {backendStatus === 'checking' && (
          <div className="auth-banner banner-info">
            <span className="spinner-sm" /> 正在检测后端服务状态...
          </div>
        )}
        {backendStatus === 'offline' && (
          <div className="auth-banner banner-error">
            ⚠️ <strong>后端服务未启动</strong>
            <p>请在 devops-platform 目录执行：<code>mvnw spring-boot:run</code></p>
          </div>
        )}
        {backendStatus === 'error' && (
          <div className="auth-banner banner-warn">
            ⚠️ 后端服务异常，部分功能可能不可用
          </div>
        )}

        {error && (
          <div className="auth-banner banner-error">{error}</div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>用户名</label>
            <input
              type="text"
              placeholder="admin"
              value={form.username}
              onChange={e => setForm({ ...form, username: e.target.value })}
              autoFocus
              disabled={loading}
            />
          </div>
          <div className="form-group">
            <label>密码</label>
            <input
              type="password"
              placeholder="••••••••"
              value={form.password}
              onChange={e => setForm({ ...form, password: e.target.value })}
              disabled={loading}
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary auth-submit"
            disabled={loading || backendStatus === 'offline'}
          >
            {loading ? <><span className="spinner" /> 登录中...</> : '登 录'}
          </button>
        </form>

        <div className="auth-footer">
          还没有账号？<Link to="/register">立即注册</Link>
        </div>
      </div>
    </div>
  )
}
