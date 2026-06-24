import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { fetchTemplates, createTemplate, updateTemplate, deleteTemplate } from '../api'

const TYPE_LABELS = {
  DOCKERFILE: 'Dockerfile',
  K8S_DEPLOYMENT: 'K8s Deployment',
  K8S_SERVICE: 'K8s Service',
  K8S_INGRESS: 'K8s Ingress',
  DOCKER_COMPOSE: 'Docker Compose',
  PIPELINE: '流水线模板'
}

export default function TemplateList() {
  const { canManage } = useAuth()
  const [templates, setTemplates] = useState([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState({ name: '', type: 'DOCKERFILE', category: 'Java', content: '', description: '' })
  const [viewContent, setViewContent] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    try { setTemplates(await fetchTemplates(filter)) } catch (e) { console.error(e) }
    setLoading(false)
  }, [filter])

  useEffect(() => { load() }, [load])

  const openCreate = () => {
    setEditing(null)
    setForm({ name: '', type: 'DOCKERFILE', category: 'Java', content: '', description: '' })
    setShowModal(true)
  }

  const openEdit = (t) => {
    setEditing(t.id)
    setForm({ name: t.name, type: t.type, category: t.category || '', content: t.content, description: t.description || '' })
    setShowModal(true)
  }

  const handleSave = async () => {
    try {
      if (editing) {
        await updateTemplate(editing, form)
      } else {
        await createTemplate(form)
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (id) => {
    if (!window.confirm('确定删除此模板？')) return
    try { await deleteTemplate(id); load() } catch (e) { alert('删除失败: ' + e.message) }
  }

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text).then(() => alert('已复制到剪贴板'))
  }

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>📄 模板管理</h2>
        <div className="filter-bar">
          <select value={filter} onChange={e => setFilter(e.target.value)}>
            <option value="">全部类型</option>
            <option value="DOCKERFILE">Dockerfile</option>
            <option value="K8S_DEPLOYMENT">K8s Deployment</option>
            <option value="K8S_SERVICE">K8s Service</option>
            <option value="K8S_INGRESS">K8s Ingress</option>
            <option value="DOCKER_COMPOSE">Docker Compose</option>
          </select>
          {canManage && <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建模板</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>名称</th><th>类型</th><th>分类</th><th>内置</th><th>描述</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              {templates.length === 0 ? (
                <tr><td colSpan={6} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无模板</td></tr>
              ) : (
                templates.map(t => (
                  <tr key={t.id}>
                    <td style={{ fontWeight: 600 }}>{t.name}</td>
                    <td><span className="badge badge-success">{TYPE_LABELS[t.type] || t.type}</span></td>
                    <td>{t.category || '-'}</td>
                    <td>{t.isBuiltin ? '是' : '否'}</td>
                    <td style={{ color: '#6b7280', fontSize: 12 }}>{t.description || '-'}</td>
                    <td>
                      <div className="btn-group">
                        <button className="btn btn-outline btn-sm" onClick={() => setViewContent(t)}>查看</button>
                        {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(t)}>编辑</button>}
                        {canManage && !t.isBuiltin && (
                          <button className="btn btn-danger btn-sm" onClick={() => handleDelete(t.id)}>删除</button>
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

      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑模板' : '新建模板'}</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 16 }}>
              <input placeholder="模板名称" value={form.name}
                onChange={e => setForm({ ...form, name: e.target.value })} />
              <div style={{ display: 'flex', gap: 8 }}>
                <select value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}
                  style={{ flex: 1 }}>
                  {Object.entries(TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
                <select value={form.category} onChange={e => setForm({ ...form, category: e.target.value })}
                  style={{ flex: 1 }}>
                  <option value="Java">Java</option>
                  <option value="Node.js">Node.js</option>
                  <option value="Python">Python</option>
                  <option value="Go">Go</option>
                  <option value="Generic">Generic</option>
                </select>
              </div>
              <input placeholder="描述" value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })} />
              <textarea placeholder="模板内容" value={form.content}
                onChange={e => setForm({ ...form, content: e.target.value })}
                style={{ minHeight: 200, fontFamily: 'monospace', fontSize: 12 }} />
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
                <button className="btn btn-primary" onClick={handleSave}>保存</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {viewContent && (
        <div className="modal-overlay" onClick={() => setViewContent(null)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h3 style={{ margin: 0 }}>{viewContent.name}</h3>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn btn-outline btn-sm"
                  onClick={() => copyToClipboard(viewContent.content)}>📋 复制</button>
                <button className="btn btn-outline btn-sm" onClick={() => setViewContent(null)}>关闭</button>
              </div>
            </div>
            <div className="log-container" style={{ whiteSpace: 'pre-wrap', fontSize: 12, fontFamily: 'monospace' }}>
              {viewContent.content}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
