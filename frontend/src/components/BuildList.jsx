import React, { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchBuilds, fetchBuildLog, triggerBuild, cancelBuild, deleteBuild, fetchProjects, fetchPipelines } from '../api'

const STATUS_MAP = { SUCCESS: '成功', FAILED: '失败', RUNNING: '运行中', CANCELLED: '已取消' }

export default function BuildList() {
  const navigate = useNavigate()
  const { canTrigger, canManage } = useAuth()
  const [builds, setBuilds] = useState([])
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState(null) // { text, type: 'success'|'error'|'info' }
  const prevStatusRef = useRef({}) // buildId → status, 用于检测完成
  const [statusFilter, setStatusFilter] = useState('')
  const [logModal, setLogModal] = useState(null)
  const [logText, setLogText] = useState('')
  const [logConnected, setLogConnected] = useState(false)

  // Log polling ref
  const [showTrigger, setShowTrigger] = useState(false)
  const [triggerProject, setTriggerProject] = useState('')
  const [triggerPipeline, setTriggerPipeline] = useState('')
  const [triggerPipeList, setTriggerPipeList] = useState([])
  const [allPipeList, setAllPipeList] = useState([])  // 全部流水线（含非活跃）
  const [pipesLoading, setPipesLoading] = useState(false)
  const [triggerVersion, setTriggerVersion] = useState('')
  const [triggerBranch, setTriggerBranch] = useState('')
  const [triggerEnv, setTriggerEnv] = useState('')

  // Webhook modal
  const [showWebhook, setShowWebhook] = useState(false)

  // Delete confirm modal
  const [deleteTarget, setDeleteTarget] = useState(null) // build object to delete

  // ===== Log auto-scroll (sticky bottom) =====
  // logContainerRef   : the scrollable log <div>
  // stickToBottomRef  : whether to keep following the latest line.
  //                     Set to true initially and whenever the user is near
  //                     the bottom; flipped to false when the user scrolls up
  //                     to read history, so the view stops jumping back to top.
  const logContainerRef = useRef(null)
  const stickToBottomRef = useRef(true)
  // Bump on every logText change so the effect runs even when content is
  // replaced with an equal-length string (rare, but possible during retransmits).
  const [logTick, setLogTick] = useState(0)

  // Track user scroll position: stick to bottom unless they scrolled up.
  const handleLogScroll = useCallback(() => {
    const el = logContainerRef.current
    if (!el) return
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    stickToBottomRef.current = distanceFromBottom < 48
  }, [])

  // Whenever the log text changes, jump to the bottom if we are still
  // in "follow" mode. This is what stops the view from snapping back to
  // the top every time the poll writes a new chunk.
  useEffect(() => {
    if (!logModal) return
    const el = logContainerRef.current
    if (!el) return
    if (stickToBottomRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [logText, logTick, logModal])

  // 初始加载 — 显示 spinner；自动刷新与手动刷新 — 静默更新
  const load = useCallback(async (showSpinner = true) => {
    if (showSpinner) setLoading(true)
    try {
      const [bList, pList] = await Promise.all([fetchBuilds(undefined, statusFilter), fetchProjects()])
      setBuilds(bList)
      setProjects(pList)
    } catch (e) { console.error(e) }
    finally { if (showSpinner) setLoading(false) }
  }, [statusFilter])

  // 初始加载（仅一次）
  const initRef = useRef(true)
  useEffect(() => { load(true) }, [load])

  // ===== 静默自动刷新：有运行中构建时每 3 秒更新数据，不触发 loading 状态 =====
  useEffect(() => {
    const hasRunning = builds.some(b => b.status === 'RUNNING')
    if (!hasRunning) return
    const timer = setInterval(() => load(false), 3000)
    return () => clearInterval(timer)
  }, [builds, load])

  // ===== 检测构建完成并显示 toast 通知 =====
  useEffect(() => {
    builds.forEach(b => {
      const prev = prevStatusRef.current[b.id]
      if (prev === 'RUNNING' && (b.status === 'SUCCESS' || b.status === 'FAILED')) {
        const isSuccess = b.status === 'SUCCESS'
        setToast({
          text: `构建 ${b.buildNumber} ${isSuccess ? '完成 ✅' : '失败 ❌'} — ${isSuccess ? '部署已就绪' : '请查看构建日志'}`,
          type: isSuccess ? 'success' : 'error'
        })
        setTimeout(() => setToast(null), 5000)
      }
      prevStatusRef.current[b.id] = b.status
    })
  }, [builds])

  // WebSocket log connection (fallback: polling)
  const logPollRef = useRef(null)

  const startLogPolling = useCallback((buildId) => {
    if (logPollRef.current) clearInterval(logPollRef.current)
    logPollRef.current = setInterval(async () => {
      try {
        const log = await fetchBuildLog(buildId)
        setLogText(log || '')
        setLogTick(t => t + 1)  // trigger sticky-bottom scroll
        // Check if build finished
        const b = await fetchBuilds().then(bs => bs.find(x => x.id === buildId))
        if (b && b.status !== 'RUNNING') {
          setLogConnected(false)
          clearInterval(logPollRef.current)
          logPollRef.current = null
          load(false)
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
    setLogTick(t => t + 1)
    // Reset follow mode for the new log: start stuck to the bottom.
    stickToBottomRef.current = true
    stopLogPolling()

    if (build.status === 'RUNNING') {
      setLogConnected(true)
      try {
        const log = await fetchBuildLog(build.id)
        setLogText(log || '')
        setLogTick(t => t + 1)
      } catch { setLogText('等待日志...') }
      startLogPolling(build.id)
    } else {
      try {
        const log = await fetchBuildLog(build.id)
        setLogText(log || '(empty log)')
        setLogTick(t => t + 1)
      } catch (e) { setLogText('无法加载日志') }
    }
  }

  const closeLog = () => {
    stopLogPolling()
    setLogModal(null)
    setLogText('')
    setLogConnected(false)
    stickToBottomRef.current = true
  }

  // "Jump to latest" button: force scroll to bottom and resume following.
  const jumpToLatest = useCallback(() => {
    stickToBottomRef.current = true
    const el = logContainerRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [])

  // Trigger build
  const handleTrigger = async () => {
    if (!triggerProject) return alert('请选择项目')
    if (!triggerPipeline) return alert('请选择流水线。如该项目暂无流水线，请先前往流水线管理页面创建。')
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
      setTriggerProject(''); setTriggerPipeline(''); setTriggerPipeList([]); setAllPipeList([])
      setTriggerVersion(''); setTriggerBranch(''); setTriggerEnv('')
      load(false)
    } catch (e) { alert('触发失败: ' + e.message) }
  }

  const loadPipelines = async (projectId) => {
    setTriggerProject(projectId)
    setTriggerPipeline('')
    if (!projectId) {
      setTriggerPipeList([])
      setAllPipeList([])
      return
    }
    setPipesLoading(true)
    try {
      const pipes = await fetchPipelines(projectId)
      setAllPipeList(pipes)
      setTriggerPipeList(pipes.filter(p => p.status === 'ACTIVE'))
    } catch {
      setTriggerPipeList([])
      setAllPipeList([])
    } finally {
      setPipesLoading(false)
    }
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
            <button className="btn btn-outline btn-sm" onClick={() => load(false)}>🔄 刷新</button>
          </div>
          {canTrigger && (
            <button
              className="btn btn-primary"
              onClick={() => {
                if (projects.length === 0) {
                  alert('当前没有任何项目，请先在项目管理中创建项目，然后为其配置流水线。')
                  return
                }
                setShowTrigger(true)
              }}
              title={projects.length === 0 ? '需要先创建项目' : '触发构建'}
            >
              ▶ 触发构建
            </button>
          )}
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
                          <button className="btn btn-danger btn-sm" onClick={async () => { await cancelBuild(b.id); load(false) }}>⏹ 取消</button>
                        )}
                        {b.status !== 'RUNNING' && canManage && (
                          <button className="btn btn-danger btn-sm" onClick={() => setDeleteTarget(b)}>🗑 删除</button>
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
                {pipesLoading ? (
                  <div style={{ marginTop: 6, fontSize: 13, color: '#9ca3af' }}>加载中...</div>
                ) : allPipeList.length === 0 ? (
                  <div style={{
                    marginTop: 8,
                    padding: '14px 16px',
                    background: '#fffbeb',
                    border: '1px solid #fcd34d',
                    borderRadius: 8,
                    fontSize: 13,
                    lineHeight: 1.7,
                  }}>
                    <div style={{ color: '#92400e', fontWeight: 600, marginBottom: 6 }}>
                      ⚠️ 该项目暂无可用流水线
                    </div>
                    <div style={{ color: '#a16207', marginBottom: 10 }}>
                      构建必须通过流水线触发，请先为此项目创建一条流水线。
                    </div>
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => { setShowTrigger(false); navigate('/pipelines') }}
                      style={{ fontSize: 12 }}
                    >
                      🔧 前往流水线管理
                    </button>
                  </div>
                ) : (
                  <>
                    <select value={triggerPipeline} onChange={e => setTriggerPipeline(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                      <option value="">-- 选择流水线（{triggerPipeList.length} 条可用）--</option>
                      {allPipeList.map(p => {
                        const isActive = p.status === 'ACTIVE'
                        return (
                          <option key={p.id} value={p.id} disabled={!isActive}>
                            {p.name}{!isActive ? ' [已停用]' : ''}
                          </option>
                        )
                      })}
                    </select>
                    {triggerPipeList.length === 0 && (
                      <div style={{ marginTop: 6, fontSize: 12, color: '#f59e0b' }}>
                        ⚠️ 所有流水线均处于停用状态，请在流水线管理中启用。
                      </div>
                    )}
                  </>
                )}
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

      {/* Delete Confirm Modal */}
      {deleteTarget && (
        <div className="modal-overlay" onClick={() => setDeleteTarget(null)}>
          <div className="modal" style={{ maxWidth: 420 }} onClick={e => e.stopPropagation()}>
            <h3>🗑 确认删除构建记录</h3>
            <div style={{ marginTop: 16, fontSize: 14, color: '#374151', lineHeight: 1.8 }}>
              <p>即将删除：</p>
              <div style={{ background: '#f3f4f6', borderRadius: 8, padding: '10px 14px', marginBottom: 8 }}>
                <div><strong>构建编号：</strong>#{deleteTarget.buildNumber}</div>
                <div><strong>状态：</strong>{STATUS_MAP[deleteTarget.status] || deleteTarget.status}</div>
                <div><strong>触发者：</strong>{deleteTarget.triggeredBy || '-'}</div>
              </div>
              <p style={{ color: '#ef4444', fontSize: 12 }}>⚠️ 此操作不可撤销，构建日志也将一并删除。</p>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn btn-outline" onClick={() => setDeleteTarget(null)}>取消</button>
              <button className="btn btn-danger" onClick={async () => {
                try {
                  await deleteBuild(deleteTarget.id)
                  setDeleteTarget(null)
                  load(false)
                } catch (e) {
                  alert('删除失败：' + e.message)
                }
              }}>确认删除</button>
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
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn btn-outline btn-sm" onClick={jumpToLatest} title="滚动到最新日志">⬇ 最新</button>
                <button className="btn btn-outline btn-sm" onClick={closeLog}>关闭</button>
              </div>
            </div>
            <div
              className="log-container"
              ref={logContainerRef}
              onScroll={handleLogScroll}
            >{formatLog(logText)}</div>
            <div style={{ marginTop: 8, fontSize: 11, color: '#9ca3af', display: 'flex', justifyContent: 'space-between' }}>
              <span>新日志到达时自动滚动到底部；向上滚动可查看历史，此时暂停跟随。</span>
              <span>每 2 秒刷新一次</span>
            </div>
          </div>
        </div>
      )}

      {/* Toast notification — 构建完成提示 */}
      {toast && (
        <div className={`toast toast-${toast.type}`}>
          {toast.text}
        </div>
      )}
    </>
  )
}
