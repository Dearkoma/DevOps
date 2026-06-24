import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function Register() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({
    username: '', password: '', confirmPassword: '', email: '', realName: ''
  })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (!form.username.trim()) { setError('请输入用户名'); return }
    if (form.username.length < 3) { setError('用户名至少 3 个字符'); return }
    if (!form.password) { setError('请输入密码'); return }
    if (form.password.length < 6) { setError('密码至少 6 个字符'); return }
    if (form.password !== form.confirmPassword) { setError('两次输入的密码不一致'); return }

    setLoading(true)
    try {
      const res = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: form.username.trim(),
          password: form.password,
          email: form.email || undefined,
          realName: form.realName || undefined,
        }),
      })

      const data = await res.json()

      if (!res.ok) {
        setError(data.error || '注册失败')
        return
      }

      // 注册成功，自动登录
      login(data)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      if (err.message.includes('Failed to fetch')) {
        setError('无法连接后端服务，请确认后端已启动')
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
          <p>创建新账号</p>
        </div>

        {error && (
          <div className="auth-banner banner-error">{error}</div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>用户名 *</label>
            <input
              type="text" placeholder="3-50 个字符"
              value={form.username}
              onChange={e => setForm({ ...form, username: e.target.value })}
              autoFocus disabled={loading}
            />
          </div>
          <div className="form-group">
            <label>密码 *</label>
            <input
              type="password" placeholder="至少 6 个字符"
              value={form.password}
              onChange={e => setForm({ ...form, password: e.target.value })}
              disabled={loading}
            />
          </div>
          <div className="form-group">
            <label>确认密码 *</label>
            <input
              type="password" placeholder="再次输入密码"
              value={form.confirmPassword}
              onChange={e => setForm({ ...form, confirmPassword: e.target.value })}
              disabled={loading}
            />
          </div>
          <div className="form-group">
            <label>真实姓名</label>
            <input
              type="text" placeholder="选填"
              value={form.realName}
              onChange={e => setForm({ ...form, realName: e.target.value })}
              disabled={loading}
            />
          </div>
          <div className="form-group">
            <label>邮箱</label>
            <input
              type="email" placeholder="选填"
              value={form.email}
              onChange={e => setForm({ ...form, email: e.target.value })}
              disabled={loading}
            />
          </div>
          <button type="submit" className="btn btn-primary auth-submit" disabled={loading}>
            {loading ? <><span className="spinner" /> 注册中...</> : '注 册'}
          </button>
        </form>

        <div className="auth-footer">
          已有账号？<Link to="/login">返回登录</Link>
        </div>
      </div>
    </div>
  )
}
