import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { fetchEnvironments, createEnvironment, updateEnvironment, deleteEnvironment } from '../api'

const DEFAULT_FORM = {
  name: '', displayName: '', description: '',
  deployUrl: '', k8sNamespace: 'default',
  status: 'ACTIVE', protectedEnv: false,
}

export default function EnvironmentList() {
  const { canManage } = useAuth()
  const [environments, setEnvironments] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try { setEnvironments(await fetchEnvironments()) }
    catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const openNew = () => {
    setEditing(null)
    setForm(DEFAULT_FORM)
    setShowModal(true)
  }

  const openEdit = (env) => {
    setEditing(env)
    setForm({
      name: env.name || '',
      displayName: env.displayName || '',
      description: env.description || '',
      deployUrl: env.deployUrl || '',
      k8sNamespace: env.k8sNamespace || 'default',
      status: env.status || 'ACTIVE',
      protectedEnv: env.protectedEnv || false,
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    try {
      if (editing) {
        await updateEnvironment(editing.id, form)
      } else {
        await createEnvironment(form)
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (env) => {
    if (!confirm(`确定要删除环境「${env.name}」吗？`)) return
    try { await deleteEnvironment(env.id); load() }
    catch (e) { alert('删除失败: ' + e.message) }
  }

  const getStatusDot = (status) =>
    status === 'ACTIVE' ? '#10b981' : '#ef4444'

  const filtered = environments.filter(env =>
    !search || env.name?.toLowerCase().includes(search.toLowerCase()) ||
    env.displayName?.toLowerCase().includes(search.toLowerCase())
  )

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🌐 环境管理</h2>
        <div className="btn-group">
          <input
            placeholder="搜索环境..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          {canManage && <button className="btn btn-primary" onClick={openNew}>+ 新建环境</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>环境名称</th>
                <th>显示名称</th>
                <th>部署地址</th>
                <th>K8s 命名空间</th>
                <th>受保护</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={7} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无环境数据</td></tr>
              ) : (
                filtered.map(env => (
                  <tr key={env.id}>
                    <td style={{ fontWeight: 600 }}>{env.name}</td>
                    <td>{env.displayName || '-'}</td>
                    <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{env.deployUrl || '-'}</td>
                    <td><code>{env.k8sNamespace || '-'}</code></td>
                    <td>{env.protectedEnv ? '🔒 是' : '否'}</td>
                    <td>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: getStatusDot(env.status), display: 'inline-block' }} />
                        {env.status}
                      </span>
                    </td>
                    <td>
                      <div className="btn-group">
                        {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(env)}>✏️</button>}
                        {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleDelete(env)}>🗑</button>}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑环境' : '新建环境'}</h3>
            <div className="form-group">
              <label>环境名称 *</label>
              <input value={form.name} onChange={e => setForm({...form, name: e.target.value})} placeholder="如: dev / staging / prod" />
            </div>
            <div className="form-group">
              <label>显示名称</label>
              <input value={form.displayName} onChange={e => setForm({...form, displayName: e.target.value})} placeholder="如: 开发环境" />
            </div>
            <div className="form-group">
              <label>描述</label>
              <textarea value={form.description} onChange={e => setForm({...form, description: e.target.value})} rows={2} placeholder="环境用途说明" />
            </div>
            <div className="detail-grid">
              <div className="form-group">
                <label>部署地址</label>
                <input value={form.deployUrl} onChange={e => setForm({...form, deployUrl: e.target.value})} placeholder="https://dev.example.com" />
              </div>
              <div className="form-group">
                <label>K8s 命名空间</label>
                <input value={form.k8sNamespace} onChange={e => setForm({...form, k8sNamespace: e.target.value})} placeholder="default" />
              </div>
            </div>
            <div className="detail-grid">
              <div className="form-group">
                <label>状态</label>
                <select value={form.status} onChange={e => setForm({...form, status: e.target.value})}>
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="INACTIVE">INACTIVE</option>
                </select>
              </div>
              <div className="form-group">
                <label>受保护环境</label>
                <select value={form.protectedEnv} onChange={e => setForm({...form, protectedEnv: e.target.value === 'true'})}>
                  <option value="false">否</option>
                  <option value="true">是 (需审批)</option>
                </select>
              </div>
            </div>
            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
