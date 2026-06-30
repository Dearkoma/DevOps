import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { fetchProjects, fetchProjectFiles, fetchProjectFileContent, saveProjectFile } from '../api'

const CONTEXTS = [
  { value: 'workspace', label: '📁 工作空间', desc: '构建前 · 只读查看', writable: false },
  { value: 'docker', label: '🐳 Docker', desc: '源码编辑 · 需重建部署', writable: true },
  { value: 'k8s', label: '☸️ K8s', desc: '源码编辑 · 需重建部署', writable: true },
]

const ICONS = {
  text: '📄', archive: '📦', image: '🖼️', log: '📋', binary: '📎',
}

function formatSize(bytes) {
  if (!bytes || bytes === 0) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

export default function ProjectFiles() {
  const { projectId } = useParams()
  const navigate = useNavigate()
  const [project, setProject] = useState(null)
  const [context, setContext] = useState('workspace')

  const [files, setFiles] = useState([])
  const [currentPath, setCurrentPath] = useState('')
  const [pathStack, setPathStack] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // 文件内容查看/编辑
  const [viewingFile, setViewingFile] = useState(null)
  const [fileContent, setFileContent] = useState(null)
  const [contentLoading, setContentLoading] = useState(false)
  const [contentError, setContentError] = useState(null)

  // 编辑模式
  const [editing, setEditing] = useState(false)
  const [editText, setEditText] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState(null)

  const writable = CONTEXTS.find(c => c.value === context)?.writable || false

  const loadProject = useCallback(async () => {
    try {
      const all = await fetchProjects()
      const p = all.find(p => p.id === Number(projectId))
      if (p) {
        setProject(p)
      } else {
        setError('项目不存在')
      }
    } catch (e) {
      setError('加载项目信息失败: ' + e.message)
    }
  }, [projectId])

  const loadFiles = useCallback(async (path, ctx) => {
    setLoading(true)
    setError(null)
    setViewingFile(null)
    setFileContent(null)
    setEditing(false)
    setEditText('')
    setSaveMessage(null)
    try {
      const data = await fetchProjectFiles(projectId, path, ctx)
      setFiles(data)
      setCurrentPath(path)
    } catch (e) {
      setError('加载文件列表失败: ' + e.message)
      setFiles([])
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    loadProject()
  }, [loadProject])

  useEffect(() => {
    if (project) {
      loadFiles('', context)
    }
  }, [project, context, loadFiles])

  const switchContext = (newContext) => {
    setContext(newContext)
    setPathStack([])
    setCurrentPath('')
    setViewingFile(null)
    setFileContent(null)
    setEditing(false)
    setSaveMessage(null)
  }

  const navigateTo = (dirName) => {
    const newPath = currentPath ? currentPath + '/' + dirName : dirName
    setPathStack([...pathStack, currentPath])
    loadFiles(newPath, context)
  }

  const goBack = () => {
    if (pathStack.length === 0) return
    const prevStack = [...pathStack]
    const prev = prevStack.pop()
    setPathStack(prevStack)
    loadFiles(prev || '', context)
  }

  const goToRoot = () => {
    setPathStack([])
    loadFiles('', context)
  }

  const openFile = async (file) => {
    setViewingFile(file)
    setContentLoading(true)
    setContentError(null)
    setFileContent(null)
    setEditing(false)
    setEditText('')
    setSaveMessage(null)
    try {
      const filePath = currentPath ? currentPath + '/' + file.name : file.name
      const data = await fetchProjectFileContent(projectId, filePath, context)
      setFileContent(data)
    } catch (e) {
      setContentError('读取文件失败: ' + e.message)
    } finally {
      setContentLoading(false)
    }
  }

  const closeViewer = () => {
    setViewingFile(null)
    setFileContent(null)
    setContentError(null)
    setEditing(false)
    setEditText('')
    setSaveMessage(null)
  }

  const startEdit = () => {
    setEditText(fileContent?.content || '')
    setEditing(true)
    setSaveMessage(null)
  }

  const cancelEdit = () => {
    setEditing(false)
    setEditText('')
    setSaveMessage(null)
  }

  const handleSave = async () => {
    setSaving(true)
    setSaveMessage(null)
    try {
      const filePath = currentPath ? currentPath + '/' + viewingFile.name : viewingFile.name
      await saveProjectFile(projectId, filePath, editText, context)
      setSaveMessage({ type: 'success', text: '✅ 文件已保存' })
      setEditing(false)
      // 重新读取以刷新内容
      const updated = await fetchProjectFileContent(projectId, filePath, context)
      setFileContent(updated)
    } catch (e) {
      setSaveMessage({ type: 'error', text: '❌ 保存失败: ' + e.message })
    } finally {
      setSaving(false)
    }
  }

  const breadcrumbParts = currentPath ? currentPath.split('/').filter(Boolean) : []

  if (!project && !error) {
    return <div className="empty-state"><div className="spinner" /></div>
  }

  if (error && !project) {
    return (
      <div className="empty-state">
        <div style={{ color: '#ef4444', fontSize: 16, marginBottom: 12 }}>❌ {error}</div>
        <button className="btn btn-outline" onClick={() => navigate('/projects')}>返回项目列表</button>
      </div>
    )
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* 页面头 */}
      <div className="page-header">
        <div>
          <h2 style={{ display: 'flex', alignItems: 'center', gap: 8, margin: 0 }}>
            📁 项目文件
            <span style={{ fontSize: 14, fontWeight: 400, color: '#9ca3af' }}>
              {project?.name}
            </span>
          </h2>
        </div>
        <div className="btn-group">
          <button className="btn btn-outline btn-sm" onClick={() => navigate('/projects')}>
            ← 返回项目列表
          </button>
        </div>
      </div>

      {/* 环境选择器 */}
      <div style={{
        display: 'flex', gap: 8, marginBottom: 12, padding: '0 0 12px 0',
        borderBottom: '1px solid #e5e7eb'
      }}>
        {CONTEXTS.map(c => (
          <button
            key={c.value}
            onClick={() => switchContext(c.value)}
            className={context === c.value ? 'btn btn-primary btn-sm' : 'btn btn-outline btn-sm'}
            style={{
              fontSize: 12,
              opacity: context === c.value ? 1 : 0.7,
            }}
            title={c.desc}
          >
            {c.label}
          </button>
        ))}
        {!writable && (
          <span style={{
            fontSize: 11, color: '#9ca3af', display: 'flex', alignItems: 'center',
            background: '#f3f4f6', padding: '2px 10px', borderRadius: 4
          }}>
            🔒 只读模式
          </span>
        )}
        {writable && (
          <span style={{
            fontSize: 11, color: '#059669', display: 'flex', alignItems: 'center',
            background: '#ecfdf5', padding: '2px 10px', borderRadius: 4
          }}>
            ✏️ 可编辑
          </span>
        )}
      </div>

      {/* 错误提示 */}
      {error && (
        <div style={{
          padding: '10px 14px', margin: '0 0 12px 0', borderRadius: 8,
          background: '#fef2f2', border: '1px solid #fecaca', color: '#991b1b', fontSize: 13
        }}>
          {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 16, flex: 1, minHeight: 0 }}>
        {/* 左侧：文件树 */}
        <div className="card" style={{ flex: '0 0 420px', display: 'flex', flexDirection: 'column', padding: 0 }}>
          {/* 面包屑导航 */}
          <div style={{
            padding: '10px 14px', borderBottom: '1px solid #e5e7eb', fontSize: 13,
            display: 'flex', alignItems: 'center', gap: 4, background: '#fafafa',
            flexWrap: 'wrap', minHeight: 40
          }}>
            <span
              onClick={goToRoot}
              style={{ color: currentPath ? '#6c63ff' : '#333', fontWeight: currentPath ? 400 : 600, cursor: 'pointer' }}
            >
              🏠 /app
            </span>
            {breadcrumbParts.map((part, i) => (
              <span key={i} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ color: '#ccc' }}>/</span>
                <span
                  onClick={() => {
                    const targetPath = breadcrumbParts.slice(0, i + 1).join('/')
                    setPathStack(pathStack.slice(0, i))
                    loadFiles(targetPath, context)
                  }}
                  style={{
                    color: i === breadcrumbParts.length - 1 ? '#333' : '#6c63ff',
                    fontWeight: i === breadcrumbParts.length - 1 ? 600 : 400,
                    cursor: i === breadcrumbParts.length - 1 ? 'default' : 'pointer'
                  }}
                >
                  {part}
                </span>
              </span>
            ))}
            {pathStack.length > 0 && (
              <span onClick={goBack} style={{ marginLeft: 'auto', cursor: 'pointer', fontSize: 18, color: '#6c63ff' }} title="返回上级目录">
                ↩️
              </span>
            )}
          </div>

          {/* 文件列表 */}
          <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
            {loading ? (
              <div style={{ padding: 32, textAlign: 'center' }}><div className="spinner" /></div>
            ) : files.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>
                {context === 'workspace'
                  ? '工作目录尚未创建，请先触发一次构建'
                  : '工作目录尚未创建，请先在「工作空间」标签触发构建拉取代码'}
              </div>
            ) : (
              <div>
                {files.map((file, i) => (
                  <div
                    key={i}
                    onClick={() => file.isDirectory ? navigateTo(file.name) : openFile(file)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px',
                      cursor: 'pointer', fontSize: 13, borderBottom: '1px solid #f3f4f6',
                      transition: 'background 0.15s',
                      background: viewingFile?.name === file.name ? '#ede9fe' : 'transparent',
                    }}
                    onMouseEnter={e => e.currentTarget.style.background = viewingFile?.name === file.name ? '#ede9fe' : '#f9fafb'}
                    onMouseLeave={e => e.currentTarget.style.background = viewingFile?.name === file.name ? '#ede9fe' : 'transparent'}
                  >
                    <span style={{ fontSize: 18 }}>
                      {file.isDirectory ? '📁' : (ICONS[file.type] || '📎')}
                    </span>
                    <span style={{
                      flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      fontWeight: file.isDirectory ? 600 : 400,
                      color: file.isDirectory ? '#6c63ff' : '#333'
                    }}>
                      {file.name}
                    </span>
                    <span style={{ color: '#9ca3af', fontSize: 11, minWidth: 60, textAlign: 'right' }}>
                      {file.isDirectory ? '' : formatSize(file.size)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 右侧：文件内容预览 / 编辑 */}
        <div className="card" style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: 0, minWidth: 0 }}>
          {!viewingFile ? (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#9ca3af', fontSize: 14 }}>
              👈 点击左侧文件名查看内容
            </div>
          ) : contentLoading ? (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <div className="spinner" />
            </div>
          ) : contentError ? (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <div style={{ color: '#ef4444', fontSize: 14 }}>❌ {contentError}</div>
              <button className="btn btn-outline btn-sm" onClick={closeViewer}>关闭</button>
            </div>
          ) : fileContent ? (
            <>
              {/* 文件信息栏 */}
              <div style={{
                padding: '8px 14px', borderBottom: '1px solid #e5e7eb', fontSize: 12,
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                background: '#fafafa', flexWrap: 'wrap', gap: 8
              }}>
                <span>
                  <strong>{viewingFile.name}</strong>
                  <span style={{ color: '#9ca3af', marginLeft: 8 }}>
                    {formatSize(fileContent.size)}
                    {fileContent.truncated && ` (仅显示前 ${formatSize(500 * 1024)})`}
                  </span>
                </span>
                <div className="btn-group" style={{ gap: 4 }}>
                  {writable && !editing && (
                    <button className="btn btn-primary btn-sm" onClick={startEdit} style={{ fontSize: 11 }}>
                      ✏️ 编辑
                    </button>
                  )}
                  <button className="btn btn-outline btn-sm" onClick={closeViewer} style={{ fontSize: 11 }}>
                    ✕ 关闭
                  </button>
                </div>
              </div>

              {/* 保存状态消息 */}
              {saveMessage && (
                <div style={{
                  padding: '6px 14px', fontSize: 12,
                  background: saveMessage.type === 'success' ? '#f0fdf4' : '#fef2f2',
                  color: saveMessage.type === 'success' ? '#166534' : '#991b1b',
                  borderBottom: `1px solid ${saveMessage.type === 'success' ? '#bbf7d0' : '#fecaca'}`
                }}>
                  {saveMessage.text}
                </div>
              )}

              {/* 内容区域 */}
              {editing ? (
                <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
                  <textarea
                    value={editText}
                    onChange={e => setEditText(e.target.value)}
                    style={{
                      flex: 1, margin: 0, padding: '12px 14px',
                      fontSize: 12, lineHeight: 1.6,
                      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace",
                      background: '#1e1e2e', color: '#cdd6f4',
                      border: 'none', resize: 'none', outline: 'none',
                      whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                      tabSize: 2,
                    }}
                    spellCheck={false}
                  />
                  <div style={{
                    padding: '8px 14px', borderTop: '1px solid #e5e7eb',
                    display: 'flex', justifyContent: 'flex-end', gap: 8,
                    background: '#fafafa'
                  }}>
                    <button className="btn btn-outline btn-sm" onClick={cancelEdit} disabled={saving}>
                      取消
                    </button>
                    <button className="btn btn-primary btn-sm" onClick={handleSave} disabled={saving}>
                      {saving ? '⏳ 保存中...' : '💾 保存'}
                    </button>
                  </div>
                </div>
              ) : (
                <pre style={{
                  flex: 1, margin: 0, padding: '12px 14px', overflow: 'auto',
                  fontSize: 12, lineHeight: 1.6, fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace",
                  background: '#1e1e2e', color: '#cdd6f4', whiteSpace: 'pre-wrap', wordBreak: 'break-all'
                }}>
                  {fileContent.content}
                </pre>
              )}
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}
