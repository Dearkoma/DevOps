import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchSchedules, updatePipelineCron, fetchProjects, fetchPipelines } from '../api'

const CRON_EXAMPLES = [
  { label: '每天凌晨2:00', expr: '0 2 * * *' },
  { label: '工作日早上9:00', expr: '0 9 * * 1-5' },
  { label: '每30分钟', expr: '*/30 * * * *' },
  { label: '每6小时', expr: '0 */6 * * *' },
  { label: '每周一凌晨3:00', expr: '0 3 * * 1' },
  { label: '每月1号0:00', expr: '0 0 1 * *' },
]

export default function SchedulerList() {
  const { canManage } = useAuth()
  const navigate = useNavigate()
  const [schedules, setSchedules] = useState([])
  const [projects, setProjects] = useState([])
  const [allPipelines, setAllPipelines] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [editId, setEditId] = useState(null)
  const [editCron, setEditCron] = useState('')
  const [toast, setToast] = useState(null)

  // 非 ADMIN/MANAGER 禁止访问
  useEffect(() => {
    if (!canManage) {
      navigate('/', { replace: true })
    }
  }, [canManage, navigate])

  // 添加定时任务模态框
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ pipelineId: '', cronExpression: '0 9 * * 1-5', cronEnabled: true })
  const [saving, setSaving] = useState(false)

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3000)
  }

  const load = useCallback(async (silent = false) => {
    if (!canManage) return
    if (!silent) setLoading(true)
    setError(null)
    try {
      const [sList, pList, aList] = await Promise.all([
        fetchSchedules(),
        fetchProjects(),
        fetchPipelines()
      ])
      setSchedules(sList || [])
      setProjects(pList || [])
      setAllPipelines(aList || [])
    } catch (e) {
      setError('加载失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const handleToggle = async (pipelineId, enabled) => {
    const item = schedules.find(s => s.pipelineId === pipelineId)
    if (!item) return
    try {
      await updatePipelineCron(pipelineId, item.cronExpression, enabled)
      showToast(enabled ? '已启用' : '已禁用')
      load(true)
    } catch (e) { showToast('操作失败: ' + e.message, 'error') }
  }

  const startEdit = (item) => {
    setEditId(item.pipelineId)
    setEditCron(item.cronExpression || '')
  }

  const saveEdit = async () => {
    if (!editCron.trim()) { showToast('请输入 Cron 表达式', 'error'); return }
    setSaving(true)
    try {
      const item = schedules.find(s => s.pipelineId === editId)
      await updatePipelineCron(editId, editCron.trim(), item ? item.cronEnabled : true)
      setEditId(null)
      showToast('保存成功')
      load(true)
    } catch (e) { showToast('保存失败: ' + e.message, 'error') }
    finally { setSaving(false) }
  }

  const openAddModal = () => {
    const configuredIds = new Set(schedules.map(s => s.pipelineId))
    const available = allPipelines.filter(p => !configuredIds.has(p.id))
    setAddForm({
      pipelineId: available.length > 0 ? String(available[0].id) : '',
      cronExpression: '0 9 * * 1-5',
      cronEnabled: true
    })
    setShowAdd(true)
  }

  const handleAdd = async () => {
    if (!addForm.pipelineId) { showToast('请选择流水线', 'error'); return }
    if (!addForm.cronExpression.trim()) { showToast('请输入 Cron 表达式', 'error'); return }
    setSaving(true)
    try {
      await updatePipelineCron(
        Number(addForm.pipelineId),
        addForm.cronExpression.trim(),
        addForm.cronEnabled
      )
      setShowAdd(false)
      setAddForm({ pipelineId: '', cronExpression: '0 9 * * 1-5', cronEnabled: true })
      showToast('添加成功')
      load(true)
    } catch (e) { showToast('添加失败: ' + e.message, 'error') }
    finally { setSaving(false) }
  }

  const handleDelete = async (pipelineId, pipelineName) => {
    if (!window.confirm(`确认删除"${pipelineName}"的定时任务？`)) return
    try {
      await updatePipelineCron(pipelineId, '', false)
      showToast('已删除')
      load(true)
    } catch (e) { showToast('删除失败: ' + e.message, 'error') }
  }

  const getProjectName = (projectId) => {
    const p = projects.find(x => x.id === projectId)
    return p ? p.name : `#${projectId}`
  }

  const explainCron = (expr) => {
    if (!expr) return '-'
    const parts = expr.trim().split(/\s+/)
    if (parts.length < 5) return expr
    const [min, hour, day, month, week] = parts
    if (min.startsWith('*/')) return `每${min.slice(2)}分钟一次`
    if (hour.startsWith('*/')) return `每${hour.slice(2)}小时（:${min.padStart(2, '0')}）`
    const weekNames = ['日', '一', '二', '三', '四', '五', '六']
    if (week !== '*' && week !== '1-5') {
      const w = parseInt(week)
      return `每周${!isNaN(w) ? weekNames[w] : week} ${hour}:${min.padStart(2, '0')}`
    }
    if (week === '1-5') return `工作日 ${hour}:${min.padStart(2, '0')}`
    if (day !== '*' && month !== '*') return `${month}月${day}号 ${hour}:${min.padStart(2, '0')}`
    if (day !== '*') return `每月${day}号 ${hour}:${min.padStart(2, '0')}`
    return `每天 ${hour}:${min.padStart(2, '0')}`
  }

  const availableForAdd = allPipelines.filter(p => !schedules.find(s => s.pipelineId === p.id))

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', bottom: 24, right: 24, zIndex: 9999,
          padding: '10px 20px', borderRadius: 8, fontSize: 14, fontWeight: 500,
          background: toast.type === 'error' ? '#ef4444' : '#22c55e',
          color: '#fff', boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
          animation: 'fadeIn .2s ease'
        }}>
          {toast.type === 'error' ? '❌ ' : '✅ '}{toast.msg}
        </div>
      )}

      <div className="page-header">
        <h2>⏰ 定时任务</h2>
        <div className="btn-group">
          {canManage && <button className="btn btn-primary" onClick={openAddModal}>+ 添加定时任务</button>}
          <button className="btn btn-outline btn-sm" onClick={() => load(false)}>🔄 刷新</button>
        </div>
      </div>

      {error && (
        <div style={{ background: '#fee2e2', border: '1px solid #fca5a5', borderRadius: 8, padding: '10px 16px', marginBottom: 16, color: '#b91c1c', fontSize: 13 }}>
          ⚠️ {error}
        </div>
      )}

      <div className="card" style={{ background: '#f0f4ff', border: '1px solid #dbe4ff' }}>
        <div style={{ fontSize: 13, color: '#4c51bf', lineHeight: 1.8 }}>
          <strong>定时构建</strong> 允许流水线按 Cron 表达式定期自动触发。<br />
          点击"添加定时任务"选择流水线并设置 Cron 表达式。系统每分钟检查一次自动触发。
        </div>
        <div style={{ marginTop: 8, fontSize: 12, color: '#718096' }}>
          示例: <code>0 9 * * 1-5</code>（工作日9:00）｜ <code>*/30 * * * *</code>（每30分钟）｜ <code>0 2 * * *</code>（每天凌晨2:00）
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>流水线</th>
                <th>所属项目</th>
                <th>Cron 表达式</th>
                <th>可读描述</th>
                <th>启用状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {schedules.length === 0 ? (
                <tr>
                  <td colSpan={6} style={{ textAlign: 'center', padding: 40, color: '#9ca3af' }}>
                    <div style={{ fontSize: 36, marginBottom: 8 }}>⏰</div>
                    <div>暂无定时任务配置</div>
                    <div style={{ fontSize: 12, marginTop: 4 }}>点击上方"添加定时任务"为流水线配置定时触发</div>
                  </td>
                </tr>
              ) : (
                schedules.map(s => (
                  <tr key={s.pipelineId}>
                    <td style={{ fontWeight: 600 }}>{s.pipelineName}</td>
                    <td>{getProjectName(s.projectId)}</td>
                    <td>
                      {editId === s.pipelineId ? (
                        <input
                          value={editCron}
                          onChange={e => setEditCron(e.target.value)}
                          style={{ width: 140, fontFamily: 'monospace', fontSize: 12 }}
                          placeholder="分 时 日 月 周"
                          autoFocus
                        />
                      ) : (
                        <code style={{ fontSize: 12, background: '#f3f4f6', padding: '2px 6px', borderRadius: 4 }}>
                          {s.cronExpression}
                        </code>
                      )}
                    </td>
                    <td style={{ fontSize: 12, color: '#6b7280' }}>{explainCron(s.cronExpression)}</td>
                    <td>
                      {canManage ? (
                        <div
                          onClick={() => handleToggle(s.pipelineId, !s.cronEnabled)}
                          title={s.cronEnabled ? '点击禁用' : '点击启用'}
                          style={{
                            display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer'
                          }}
                        >
                          <div style={{
                            width: 36, height: 20, borderRadius: 10,
                            background: s.cronEnabled ? '#22c55e' : '#d1d5db',
                            position: 'relative', transition: 'background 0.2s',
                            flexShrink: 0
                          }}>
                            <div style={{
                              width: 16, height: 16, borderRadius: '50%', background: '#fff',
                              position: 'absolute', top: 2,
                              left: s.cronEnabled ? 18 : 2,
                              transition: 'left 0.2s',
                              boxShadow: '0 1px 3px rgba(0,0,0,0.2)'
                            }} />
                          </div>
                          <span style={{ fontSize: 12, color: s.cronEnabled ? '#16a34a' : '#9ca3af' }}>
                            {s.cronEnabled ? '启用' : '禁用'}
                          </span>
                        </div>
                      ) : (
                        <span style={{ fontSize: 12, color: s.cronEnabled ? '#16a34a' : '#9ca3af' }}>
                          {s.cronEnabled ? '✅ 启用' : '❌ 禁用'}
                        </span>
                      )}
                    </td>
                    <td>
                      {canManage && (
                        editId === s.pipelineId ? (
                          <div className="btn-group">
                            <button className="btn btn-primary btn-sm" onClick={saveEdit} disabled={saving}>
                              {saving ? '...' : '保存'}
                            </button>
                            <button className="btn btn-outline btn-sm" onClick={() => setEditId(null)}>取消</button>
                          </div>
                        ) : (
                          <div className="btn-group">
                            <button className="btn btn-outline btn-sm" onClick={() => startEdit(s)}>编辑</button>
                            <button className="btn btn-danger btn-sm" onClick={() => handleDelete(s.pipelineId, s.pipelineName)}>删除</button>
                          </div>
                        )
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 添加定时任务模态框 */}
      {showAdd && (
        <div className="modal-overlay" onClick={() => setShowAdd(false)}>
          <div className="modal" style={{ maxWidth: 520 }} onClick={e => e.stopPropagation()}>
            <h3>➕ 添加定时任务</h3>
            <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 16 }}>
              选择一条流水线并为其配置定时触发规则
            </p>

            <div className="form-group">
              <label>选择流水线 *</label>
              {availableForAdd.length === 0 ? (
                <div style={{ color: '#9ca3af', fontSize: 13, padding: '8px 0' }}>
                  ✅ 所有流水线已配置定时任务，如需修改请在表格中点击"编辑"。
                </div>
              ) : (
                <select
                  value={addForm.pipelineId}
                  onChange={e => setAddForm({ ...addForm, pipelineId: e.target.value })}
                >
                  <option value="">-- 选择流水线 --</option>
                  {availableForAdd.map(p => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({getProjectName(p.projectId)})
                    </option>
                  ))}
                </select>
              )}
            </div>

            <div className="form-group">
              <label>Cron 表达式（5位: 分 时 日 月 周）*</label>
              <input
                value={addForm.cronExpression}
                onChange={e => setAddForm({ ...addForm, cronExpression: e.target.value })}
                placeholder="如: 0 2 * * * (每天凌晨2:00)"
                style={{ fontFamily: 'monospace', fontSize: 14 }}
              />
            </div>

            {/* 快捷选择 */}
            <div style={{ marginBottom: 14 }}>
              <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 6 }}>快捷选择:</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {CRON_EXAMPLES.map((ex, i) => (
                  <button
                    key={i}
                    type="button"
                    className="btn btn-outline btn-sm"
                    style={{ fontSize: 11 }}
                    onClick={() => setAddForm({ ...addForm, cronExpression: ex.expr })}
                  >
                    {ex.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="form-group">
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 13 }}>
                <input
                  type="checkbox"
                  checked={addForm.cronEnabled}
                  onChange={e => setAddForm({ ...addForm, cronEnabled: e.target.checked })}
                />
                立即启用
              </label>
            </div>

            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button type="button" className="btn btn-outline" onClick={() => setShowAdd(false)}>取消</button>
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleAdd}
                disabled={saving || availableForAdd.length === 0}
              >
                {saving ? '添加中...' : '添加'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
