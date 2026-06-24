import React, { useState, useEffect, useCallback, useRef } from 'react'
import { useAuth } from '../context/AuthContext'
import { fetchBuilds, fetchBuildLog, triggerBuild, cancelBuild, fetchProjects, fetchPipelines } from '../api'

const STATUS_MAP = { SUCCESS: '成功', FAILED: '失败', RUNNING: '运行中', CANCELLED: '已取消' }

export default function BuildList() {
  const { canTrigger, canManage } = useAuth()
  const [builds, setBuilds] = useState([])
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState('')
  const [logModal, setLogModal] = useState(null)
  const [logText, setLogText] = useState('')
  const [logConnected, setLogConnected] = useState(false)

  // Log polling ref
  const [showTrigger, setShowTrigger] = useState(false)
  const [triggerProject, setTriggerProject] = useState('')
  const [triggerPipeline, setTriggerPipeline] = useState('')
  const [triggerPipeList, setTriggerPipeList] = useState([])
  const [triggerVersion, setTriggerVersion] = useState('')
  const [triggerBranch, setTriggerBranch] = useState('')
  const [triggerEnv, setTriggerEnv] = useState('')

  // Webhook modal
  const [showWebhook, setShowWebhook] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [bList, pList] = await Promise.all([fetchBuilds(undefined, statusFilter), fetchProjects()])
      setBuilds(bList)
      setProjects(pList)
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [statusFilter])

  useEffect(() => { load() }, [load])

  // Auto-refresh when running builds exist
  useEffect(() => {
    const hasRunning = builds.some(b => b.status === 'RUNNING')
    if (!hasRunning) return
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [builds, load])

  // WebSocket log connection (fallback: polling)
  const logPollRef = useRef(null)

  const startLogPolling = useCallback((buildId) => {
    if (logPollRef.current) clearInterval(logPollRef.current)
    logPollRef.current = setInterval(async () => {
      try {
        const log = await fetchBuildLog(buildId)
        setLogText(log || '')
        // Check if build finished
        const b = await fetchBuilds().then(bs => bs.find(x => x.id === buildId))
        if (b && b.status !== 'RUNNING') {
          setLogConnected(false)
          clearInterval(logPollRef.current)
          logPollRef.current = null
          load()
        }
      } catch {}
    }, 2000)
  }, [])

  const stopLogPolling = useCallback(() => {
    if (logPollRef.current) {
      clearInterval(logPollRef.current)
      logPollRef.current = null
    }
  }, [])

  const viewLog = async (build) => {
    setLogModal(build)
    setLogConnected(false)
    setLogText('')
    stopLogPolling()

    if (build.status === 'RUNNING') {
      setLogConnected(true)
      try {
        const log = await fetchBuildLog(build.id)
        setLogText(log || '')
      } catch { setLogText('等待日志...') }
      startLogPolling(build.id)
    } else {
      try {
        const log = await fetchBuildLog(build.id)
        setLogText(log || '(empty log)')
      } catch (e) { setLogText('无法加载日志') }
    }
  }

  const closeLog = () => {
    stopLogPolling()
    setLogModal(null)
    setLogText('')
    setLogConnected(false)
  }

  // Trigger build
  const handleTrigger = async () => {
    if (!triggerProject || !triggerPipeline) return alert('请选择项目和水线')
    try {
      let buildParams = null
      if (triggerVersion || triggerBranch || triggerEnv) {
        const params = {}
        if (triggerVersion) params.version = triggerVersion
        if (triggerBranch) params.branch = triggerBranch
        if (triggerEnv) params.env = triggerEnv
        buildParams = JSON.stringify(params)
      }
      await triggerBuild(Number(triggerProject), Number(triggerPipeline), buildParams, triggerBranch || null)
      setShowTrigger(false)
      setTriggerVersion(''); setTriggerBranch(''); setTriggerEnv('')
      load()
    } catch (e) { alert('触发失败: ' + e.message) }
  }

  const loadPipelines = async (projectId) => {
    setTriggerProject(projectId)
    if (!projectId) { setTriggerPipeList([]); return }
    try {
      const pipes = await fetchPipelines(projectId)
      setTriggerPipeList(pipes.filter(p => p.status === 'ACTIVE'))
    } catch { setTriggerPipeList([]) }
  }

  // Webhook URL
  const getWebhookUrl = () => {
    return window.location.origin + '/api/webhook/' + (triggerProject || '{projectId}')
  }

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text).then(() => alert('已复制到剪贴板'))
  }

  const formatLog = (text) => {
    return text.split('\n').map((line, i) => {
      let cls = ''
      if (line.includes('[ERROR]')) cls = 'log-error'
      else if (line.includes('[SUCCESS]')) cls = 'log-success'
      else if (line.includes('[WARN]')) cls = 'log-warn'
      else if (line.includes('[INFO]') || line.includes('[GIT]') || line.includes('[DOCKER]') || line.includes('[K8S]') || line.includes('[TEST]') || line.includes('[STEP]') || line.includes('[ARTIFACT]')) cls = 'log-info'
      return <div key={i} className={cls}>{line || '\u00A0'}</div>
    })
  }

  const formatTime = (t) => t ? new Date(t).toLocaleString() : '-'
  const formatDuration = (ms) => {
    if (!ms) return '-'
    if (ms < 1000) return `${ms}ms`
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
    return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`
  }

  const getProjectName = (projectId) => {
    const p = projects.find(x => x.id === projectId)
    return p ? p.name : `#${projectId}`
  }

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>📋 构建记录</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <div className="filter-bar">
            <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
              <option value="">全部状态</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILED">失败</option>
              <option value="RUNNING">运行中</option>
              <option value="CANCELLED">已取消</option>
            </select>
            <button className="btn btn-outline btn-sm" onClick={load}>🔄 刷新</button>
          </div>
          {canTrigger && <button className="btn btn-primary" onClick={() => setShowTrigger(true)}>▶ 触发构建</button>}
          <button className="btn btn-outline btn-sm" onClick={() => setShowWebhook(true)}>🔗 Webhook</button>
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>构建编号</th>
                <th>项目</th>
                <th>触发方式</th>
                <th>分支</th>
                <th>状态</th>
                <th>进度</th>
                <th>触发者</th>
                <th>开始时间</th>
                <th>耗时</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {builds.length === 0 ? (
                <tr><td colSpan={10} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无构建记录</td></tr>
              ) : (
                builds.map(b => (
                  <tr key={b.id}>
                    <td style={{ fontWeight: 600 }}>
                      #{b.buildNumber}
                      {b.gitCommit && <div style={{ fontSize: 10, color: '#9ca3af' }}>{b.gitCommit.substring(0, 8)}</div>}
                    </td>
                    <td>{getProjectName(b.projectId)}</td>
                    <td>
                      <span className="badge badge-developer" style={{ fontSize: 11 }}>
                        {b.triggerType === 'PUSH' ? '🔀 Push' :
                         b.triggerType === 'SCHEDULE' ? '⏰ 定时' : '👤 手动'}
                      </span>
                    </td>
                    <td style={{ fontSize: 12, color: '#6b7280' }}>{b.branch || '-'}</td>
                    <td>
                      <span className={`badge badge-${b.status?.toLowerCase()}`}>
                        {STATUS_MAP[b.status] || b.status}
                      </span>
                    </td>
                    <td>
                      {b.totalSteps > 0 && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <div className="progress-bar" style={{ width: 60 }}>
                            <div className="fill" style={{ width: `${(b.completedSteps / b.totalSteps) * 100}%` }} />
                          </div>
                          <span style={{ fontSize: 11, color: '#6b7280' }}>{b.completedSteps}/{b.totalSteps}</span>
                        </div>
                      )}
                    </td>
                    <td>{b.triggeredBy || '-'}</td>
                    <td style={{ fontSize: 12 }}>{formatTime(b.startTime)}</td>
                    <td>{formatDuration(b.durationMs)}</td>
                    <td>
                      <div className="btn-group">
                        <button className="btn btn-outline btn-sm" onClick={() => viewLog(b)}>
                          {b.status === 'RUNNING' ? '📡 实时' : '📄 日志'}
                        </button>
                        {b.status === 'RUNNING' && canManage && (
                          <button className="btn btn-danger btn-sm" onClick={async () => { await cancelBuild(b.id); load() }}>⏹ 取消</button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Trigger Build Modal */}
      {showTrigger && (
        <div className="modal-overlay" onClick={() => setShowTrigger(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>▶ 触发构建</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
              <label>项目:
                <select value={triggerProject} onChange={e => loadPipelines(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                  <option value="">-- 选择项目 --</option>
                  {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </label>
              <label>流水线:
                <select value={triggerPipeline} onChange={e => setTriggerPipeline(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                  <option value="">-- 选择流水线 --</option>
                  {triggerPipeList.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </label>
              <details>
                <summary style={{ fontSize: 13, color: '#6b7280', cursor: 'pointer' }}>构建参数（可选）</summary>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
                  <input placeholder="版本号, 如 1.2.0" value={triggerVersion}
                    onChange={e => setTriggerVersion(e.target.value)} />
                  <input placeholder="分支, 如 feature/login" value={triggerBranch}
                    onChange={e => setTriggerBranch(e.target.value)} />
                  <input placeholder="环境, 如 prod" value={triggerEnv}
                    onChange={e => setTriggerEnv(e.target.value)} />
                </div>
              </details>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn btn-outline" onClick={() => setShowTrigger(false)}>取消</button>
                <button className="btn btn-primary" onClick={handleTrigger}>触发构建</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Webhook URL Modal */}
      {showWebhook && (
        <div className="modal-overlay" onClick={() => setShowWebhook(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>🔗 Webhook 配置</h3>
            <div style={{ marginTop: 16, fontSize: 13, color: '#6b7280' }}>
              <p>在 Git 平台（GitHub/GitLab/Gitee）添加以下 Webhook URL，代码推送后将自动触发构建。</p>
              <div style={{ background: '#f3f4f6', padding: 12, borderRadius: 8, marginTop: 12, fontFamily: 'monospace', fontSize: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <code style={{ wordBreak: 'break-all' }}>{getWebhookUrl()}</code>
                <button className="btn btn-outline btn-sm" onClick={() => copyToClipboard(getWebhookUrl())} style={{ marginLeft: 8, flexShrink: 0 }}>📋 复制</button>
              </div>
              <p style={{ marginTop: 16, fontSize: 12 }}>
                支持的平台格式：<br />
                • GitHub: <code>POST /api/webhook/github/{'{projectId}'}</code><br />
                • GitLab: <code>POST /api/webhook/gitlab/{'{projectId}'}</code><br />
                • Gitee: <code>POST /api/webhook/gitee/{'{projectId}'}</code><br />
                • 通用: <code>POST /api/webhook/{'{projectId}'}</code>
              </p>
            </div>
            <div style={{ textAlign: 'right', marginTop: 16 }}>
              <button className="btn btn-outline" onClick={() => setShowWebhook(false)}>关闭</button>
            </div>
          </div>
        </div>
      )}

      {/* Log Modal */}
      {logModal && (
        <div className="modal-overlay" onClick={closeLog}>
          <div className="modal" style={{ maxWidth: 800 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <h3 style={{ margin: 0 }}>
                📄 构建日志 #{logModal.buildNumber}
                {logConnected && <span style={{ color: '#22c55e', fontSize: 12, marginLeft: 8 }}>● 实时</span>}
              </h3>
              <button className="btn btn-outline btn-sm" onClick={closeLog}>关闭</button>
            </div>
            <div className="log-container">{formatLog(logText)}</div>
          </div>
        </div>
      )}
    </>
  )
}
