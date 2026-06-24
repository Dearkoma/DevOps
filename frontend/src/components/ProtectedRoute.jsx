import { Navigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function ProtectedRoute({ children, requireAdmin }) {
  const { isAuthenticated, loading, user } = useAuth()

  if (loading) {
    return (
      <div className="auth-page">
        <div className="auth-card" style={{ textAlign: 'center' }}>
          <span className="spinner" style={{ width: 32, height: 32 }} />
          <p style={{ marginTop: 16, color: '#6b7280' }}>验证登录状态...</p>
        </div>
      </div>
    )
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  if (requireAdmin && user?.role !== 'ADMIN') {
    return <Navigate to="/dashboard" replace />
  }

  return children
}
