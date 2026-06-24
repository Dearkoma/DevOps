import React, { useState, useEffect, useCallback } from 'react'
import { fetchInstances, fetchInstanceStats, fetchProjectInstances } from '../api'

export default function InstanceList() {
  const [instances, setInstances] = useState([])
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [list, s] = await Promise.all([fetchInstances(), fetchInstanceStats()])
      setInstances(list || [])
      setStats(s)
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  // Auto-refresh every 10 seconds
  useEffect(() => {
    const timer = setInterval(load, 10000)
    return () => clearInterval(timer)
  }, [load])

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🖥 服务实例</h2>
        <button className="btn btn-outline btn-sm" onClick={load}>🔄 刷新</button>
      </div>

      {stats && (
        <div className="stats-grid" style={{ marginBottom: 16 }}>
          <div className="stats-card">
            <div className="stats-label">实例总数</div>
            <div className="stats-value">{stats.total}</div>
          </div>
          <div className="stats-card">
            <div className="stats-label">运行中</div>
            <div className="stats-value" style={{ color: '#22c55e' }}>{stats.running}</div>
          </div>
          <div className="stats-card">
            <div className="stats-label">健康</div>
            <div className="stats-value" style={{ color: '#22c55e' }}>{stats.healthy}</div>
          </div>
          <div className="stats-card">
            <div className="stats-label">异常</div>
            <div className="stats-value" style={{ color: '#ef4444' }}>{stats.unhealthy}</div>
          </div>
        </div>
      )}

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>实例名称</th><th>项目</th><th>状态</th><th>健康</th>
                <th>镜像</th><th>CPU</th><th>内存</th>
                <th>重启次数</th><th>最后心跳</th>
              </tr>
            </thead>
            <tbody>
              {instances.length === 0 ? (
                <tr><td colSpan={9} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>
                  暂无服务实例（构建并 K8s 部署后会自动注册）
                </td></tr>
              ) : (
                instances.map(inst => (
                  <tr key={inst.id}>
                    <td style={{ fontWeight: 600 }}>{inst.instanceName}</td>
                    <td style={{ color: '#6b7280' }}>{inst.projectName || `#${inst.projectId}`}</td>
                    <td>
                      <span className={`badge badge-${inst.status?.toLowerCase() === 'running' ? 'success' : 'failed'}`}>
                        {inst.status === 'RUNNING' ? '运行中' : inst.status}
                      </span>
                    </td>
                    <td>
                      <span className={`badge badge-${inst.healthStatus?.toLowerCase() === 'healthy' ? 'success' : inst.healthStatus?.toLowerCase() === 'unhealthy' ? 'failed' : 'running'}`}>
                        {inst.healthStatus === 'HEALTHY' ? '健康' :
                         inst.healthStatus === 'UNHEALTHY' ? '异常' : inst.healthStatus || '未知'}
                      </span>
                    </td>
                    <td style={{ fontSize: 12 }}>{inst.imageName}:{inst.imageTag || 'latest'}</td>
                    <td>{inst.cpuUsage ? inst.cpuUsage.toFixed(1) + '%' : '-'}</td>
                    <td>{inst.memoryUsage ? inst.memoryUsage.toFixed(0) + 'MB' : '-'}</td>
                    <td>{inst.restartCount || 0}</td>
                    <td style={{ fontSize: 12 }}>{inst.lastHeartbeat ? new Date(inst.lastHeartbeat).toLocaleString() : '-'}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
