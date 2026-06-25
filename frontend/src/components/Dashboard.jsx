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

      {/* Donut Chart: 7天构建分布 */}
      <div className="card">
        <h3 style={{marginBottom: 16}}>📈 最近 7 天构建分布</h3>
        {trend.length > 0 ? (
          <BuildDonut trend={trend} />
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

// ===================== 环形图组件 =====================
// 使用 stroke-dasharray + stroke-dashoffset 绘制，比 path arc 更稳健：
// - 单一状态（100%）也能完整闭合显示
// - 段间留间隙，端点圆角，更符合现代环形图视觉
function BuildDonut({ trend }) {
  const total = { success: 0, failed: 0, running: 0, count: 0 }
  trend.forEach(d => {
    total.success += d.success || 0
    total.failed += d.failed || 0
    total.running += d.running || 0
  })
  total.count = total.success + total.failed + total.running

  const colors = { success: '#10b981', failed: '#ef4444', running: '#f59e0b' }
  const labels = { success: '成功', failed: '失败', running: '运行中' }

  // 环形几何参数
  const size = 240
  const cx = size / 2
  const cy = size / 2
  const r = 90
  const ringWidth = 26
  const C = 2 * Math.PI * r           // 周长
  const gapPx = total.count > 0 && (total.success && total.failed && total.running) ? 6 : 0
  // 只有存在多段时才留间隙；单段时不要留白，避免环看起来缺一块

  // 计算每段
  const segments = []
  if (total.count > 0) {
    let offset = 0
    for (const key of ['success', 'failed', 'running']) {
      const val = total[key]
      if (val <= 0) continue
      const pct = val / total.count
      const len = pct * C
      const dash = Math.max(len - gapPx, 0.5)
      segments.push({
        key,
        color: colors[key],
        label: labels[key],
        value: val,
        pct,
        dashArray: `${dash} ${C - dash}`,
        dashOffset: -offset,
      })
      offset += len
    }
  }

  // 主状态（占比最大的）用于中心显示
  const dominant = segments.length > 0
    ? segments.reduce((a, b) => a.pct > b.pct ? a : b)
    : null

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 32, flexWrap: 'wrap', justifyContent: 'center' }}>
      <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
        <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size}>
          {/* 底色环 */}
          <circle cx={cx} cy={cy} r={r} fill="none" stroke="#e5e7eb" strokeWidth={ringWidth} />
          {/* 数据段：rotate(-90) 让起点从顶部 12 点钟方向开始 */}
          <g transform={`rotate(-90 ${cx} ${cy})`}>
            {segments.map(seg => (
              <circle
                key={seg.key}
                cx={cx} cy={cy} r={r}
                fill="none"
                stroke={seg.color}
                strokeWidth={ringWidth}
                strokeLinecap={segments.length > 1 ? 'round' : 'butt'}
                strokeDasharray={seg.dashArray}
                strokeDashoffset={seg.dashOffset}
              >
                <title>{`${seg.label}: ${seg.value} (${(seg.pct * 100).toFixed(1)}%)`}</title>
              </circle>
            ))}
          </g>
        </svg>
        {/* 中心文字 */}
        <div style={{
          position: 'absolute', top: 0, left: 0, width: '100%', height: '100%',
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          pointerEvents: 'none', textAlign: 'center'
        }}>
          <span style={{ fontSize: 38, fontWeight: 700, color: '#1f2937', lineHeight: 1 }}>
            {total.count}
          </span>
          <span style={{ fontSize: 12, color: '#9ca3af', marginTop: 4 }}>总构建次数</span>
          {dominant && (
            <span style={{
              fontSize: 13, fontWeight: 600, marginTop: 8,
              color: dominant.color,
            }}>
              {dominant.label} {(dominant.pct * 100).toFixed(0)}%
            </span>
          )}
        </div>
      </div>
      {/* 图例 + 每日明细 */}
      <div style={{ flex: 1, minWidth: 220 }}>
        <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
          <LegendItem color={colors.success} label="成功" count={total.success} total={total.count} />
          <LegendItem color={colors.failed} label="失败" count={total.failed} total={total.count} />
          <LegendItem color={colors.running} label="运行中" count={total.running} total={total.count} />
        </div>
        <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #e5e7eb' }}>
              <th style={{ textAlign: 'left', padding: '4px 8px', color: '#9ca3af', fontWeight: 500 }}>日期</th>
              <th style={{ textAlign: 'center', padding: '4px 8px', color: '#9ca3af', fontWeight: 500 }}>成功</th>
              <th style={{ textAlign: 'center', padding: '4px 8px', color: '#9ca3af', fontWeight: 500 }}>失败</th>
              <th style={{ textAlign: 'center', padding: '4px 8px', color: '#9ca3af', fontWeight: 500 }}>运行中</th>
              <th style={{ textAlign: 'center', padding: '4px 8px', color: '#9ca3af', fontWeight: 500 }}>合计</th>
            </tr>
          </thead>
          <tbody>
            {trend.map((d, i) => (
              <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <td style={{ padding: '4px 8px', color: '#374151' }}>{d.date?.slice(5)}</td>
                <td style={{ textAlign: 'center', padding: '4px 8px', color: colors.success, fontWeight: 600 }}>{d.success ?? 0}</td>
                <td style={{ textAlign: 'center', padding: '4px 8px', color: colors.failed, fontWeight: 600 }}>{d.failed ?? 0}</td>
                <td style={{ textAlign: 'center', padding: '4px 8px', color: colors.running, fontWeight: 600 }}>{d.running ?? 0}</td>
                <td style={{ textAlign: 'center', padding: '4px 8px', fontWeight: 600, color: '#1f2937' }}>{d.total ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function LegendItem({ color, label, count, total }) {
  const pct = total > 0 ? ((count / total) * 100).toFixed(1) : '0.0'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <span style={{ width: 12, height: 12, borderRadius: 3, background: color, display: 'inline-block' }} />
      <span style={{ fontSize: 13, color: '#6b7280' }}>{label}</span>
      <span style={{ fontSize: 14, fontWeight: 600, color: '#1f2937' }}>{count}</span>
      <span style={{ fontSize: 11, color: '#9ca3af' }}>{pct}%</span>
    </div>
  )
}
