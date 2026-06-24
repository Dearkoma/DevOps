import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  fetchDeploymentsPending, fetchDeploymentRequests, fetchDeploymentHistory,
  fetchRollbackCandidates, requestDeploy, approveDeploy, rejectDeploy, rollbackDeploy,
  fetchBuilds, fetchProjects, fetchEnvironments, fetchPipelines
} from '../api'

export default function DeploymentList() {
  const { canTrigger, canManage } = useAuth()
  const [tab, setTab] = useState('requests')
  const [pending, setPending] = useState([])
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)

  const [showModal, setShowModal] = useState(false)
  const [builds, setBuilds] = useState([])
  const [environments, setEnvironments] = useState([])
  const [selectedBuild, setSelectedBuild] = useState('')
  const [selectedEnv, setSelectedEnv] = useState('')
  const [reason, setReason] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [p, h] = await Promise.all([
        fetchDeploymentRequests(),
        fetchDeploymentHistory()
      ])
      setPending(p.filter(r => r.status === 'PENDING'))
      setHistory(Array.isArray(h) ? h : [])
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  const openRequestModal = async () => {
    try {
      const [bList, eList] = await Promise.all([fetchBuilds(), fetchEnvironments()])
      setBuilds(bList.filter(b => b.status === 'SUCCESS'))
      setEnvironments(eList)
    } catch (e) {}
    setShowModal(true)
  }

  const handleRequest = async () => {
    if (!selectedBuild || !selectedEnv) return alert('请选择构建和环境')
    try {
      await requestDeploy({
        buildId: Number(selectedBuild),
        environmentId: Number(selectedEnv),
        reason
      })
      setShowModal(false)
      setSelectedBuild(''); setSelectedEnv(''); setReason('')
      load()
    } catch (e) { alert('部署申请失败: ' + e.message) }
  }

  const handleApprove = async (id) => {
    try { await approveDeploy(id); load() } catch (e) { alert(e.message) }
  }

  const handleReject = async (id) => {
    const r = prompt('拒绝原因:')
    if (!r) return
    try { await rejectDeploy(id, r); load() } catch (e) { alert(e.message) }
  }

  const handleRollback = async (projectId, envId) => {
    try {
      const candidates = await fetchRollbackCandidates(projectId, envId)
      if (!candidates || candidates.length === 0) {
        alert('没有可回滚的部署点')
        return
      }
      const target = candidates[0]
      if (window.confirm(`确定回滚到构建 ${target.buildNumber}？`)) {
        await rollbackDeploy(target.id)
        load()
      }
    } catch (e) { alert('回滚失败: ' + e.message) }
  }

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🚀 部署管理</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <div className="filter-bar">
            <button className={`btn ${tab === 'requests' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('requests')}>部署申请</button>
            <button className={`btn ${tab === 'pending' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('pending')}>
              待审批 {pending.length > 0 && <span style={{ color: '#ef4444' }}>({pending.length})</span>}
            </button>
            <button className={`btn ${tab === 'history' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('history')}>部署历史</button>
          </div>
          {canTrigger && <button className="btn btn-primary" onClick={openRequestModal}>+ 申请部署</button>}
        </div>
      </div>

      {tab === 'requests' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>申请者</th>
                  <th>状态</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {pending.length === 0 ? (
                  <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无部署申请</td></tr>
                ) : (
                  pending.map(r => (
                    <tr key={r.id}>
                      <td>#{r.id}</td>
                      <td>{r.projectName}</td>
                      <td>{r.environmentName}</td>
                      <td>#{r.buildId}</td>
                      <td>{r.requestedBy}</td>
                      <td><span className="badge badge-running">待审批</span></td>
                      <td style={{ fontSize: 12 }}>{new Date(r.createdAt).toLocaleString()}</td>
                      <td>
                        <div className="btn-group">
                          {canManage && <button className="btn btn-primary btn-sm" onClick={() => handleApprove(r.id)}>✓ 通过</button>}
                          {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleReject(r.id)}>✗ 拒绝</button>}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>版本</th>
                  <th>部署者</th><th>状态</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {history.length === 0 ? (
                  <tr><td colSpan={9} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无部署历史</td></tr>
                ) : (
                  history.map(h => (
                    <tr key={h.id}>
                      <td>#{h.id}</td>
                      <td>{h.projectName}</td>
                      <td>{h.environmentName}</td>
                      <td>{h.buildNumber}</td>
                      <td>{h.version || '-'}</td>
                      <td>{h.deployedBy || '-'}</td>
                      <td>
                        <span className={`badge badge-${h.status?.toLowerCase() === 'deployed' ? 'success' : 'failed'}`}>
                          {h.status === 'DEPLOYED' ? '已部署' : h.status === 'ROLLED_BACK' ? '已回滚' : h.status}
                        </span>
                      </td>
                      <td style={{ fontSize: 12 }}>{new Date(h.deployedAt).toLocaleString()}</td>
                      <td>
                        {canManage && <button className="btn btn-outline btn-sm"
                          onClick={() => handleRollback(h.projectId, h.environmentId)}>↩ 回滚</button>}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'pending' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>申请者</th>
                  <th>原因</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {pending.length === 0 ? (
                  <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无待审批</td></tr>
                ) : (
                  pending.map(r => (
                    <tr key={r.id}>
                      <td>#{r.id}</td>
                      <td>{r.projectName}</td>
                      <td>{r.environmentName}</td>
                      <td>#{r.buildId}</td>
                      <td>{r.requestedBy}</td>
                      <td style={{ color: '#6b7280', fontSize: 12 }}>{r.reason || '-'}</td>
                      <td style={{ fontSize: 12 }}>{new Date(r.createdAt).toLocaleString()}</td>
                      <td>
                        <div className="btn-group">
                          {canManage && <button className="btn btn-primary btn-sm" onClick={() => handleApprove(r.id)}>✓</button>}
                          {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleReject(r.id)}>✗</button>}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>申请部署</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
              <label>选择构建:
                <select value={selectedBuild} onChange={e => setSelectedBuild(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                  <option value="">-- 选择成功的构建 --</option>
                  {builds.map(b => <option key={b.id} value={b.id}>#{b.buildNumber} - 项目#{b.projectId}</option>)}
                </select>
              </label>
              <label>目标环境:
                <select value={selectedEnv} onChange={e => setSelectedEnv(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                  <option value="">-- 选择环境 --</option>
                  {environments.map(e => <option key={e.id} value={e.id}>{e.displayName || e.name} {e.protectedEnv ? '(需审批)' : ''}</option>)}
                </select>
              </label>
              <label>申请原因:
                <textarea value={reason} onChange={e => setReason(e.target.value)}
                  style={{ width: '100%', marginTop: 4, minHeight: 60 }} placeholder="可选填" />
              </label>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
                <button className="btn btn-primary" onClick={handleRequest}>提交申请</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
