import React, { useState, useEffect, useCallback } from 'react'
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
  const [schedules, setSchedules] = useState([])
  const [projects, setProjects] = useState([])
  const [allPipelines, setAllPipelines] = useState([])
  const [loading, setLoading] = useState(true)
  const [editId, setEditId] = useState(null)
  const [editCron, setEditCron] = useState('')
  const [editBranch, setEditBranch] = useState('')

  // 添加定时任务模态框
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ pipelineId: '', cronExpression: '', cronEnabled: true })

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [sList, pList, aList] = await Promise.all([
        fetchSchedules(),
        fetchProjects(),
        fetchPipelines()  // 获取所有流水线用于下拉选择
      ])
      setSchedules(sList || [])
      setProjects(pList || [])
      setAllPipelines(aList || [])
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  const handleToggle = async (id, enabled) => {
    try {
      const item = schedules.find(s => s.pipelineId === id)
      await updatePipelineCron(id, item.cronExpression, enabled)
      load()
    } catch (e) { alert('操作失败: ' + e.message) }
  }

  const startEdit = (item) => {
    setEditId(item.pipelineId)
    setEditCron(item.cronExpression || '')
    setEditBranch(item.branchPattern || '')
  }

  const saveEdit = async () => {
    if (!editCron.trim()) return alert('请输入 Cron 表达式')
    try {
      const item = schedules.find(s => s.pipelineId === editId)
      await updatePipelineCron(editId, editCron.trim(), item ? item.cronEnabled : true)
      setEditId(null)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  // 添加新定时任务
  const handleAdd = async () => {
    if (!addForm.pipelineId) return alert('请选择流水线')
    if (!addForm.cronExpression.trim()) return alert('请输入 Cron 表达式')
    try {
      await updatePipelineCron(
        Number(addForm.pipelineId),
        addForm.cronExpression.trim(),
        addForm.cronEnabled
      )
      setShowAdd(false)
      setAddForm({ pipelineId: '', cronExpression: '', cronEnabled: true })
      load()
    } catch (e) { alert('添加失败: ' + e.message) }
  }

  const openAddModal = () => {
    // 过滤掉已有 cron 的流水线（避免重复），但仍可在已配置的流水线上修改
    const configuredIds = new Set(schedules.map(s => s.pipelineId))
    const available = allPipelines.filter(p => !configuredIds.has(p.id))
    setAddForm({
      pipelineId: available.length > 0 ? String(available[0].id) : '',
      cronExpression: '0 9 * * 1-5',
      cronEnabled: true
    })
    setShowAdd(true)
  }

  const getProjectName = (projectId) => {
    const p = projects.find(x => x.id === projectId)
    return p ? p.name : `#${projectId}`
  }

  const getPipelineProjectName = (pipelineId) => {
    const p = allPipelines.find(x => x.id === pipelineId)
    return p ? `${p.name} (${getProjectName(p.projectId)})` : `#${pipelineId}`
  }

  const explainCron = (expr) => {
    if (!expr) return '-'
    const parts = expr.trim().split(/\s+/)
    if (parts.length < 5) return expr
    const [min, hour, day, month, week] = parts
    const weekNames = ['日', '一', '二', '三', '四', '五', '六']
    let desc = ''
    if (min !== '*' || hour !== '*') {
      desc = `每天 ${hour}:${min.padStart(2, '0')}`
    } else if (week !== '*') {
      desc = `每周${weekNames[parseInt(week)]}`
    } else if (day !== '*' && month === '*') {
      desc = `每月${day}号`
    } else {
      desc = '自定义'
    }
    if (day !== '*' && month !== '*') {
      desc = `${month}月${day}号 ${hour}:${min.padStart(2, '0')}`
    }
    return desc
  }

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>⏰ 定时任务</h2>
        <div className="btn-group">
          <button className="btn btn-primary" onClick={openAddModal}>+ 添加定时任务</button>
          <button className="btn btn-outline btn-sm" onClick={load}>🔄 刷新</button>
        </div>
      </div>

      {/* Explanation card */}
      <div className="card" style={{ background: '#f0f4ff', border: '1px solid #dbe4ff' }}>
        <div style={{ fontSize: 13, color: '#4c51bf', lineHeight: 1.8 }}>
          <strong>定时构建</strong> 允许流水线按 Cron 表达式定期自动触发。<br />
          配置方式：点击"添加定时任务"，选择流水线并设置 Cron 表达式。<br />
          或在<strong>流水线管理</strong>中编辑流水线时设置 <code>cronExpression</code> 和 <code>cronEnabled</code>。<br />
          系统每分钟检查一次，匹配当前时间的流水线将被自动触发。
        </div>
        <div style={{ marginTop: 12, fontSize: 12, color: '#718096' }}>
          示例: <code>0 9 * * 1-5</code>（工作日9:00）｜ <code>*/30 * * * *</code>（每30分钟）｜ <code>0 2 * * *</code>（每天凌晨2:00）｜ <code>0 0 1 * *</code>（每月1号0:00）
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
                    <div style={{ fontSize: 12, marginTop: 4 }}>
                      点击上方"添加定时任务"按钮为流水线配置定时触发
                    </div>
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
                        />
                      ) : (
                        <code style={{ fontSize: 12, background: '#f3f4f6', padding: '2px 6px', borderRadius: 4 }}>
                          {s.cronExpression}
                        </code>
                      )}
                    </td>
                    <td style={{ fontSize: 12, color: '#6b7280' }}>{explainCron(s.cronExpression)}</td>
                    <td>
                      <label className="toggle-switch" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                        <div
                          onClick={() => handleToggle(s.pipelineId, !s.cronEnabled)}
                          style={{
                            width: 36, height: 20, borderRadius: 10,
                            background: s.cronEnabled ? '#22c55e' : '#d1d5db',
                            position: 'relative', transition: 'background 0.2s',
                            cursor: 'pointer'
                          }}
                        >
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
                      </label>
                    </td>
                    <td>
                      {editId === s.pipelineId ? (
                        <div className="btn-group">
                          <button className="btn btn-primary btn-sm" onClick={saveEdit}>保存</button>
                          <button className="btn btn-outline btn-sm" onClick={() => setEditId(null)}>取消</button>
                        </div>
                      ) : (
                        <button className="btn btn-outline btn-sm" onClick={() => startEdit(s)}>编辑</button>
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
              <select
                value={addForm.pipelineId}
                onChange={e => setAddForm({ ...addForm, pipelineId: e.target.value })}
              >
                <option value="">-- 选择流水线 --</option>
                {allPipelines
                  .filter(p => !schedules.find(s => s.pipelineId === p.id))
                  .map(p => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({getProjectName(p.projectId)})
                    </option>
                  ))}
                {allPipelines.filter(p => !schedules.find(s => s.pipelineId === p.id)).length === 0 && (
                  <option value="" disabled>所有流水线已配置定时任务</option>
                )}
              </select>
            </div>

            <div className="form-group">
              <label>Cron 表达式 (5位: 分 时 日 月 周) *</label>
              <input
                value={addForm.cronExpression}
                onChange={e => setAddForm({ ...addForm, cronExpression: e.target.value })}
                placeholder="如: 0 2 * * * (每天凌晨2:00)"
                style={{ fontFamily: 'monospace', fontSize: 14 }}
              />
            </div>

            {/* 快捷选择 */}
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 6 }}>快捷选择:</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {CRON_EXAMPLES.map((ex, i) => (
                  <button
                    key={i}
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
              <button className="btn btn-outline" onClick={() => setShowAdd(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleAdd}>添加</button>
            </div>
          </div>
        </div>
      )}

      {/* 说明: 也可在已有任务上重新选择流水线 */}
      {schedules.length > 0 && allPipelines.filter(p => !schedules.find(s => s.pipelineId === p.id)).length === 0 && (
        <div style={{ marginTop: 12, fontSize: 12, color: '#9ca3af', textAlign: 'center' }}>
          ✅ 所有流水线已配置定时任务。如需修改，请在表格中编辑或通过流水线管理页面调整。
        </div>
      )}
    </>
  )
}
