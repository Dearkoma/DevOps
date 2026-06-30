import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchUsers, createUser, updateUser, deleteUser } from '../api'

const ROLES = ['ADMIN', 'MANAGER', 'DEVELOPER', 'VIEWER']
const ROLE_LABELS = { ADMIN: '管理员', MANAGER: '项目经理', DEVELOPER: '开发者', VIEWER: '观察者' }

const DEFAULT_FORM = {
  username: '', password: '', email: '', role: 'DEVELOPER',
}

export default function UserManagement() {
  const { isAdmin, user: currentUser } = useAuth()
  const navigate = useNavigate()
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')

  // 非 ADMIN 禁止访问
  useEffect(() => {
    if (!isAdmin) {
      navigate('/', { replace: true })
    }
  }, [isAdmin, navigate])

  const load = useCallback(async () => {
    setLoading(true)
    try { setUsers(await fetchUsers()) } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { if (isAdmin) load() }, [isAdmin, load])

  const openNew = () => {
    setEditing(null)
    setForm(DEFAULT_FORM)
    setShowModal(true)
  }

  const openEdit = (u) => {
    setEditing(u)
    setForm({
      username: u.username || '',
      password: '',
      email: u.email || '',
      role: u.role || 'DEVELOPER',
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    try {
      if (editing) {
        const data = { ...form }
        if (!data.password) delete data.password
        await updateUser(editing.id, data)
      } else {
        if (!form.password) { alert('密码不能为空'); return }
        await createUser(form)
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (u) => {
    if (!confirm(`确定要删除用户「${u.username}」吗？`)) return
    try { await deleteUser(u.id); load() } catch (e) { alert('删除失败: ' + e.message) }
  }

  const getRoleBadge = (role) => {
    if (role === 'ADMIN') return 'badge-admin'
    if (role === 'MANAGER') return 'badge-developer'
    if (role === 'DEVELOPER') return 'badge-developer'
    return 'badge-viewer'
  }

  const filtered = users.filter(u =>
    !search || u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.email?.toLowerCase().includes(search.toLowerCase())
  )

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>👥 用户管理</h2>
        <div className="btn-group">
          <input
            placeholder="搜索用户..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          {isAdmin && <button className="btn btn-primary" onClick={openNew}>+ 新建用户</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>用户名</th>
                <th>邮箱</th>
                <th>角色</th>
                <th>创建时间</th>
                {isAdmin && <th>操作</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={isAdmin ? 5 : 4} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无用户数据</td></tr>
              ) : (
                filtered.map(u => (
                  <tr key={u.id}>
                    <td style={{ fontWeight: 600 }}>👤 {u.username}</td>
                    <td style={{ color: '#6b7280' }}>{u.email || '-'}</td>
                    <td><span className={`badge ${getRoleBadge(u.role)}`}>{ROLE_LABELS[u.role] || u.role}</span></td>
                    <td style={{ color: '#9ca3af', fontSize: 12 }}>{u.createdAt ? new Date(u.createdAt).toLocaleDateString() : '-'}</td>
                    {isAdmin && (
                      <td>
                        <div className="btn-group">
                          <button className="btn btn-outline btn-sm" onClick={() => openEdit(u)}>✏️</button>
                          {u.id !== currentUser?.id && (
                            <button className="btn btn-danger btn-sm" onClick={() => handleDelete(u)}>🗑</button>
                          )}
                        </div>
                      </td>
                    )}
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
            <h3>{editing ? '编辑用户' : '新建用户'}</h3>
            <div className="form-group">
              <label>用户名 *</label>
              <input value={form.username} onChange={e => setForm({...form, username: e.target.value})} placeholder="用户名" disabled={!!editing} />
            </div>
            <div className="form-group">
              <label>{editing ? '新密码 (留空不修改)' : '密码 *'}</label>
              <input type="password" value={form.password} onChange={e => setForm({...form, password: e.target.value})} placeholder={editing ? '留空不修改密码' : '输入密码'} />
            </div>
            <div className="form-group">
              <label>邮箱</label>
              <input value={form.email} onChange={e => setForm({...form, email: e.target.value})} placeholder="user@example.com" />
            </div>
            <div className="form-group">
              <label>角色</label>
              <select value={form.role} onChange={e => setForm({...form, role: e.target.value})}>
                {ROLES.map(r => <option key={r} value={r}>{ROLE_LABELS[r] || r}</option>)}
              </select>
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
