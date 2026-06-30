import React, { useState, useEffect, useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  fetchInstances, fetchInstanceStats, fetchStatsByType,
  fetchAvailability, fetchK8sStatus, reconnectK8s,
  deleteInstance, restartInstance, stopInstance, startInstance,
  fetchK8sDeployments, getK8sDeployment, deleteK8sDeployment,
  getAccessInfo, exposeToExternal, getInstanceLogs, getInstanceBuildLogs
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
  const [restartTarget, setRestartTarget] = useState(null)
  const [stopTarget, setStopTarget] = useState(null)
  const [startTarget, setStartTarget] = useState(null)

  // 展开行：访问信息
  const [expandedId, setExpandedId] = useState(null)
  const [accessInfo, setAccessInfo] = useState(null)
  const [accessLoading, setAccessLoading] = useState(false)
  const [exposingId, setExposingId] = useState(null)

  // 日志查看
  const [logsData, setLogsData] = useState(null)       // 当前实例的日志数据
  const [logsLoading, setLogsLoading] = useState(null)  // 正在加载日志的 instanceId
  const [showLogsId, setShowLogsId] = useState(null)    // 当前显示日志的 instanceId
  const [logsCache, setLogsCache] = useState({})        // 日志缓存: { [id]: { logs, source, timestamp } }
  const [logType, setLogType] = useState(null)          // 'backend' | 'frontend' — 当前日志类型

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
    const r = await deleteInstance(deleteTarget.id)
    setDeleteTarget(null)
    alert(r?.message || '已删除')
    loadAll()
  }

  const handleRestartInstance = async () => {
    try {
      const r = await restartInstance(restartTarget.id)
      const instId = restartTarget.id
      setRestartTarget(null)
      alert(r?.message || (r?.success ? '重启成功' : '重启失败: ' + (r?.error || '')))
      loadAll()
      // 重启后如果后台日志面板正打开，延迟刷新（Pod 需要几秒启动）
      if (showLogsId === instId && logType === 'backend') {
        setLogsData({ success: true, logs: '⏳ 实例正在重启，等待 Pod 启动后自动获取日志...', source: 'waiting' })
        setTimeout(() => handleViewLogs({ id: instId }), 5000)
      }
    } catch (e) { setRestartTarget(null); alert('重启失败: ' + e.message) }
  }

  const handleStopInstance = async () => {
    try {
      const r = await stopInstance(stopTarget.id)
      const instId = stopTarget.id
      setStopTarget(null)
      alert(r?.message || (r?.success ? '已停止' : '停止失败: ' + (r?.error || '')))
      loadAll()
      // 停止后后台日志面板显示缓存日志 + 提示
      if (showLogsId === instId && logType === 'backend') {
        const cached = logsCache[instId]
        setLogsData({
          success: false,
          stopped: true,
          error: '实例已停止，K8s Pod 已被回收。以下为停止前最后一次获取的日志缓存。',
          logs: cached?.logs || '',
          source: cached?.source || null,
          cachedAt: cached?.timestamp
        })
      }
    } catch (e) { setStopTarget(null); alert('停止失败: ' + e.message) }
  }

  const handleStartInstance = async () => {
    try {
      const r = await startInstance(startTarget.id)
      const instId = startTarget.id
      setStartTarget(null)
      alert(r?.message || (r?.success ? '启动成功' : '启动失败: ' + (r?.error || '')))
      loadAll()
      // 启动后如果后台日志面板正打开，延迟刷新
      if (showLogsId === instId && logType === 'backend') {
        setLogsData({ success: true, logs: '⏳ 实例正在启动，等待 Pod 就绪后自动获取日志...', source: 'waiting' })
        setTimeout(() => handleViewLogs({ id: instId }), 5000)
      }
    } catch (e) { setStartTarget(null); alert('启动失败: ' + e.message) }
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

  // 展开/收起访问信息
  const handleToggleRow = async (inst) => {
    if (expandedId === inst.id) {
      setExpandedId(null); setAccessInfo(null)
      setShowLogsId(null); setLogsData(null); setLogType(null)
      return
    }
    setExpandedId(inst.id)
    setAccessLoading(true)
    setShowLogsId(null)
    setLogsData(null)
    try { setAccessInfo(await getAccessInfo(inst.id)) }
    catch (e) { setAccessInfo({ success: false, error: e.message }) }
    finally { setAccessLoading(false) }
  }

  // 查看后台容器日志
  const handleViewLogs = async (inst, tail = 200) => {
    setShowLogsId(inst.id)
    setLogType('backend')
    setLogsLoading(inst.id)
    try {
      const r = await getInstanceLogs(inst.id, tail)
      console.log('[后台日志API] 实例', inst.id, '返回:', r)
      setLogsData(r)
      // 成功获取日志时缓存
      if (r.success && r.logs) {
        setLogsCache(prev => ({ ...prev, [inst.id]: { logs: r.logs, source: r.source, timestamp: Date.now() } }))
      }
    } catch (e) {
      console.error('[后台日志API] 实例', inst.id, '失败:', e)
      setLogsData({ success: false, error: e.message, logs: '' })
    } finally {
      setLogsLoading(null)
    }
  }

  // 查看前台构建日志
  const handleViewBuildLogs = async (inst) => {
    setShowLogsId(inst.id)
    setLogType('frontend')
    setLogsLoading(inst.id)
    try {
      const r = await getInstanceBuildLogs(inst.id)
      console.log('[前台日志API] 实例', inst.id, '返回:', r)
      setLogsData(r)
    } catch (e) {
      console.error('[前台日志API] 实例', inst.id, '失败:', e)
      setLogsData({ success: false, error: e.message, logs: '' })
    } finally {
      setLogsLoading(null)
    }
  }

  // 保存日志到文件
  const handleSaveLogs = (inst) => {
    const data = logsData
    if (!data?.logs) { alert('暂无日志可保存'); return }
    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
    const typeLabel = logType === 'frontend' ? 'frontend' : 'backend'
    const filename = `${inst.instanceName || 'instance'}-${typeLabel}-logs-${ts}.txt`
    const header = `# 实例: ${inst.instanceName}\n# 日志类型: ${logType === 'frontend' ? '前台构建日志' : '后台运行日志'}\n# 部署类型: ${inst.deployType}\n# 来源: ${data.source || '-'}\n# 保存时间: ${new Date().toLocaleString()}\n${'='.repeat(60)}\n\n`
    const blob = new Blob([header + data.logs], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = filename; a.click()
    URL.revokeObjectURL(url)
  }

  // 关闭日志
  const handleCloseLogs = () => {
    setShowLogsId(null)
    setLogsData(null)
    setLogType(null)
  }

  // 一键部署到外部
  const handleExpose = async (id) => {
    setExposingId(id)
    try {
      const r = await exposeToExternal(id)
      alert(r?.success ? (r?.message || '暴露成功') : (r?.error || '操作失败'))
      if (r?.success) loadAll()
    } catch (e) { alert('外部部署失败: ' + e.message) }
    finally { setExposingId(null) }
  }

  if (loading && !dockerStatus && !k8sStatus) return <div className="empty-state"><div className="spinner" /></div>

  const shared = { deleteTarget, setDeleteTarget, handleDeleteInstance, restartTarget, setRestartTarget, handleRestartInstance, stopTarget, setStopTarget, handleStopInstance, startTarget, setStartTarget, handleStartInstance, canManage, loadAll,
    expandedId, accessInfo, accessLoading, exposingId,
    onToggleRow: handleToggleRow, onExpose: handleExpose,
    logsData, logsLoading, showLogsId, logType, onViewLogs: handleViewLogs, onViewBuildLogs: handleViewBuildLogs, onSaveLogs: handleSaveLogs, onCloseLogs: handleCloseLogs }

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

      {/* 重启实例弹窗 */}
      {restartTarget && <RestartInstanceModal target={restartTarget} onClose={() => setRestartTarget(null)} onConfirm={handleRestartInstance} />}

      {/* 停止实例弹窗 */}
      {stopTarget && <StopInstanceModal target={stopTarget} onClose={() => setStopTarget(null)} onConfirm={handleStopInstance} />}

      {/* 启动实例弹窗 */}
      {startTarget && <StartInstanceModal target={startTarget} onClose={() => setStartTarget(null)} onConfirm={handleStartInstance} />}

      {/* 删除 K8s Deployment 弹窗 */}
      {deleteDepTarget && <DeleteDeploymentModal target={deleteDepTarget} namespace={depNamespace} onClose={() => setDeleteDepTarget(null)} onConfirm={handleDeleteDeployment} />}
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
          <p>即将删除以下实例：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>实例名称：</strong>{target.instanceName}</div>
            <div><strong>项目：</strong>{target.projectName || `#${target.projectId}`}</div>
            <div><strong>部署类型：</strong>{target.deployType}</div>
            <div><strong>状态：</strong>{target.status}</div>
          </div>
          <p style={{ color: '#ef4444', fontSize: 12 }}>⚠️ 此操作将{target.deployType === 'K8S' ? '删除 K8s Deployment 及所有 Pod' : '停止并删除 Docker 容器'}，并移除平台记录。不可恢复！</p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-danger" onClick={onConfirm}>确认删除</button>
        </div>
      </div>
    </div>
  )
}

function RestartInstanceModal({ target, onClose, onConfirm }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
        <h3>🔄 确认重启服务实例</h3>
        <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
          <p>即将重启以下实例：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>实例名称：</strong>{target.instanceName}</div>
            <div><strong>项目：</strong>{target.projectName || `#${target.projectId}`}</div>
            <div><strong>部署类型：</strong>{target.deployType}</div>
          </div>
          <p style={{ color: '#6366f1', fontSize: 12 }}>
            {target.deployType === 'K8S' ? '☸️ 将执行 kubectl rollout restart，滚动重启所有 Pod' : '🐳 将执行 docker restart，重启容器'}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-primary" onClick={onConfirm}>确认重启</button>
        </div>
      </div>
    </div>
  )
}

function StopInstanceModal({ target, onClose, onConfirm }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
        <h3>⏹ 确认停止服务实例</h3>
        <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
          <p>即将停止以下实例：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>实例名称：</strong>{target.instanceName}</div>
            <div><strong>项目：</strong>{target.projectName || `#${target.projectId}`}</div>
            <div><strong>部署类型：</strong>{target.deployType}</div>
          </div>
          <p style={{ color: '#f59e0b', fontSize: 12 }}>
            {target.deployType === 'K8S' ? '☸️ 将执行 kubectl scale --replicas=0，停止所有 Pod（可恢复）' : '🐳 将执行 docker stop，停止容器（可恢复）'}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-warning" style={{ background: '#f59e0b', color: '#fff', border: 'none' }} onClick={onConfirm}>确认停止</button>
        </div>
      </div>
    </div>
  )
}

function StartInstanceModal({ target, onClose, onConfirm }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
        <h3>▶️ 确认启动服务实例</h3>
        <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
          <p>即将启动以下实例：</p>
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
            <div><strong>实例名称：</strong>{target.instanceName}</div>
            <div><strong>项目：</strong>{target.projectName || `#${target.projectId}`}</div>
            <div><strong>部署类型：</strong>{target.deployType}</div>
            <div><strong>当前状态：</strong><span style={{ color: '#f59e0b' }}>{target.status}</span></div>
          </div>
          <p style={{ color: '#10b981', fontSize: 12 }}>
            {target.deployType === 'K8S' ? '☸️ 将执行 kubectl scale --replicas=1，恢复 Pod 运行' : '🐳 将执行 docker start，恢复容器运行'}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn btn-outline" onClick={onClose}>取消</button>
          <button className="btn btn-success" style={{ background: '#10b981', color: '#fff', border: 'none' }} onClick={onConfirm}>确认启动</button>
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
function AllInstancesView({ instances, stats, setDeleteTarget, setRestartTarget, setStopTarget, setStartTarget, loadAll, expandedId, accessInfo, accessLoading, exposingId, onToggleRow, onExpose, logsData, logsLoading, showLogsId, logType, onViewLogs, onViewBuildLogs, onSaveLogs, onCloseLogs }) {
  return (
    <>
      <div className="page-header">
        <h2>🖥 全部服务实例</h2>
        <button className="btn btn-outline btn-sm" onClick={loadAll}>🔄 刷新</button>
      </div>
      {stats && <StatsRow stats={stats} />}
      <InstanceTable instances={instances} showType setDeleteTarget={setDeleteTarget} setRestartTarget={setRestartTarget} setStopTarget={setStopTarget} setStartTarget={setStartTarget}
        expandedId={expandedId} accessInfo={accessInfo} accessLoading={accessLoading} exposingId={exposingId} onToggleRow={onToggleRow} onExpose={onExpose}
        logsData={logsData} logsLoading={logsLoading} showLogsId={showLogsId} logType={logType} onViewLogs={onViewLogs} onViewBuildLogs={onViewBuildLogs} onSaveLogs={onSaveLogs} onCloseLogs={onCloseLogs} />
    </>
  )
}

function DockerView({ dockerStatus, statsByType, dockerInstances, setDeleteTarget, setRestartTarget, setStopTarget, setStartTarget, loadAll, expandedId, accessInfo, accessLoading, exposingId, onToggleRow, onExpose, logsData, logsLoading, showLogsId, logType, onViewLogs, onViewBuildLogs, onSaveLogs, onCloseLogs }) {
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
      <InstanceTable instances={dockerInstances} setDeleteTarget={setDeleteTarget} setRestartTarget={setRestartTarget} setStopTarget={setStopTarget} setStartTarget={setStartTarget}
        expandedId={expandedId} accessInfo={accessInfo} accessLoading={accessLoading} exposingId={exposingId} onToggleRow={onToggleRow} onExpose={onExpose}
        logsData={logsData} logsLoading={logsLoading} showLogsId={showLogsId} logType={logType} onViewLogs={onViewLogs} onViewBuildLogs={onViewBuildLogs} onSaveLogs={onSaveLogs} onCloseLogs={onCloseLogs} />
    </>
  )
}

function K8sView({
  k8sStatus, reconnecting, handleReconnect, statsByType, k8sInstances,
  deployments, depLoading, depNamespace, setDepNamespace, loadDeployments,
  depDetail, setDepDetail, depDetailLoading, viewDepDetail,
  deleteDepTarget, setDeleteDepTarget, handleDeleteDeployment,
  setDeleteTarget, setRestartTarget, setStopTarget, setStartTarget, loadAll, canManage,
  expandedId, accessInfo, accessLoading, exposingId, onToggleRow, onExpose,
  logsData, logsLoading, showLogsId, logType, onViewLogs, onViewBuildLogs, onSaveLogs, onCloseLogs,
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
      <InstanceTable instances={k8sInstances} setDeleteTarget={setDeleteTarget} setRestartTarget={setRestartTarget} setStopTarget={setStopTarget} setStartTarget={setStartTarget}
        expandedId={expandedId} accessInfo={accessInfo} accessLoading={accessLoading} exposingId={exposingId} onToggleRow={onToggleRow} onExpose={onExpose}
        logsData={logsData} logsLoading={logsLoading} showLogsId={showLogsId} logType={logType} onViewLogs={onViewLogs} onViewBuildLogs={onViewBuildLogs} onSaveLogs={onSaveLogs} onCloseLogs={onCloseLogs} />
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

function InstanceTable({ instances, showType, setDeleteTarget, setRestartTarget, setStopTarget, setStartTarget, expandedId, accessInfo, accessLoading, exposingId, onToggleRow, onExpose, logsData, logsLoading, showLogsId, logType, onViewLogs, onViewBuildLogs, onSaveLogs, onCloseLogs }) {
  if (instances.length === 0) return (
    <div className="card"><div className="empty-state"><div className="icon">📦</div><p>暂无服务实例</p><p style={{ fontSize: 12 }}>构建并部署后，服务实例将自动注册</p></div></div>
  )
  return (
    <div className="card" style={{ padding: 0 }}>
      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th style={{ width: 30 }}></th>
              <th>实例名称</th><th>项目</th>
              {showType && <th>部署类型</th>}
              <th>状态</th><th>健康</th><th>镜像</th><th>CPU</th><th>内存</th><th>最后心跳</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            {instances.map(inst => (
              <React.Fragment key={inst.id}>
                <tr onClick={() => onToggleRow(inst)} style={{ cursor: 'pointer' }}>
                  <td style={{ textAlign: 'center', color: '#9ca3af', fontSize: 12 }}>
                    {expandedId === inst.id ? '▼' : '▶'}
                  </td>
                  <td style={{ fontWeight: 600 }}>{inst.instanceName}</td>
                  <td style={{ color: '#6b7280' }}>{inst.projectName || `#${inst.projectId}`}</td>
                  {showType && (
                    <td><span className={`badge ${inst.deployType === 'K8S' ? 'badge-admin' : 'badge-developer'}`} style={{ fontSize: 11 }}>{inst.deployType === 'K8S' ? '☸️ K8s' : '🐳 Docker'}</span></td>
                  )}
                  <td><BadgeStatus val={inst.status} /></td>
                  <td><BadgeHealth val={inst.healthStatus} /></td>
                  <td style={{ fontSize: 12 }}>{inst.imageName}:{inst.imageTag || 'latest'}</td>
                  <td>{inst.cpuUsage != null ? inst.cpuUsage.toFixed(1) + '%' : '-'}</td>
                  <td>{inst.memoryUsage != null ? inst.memoryUsage.toFixed(0) + 'MB' : '-'}</td>
                  <td style={{ fontSize: 12 }}>{inst.lastHeartbeat ? new Date(inst.lastHeartbeat).toLocaleString() : '-'}</td>
                  <td onClick={e => e.stopPropagation()}>
                    <div className="btn-group">
                      {inst.status === 'STOPPED' ? (
                        <button className="btn btn-outline btn-sm" style={{ color: '#10b981', borderColor: '#10b981' }} onClick={() => setStartTarget(inst)}
                          title="启动实例">▶️ 启动</button>
                      ) : (
                        <>
                          <button className="btn btn-outline btn-sm" onClick={() => setRestartTarget(inst)}
                            title={inst.status !== 'RUNNING' ? '实例状态异常，尝试重启恢复' : '重启实例'}>
                            🔄 重启
                          </button>
                          <button className="btn btn-outline btn-sm" style={{ color: '#f59e0b', borderColor: '#f59e0b' }} onClick={() => setStopTarget(inst)}>⏹ 停止</button>
                        </>
                      )}
                      <button className="btn btn-danger btn-sm" onClick={() => setDeleteTarget(inst)}>🗑 删除</button>
                    </div>
                  </td>
                </tr>
                {/* 展开行：访问信息 */}
                {expandedId === inst.id && (
                  <tr>
                    <td colSpan={showType ? 11 : 10} style={{ background: '#f9fafb', padding: 0 }}>
                      <div style={{ padding: '14px 20px', borderTop: '2px solid #6366f1' }}>
                        {accessLoading ? (
                          <div style={{ textAlign: 'center', padding: 12 }}><div className="spinner" /></div>
                        ) : accessInfo?.stopped ? (
                          <div style={{
                            background: '#fef3c7', border: '1px solid #fcd34d', borderRadius: 8,
                            padding: '10px 14px', fontSize: 13, color: '#92400e', display: 'flex', alignItems: 'center', gap: 8
                          }}>
                            ⏹ 实例已停止，无可用访问链接。请先启动实例后查看。
                          </div>
                        ) : accessInfo?.success === false ? (
                          <div style={{ color: '#ef4444', fontSize: 13 }}>❌ {accessInfo.error}</div>
                        ) : accessInfo ? (
                          <div style={{ display: 'flex', alignItems: 'center', gap: 24, flexWrap: 'wrap' }}>
                            {/* 内部链接 */}
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ fontSize: 12, color: '#6b7280', whiteSpace: 'nowrap' }}>🔗 内部链接：</span>
                              <code style={{
                                background: '#e5e7eb', padding: '4px 10px', borderRadius: 6,
                                fontSize: 13, color: '#111827', fontWeight: 500
                              }}>{accessInfo.internalUrl || '-'}</code>
                              <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                onClick={() => { navigator.clipboard.writeText(accessInfo.internalUrl); alert('已复制内部链接') }}>
                                📋 复制
                              </button>
                            </div>

                            {/* 分隔 */}
                            <div style={{ width: 1, height: 24, background: '#d1d5db' }} />

                            {/* 外部访问状态 */}
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ fontSize: 12, color: '#6b7280', whiteSpace: 'nowrap' }}>
                                {accessInfo.externalExposed ? '🌐' : '🔒'} 外部访问：
                              </span>
                              {accessInfo.externalExposed ? (
                                <>
                                  <code style={{
                                    background: '#dcfce7', padding: '4px 10px', borderRadius: 6,
                                    fontSize: 13, color: '#166534', fontWeight: 500
                                  }}>{accessInfo.externalUrl}</code>
                                  {accessInfo.externalPort && (
                                    <span className="badge badge-success" style={{ fontSize: 11 }}>端口 {accessInfo.externalPort}</span>
                                  )}
                                  <a className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px', textDecoration: 'none' }}
                                    href={accessInfo.externalUrl} target="_blank" rel="noopener noreferrer"
                                    onClick={(e) => e.stopPropagation()}>
                                    🔗 打开
                                  </a>
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                    onClick={() => { navigator.clipboard.writeText(accessInfo.externalUrl); alert('已复制外部链接') }}>
                                    📋 复制
                                  </button>
                                </>
                              ) : (
                                <>
                                  <span style={{ fontSize: 12, color: '#9ca3af' }}>{accessInfo.externalLabel || '未暴露'}</span>
                                  <button
                                    className="btn btn-primary btn-sm"
                                    style={{ fontSize: 11, padding: '3px 10px', marginLeft: 4 }}
                                    onClick={(e) => { e.stopPropagation(); onExpose(inst.id) }}
                                    disabled={exposingId === inst.id}
                                  >
                                    {exposingId === inst.id ? '⏳ 部署中...' : '🚀 一键部署到外部'}
                                  </button>
                                </>
                              )}
                            </div>
                          </div>
                        ) : null}

                        {/* 日志区域 — 独立于 accessInfo，只要展开就显示 */}
                        {!accessLoading && (
                          <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px dashed #d1d5db' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
                              {showLogsId !== inst.id ? (
                                <>
                                  <span style={{ fontSize: 12, color: '#6b7280', fontWeight: 600, whiteSpace: 'nowrap' }}>📜 日志查看</span>
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                    onClick={(e) => { e.stopPropagation(); onViewLogs(inst) }}>
                                    🖥 后台日志
                                  </button>
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                    onClick={(e) => { e.stopPropagation(); onViewBuildLogs(inst) }}>
                                    🌐 前台日志
                                  </button>
                                </>
                              ) : (
                                <>
                                  {/* 标签页式切换 */}
                                  <button style={{ fontSize: 11, padding: '3px 10px', borderRadius: '4px 0 0 4px', border: '1px solid #d1d5db',
                                      background: logType === 'backend' ? '#6366f1' : '#fff', color: logType === 'backend' ? '#fff' : '#374151',
                                      cursor: 'pointer', fontWeight: 600 }}
                                    onClick={(e) => { e.stopPropagation(); if (logType !== 'backend') onViewLogs(inst) }}>
                                    🖥 后台日志
                                  </button>
                                  <button style={{ fontSize: 11, padding: '3px 10px', borderRadius: '0 4px 4px 0', border: '1px solid #d1d5db', borderLeft: 'none',
                                      background: logType === 'frontend' ? '#6366f1' : '#fff', color: logType === 'frontend' ? '#fff' : '#374151',
                                      cursor: 'pointer', fontWeight: 600 }}
                                    onClick={(e) => { e.stopPropagation(); if (logType !== 'frontend') onViewBuildLogs(inst) }}>
                                    🌐 前台日志
                                  </button>
                                  {logsData?.source && (
                                    <span style={{ fontSize: 11, color: '#9ca3af' }}>({logsData.source})</span>
                                  )}
                                  {/* 行数选择 — 仅后台日志 */}
                                  {logType === 'backend' && (
                                    <select style={{ fontSize: 11, padding: '2px 4px', borderRadius: 4, border: '1px solid #d1d5db' }}
                                      onChange={(e) => { e.stopPropagation(); onViewLogs(inst, parseInt(e.target.value)) }}
                                      value={logsData?.tailLines || 200}
                                    >
                                      <option value={100}>100行</option>
                                      <option value={200}>200行</option>
                                      <option value={500}>500行</option>
                                      <option value={1000}>1000行</option>
                                    </select>
                                  )}
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                    onClick={(e) => { e.stopPropagation(); logType === 'backend' ? onViewLogs(inst, logsData?.tailLines || 200) : onViewBuildLogs(inst) }}
                                    disabled={logsLoading === inst.id}>
                                    {logsLoading === inst.id ? '⏳ 加载中...' : '🔄 刷新'}
                                  </button>
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px', color: '#6366f1', borderColor: '#6366f1' }}
                                    onClick={(e) => { e.stopPropagation(); onSaveLogs(inst) }}
                                    disabled={!logsData?.logs}>
                                    💾 保存到文件
                                  </button>
                                  <button className="btn btn-outline btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}
                                    onClick={(e) => { e.stopPropagation(); onCloseLogs() }}>
                                    ✕ 关闭
                                  </button>
                                </>
                              )}
                            </div>
                            {showLogsId === inst.id && (
                              logsLoading === inst.id ? (
                                <div style={{ textAlign: 'center', padding: 16, color: '#6b7280' }}><div className="spinner" /></div>
                              ) : logsData?.stopped ? (
                                /* 停止状态：显示缓存日志 + 黄色警告条 */
                                <>
                                  {logsData.logs ? (
                                    <>
                                      <div style={{
                                        background: '#fef3c7', border: '1px solid #fcd34d', borderRadius: 6,
                                        padding: '6px 10px', marginBottom: 6, fontSize: 12, color: '#92400e'
                                      }}>
                                        ⚠️ {logsData.error || '实例已停止，以下为停止前缓存的日志'}
                                      </div>
                                      <pre style={{
                                        background: '#1e1e1e', color: '#d4d4d4', borderRadius: 8,
                                        padding: 12, maxHeight: 400, overflow: 'auto',
                                        fontSize: 12, lineHeight: 1.5, fontFamily: 'Consolas, Monaco, "Courier New", monospace',
                                        whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0
                                      }}>{logsData.logs}</pre>
                                    </>
                                  ) : (
                                    <div style={{
                                      background: '#fef3c7', border: '1px solid #fcd34d', borderRadius: 6,
                                      padding: '10px 14px', fontSize: 12, color: '#92400e'
                                    }}>
                                      ⚠️ 实例已停止，且无缓存日志可用。请先启动实例后查看日志。
                                    </div>
                                  )}
                                </>
                              ) : logsData?.success === false ? (
                                <div style={{ color: '#ef4444', fontSize: 12, padding: 8, background: '#fef2f2', borderRadius: 6 }}>❌ {logsData.error}</div>
                              ) : logsData?.logs ? (
                                <pre style={{
                                  background: '#1e1e1e', color: '#d4d4d4', borderRadius: 8,
                                  padding: 12, maxHeight: 400, overflow: 'auto',
                                  fontSize: 12, lineHeight: 1.5, fontFamily: 'Consolas, Monaco, "Courier New", monospace',
                                  whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0
                                }}>{logsData.logs}</pre>
                              ) : (
                                <div style={{ color: '#9ca3af', fontSize: 12, padding: 8 }}>(暂无日志)</div>
                              )
                            )}
                          </div>
                        )}
                      </div>
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
