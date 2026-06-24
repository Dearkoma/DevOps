import React, { useState, useEffect } from 'react'
import { useAuth } from '../context/AuthContext'
import { fetchArtifacts, deleteArtifact, fetchBuilds } from '../api'

const TYPE_LABELS = {
  JAR: 'JAR 包',
  WAR: 'WAR 包',
  DOCKER_IMAGE: 'Docker 镜像',
  NPM_PACKAGE: 'NPM 包',
  OTHER: '其他'
}

const TYPE_ICONS = {
  JAR: '📦',
  WAR: '📦',
  DOCKER_IMAGE: '🐳',
  NPM_PACKAGE: '📦',
  OTHER: '📄'
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

export default function ArtifactList() {
  const { canManage } = useAuth()
  const [artifacts, setArtifacts] = useState([])
  const [builds, setBuilds] = useState([])
  const [selectedBuildId, setSelectedBuildId] = useState('')
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)

  // Load recent builds for the dropdown
  useEffect(() => {
    fetchBuilds()
      .then(b => setBuilds(b || []))
      .catch(console.error)
  }, [])

  // Load artifacts when a build is selected
  useEffect(() => {
    if (!selectedBuildId) {
      setArtifacts([])
      return
    }
    setLoading(true)
    fetchArtifacts(selectedBuildId)
      .then(a => setArtifacts(a || []))
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [selectedBuildId])

  const handleDelete = async (id) => {
    if (!window.confirm('确定删除该制品？此操作不可撤销。')) return
    try {
      setBuilding(true)
      await deleteArtifact(id)
      setArtifacts(prev => prev.filter(a => a.id !== id))
    } catch (e) {
      alert('删除失败: ' + e.message)
    } finally {
      setBuilding(false)
    }
  }

  const handleDownload = (artifact) => {
    // Try direct download URL first, fallback to file path
    const url = artifact.downloadUrl || `/api/artifacts/${artifact.id}/download`
    window.open(url, '_blank')
  }

  return (
    <>
      <div className="page-header">
        <h2>🗄️ 制品管理</h2>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <label style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>选择构建：</label>
          <select
            value={selectedBuildId}
            onChange={e => setSelectedBuildId(e.target.value)}
            style={{ flex: 1, padding: '8px 12px', borderRadius: 6, border: '1px solid #374151', background: '#1f2937', color: '#f3f4f6', fontSize: 14 }}
          >
            <option value="">-- 请选择一次构建记录 --</option>
            {builds.map(b => (
              <option key={b.id} value={b.id}>
                #{b.buildNumber || b.id} — {b.projectName || `项目#${b.projectId}`} — {b.status}
              </option>
            ))}
          </select>
          <button
            className="btn btn-outline btn-sm"
            onClick={() => { setSelectedBuildId(''); setArtifacts([]) }}
          >
            清除
          </button>
        </div>
      </div>

      {!selectedBuildId ? (
        <div className="empty-state">
          <div style={{ fontSize: 40, marginBottom: 12 }}>🗄️</div>
          <div>请先选择一次构建记录，查看其产生的制品</div>
          <div style={{ marginTop: 8, color: '#9ca3af', fontSize: 13 }}>
            制品在每次构建成功后自动从 target/ 目录收集
          </div>
        </div>
      ) : loading ? (
        <div className="empty-state"><div className="spinner" /></div>
      ) : (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>制品类型</th>
                  <th>文件名</th>
                  <th>版本</th>
                  <th>大小</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {artifacts.length === 0 ? (
                  <tr>
                    <td colSpan={6} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>
                      该构建未产生制品（构建可能尚未完成或未生成 JAR/WAR 文件）
                    </td>
                  </tr>
                ) : (
                  artifacts.map(art => (
                    <tr key={art.id}>
                      <td>
                        <span style={{ marginRight: 6 }}>{TYPE_ICONS[art.fileType] || '📄'}</span>
                        <span className={`badge badge-${art.fileType === 'DOCKER_IMAGE' ? 'info' : 'success'}`}>
                          {TYPE_LABELS[art.fileType] || art.fileType || 'OTHER'}
                        </span>
                      </td>
                      <td style={{ fontWeight: 600, fontFamily: 'monospace', fontSize: 13 }}>
                        {art.fileName}
                      </td>
                      <td style={{ fontFamily: 'monospace', fontSize: 13, color: '#60a5fa' }}>
                        {art.version || '-'}
                      </td>
                      <td style={{ color: '#9ca3af' }}>
                        {formatSize(art.fileSize)}
                      </td>
                      <td style={{ fontSize: 12, color: '#9ca3af' }}>
                        {art.createdAt ? new Date(art.createdAt).toLocaleString() : '-'}
                      </td>
                      <td>
                        <div style={{ display: 'flex', gap: 8 }}>
                          <button
                            className="btn btn-outline btn-sm"
                            onClick={() => handleDownload(art)}
                            disabled={building}
                          >
                            ⬇ 下载
                          </button>
                          {canManage && <button
                            className="btn btn-outline btn-sm"
                            onClick={() => handleDelete(art.id)}
                            disabled={building || !canManage}
                            style={{ color: '#ef4444', borderColor: '#ef4444' }}
                          >
                            🗑 删除
                          </button>}
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
    </>
  )
}
