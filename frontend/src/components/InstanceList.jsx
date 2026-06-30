import React, { useState, useEffect, useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  fetchInstances, fetchInstanceStats, fetchStatsByType,
  fetchAvailability, fetchK8sStatus, reconnectK8s,
  deleteInstance, stopInstance, startInstance, fetchInstanceAccess,
  startPortForward, stopPortForward,
  fetchK8sDeployments, getK8sDeployment, deleteK8sDeployment
} from '../api'

function useActivePage() {
  const { pathname } = useLocation()
  if (pathname.startsWith('/instances/docker')) return 'docker'
  if (pathname.startsWith('/instances/k8s'))    return 'k8s'
  return 'instances'
}

export default function InstanceList() {
  const { canManage } = useAuth()
  const page = useActivePage()

  const [loading, setLoading] = useState(true)
  const [instances, setInstances] = useState([])
  const [stats, setStats] = useState(null)
  const [statsByType, setStatsByType] = useState(null)
  const [dockerStatus, setDockerStatus] = useState(null)
  const [k8sStatus, setK8sStatus] = useState(null)
  const [reconnecting, setReconnecting] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [toast, setToast] = useState(null) // { text, type: 'info'|'error' }
  const [actionLoading, setActionLoading] = useState(null) // instance id being acted on
  const [accessData, setAccessData] = useState({})  // instanceId -> access info
  const [accessLoading, setAccessLoading] = useState({}) // instanceId -> boolean
  const [expandedAccess, setExpandedAccess] = useState({}) // instanceId -> boolean

  const showToast = (text, type = 'info') => {
    setToast({ text, type })
    setTimeout(() => setToast(null), type === 'error' ? 5000 : 3000)
  }

  // K8s Deployments
  const [deployments, setDeployments] = useState([])
  const [depLoading, setDepLoading] = useState(false)
  const [depNamespace, setDepNamespace] = useState('devops')
  const [depDetail, setDepDetail] = useState(null)
  const [depDetailLoading, setDepDetailLoading] = useState(false)
  const [deleteDepTarget, setDeleteDepTarget] = useState(null)

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [list, s, st, avail] = await Promise.all([
        fetchInstances(), fetchInstanceStats(), fetchStatsByType(), fetchAvailability()
      ])
      setInstances(list || [])
      setStats(s)
      setStatsByType(st)
      setDockerStatus(avail?.docker || null)
      setK8sStatus(avail?.k8s || null)
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { loadAll() }, [loadAll])
  useEffect(() => { const t = setInterval(loadAll, 15000); return () => clearInterval(t) }, [loadAll])

  const loadDeployments = useCallback(async () => {
    if (!k8sStatus?.connected) return
    setDepLoading(true)
    try { const res = await fetchK8sDeployments(depNamespace); setDeployments(res?.deployments || []) }
    catch { setDeployments([]) }
    finally { setDepLoading(false) }
  }, [depNamespace, k8sStatus?.connected])

  useEffect(() => {
    if (page === 'k8s' && k8sStatus?.connected) loadDeployments()
  }, [page, k8sStatus?.connected, loadDeployments])

  const handleReconnect = async () => {
    setReconnecting(true)
    try { setK8sStatus(await reconnectK8s()) }
    catch (e) { setK8sStatus({ connected: false, error: '重连失败: ' + e.message }) }
    finally { setReconnecting(false) }
  }

  const handleDeleteInstance = async () => {
    try { await deleteInstance(deleteTarget.id); setDeleteTarget(null); loadAll() }
    catch (e) { alert('删除失败：' + e.message) }
  }

  const handleToggleAccess = async (inst) => {
    // 如果已展开则收起
    if (expandedAccess[inst.id]) {
      setExpandedAccess(prev => ({ ...prev, [inst.id]: false }))
      return
    }
    // 展开
    setExpandedAccess(prev => ({ ...prev, [inst.id]: true }))
    // 如果没有缓存数据则加载
    if (!accessData[inst.id]) {
      setAccessLoading(prev => ({ ...prev, [inst.id]: true }))
      try {
        const data = await fetchInstanceAccess(inst.id)
        setAccessData(prev => ({ ...prev, [inst.id]: data }))
      } catch (e) {
        setAccessData(prev => ({ ...prev, [inst.id]: { success: false, message: '获取失败: ' + e.message } }))
      } finally {
        setAccessLoading(prev => ({ ...prev, [inst.id]: false }))
      }
    }
  }

  const handleStop = async (inst) => {
    setActionLoading(inst.id)
    try {
      const res = await stopInstance(inst.id)
      if (res?.success) {
        showToast(`✅ ${inst.instanceName} 已停止`)
        loadAll()
      } else {
        showToast(`❌ ${res?.message || '停止失败'}`, 'error')
      }
    } catch (e) {
      showToast('❌ 停止失败: ' + e.message, 'error')
    } finally { setActionLoading(null) }
  }

  const handleStart = async (inst) => {
    setActionLoading(inst.id)
    try {
      const res = await startInstance(inst.id)
      if (res?.success) {
        showToast(`✅ ${inst.instanceName} 已启动`)
        loadAll()
      } else {
        showToast(`❌ ${res?.message || '启动失败'}`, 'error')
      }
    } catch (e) {
      showToast('❌ 启动失败: ' + e.message, 'error')
    } finally { setActionLoading(null) }
  }

  const handleStartForward = async (inst) => {
    setActionLoading(inst.id)
    try {
      const res = await startPortForward(inst.id)
      if (res?.success) {
        showToast(`🔗 端口转发已启动 → localhost:${res.localPort}`)
        // 刷新 access 缓存以显示真实 URL
        const accessRes = await fetchInstanceAccess(inst.id)
        setAccessData(prev => ({ ...prev, [inst.id]: accessRes }))
      } else {
        showToast(`❌ ${res?.message || '转发失败'}`, 'error')
      }
    } catch (e) {
      showToast('❌ 转发失败: ' + e.message, 'error')
    } finally { setActionLoading(null) }
  }

  const handleStopForward = async (inst) => {
    setActionLoading(inst.id)
    try {
      const res = await stopPortForward(inst.id)
      if (res?.success) {
        showToast(`⏹ ${res.message}`)
        // 刷新 access 缓存
        const accessRes = await fetchInstanceAccess(inst.id)
        setAccessData(prev => ({ ...prev, [inst.id]: accessRes }))
      } else {
        showToast(`❌ ${res?.message || '停止失败'}`, 'error')
      }
    } catch (e) {
      showToast('❌ 停止转发失败: ' + e.message, 'error')
    } finally { setActionLoading(null) }
  }

  const viewDepDetail = async (dep) => {
    setDepDetail({ name: dep.name, data: null }); setDepDetailLoading(true)
    try { setDepDetail({ name: dep.name, data: await getK8sDeployment(dep.name, depNamespace) }) }
    catch (e) { setDepDetail({ name: dep.name, data: { error: e.message } }) }
    finally { setDepDetailLoading(false) }
  }

  const handleDeleteDeployment = async () => {
    try { await deleteK8sDeployment(deleteDepTarget.name, depNamespace); setDeleteDepTarget(null); loadDeployments() }
    catch (e) { alert('删除 K8s Deployment 失败：' + e.message) }
  }

  if (loading && !dockerStatus && !k8sStatus) return <div className="empty-state"><div className="spinner" /></div>

  const shared = { deleteTarget, setDeleteTarget, handleDeleteInstance, canManage, loadAll, handleStop, handleStart, actionLoading, accessData, accessLoading, expandedAccess, handleToggleAccess, handleStartForward, handleStopForward }

  return (
    <>
      {page === 'instances' && <AllInstancesView instances={instances} stats={stats} {...shared} />}
      {page === 'docker'    && <DockerView dockerStatus={dockerStatus} statsByType={statsByType} dockerInstances={instances.filter(i => i.deployType === 'DOCKER')} {...shared} />}
      {page === 'k8s'       && <K8sView
        k8sStatus={k8sStatus} reconnecting={reconnecting} handleReconnect={handleReconnect}
        statsByType={statsByType} k8sInstances={instances.filter(i => i.deployType === 'K8S')}
        deployments={deployments} depLoading={depLoading}
        depNamespace={depNamespace} setDepNamespace={setDepNamespace} loadDeployments={loadDeployments}
        depDetail={depDetail} setDepDetail={setDepDetail}
        depDetailLoading={depDetailLoading} viewDepDetail={viewDepDetail}
        deleteDepTarget={deleteDepTarget} setDeleteDepTarget={setDeleteDepTarget}
        handleDeleteDeployment={handleDeleteDeployment}
        {...shared}
      />}

      {/* 删除实例弹窗 */}
      {deleteTarget && <DeleteInstanceModal target={deleteTarget} onClose={() => setDeleteTarget(null)} onConfirm={handleDeleteInstance} />}

      {/* 删除 K8s Deployment 弹窗 */}
      {deleteDepTarget && <DeleteDeploymentModal target={deleteDepTarget} namespace={depNamespace} onClose={() => setDeleteDepTarget(null)} onConfirm={handleDeleteDeployment} />}

      {/* Toast */}
      {toast && (
        <div className={`toast ${toast.type === 'error' ? 'toast-error' : 'toast-info'}`} style={{ position: 'fixed', bottom: 24, right: 24, zIndex: 9999 }}>
          {toast.text}
        </div>
      )}
    </>
  )
}

// ==================== 弹窗组件 ====================
function DeleteInstanceModal({ target, onClose, onConfirm }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
        <h3>🗑 确认删除服务实例</h3>
        <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
          <p>即将删除以下实例记录：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>实例名称：</strong>{target.instanceName}</div>
            <div><strong>项目：</strong>{target.projectName || `#${target.projectId}`}</div>
            <div><strong>部署类型：</strong>{target.deployType}</div>
            <div><strong>状态：</strong>{target.status}</div>
          </div>
          <p style={{ color: '#ef4444', fontSize: 12 }}>⚠️ 此操作仅删除平台中的记录，不会停止正在运行的容器/Pod。</p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-danger" onClick={onConfirm}>确认删除</button>
        </div>
      </div>
    </div>
  )
}

function DeleteDeploymentModal({ target, namespace, onClose, onConfirm }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
        <h3>🗑 确认删除 K8s Deployment</h3>
        <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
          <p>即将删除：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>名称：</strong>{target.name}</div>
            <div><strong>命名空间：</strong>{namespace}</div>
            <div><strong>副本数：</strong>{target.replicas}</div>
          </div>
          <p style={{ color: '#ef4444', fontSize: 12 }}>⚠️ 此操作将从 K8s 集群中永久删除该 Deployment 及 Pod，不可恢复！</p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-danger" onClick={onConfirm}>确认删除</button>
        </div>
      </div>
    </div>
  )
}

// ==================== 页面视图 ====================
function AllInstancesView({ instances, stats, setDeleteTarget, loadAll, handleStop, handleStart, actionLoading, accessData, accessLoading, expandedAccess, handleToggleAccess, handleStartForward, handleStopForward }) {
  return (
    <>
      <div className="page-header">
        <h2>🖥 全部服务实例</h2>
        <button className="btn btn-outline btn-sm" onClick={loadAll}>🔄 刷新</button>
      </div>
      {stats && <StatsRow stats={stats} />}
      <InstanceTable instances={instances} showType setDeleteTarget={setDeleteTarget} handleStop={handleStop} handleStart={handleStart} actionLoading={actionLoading} accessData={accessData} accessLoading={accessLoading} expandedAccess={expandedAccess} handleToggleAccess={handleToggleAccess} handleStartForward={handleStartForward} handleStopForward={handleStopForward} />
    </>
  )
}

function DockerView({ dockerStatus, statsByType, dockerInstances, setDeleteTarget, loadAll, handleStop, handleStart, actionLoading, accessData, accessLoading, expandedAccess, handleToggleAccess, handleStartForward, handleStopForward }) {
  const dStats = statsByType?.docker
  return (
    <>
      <div className="page-header">
        <h2>🐳 Docker 服务实例</h2>
        <button className="btn btn-outline btn-sm" onClick={loadAll}>🔄 刷新</button>
      </div>
      {dockerStatus && <StatusBar connected={dockerStatus.connected} label="Docker" version={dockerStatus.version} message={dockerStatus.message} error={dockerStatus.error} />}
      {dockerStatus?.connected && (
        <div className="stats-grid" style={{ marginBottom: 16 }}>
          <StatCard label="容器总数" value={dockerStatus.totalContainers ?? 0} />
          <StatCard label="运行中容器" value={dockerStatus.containersRunning ?? 0} color="#22c55e" />
          <StatCard label="已停止容器" value={dockerStatus.containersStopped ?? 0} color="#f59e0b" />
          <StatCard label="镜像数" value={dockerStatus.images ?? 0} color="#6366f1" />
        </div>
      )}
      {dStats && <TypeStats summary={dStats} label="Docker" />}
      <InstanceTable instances={dockerInstances} setDeleteTarget={setDeleteTarget} handleStop={handleStop} handleStart={handleStart} actionLoading={actionLoading} accessData={accessData} accessLoading={accessLoading} expandedAccess={expandedAccess} handleToggleAccess={handleToggleAccess} handleStartForward={handleStartForward} handleStopForward={handleStopForward} />
    </>
  )
}

function K8sView({
  k8sStatus, reconnecting, handleReconnect, statsByType, k8sInstances,
  deployments, depLoading, depNamespace, setDepNamespace, loadDeployments,
  depDetail, setDepDetail, depDetailLoading, viewDepDetail,
  deleteDepTarget, setDeleteDepTarget, handleDeleteDeployment,
  setDeleteTarget, loadAll, canManage, handleStop, handleStart, actionLoading,
  accessData, accessLoading, expandedAccess, handleToggleAccess,
  handleStartForward, handleStopForward
}) {
  const kStats = statsByType?.k8s
  return (
    <>
      <div className="page-header">
        <h2>☸️ Kubernetes 服务实例</h2>
        <button className="btn btn-outline btn-sm" onClick={loadAll}>🔄 刷新</button>
      </div>
      <StatusBar
        connected={k8sStatus?.connected} label="Kubernetes" version={k8sStatus?.serverVersion}
        message={!k8sStatus?.connected ? k8sStatus?.error : undefined} error={k8sStatus?.error}
        onReconnect={handleReconnect} reconnecting={reconnecting}
        extra={k8sStatus?.connected && k8sStatus?.pods?.length > 0 && <PodList pods={k8sStatus.pods} count={k8sStatus.podCount} />}
      />
      {kStats && <TypeStats summary={kStats} label="K8s" />}
      <InstanceTable instances={k8sInstances} setDeleteTarget={setDeleteTarget} handleStop={handleStop} handleStart={handleStart} actionLoading={actionLoading} accessData={accessData} accessLoading={accessLoading} expandedAccess={expandedAccess} handleToggleAccess={handleToggleAccess} handleStartForward={handleStartForward} handleStopForward={handleStopForward} />
      {k8sStatus?.connected && <DeploymentPanel
        deployments={deployments} depLoading={depLoading}
        depNamespace={depNamespace} setDepNamespace={setDepNamespace} loadDeployments={loadDeployments}
        depDetail={depDetail} setDepDetail={setDepDetail} depDetailLoading={depDetailLoading}
        viewDepDetail={viewDepDetail}
        setDeleteDepTarget={setDeleteDepTarget} canManage={canManage}
      />}
      {deleteDepTarget && <DeleteDeploymentModal target={deleteDepTarget} namespace={depNamespace} onClose={() => setDeleteDepTarget(null)} onConfirm={handleDeleteDeployment} />}
    </>
  )
}

// ==================== 小组件 ====================
function StatsRow({ stats }) {
  return (
    <div className="stats-grid" style={{ marginBottom: 16 }}>
      <StatCard label="实例总数" value={stats.total} />
      <StatCard label="运行中" value={stats.running} color="#22c55e" />
      <StatCard label="健康" value={stats.healthy} color="#22c55e" />
      <StatCard label="异常" value={stats.unhealthy} color="#ef4444" />
    </div>
  )
}

function StatCard({ label, value, color }) {
  return (
    <div className="stat-card">
      <div className="label">{label}</div>
      <div className="value" style={color ? { color } : undefined}>{value}</div>
    </div>
  )
}

function TypeStats({ summary, label }) {
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h4 style={{ margin: '0 0 12px 0', fontSize: 14, color: '#374151' }}>平台注册的 {label} 实例</h4>
      <div style={{ display: 'flex', gap: 24, fontSize: 13, color: '#6b7280' }}>
        <span>总数：<strong style={{ color: '#111827' }}>{summary.total}</strong></span>
        <span>运行：<strong style={{ color: '#22c55e' }}>{summary.running}</strong></span>
        <span>已停止：<strong style={{ color: '#f59e0b' }}>{summary.stopped}</strong></span>
        <span>健康：<strong style={{ color: '#22c55e' }}>{summary.healthy}</strong></span>
        <span>异常：<strong style={{ color: '#ef4444' }}>{summary.unhealthy}</strong></span>
      </div>
    </div>
  )
}

function PodList({ pods, count }) {
  return (
    <div style={{ marginTop: 10 }}>
      <div style={{ fontWeight: 600, fontSize: 12, color: '#374151', marginBottom: 6 }}>已发现的 Pod ({count}):</div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {pods.map((pod, i) => (
          <span key={i} className="badge" style={{
            background: pod.status === 'Running' ? '#dcfce7' : '#fef9c3',
            color: pod.status === 'Running' ? '#166534' : '#92400e',
            fontSize: 11, padding: '3px 8px',
            border: '1px solid ' + (pod.status === 'Running' ? '#86efac' : '#fde68a'),
          }}>{pod.name} <span style={{ marginLeft: 4, opacity: 0.7 }}>({pod.status === 'Running' ? '运行中' : pod.status})</span></span>
        ))}
      </div>
    </div>
  )
}

function InstanceTable({ instances, showType, setDeleteTarget, handleStop, handleStart, actionLoading, accessData, accessLoading, expandedAccess, handleToggleAccess, handleStartForward, handleStopForward }) {
  if (instances.length === 0) return (
    <div className="card"><div className="empty-state"><div className="icon">📦</div><p>暂无服务实例</p><p style={{ fontSize: 12 }}>构建并部署后，服务实例将自动注册</p></div></div>
  )
  return (
    <div className="card" style={{ padding: 0 }}>
      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th style={{ width: 36 }}></th>
              <th>实例名称</th><th>项目</th>
              {showType && <th>部署类型</th>}
              <th>状态</th><th>健康</th><th>镜像</th><th>CPU</th><th>内存</th><th>最后心跳</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            {instances.map(inst => (
              <React.Fragment key={inst.id}>
                <tr style={{ cursor: 'pointer' }} onClick={() => inst.status === 'RUNNING' && handleToggleAccess(inst)}>
                  <td style={{ textAlign: 'center', fontSize: 12, color: '#9ca3af' }}>
                    {inst.status === 'RUNNING' ? (
                      expandedAccess[inst.id] ? '▼' : '▶'
                    ) : ''}
                  </td>
                  <td style={{ fontWeight: 600 }}>{inst.instanceName}</td>
                  <td style={{ color: '#6b7280' }}>{inst.projectName || `#${inst.projectId}`}</td>
                  {showType && (
                    <td><span className={`badge ${inst.deployType === 'K8S' ? 'badge-admin' : 'badge-developer'}`} style={{ fontSize: 11 }}>{inst.deployType === 'K8S' ? '☸️ K8s' : '🐳 Docker'}</span></td>
                  )}
                  <td><BadgeStatus val={inst.status} /></td>
                  <td><BadgeHealth val={inst.healthStatus} /></td>
                  <td style={{ fontSize: 12 }}>{inst.imageName}:{inst.imageTag || 'latest'}</td>
                  <td>{inst.cpuUsage ? inst.cpuUsage.toFixed(1) + '%' : '-'}</td>
                  <td>{inst.memoryUsage ? inst.memoryUsage.toFixed(0) + 'MB' : '-'}</td>
                  <td style={{ fontSize: 12 }}>{inst.lastHeartbeat ? new Date(inst.lastHeartbeat).toLocaleString() : '-'}</td>
                  <td onClick={e => e.stopPropagation()}>
                    <div className="btn-group">
                      {inst.status === 'RUNNING' && (
                        <button className="btn btn-warning btn-sm" onClick={() => handleStop(inst)} disabled={actionLoading === inst.id}>
                          {actionLoading === inst.id ? '⏳' : '⏹'} 停止
                        </button>
                      )}
                      {inst.status === 'STOPPED' && (
                        <button className="btn btn-success btn-sm" onClick={() => handleStart(inst)} disabled={actionLoading === inst.id}>
                          {actionLoading === inst.id ? '⏳' : '▶'} 启动
                        </button>
                      )}
                      {inst.status === 'UNKNOWN' && (
                        <button className="btn btn-outline btn-sm" onClick={() => handleStart(inst)} disabled={actionLoading === inst.id}>
                          {actionLoading === inst.id ? '⏳' : '▶'} 尝试启动
                        </button>
                      )}
                      <button className="btn btn-danger btn-sm" onClick={() => setDeleteTarget(inst)}>🗑 删除</button>
                    </div>
                  </td>
                </tr>
                {/* 访问信息展开行 */}
                {expandedAccess[inst.id] && (
                  <tr>
                    <td colSpan={showType ? 11 : 10} style={{ background: '#f0f9ff', padding: 0, borderBottom: '2px solid #bae6fd' }}>
                      <AccessInfoPanel inst={inst} data={accessData[inst.id]} loading={accessLoading[inst.id]} handleStartForward={handleStartForward} handleStopForward={handleStopForward} actionLoading={actionLoading} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ==================== 访问信息面板 ====================
function AccessInfoPanel({ inst, data, loading, handleStartForward, handleStopForward, actionLoading }) {
  if (loading) {
    return (
      <div style={{ padding: '16px 20px', textAlign: 'center', color: '#6b7280', fontSize: 13 }}>
        <div className="spinner" style={{ margin: '0 auto 8px' }} />
        正在获取端口映射...
      </div>
    )
  }
  if (!data) return null
  if (!data.success) {
    return (
      <div style={{ padding: '16px 20px', color: '#ef4444', fontSize: 13 }}>
        {data.message || '获取访问信息失败'}
      </div>
    )
  }

  const { host, port, imageName, imageTag, ports, internalUrls, externalUrls, deployType, containerName,
    portForwardActive, canPortForward, portForwardUrl } = data

  const isExternalCmd = (url) => url.startsWith('kubectl ') || url.startsWith('docker ')

  return (
    <div style={{ padding: '14px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: '#0369a1' }}>
          🔗 {inst.instanceName} 访问信息
        </span>
        <span className={`badge ${deployType === 'K8S' ? 'badge-admin' : 'badge-developer'}`} style={{ fontSize: 10 }}>
          {deployType === 'K8S' ? '☸️ K8s' : '🐳 Docker'}
        </span>
      </div>

      {/* 基本信息行 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, marginBottom: 12, fontSize: 12, color: '#475569' }}>
        <span>镜像: <code style={{ background: '#e0f2fe', padding: '1px 6px', borderRadius: 3 }}>{imageName}:{imageTag}</code></span>
        {containerName && <span>容器: <code style={{ background: '#e0f2fe', padding: '1px 6px', borderRadius: 3 }}>{containerName}</code></span>}
        {host && <span>主机: <code style={{ background: '#e0f2fe', padding: '1px 6px', borderRadius: 3 }}>{host}</code></span>}
        {port && <span>端口: <code style={{ background: '#e0f2fe', padding: '1px 6px', borderRadius: 3 }}>{port}</code></span>}
      </div>

      {/* 端口映射表格 */}
      {ports && ports.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 6 }}>📋 端口映射</div>
          <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse', background: '#fff', borderRadius: 6, overflow: 'hidden', border: '1px solid #e0f2fe' }}>
            <thead>
              <tr style={{ background: '#f0f9ff' }}>
                {deployType === 'DOCKER' ? (
                  <>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>容器端口</th>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>映射到宿主机</th>
                  </>
                ) : (
                  <>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>Service</th>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>类型</th>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>端口</th>
                    <th style={{ padding: '6px 10px', textAlign: 'left', fontWeight: 600, color: '#0369a1' }}>ClusterIP</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {ports.map((p, i) => (
                <tr key={i} style={{ borderTop: '1px solid #e0f2fe' }}>
                  {deployType === 'DOCKER' ? (
                    <>
                      <td style={{ padding: '6px 10px', fontFamily: 'monospace' }}>{p.containerPort}</td>
                      <td style={{ padding: '6px 10px', fontFamily: 'monospace', color: '#059669', fontWeight: 600 }}>{p.hostBinding}</td>
                    </>
                  ) : (
                    <>
                      <td style={{ padding: '6px 10px', fontFamily: 'monospace' }}>{p.serviceName || '-'}</td>
                      <td style={{ padding: '6px 10px' }}><span className="badge" style={{ fontSize: 10, background: '#dbeafe', color: '#1e40af' }}>{p.serviceType}</span></td>
                      <td style={{ padding: '6px 10px', fontFamily: 'monospace' }}>{p.port}</td>
                      <td style={{ padding: '6px 10px', fontFamily: 'monospace', fontSize: 11, color: '#6b7280' }}>{p.clusterIP || '-'}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* K8s ClusterIP: 一键端口转发 */}
      {canPortForward && (
        <div style={{ marginBottom: 12, padding: '10px 14px', background: portForwardActive ? '#ecfdf5' : '#fef9e7', borderRadius: 6, border: '1px solid ' + (portForwardActive ? '#a7f3d0' : '#fde68a') }}>
          {portForwardActive ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: 14 }}>🔗</span>
              <span style={{ flex: 1, fontSize: 13, fontWeight: 600, color: '#065f46' }}>
                端口转发已激活：
                <a href={portForwardUrl} target="_blank" rel="noreferrer" style={{ color: '#059669', marginLeft: 6 }}>
                  {portForwardUrl}
                </a>
              </span>
              <button
                className="btn btn-warning btn-sm"
                style={{ fontSize: 11, whiteSpace: 'nowrap' }}
                onClick={(e) => { e.stopPropagation(); handleStopForward(inst) }}
                disabled={actionLoading === inst.id}
              >{actionLoading === inst.id ? '⏳' : '⏹'} 停止转发</button>
            </div>
          ) : (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: 14 }}>🔌</span>
              <span style={{ flex: 1, fontSize: 13, color: '#92400e' }}>
                该服务为 ClusterIP 类型，集群外无法直接访问。点击按钮自动执行 <code style={{ background: '#fef3c7', padding: '1px 4px', borderRadius: 3 }}>kubectl port-forward</code>
              </span>
              <button
                className="btn btn-success btn-sm"
                style={{ fontSize: 11, whiteSpace: 'nowrap' }}
                onClick={(e) => { e.stopPropagation(); handleStartForward(inst) }}
                disabled={actionLoading === inst.id}
              >{actionLoading === inst.id ? '⏳' : '⚡'} 一键转发</button>
            </div>
          )}
        </div>
      )}

      {/* 两栏布局：集群内部 ｜ 外部访问 */}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 集群内部访问 */}
        <UrlSection
          title="🏠 集群内部访问"
          subtitle={deployType === 'DOCKER' ? 'Docker 网络内其他容器可通过此地址访问' : 'K8s 集群内 Pod/Service 可通过此地址访问'}
          urls={internalUrls}
          color="#0891b2"
          bgColor="#ecfeff"
          borderColor="#a5f3fc"
          isExternalCmd={isExternalCmd}
        />
        {/* 外部访问 */}
        <UrlSection
          title="🌍 外部访问（浏览器可直接打开）"
          subtitle={deployType === 'DOCKER' ? '通过宿主机端口映射访问' : '通过 NodePort / LoadBalancer / Port-forward 访问'}
          urls={externalUrls}
          color="#059669"
          bgColor="#ecfdf5"
          borderColor="#a7f3d0"
          isExternalCmd={isExternalCmd}
        />
      </div>

      {(!ports || ports.length === 0) && (
        <div style={{ fontSize: 12, color: '#f59e0b', padding: '8px 0' }}>
          ⚠️ 未检测到端口映射。请确认容器启动时配置了端口映射（docker run -p）或 K8s Service 已创建。
        </div>
      )}
    </div>
  )
}

function UrlSection({ title, subtitle, urls, color, bgColor, borderColor, isExternalCmd }) {
  if (!urls || urls.length === 0) {
    return (
      <div style={{ flex: '1 1 280px', minWidth: 250, padding: '10px 12px', background: '#f9fafb', borderRadius: 6, border: '1px dashed #d1d5db', fontSize: 12, color: '#9ca3af', textAlign: 'center' }}>
        {title} — 暂无
      </div>
    )
  }
  return (
    <div style={{ flex: '1 1 280px', minWidth: 250 }}>
      <div style={{ fontSize: 12, fontWeight: 600, color, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 11, color: '#6b7280', marginBottom: 8 }}>{subtitle}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {urls.map((url, i) => {
          const isCmd = isExternalCmd(url)
          const cleanUrl = isCmd ? url : url.replace(/\s*\(.*\)$/, '')
          return (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '8px 12px', background: bgColor, borderRadius: 6,
              border: '1px solid ' + borderColor, fontSize: 12
            }}>
              <span style={{ fontSize: 14 }}>{isCmd ? '💻' : '🔗'}</span>
              <code style={{
                flex: 1, fontFamily: isCmd ? 'Consolas, monospace' : 'monospace',
                fontSize: isCmd ? 11 : 12,
                color: isCmd ? '#7c3aed' : color,
                wordBreak: 'break-all', lineHeight: 1.5
              }}>
                {url}
              </code>
              {!isCmd && (
                <button
                  className="btn btn-outline btn-sm"
                  style={{ fontSize: 11, whiteSpace: 'nowrap' }}
                  onClick={() => {
                    navigator.clipboard.writeText(cleanUrl).catch(() => {})
                    window.open(cleanUrl, '_blank')
                  }}
                >📋 打开</button>
              )}
              {isCmd && (
                <button
                  className="btn btn-outline btn-sm"
                  style={{ fontSize: 11, whiteSpace: 'nowrap' }}
                  onClick={() => {
                    navigator.clipboard.writeText(cleanUrl).catch(() => {})
                  }}
                >📋 复制</button>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function BadgeStatus({ val }) {
  const map = { RUNNING: ['success', '运行中'], STOPPED: ['running', '已停止'] }
  const [cls, label] = map[val] || ['failed', val || '-']
  return <span className={`badge badge-${cls}`}>{label}</span>
}

function BadgeHealth({ val }) {
  const map = { HEALTHY: ['success', '健康'], UNHEALTHY: ['failed', '异常'] }
  const [cls, label] = map[val] || ['running', val || '未知']
  return <span className={`badge badge-${cls}`}>{label}</span>
}

function StatusBar({ connected, label, message, version, extra, error, onReconnect, reconnecting }) {
  return (
    <div className="card" style={{ marginBottom: 16, borderLeft: `4px solid ${connected ? '#22c55e' : '#ef4444'}`, background: connected ? '#f0fdf4' : '#fef2f2' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <span style={{ fontSize: 20 }}>{connected ? '✅' : '❌'}</span>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600, fontSize: 14, color: connected ? '#166534' : '#991b1b', marginBottom: 4 }}>
            {connected ? `✅ ${label}已连接${version ? ' · 版本 ' + version : ''}` : `❌ ${label}未连接`}
          </div>
          {message && <div style={{ fontSize: 13, color: connected ? '#166534' : '#991b1b', lineHeight: 1.6 }}>{message}</div>}
          {extra}
          {error && !connected && (
            <div style={{ marginTop: 8, padding: '8px 12px', background: '#fffbeb', borderRadius: 6, border: '1px solid #fde68a', fontSize: 12, color: '#92400e', whiteSpace: 'pre-wrap' }}>
              <strong>💡 诊断信息：</strong><div style={{ marginTop: 4 }}>{error}</div>
            </div>
          )}
        </div>
        {!connected && onReconnect && (
          <button className="btn btn-primary btn-sm" onClick={onReconnect} disabled={reconnecting} style={{ whiteSpace: 'nowrap' }}>{reconnecting ? '⏳ 连接中...' : '🔄 重新连接'}</button>
        )}
      </div>
    </div>
  )
}

function DeploymentPanel({ deployments, depLoading, depNamespace, setDepNamespace, loadDeployments, depDetail, setDepDetail, depDetailLoading, viewDepDetail, setDeleteDepTarget, canManage }) {
  return (
    <div className="card" style={{ padding: 0, marginTop: 24 }}>
      <div style={{ padding: '14px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #e5e7eb' }}>
        <h4 style={{ margin: 0, fontSize: 14 }}>☸️ K8s Deployments</h4>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label style={{ fontSize: 12, color: '#6b7280' }}>
            命名空间: <input value={depNamespace} onChange={e => setDepNamespace(e.target.value)} style={{ padding: '3px 8px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 12, width: 110, marginLeft: 4 }} />
          </label>
          <button className="btn btn-outline btn-sm" onClick={loadDeployments} style={{ fontSize: 11 }}>查询</button>
        </div>
      </div>
      <div className="table-container">
        {depLoading ? <div style={{ padding: 32, textAlign: 'center' }}><div className="spinner" /></div>
          : deployments.length === 0 ? <div style={{ padding: 32, textAlign: 'center', color: '#9ca3af' }}><div style={{ fontSize: 36, marginBottom: 8 }}>📭</div><div>命名空间 <code>{depNamespace}</code> 中暂无 Deployment</div></div>
          : (
            <table>
              <thead><tr><th>名称</th><th>命名空间</th><th>期望副本</th><th>就绪副本</th><th>镜像</th><th>操作</th></tr></thead>
              <tbody>
                {deployments.map((dep, i) => (
                  <React.Fragment key={dep.name || i}>
                    <tr>
                      <td style={{ fontWeight: 600 }}>{dep.name}</td>
                      <td style={{ color: '#6b7280', fontSize: 12 }}>{dep.namespace || depNamespace}</td>
                      <td>{dep.replicas ?? '-'}</td>
                      <td><span style={{ color: (dep.readyReplicas || 0) >= (dep.replicas || 0) ? '#16a34a' : '#dc2626', fontWeight: 600 }}>{dep.readyReplicas ?? 0}</span>/{dep.replicas ?? '-'}</td>
                      <td style={{ fontSize: 12 }}>{dep.image || '-'}</td>
                      <td>
                        <div className="btn-group">
                          <button className="btn btn-outline btn-sm" onClick={() => depDetail?.name === dep.name ? setDepDetail(null) : viewDepDetail(dep)}>{depDetail?.name === dep.name ? '▲ 收起' : '📋 详情'}</button>
                          {canManage && <button className="btn btn-danger btn-sm" onClick={() => setDeleteDepTarget(dep)}>🗑 删除</button>}
                        </div>
                      </td>
                    </tr>
                    {depDetail?.name === dep.name && (
                      <tr><td colSpan={6} style={{ background: '#f9fafb', padding: '12px 20px' }}>
                        {depDetailLoading ? <div className="spinner" style={{ margin: '8px auto' }} />
                          : depDetail.data?.error ? <div style={{ color: '#dc2626', fontSize: 13 }}>❌ {depDetail.data.error}</div>
                          : <div style={{ fontSize: 12, lineHeight: 1.8 }}>{depDetail.data && Object.entries(depDetail.data).filter(([k]) => k !== 'connected').map(([k, v]) => <div key={k}><strong>{k}:</strong>&nbsp;{typeof v === 'object' ? <code style={{ fontSize: 11, background: '#e5e7eb', padding: '2px 6px', borderRadius: 4 }}>{JSON.stringify(v, null, 2)}</code> : String(v)}</div>)}</div>
                        }
                      </td></tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          )}
      </div>
    </div>
  )
}
