import React, { useState, useEffect, useCallback } from 'react'
import { useParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchPipelines, createPipeline, updatePipeline, deletePipeline, fetchProjects, triggerBuild } from '../api'

const DEFAULT_STAGES = JSON.stringify([
  { name: '编译构建', steps: [{ name: 'Maven编译', type: 'SHELL', command: 'mvn clean package -DskipTests' }] },
  { name: '单元测试', steps: [{ name: '执行测试', type: 'TEST', command: '' }] },
  { name: 'Docker构建', steps: [
    { name: '构建镜像', type: 'DOCKER_BUILD', command: 'Dockerfile' },
    { name: '推送镜像', type: 'DOCKER_PUSH', command: '' }
  ]},
  { name: 'K8s部署', steps: [{ name: '部署到集群', type: 'K8S_DEPLOY', command: 'deployment.yaml' }] }
], null, 2)

const DEFAULT_FORM = {
  name: '', projectId: '',
  definition: DEFAULT_STAGES,
  description: '',
  branchPattern: '',
  cronExpression: '',
  cronEnabled: false,
}

export default function PipelineList() {
  const { projectId } = useParams()
  const { canManage, canTrigger } = useAuth()
  const pid = projectId ? Number(projectId) : undefined
  const [pipelines, setPipelines] = useState([])
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')
  const [toast, setToast] = useState(null) // { text, type: 'info'|'error' }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setPipelines(await fetchPipelines(pid))
      setProjects(await fetchProjects())
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [projectId])

  useEffect(() => { load() }, [load])

  // When projectId changes externally, reload
  useEffect(() => { load() }, [pid, load])

  const openNew = () => {
    setEditing(null)
    setForm({ ...DEFAULT_FORM, projectId: pid || '' })
    setShowModal(true)
  }

  const openEdit = (p) => {
    setEditing(p)
    setForm({
      name: p.name || '',
      projectId: p.projectId || '',
      description: p.description || '',
      definition: typeof p.definition === 'string' ? p.definition : JSON.stringify(p.definition, null, 2),
      branchPattern: p.branchPattern || '',
      cronExpression: p.cronExpression || '',
      cronEnabled: p.cronEnabled || false,
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    // 基本字段校验
    if (!form.name.trim()) {
      alert('请输入流水线名称')
      return
    }
    if (!form.projectId) {
      alert('请选择所属项目（请先在项目管理中创建项目）')
      return
    }
    try {
      // 验证 JSON 格式
      JSON.parse(form.definition)
      const data = {
        ...form,
        name: form.name.trim(),
        projectId: Number(form.projectId),
        cronEnabled: form.cronEnabled || false,
      }
      if (editing) {
        await updatePipeline(editing.id, data)
      } else {
        await createPipeline(data)
      }
      setShowModal(false)
      load()
    } catch (e) {
      if (e instanceof SyntaxError) {
        alert('流水线定义不是有效的 JSON，请检查格式\n\n提示：确保所有括号和引号正确配对')
      } else {
        alert('保存失败: ' + (e.message || '未知错误'))
      }
    }
  }

  const handleDelete = async (p) => {
    if (!confirm(`确定要删除流水线「${p.name}」吗？`)) return
    try { await deletePipeline(p.id); load() } catch (e) { alert('删除失败: ' + e.message) }
  }

  const handleTrigger = async (p, pid) => {
    setToast({ text: '构建触发中...', type: 'info' })
    try {
      await triggerBuild(pid, p.id)
      setToast({ text: '构建已触发！可在"构建记录"中查看进度', type: 'info' })
      setTimeout(() => setToast(null), 3000)
    } catch (e) {
      setToast({ text: '触发失败: ' + e.message, type: 'error' })
      setTimeout(() => setToast(null), 4000)
    }
  }

  const getProjectName = (pid) => {
    const p = projects.find(pr => pr.id === pid)
    return p ? p.name : `#${pid}`
  }

  const filtered = pipelines.filter(p =>
    !search || p.name?.toLowerCase().includes(search.toLowerCase())
  )

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🔧 流水线管理 {pid && <span style={{fontSize:14,color:'#9ca3af'}}>(筛选项目)</span>}</h2>
        <div className="btn-group">
          <input
            placeholder="搜索流水线..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          {canManage && <button className="btn btn-primary" onClick={openNew}>+ 新建流水线</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>名称</th>
                <th>所属项目</th>
                <th>分支模式</th>
                <th>定时构建</th>
                <th>阶段数</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={7} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无流水线数据</td></tr>
              ) : (
                filtered.map(p => {
                  let stageCount = 0
                  try { stageCount = JSON.parse(typeof p.definition === 'string' ? p.definition : '[]').length } catch {}
                  return (
                    <tr key={p.id}>
                      <td style={{ fontWeight: 600 }}>{p.name}</td>
                      <td>{getProjectName(p.projectId)}</td>
                      <td style={{ fontSize: 12 }}>
                        {p.branchPattern ? (
                          <span className="badge badge-running" style={{ fontFamily: 'monospace', fontSize: 11 }}>
                            {p.branchPattern}
                          </span>
                        ) : (
                          <span style={{ color: '#6b7280' }}>全部</span>
                        )}
                      </td>
                      <td>
                        {p.cronExpression ? (
                          <span className="badge badge-success" style={{ fontSize: 11 }}>
                            ⏰ {p.cronEnabled ? '启用' : '停用'}
                          </span>
                        ) : (
                          <span style={{ color: '#6b7280' }}>—</span>
                        )}
                      </td>
                      <td>{stageCount} 阶段</td>
                      <td style={{ color: '#9ca3af', fontSize: 12 }}>{p.createdAt ? new Date(p.createdAt).toLocaleDateString() : '-'}</td>
                      <td>
                        <div className="btn-group">
                          {canTrigger && <button className="btn btn-success btn-sm" onClick={() => handleTrigger(p, p.projectId)}>▶ 执行</button>}
                          {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(p)}>✏️</button>}
                          {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleDelete(p)}>🗑</button>}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑流水线' : '新建流水线'}</h3>
            <div className="form-group">
              <label>流水线名称 *</label>
              <input value={form.name} onChange={e => setForm({...form, name: e.target.value})} placeholder="如: CI/CD 发布流程" />
            </div>
            <div className="form-group">
              <label>所属项目 *</label>
              <select value={form.projectId} onChange={e => setForm({...form, projectId: e.target.value})}>
                <option value="">-- 选择项目 --</option>
                {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label>描述</label>
              <input value={form.description || ''} onChange={e => setForm({...form, description: e.target.value})} placeholder="流水线用途说明" />
            </div>
            <div className="form-group">
              <label>分支模式 <span style={{ color: '#9ca3af', fontSize: 11, fontWeight: 400 }}>— Webhook 触发时匹配的分支，如 feature/*, main, develop</span></label>
              <input
                value={form.branchPattern}
                onChange={e => setForm({...form, branchPattern: e.target.value})}
                placeholder="留空表示匹配所有分支。示例: feature/*, hotfix/* 或 main,develop"
              />
            </div>
            <div style={{ display: 'flex', gap: 12 }}>
              <div className="form-group" style={{ flex: 2 }}>
                <label>Cron 定时表达式 <span style={{ color: '#9ca3af', fontSize: 11, fontWeight: 400 }}>— 如 0 9 * * 1-5 (工作日早上9点)</span></label>
                <input
                  value={form.cronExpression}
                  onChange={e => setForm({...form, cronExpression: e.target.value})}
                  placeholder="留空表示不启用定时。示例: 0 */6 * * * (每6小时)"
                />
              </div>
              <div className="form-group" style={{ flex: 0, minWidth: 90, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', paddingBottom: 2 }}>
                <label>&nbsp;</label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, padding: '8px 0' }}>
                  <input
                    type="checkbox"
                    checked={form.cronEnabled}
                    onChange={e => setForm({...form, cronEnabled: e.target.checked})}
                    disabled={!form.cronExpression}
                  />
                  启用
                </label>
              </div>
            </div>
            <div className="form-group">
              <label>流水线定义 (JSON)</label>
              <textarea
                value={form.definition}
                onChange={e => setForm({...form, definition: e.target.value})}
                rows={14}
                style={{ fontFamily: "'Courier New', monospace", fontSize: 12 }}
              />
            </div>
            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}
      {/* Toast notification */}
      {toast && (
        <div className={`toast toast-${toast.type}`}>
          {toast.text}
        </div>
      )}
    </>
  )
}
