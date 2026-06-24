import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchDashboardStats, fetchDashboardTrend, fetchRecentBuilds } from '../api'

function useDashboardData() {
  const [stats, setStats] = useState(null)
  const [trend, setTrend] = useState([])
  const [recent, setRecent] = useState([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [s, t, r] = await Promise.all([
        fetchDashboardStats(),
        fetchDashboardTrend(),
        fetchRecentBuilds(8),
      ])
      setStats(s)
      setTrend(t)
      setRecent(r)
    } catch (err) {
      console.error('Failed to load dashboard', err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  return { stats, trend, recent, loading, refresh: load }
}

const STATUS_LABELS = { SUCCESS: '成功', FAILED: '失败', RUNNING: '运行中', CANCELLED: '已取消' }

export default function Dashboard() {
  const navigate = useNavigate()
  const { stats, trend, recent, loading, refresh } = useDashboardData()

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  const maxBuilds = Math.max(...trend.map(d => d.total || 0), 1)

  return (
    <>
      <div className="page-header">
        <h2>监控看板</h2>
        <button className="btn btn-outline btn-sm" onClick={refresh}>🔄 刷新</button>
      </div>

      {/* Stats Cards */}
      {stats && (
        <div className="stats-grid">
          <div className="stat-card"><span className="label">项目总数</span><span className="value">{stats.totalProjects ?? 0}</span></div>
          <div className="stat-card"><span className="label">总构建次数</span><span className="value">{stats.totalBuilds ?? 0}</span></div>
          <div className="stat-card"><span className="label">成功率</span><span className="value">{stats.successRate ?? 0}%</span></div>
          <div className="stat-card"><span className="label">运行中</span><span className="value" style={{color:'#f59e0b'}}>{stats.runningBuilds ?? 0}</span></div>
          <div className="stat-card"><span className="label">平均构建耗时</span><span className="value">{stats.avgBuildTimeMs > 0 ? (stats.avgBuildTimeMs / 1000).toFixed(1) : '-'}</span><span className="suffix">秒</span></div>
          <div className="stat-card"><span className="label">活跃流水线</span><span className="value">{stats.activePipelines ?? 0}</span></div>
          <div className="stat-card"><span className="label">环境数</span><span className="value">{stats.totalEnvironments ?? 0}</span></div>
        </div>
      )}

      {/* Quick Actions */}
      <div className="quick-actions">
        <button className="quick-btn purple" onClick={() => navigate('/projects')}>📁 新建项目</button>
        <button className="quick-btn teal" onClick={() => navigate('/builds')}>🔧 构建记录</button>
        <button className="quick-btn blue" onClick={() => navigate('/environments')}>🌐 管理环境</button>
        <button className="quick-btn amber" onClick={() => navigate('/projects')}>📋 查看项目</button>
      </div>

      {/* Trend Chart */}
      <div className="card">
        <h3 style={{marginBottom: 16}}>📈 最近 7 天构建趋势</h3>
        {trend.length > 0 ? (
          <>
            <div className="trend-chart">
              {trend.map((d, i) => {
                const h = maxBuilds > 0 ? Math.max((d.total / maxBuilds) * 140, 4) : 4
                const sH = d.total > 0 ? `${(d.success / d.total) * 100}%` : '0%'
                const fH = d.total > 0 ? `${(d.failed / d.total) * 100}%` : '0%'
                const rH = d.total > 0 ? `${(d.running / d.total) * 100}%` : '0%'
                return (
                  <div key={i} className="trend-bar-group" title={`${d.date}: 成功${d.success} 失败${d.failed} 运行中${d.running}`}>
                    <div className="trend-bar-stack" style={{ height: h }}>
                      <div className="trend-bar-success" style={{ height: sH }} />
                      <div className="trend-bar-failed" style={{ height: fH }} />
                      <div className="trend-bar-running" style={{ height: rH }} />
                    </div>
                    <span className="trend-label">{d.date?.slice(5)}</span>
                  </div>
                )
              })}
            </div>
            <div className="trend-legend">
              <span className="trend-legend-item"><span className="trend-legend-dot" style={{background:'#10b981'}} /> 成功</span>
              <span className="trend-legend-item"><span className="trend-legend-dot" style={{background:'#ef4444'}} /> 失败</span>
              <span className="trend-legend-item"><span className="trend-legend-dot" style={{background:'#f59e0b'}} /> 运行中</span>
            </div>
          </>
        ) : <div className="empty-state"><p>暂无构建数据</p></div>}
      </div>

      {/* Recent Builds */}
      <div className="card">
        <h3 style={{marginBottom: 16}}>🕐 最近构建</h3>
        {recent.length > 0 ? (
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>构建编号</th>
                  <th>项目 ID</th>
                  <th>状态</th>
                  <th>开始时间</th>
                  <th>耗时</th>
                </tr>
              </thead>
              <tbody>
                {recent.map(b => (
                  <tr key={b.id}>
                    <td>#{b.buildNumber}</td>
                    <td>{b.projectId}</td>
                    <td><span className={`badge badge-${b.status?.toLowerCase()}`}>{STATUS_LABELS[b.status] || b.status}</span></td>
                    <td>{b.startTime ? new Date(b.startTime).toLocaleString() : '-'}</td>
                    <td>{b.durationMs ? `${(b.durationMs / 1000).toFixed(1)}s` : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <div className="empty-state"><p>暂无构建记录</p></div>}
      </div>
    </>
  )
}
