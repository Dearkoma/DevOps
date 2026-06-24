import React, { useState, useEffect, useCallback } from 'react'
import { fetchNotifications, fetchUnreadCount, markNotifRead, markAllNotifRead } from '../api'

const TYPE_CONFIG = {
  BUILD_SUCCESS:   { label: '构建成功', icon: '✅', color: '#22c55e', bg: 'rgba(34,197,94,0.1)' },
  BUILD_FAILED:    { label: '构建失败', icon: '❌', color: '#ef4444', bg: 'rgba(239,68,68,0.1)' },
  DEPLOY_APPROVAL: { label: '部署审批', icon: '🚀', color: '#f59e0b', bg: 'rgba(245,158,11,0.1)' },
  DEPLOY_SUCCESS:  { label: '部署成功', icon: '✅', color: '#22c55e', bg: 'rgba(34,197,94,0.1)' },
  DEPLOY_FAILED:   { label: '部署失败', icon: '❌', color: '#ef4444', bg: 'rgba(239,68,68,0.1)' },
  SYSTEM:          { label: '系统通知', icon: '📢', color: '#60a5fa', bg: 'rgba(96,165,250,0.1)' }
}

const FILTER_OPTIONS = [
  { value: '', label: '全部通知' },
  { value: 'BUILD_SUCCESS', label: '✅ 构建成功' },
  { value: 'BUILD_FAILED', label: '❌ 构建失败' },
  { value: 'DEPLOY_APPROVAL', label: '🚀 部署审批' },
  { value: 'DEPLOY_SUCCESS', label: '✅ 部署成功' },
  { value: 'DEPLOY_FAILED', label: '❌ 部署失败' },
  { value: 'SYSTEM', label: '📢 系统通知' }
]

function timeAgo(dateStr) {
  const now = new Date()
  const date = new Date(dateStr)
  const diff = now - date
  const mins = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins} 分钟前`
  if (hours < 24) return `${hours} 小时前`
  if (days < 7) return `${days} 天前`
  return date.toLocaleDateString()
}

export default function NotificationCenter() {
  const [notifications, setNotifications] = useState([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState('')
  const [acting, setActing] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [list, unread] = await Promise.all([
        fetchNotifications(),
        fetchUnreadCount()
      ])
      setNotifications(list || [])
      setUnreadCount(unread?.count ?? 0)
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  // Auto-refresh every 15 seconds
  useEffect(() => {
    const timer = setInterval(load, 15000)
    return () => clearInterval(timer)
  }, [load])

  const handleMarkRead = async (id) => {
    try {
      setActing(id)
      await markNotifRead(id)
      setNotifications(prev => prev.map(n => n.id === id ? { ...n, isRead: true } : n))
      setUnreadCount(c => Math.max(0, c - 1))
    } catch (e) { console.error(e) } finally { setActing(null) }
  }

  const handleMarkAll = async () => {
    try {
      setActing('all')
      await markAllNotifRead()
      setNotifications(prev => prev.map(n => ({ ...n, isRead: true })))
      setUnreadCount(0)
    } catch (e) { console.error(e) } finally { setActing(null) }
  }

  const filtered = filter
    ? notifications.filter(n => n.type === filter)
    : notifications

  const unreadFiltered = filtered.filter(n => !n.isRead).length

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🔔 通知中心</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {unreadCount > 0 && (
            <span className="badge badge-failed" style={{ fontSize: 13, padding: '4px 10px' }}>
              {unreadCount} 条未读
            </span>
          )}
          <button className="btn btn-outline btn-sm" onClick={load}>🔄 刷新</button>
        </div>
      </div>

      {/* Filter bar */}
      <div className="card" style={{ marginBottom: 16, padding: 12 }}>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>筛选：</span>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {FILTER_OPTIONS.map(opt => (
              <button
                key={opt.value}
                className={`btn btn-sm ${filter === opt.value ? 'btn-primary' : 'btn-outline'}`}
                onClick={() => setFilter(opt.value)}
                style={{ fontSize: 12, padding: '4px 10px' }}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <div style={{ flex: 1 }} />
          {unreadFiltered > 0 && (
            <button
              className="btn btn-outline btn-sm"
              onClick={handleMarkAll}
              disabled={acting === 'all'}
              style={{ whiteSpace: 'nowrap' }}
            >
              {acting === 'all' ? '处理中...' : `✓ 全部已读 (${unreadFiltered})`}
            </button>
          )}
        </div>
      </div>

      {/* Notification list */}
      {filtered.length === 0 ? (
        <div className="empty-state">
          <div style={{ fontSize: 40, marginBottom: 12 }}>🔔</div>
          <div>{filter ? '没有匹配的通知' : '暂无通知'}</div>
          <div style={{ marginTop: 8, color: '#9ca3af', fontSize: 13 }}>
            构建成功/失败、部署审批等事件会自动产生通知
          </div>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {filtered.map(note => {
            const cfg = TYPE_CONFIG[note.type] || TYPE_CONFIG.SYSTEM
            return (
              <div
                key={note.id}
                className="card"
                style={{
                  display: 'flex',
                  gap: 14,
                  padding: 14,
                  opacity: note.isRead ? 0.7 : 1,
                  borderLeft: `4px solid ${note.isRead ? 'transparent' : cfg.color}`,
                  transition: 'opacity 0.2s'
                }}
              >
                {/* Type icon */}
                <div style={{
                  width: 40, height: 40, borderRadius: 10,
                  background: cfg.bg,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 18, flexShrink: 0
                }}>
                  {cfg.icon}
                </div>

                {/* Content */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 4 }}>
                    <span style={{
                      fontSize: 12, fontWeight: 700, color: cfg.color,
                      padding: '1px 8px', borderRadius: 4,
                      background: cfg.bg
                    }}>
                      {cfg.label}
                    </span>
                    {!note.isRead && (
                      <span style={{
                        width: 8, height: 8, borderRadius: '50%',
                        background: cfg.color, flexShrink: 0
                      }} />
                    )}
                    <span style={{ fontSize: 12, color: '#6b7280', marginLeft: 'auto' }}>
                      {timeAgo(note.createdAt)}
                    </span>
                  </div>
                  <div style={{ fontWeight: 600, marginBottom: 2, color: '#f3f4f6' }}>
                    {note.title}
                  </div>
                  {note.message && (
                    <div style={{ fontSize: 13, color: '#9ca3af', lineHeight: 1.5 }}>
                      {note.message}
                    </div>
                  )}
                  <div style={{ fontSize: 11, color: '#6b7280', marginTop: 4 }}>
                    {note.createdAt ? new Date(note.createdAt).toLocaleString() : ''}
                  </div>
                </div>

                {/* Action */}
                {!note.isRead && (
                  <div style={{ flexShrink: 0, display: 'flex', alignItems: 'center' }}>
                    <button
                      className="btn btn-outline btn-sm"
                      onClick={() => handleMarkRead(note.id)}
                      disabled={acting === note.id}
                      style={{ fontSize: 12, whiteSpace: 'nowrap' }}
                    >
                      {acting === note.id ? '...' : '✓ 已读'}
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </>
  )
}
