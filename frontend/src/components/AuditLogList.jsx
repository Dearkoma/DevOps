import React, { useState, useEffect, useCallback } from 'react'
import { fetchAuditLogs, fetchAuditByUser, fetchAuditByAction } from '../api'

const RESOURCE_ICONS = { PROJECT: '📁', PIPELINE: '🔧', BUILD: '📋', ENVIRONMENT: '🌐', USER: '👤', DEPLOYMENT: '🚀' }
const ACTION_LABELS = { CREATE: '创建', UPDATE: '更新', DELETE: '删除', TRIGGER: '触发', CANCEL: '取消', APPROVE: '审批通过', REJECT: '审批拒绝', LOGIN: '登录', LOGOUT: '登出' }

export default function AuditLogList() {
  const [logs, setLogs] = useState([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [filterType, setFilterType] = useState('')
  const [filterValue, setFilterValue] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      let result
      if (filterType === 'user' && filterValue) {
        result = await fetchAuditByUser(filterValue)
        setLogs(Array.isArray(result) ? result : [])
        setTotalPages(1)
      } else if (filterType === 'action' && filterValue) {
        result = await fetchAuditByAction(filterValue)
        setLogs(Array.isArray(result) ? result : [])
        setTotalPages(1)
      } else {
        result = await fetchAuditLogs(page, 50)
        setLogs(result.content || [])
        setTotalPages(result.totalPages || 1)
      }
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [page, filterType, filterValue])

  useEffect(() => { load() }, [load])

  const formatTime = (t) => t ? new Date(t).toLocaleString() : '-'

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>📝 审计日志</h2>
        <div className="filter-bar">
          <select value={filterType} onChange={e => { setFilterType(e.target.value); setFilterValue(''); setPage(0) }}>
            <option value="">全部</option>
            <option value="user">按用户</option>
            <option value="action">按操作</option>
          </select>
          {filterType === 'user' && (
            <input placeholder="输入用户名" value={filterValue}
              onChange={e => setFilterValue(e.target.value)} style={{ width: 150 }} />
          )}
          {filterType === 'action' && (
            <select value={filterValue} onChange={e => setFilterValue(e.target.value)}>
              <option value="">选择操作</option>
              {Object.entries(ACTION_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          )}
          <button className="btn btn-outline btn-sm" onClick={load}>🔄 刷新</button>
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>时间</th><th>用户</th><th>操作</th><th>资源</th><th>资源名称</th>
                <th>结果</th><th>IP</th>
              </tr>
            </thead>
            <tbody>
              {logs.length === 0 ? (
                <tr><td colSpan={7} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无审计日志</td></tr>
              ) : (
                logs.map(log => (
                  <tr key={log.id}>
                    <td style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{formatTime(log.createdAt)}</td>
                    <td style={{ fontWeight: 600 }}>{log.username}</td>
                    <td>{ACTION_LABELS[log.action] || log.action}</td>
                    <td>{RESOURCE_ICONS[log.resource] || ''} {log.resource} {log.resourceId ? '#' + log.resourceId : ''}</td>
                    <td style={{ color: '#6b7280' }}>{log.resourceName || '-'}</td>
                    <td>
                      <span className={`badge badge-${log.success ? 'success' : 'failed'}`}>
                        {log.success ? '成功' : '失败'}
                      </span>
                    </td>
                    <td style={{ fontSize: 12, color: '#9ca3af' }}>{log.ipAddress || '-'}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          <button className="btn btn-outline btn-sm" disabled={page <= 0} onClick={() => setPage(p => p - 1)}>上一页</button>
          <span style={{ lineHeight: '32px', fontSize: 13 }}>第 {page + 1} / {totalPages} 页</span>
          <button className="btn btn-outline btn-sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      )}
    </>
  )
}
