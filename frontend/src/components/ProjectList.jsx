import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchProjects, createProject, updateProject, deleteProject, fetchPipelines, fetchEnvironments, triggerBuild, fetchBuilds, checkWorkspace, previewProjectCode, previewProjectFile } from '../api'

const LANGUAGES = ['Java', 'Node.js', 'Python', 'Go', 'Rust', 'Other']
const FRAMEWORKS = ['Spring Boot', 'Express', 'Django', 'Gin', 'Other']

const DEFAULT_FORM = {
  name: '', code: '', gitUrl: '', gitBranch: 'main',
  language: 'Java', framework: 'Spring Boot', buildCommand: 'mvn clean package -DskipTests',
  description: '',
}

export default function ProjectList() {
  const navigate = useNavigate()
  const { canManage } = useAuth()
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')

  // Detail modal
  const [detailProject, setDetailProject] = useState(null)
  const [detailPipelines, setDetailPipelines] = useState([])
  const [detailBuilds, setDetailBuilds] = useState([])
  const [detailEnvs, setDetailEnvs] = useState([])
  const [wsCheckResult, setWsCheckResult] = useState(null)
  const [wsChecking, setWsChecking] = useState(false)

  // Code preview
  const [previewTree, setPreviewTree] = useState(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState(null)
  const [previewFile, setPreviewFile] = useState(null) // { path, name, content, language }
  const [expandedDirs, setExpandedDirs] = useState(new Set())

  // Build trigger modal
  const [showBuildModal, setShowBuildModal] = useState(false)
  const [buildPendingPipeline, setBuildPendingPipeline] = useState(null)
  const [buildSkipDocker, setBuildSkipDocker] = useState(false)
  const [buildSkipK8s, setBuildSkipK8s] = useState(false)
  const [buildDbName, setBuildDbName] = useState('')
  const [toast, setToast] = useState(null) // { text, type: 'success'|'error'|'info' }

  // 数据库名净化（与后端 DatabaseProvisioningService.sanitizeDbName 一致）
  const sanitizeDbName = (raw) => {
    if (!raw || !raw.trim()) return ''
    let clean = raw.trim().replace(/[^a-zA-Z0-9_\-]+/g, '_')
    clean = clean.replace(/^-+/, '').replace(/^_+/, '')
    return clean || 'app'
  }
  // 实时预览有效数据库名
  const effectiveDbPreview = buildDbName
    ? sanitizeDbName(buildDbName)
    : (detailProject?.code ? `devops_${sanitizeDbName(detailProject.code)}` : 'devops_app')

  const load = useCallback(async () => {
    setLoading(true)
    try { setProjects(await fetchProjects()) } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const openNew = () => {
    setEditing(null)
    setForm(DEFAULT_FORM)
    setShowModal(true)
  }

  const openEdit = (p) => {
    setEditing(p)
    setForm({
      name: p.name || '', code: p.code || '', gitUrl: p.gitUrl || '', gitBranch: p.gitBranch || 'main',
      language: p.language || 'Java', framework: p.framework || 'Spring Boot',
      buildCommand: p.buildCommand || '', description: p.description || '',
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    try {
      if (editing) {
        await updateProject(editing.id, form)
      } else {
        await createProject(form)
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (p) => {
    if (!confirm(`确定要删除项目「${p.name}」吗？`)) return
    try {
      await deleteProject(p.id)
      load()
    } catch (e) { alert('删除失败: ' + e.message) }
  }

  const openDetail = async (p) => {
    setDetailProject(p)
    setWsCheckResult(null)
    try {
      const [pls, bs, es] = await Promise.all([
        fetchPipelines(p.id),
        fetchBuilds(p.id),
        fetchEnvironments(),
      ])
      setDetailPipelines(pls)
      setDetailBuilds(bs)
      setDetailEnvs(es)
    } catch (e) { console.error(e) }
  }

  const handleCheckWorkspace = async () => {
    if (!detailProject) return
    setWsChecking(true)
    setWsCheckResult(null)
    try {
      const res = await checkWorkspace(detailProject.id)
      setWsCheckResult(res)
    } catch (e) {
      setWsCheckResult({ error: '检查失败: ' + e.message, ok: false })
    } finally {
      setWsChecking(false)
    }
  }

  const handlePreviewCode = async () => {
    if (!detailProject) return
    setPreviewLoading(true)
    setPreviewError(null)
    setPreviewTree(null)
    setPreviewFile(null)
    try {
      const res = await previewProjectCode(detailProject.id)
      if (res.error) {
        setPreviewError(res.error)
      } else {
        setPreviewTree(res.tree)
        // 自动展开根目录
        setExpandedDirs(new Set(['']))
      }
    } catch (e) {
      setPreviewError('预览失败: ' + e.message)
    } finally {
      setPreviewLoading(false)
    }
  }

  const handlePreviewFile = async (filePath, fileName) => {
    if (!detailProject) return
    try {
      const res = await previewProjectFile(detailProject.id, filePath)
      if (res.error) {
        setPreviewFile({ path: filePath, name: fileName, content: '/* ' + res.error + ' */', language: 'plaintext' })
      } else {
        setPreviewFile({ path: res.path, name: res.name, content: res.content, language: res.language, size: res.size })
      }
    } catch (e) {
      setPreviewFile({ path: filePath, name: fileName, content: '// 加载失败: ' + e.message, language: 'plaintext' })
    }
  }

  const toggleDir = (dirPath) => {
    setExpandedDirs(prev => {
      const next = new Set(prev)
      if (next.has(dirPath)) next.delete(dirPath)
      else next.add(dirPath)
      return next
    })
  }

  // 渲染文件树节点
  const renderTreeNode = (node, depth = 0) => {
    const isDir = node.type === 'directory'
    const isExpanded = expandedDirs.has(node.path)
    const paddingLeft = 8 + depth * 18

    return (
      <div key={node.path || node.name}>
        <div
          className="tree-node"
          style={{ paddingLeft, cursor: isDir ? 'pointer' : 'pointer', display: 'flex', alignItems: 'center', gap: 4, padding: '3px 0', fontSize: 12, borderRadius: 4 }}
          onClick={() => {
            if (isDir) {
              toggleDir(node.path)
            } else if (node.type === 'file') {
              handlePreviewFile(node.path, node.name)
            }
          }}
          onMouseEnter={e => { e.currentTarget.style.background = '#f3f4f6' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
        >
          <span style={{ width: 16, textAlign: 'center', flexShrink: 0 }}>
            {isDir ? (isExpanded ? '📂' : '📁') : node.type === 'binary' ? '📦' : '📄'}
          </span>
          <span style={{
            color: isDir ? '#1f2937' : '#4b5563',
            fontWeight: isDir ? 600 : 400,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            flex: 1
          }}>
            {node.name}
          </span>
          {isDir && (
            <span style={{ color: '#9ca3af', fontSize: 10, marginLeft: 'auto', flexShrink: 0, transition: 'transform 0.15s', transform: isExpanded ? 'rotate(0deg)' : 'rotate(-90deg)' }}>
              ▼
            </span>
          )}
          {!isDir && node.size !== undefined && (
            <span style={{ color: '#9ca3af', fontSize: 10, marginLeft: 'auto', flexShrink: 0 }}>
              {node.size < 1024 ? node.size + ' B' : node.size < 1048576 ? (node.size / 1024).toFixed(1) + ' KB' : (node.size / 1048576).toFixed(1) + ' MB'}
            </span>
          )}
        </div>
        {isDir && isExpanded && node.children && (
          <div>{node.children.map(child => renderTreeNode(child, depth + 1))}</div>
        )}
      </div>
    )
  }

  const handleTriggerBuild = async (pipeline) => {
    if (!detailProject) return
    setBuildPendingPipeline(pipeline)
    setBuildSkipDocker(false)
    setBuildSkipK8s(false)
    // 默认数据库名: devops_<projectCode>
    const defaultDb = detailProject.code ? `devops_${detailProject.code.replace(/[^a-zA-Z0-9_-]/g, '_')}` : ''
    setBuildDbName(defaultDb)
    setShowBuildModal(true)
  }

  const handleConfirmBuild = async () => {
    if (!detailProject || !buildPendingPipeline) return
    setShowBuildModal(false)
    try {
      setToast({ text: '构建触发中...', type: 'info' })
      await triggerBuild(detailProject.id, buildPendingPipeline.id, null, null,
        buildSkipDocker, buildSkipK8s,
        buildDbName || null)
      setToast({ text: '构建已触发！可在"构建记录"中查看进度', type: 'info' })
      setTimeout(() => setToast(null), 3000)
    } catch (e) {
      setToast({ text: '触发失败: ' + e.message, type: 'error' })
      setTimeout(() => setToast(null), 4000)
    }
  }

  const filtered = projects.filter(p =>
    !search || p.name?.toLowerCase().includes(search.toLowerCase())
  )

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>📁 项目管理</h2>
        <div className="btn-group">
          <input
            placeholder="搜索项目..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          {canManage && <button className="btn btn-primary" onClick={openNew}>+ 新建项目</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>项目名称</th>
                <th>编码</th>
                <th>语言</th>
                <th>框架</th>
                <th>Git 分支</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无项目数据</td></tr>
              ) : (
                filtered.map(p => (
                  <tr key={p.id}>
                    <td style={{ color: '#9ca3af' }}>#{p.id}</td>
                    <td>
                      {p.gitUrl ? (
                        <a href={p.gitUrl} target="_blank" rel="noreferrer" style={{ color: '#6c63ff', fontWeight: 600, cursor: 'pointer' }}>
                          {p.name}
                        </a>
                      ) : p.name}
                    </td>
                    <td><code style={{ fontSize: 12, background: '#f3f4f6', padding: '2px 6px', borderRadius: 4 }}>{p.code}</code></td>
                    <td>{p.language}</td>
                    <td>{p.framework}</td>
                    <td>{p.gitBranch}</td>
                    <td style={{ color: '#9ca3af', fontSize: 12 }}>{p.createdAt ? new Date(p.createdAt).toLocaleDateString() : '-'}</td>
                    <td>
                      <div className="btn-group">
                        <button className="btn btn-outline btn-sm" onClick={() => openDetail(p)}>📋 详情</button>
                        {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(p)}>✏️</button>}
                        {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleDelete(p)}>🗑</button>}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑项目' : '新建项目'}</h3>
            <div className="form-group">
              <label>项目名称 *</label>
              <input value={form.name} onChange={e => setForm({...form, name: e.target.value})} placeholder="如: 用户管理系统" />
            </div>
            <div className="form-group">
              <label>项目编码 *</label>
              <input value={form.code} onChange={e => setForm({...form, code: e.target.value})} placeholder="如: user-service (用作工作目录名)" />
            </div>
            <div className="form-group">
              <label>Git 仓库地址</label>
              <input value={form.gitUrl} onChange={e => setForm({...form, gitUrl: e.target.value})} placeholder="https://github.com/user/repo.git" />
            </div>
            <div className="form-group">
              <label>Git 分支</label>
              <input value={form.gitBranch} onChange={e => setForm({...form, gitBranch: e.target.value})} />
            </div>
            <div className="detail-grid">
              <div className="form-group">
                <label>语言</label>
                <select value={form.language} onChange={e => setForm({...form, language: e.target.value})}>
                  {LANGUAGES.map(l => <option key={l} value={l}>{l}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label>框架</label>
                <select value={form.framework} onChange={e => setForm({...form, framework: e.target.value})}>
                  {FRAMEWORKS.map(f => <option key={f} value={f}>{f}</option>)}
                </select>
              </div>
            </div>
            <div className="form-group">
              <label>构建命令</label>
              <input value={form.buildCommand} onChange={e => setForm({...form, buildCommand: e.target.value})} />
            </div>
            <div className="form-group">
              <label>描述</label>
              <textarea value={form.description} onChange={e => setForm({...form, description: e.target.value})} rows={2} />
            </div>
            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      {detailProject && (
        <div className="modal-overlay" onClick={() => setDetailProject(null)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <h3>📁 {detailProject.name}</h3>

            <div className="detail-section">
              <h4>基本信息</h4>
              <div className="detail-grid">
                <div className="detail-item"><div className="label">项目编码</div><div className="value"><code>{detailProject.code}</code></div></div>
                <div className="detail-item"><div className="label">语言</div><div className="value">{detailProject.language} / {detailProject.buildTool}</div></div>
                <div className="detail-item"><div className="label">Git 地址</div><div className="value">
                  {detailProject.gitUrl ? <a href={detailProject.gitUrl} target="_blank" rel="noreferrer">{detailProject.gitUrl}</a> : '未配置'}
                </div></div>
                <div className="detail-item"><div className="label">分支</div><div className="value">{detailProject.gitBranch || 'main'}</div></div>
                <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">构建命令</div><div className="value"><code>{detailProject.buildCommand || '-'}</code></div></div>
                <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">描述</div><div className="value">{detailProject.description || '-'}</div></div>
              </div>

              {/* --- 工作目录检查 --- */}
              <div style={{ marginTop: 12, borderTop: '1px solid #e5e7eb', paddingTop: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <span style={{ fontWeight: 600, fontSize: 14 }}>📁 工作目录</span>
                  <button
                    className="btn btn-outline btn-sm"
                    onClick={handleCheckWorkspace}
                    disabled={wsChecking}
                    style={{ fontSize: 12 }}
                  >
                    {wsChecking ? '⏳ 检查中...' : '🔍 检查工作目录'}
                  </button>
                </div>

                {wsCheckResult && (
                  <div style={{
                    marginTop: 8, padding: '10px 14px', borderRadius: 8, fontSize: 12,
                    background: wsCheckResult.error ? '#fef2f2' : wsCheckResult.ok ? '#f0fdf4' : '#fffbeb',
                    border: `1px solid ${wsCheckResult.error ? '#fecaca' : wsCheckResult.ok ? '#bbf7d0' : '#fcd34d'}`,
                    color: '#333', lineHeight: 1.7, maxHeight: 260, overflow: 'auto'
                  }}>
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>
                      {wsCheckResult.workspaceExists ? '✅ 目录已就绪' : '⚠️ 目录尚未创建'}
                    </div>
                    <div>路径: <code style={{ fontSize: 11 }}>{wsCheckResult.workspacePath}</code></div>
                    <div>Git 仓库: {wsCheckResult.isGitRepo ? '✅ 是' : '❌ 否（首次构建时会自动克隆）'}</div>

                    {wsCheckResult.hasPomXml !== undefined && (
                      <div>pom.xml: {wsCheckResult.hasPomXml
                        ? `✅ ${wsCheckResult.pomXmlPath || ''}` : '❌ 未找到'}</div>
                    )}
                    {wsCheckResult.hasPackageJson !== undefined && (
                      <div>package.json: {wsCheckResult.hasPackageJson
                        ? `✅ ${wsCheckResult.packageJsonPath || ''}` : '❌ 未找到'}</div>
                    )}
                    {wsCheckResult.hasMvnw !== undefined && (
                      <div>Maven Wrapper: {wsCheckResult.hasMvnw ? '✅' : '❌'}</div>
                    )}

                    {wsCheckResult.rootFiles && wsCheckResult.rootFiles.length > 0 && (
                      <div style={{ marginTop: 4 }}>
                        <div style={{ fontWeight: 600 }}>根目录文件:</div>
                        <div style={{ wordBreak: 'break-all', color: '#666' }}>
                          {wsCheckResult.rootFiles.join(', ')}
                        </div>
                      </div>
                    )}

                    {wsCheckResult.error && !wsCheckResult.workspaceExists && (
                      <div style={{ color: '#991b1b', marginTop: 4 }}>
                        ⚠️ {wsCheckResult.error}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

              {/* --- 代码预览 --- */}
              <div style={{ marginTop: 12, borderTop: '1px solid #e5e7eb', paddingTop: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <span style={{ fontWeight: 600, fontSize: 14 }}>📂 代码预览</span>
                  <button
                    className="btn btn-outline btn-sm"
                    onClick={handlePreviewCode}
                    disabled={previewLoading}
                    style={{ fontSize: 12 }}
                  >
                    {previewLoading ? '⏳ 加载中...' : '👁 预览代码'}
                  </button>
                </div>

                {previewError && (
                  <div style={{ marginTop: 8, padding: '10px 14px', borderRadius: 8, background: '#fef2f2', border: '1px solid #fecaca', color: '#991b1b', fontSize: 12 }}>
                    ⚠️ {previewError}
                  </div>
                )}

                {previewTree && (
                  <div style={{ marginTop: 8, display: 'flex', gap: 0, border: '1px solid #e5e7eb', borderRadius: 8, overflow: 'hidden', maxHeight: 420 }}>
                    {/* 文件树 */}
                    <div style={{ width: 240, minWidth: 180, borderRight: '1px solid #e5e7eb', overflow: 'auto', background: '#fafbfc', padding: '4px 0' }}>
                      {renderTreeNode(previewTree)}
                    </div>
                    {/* 文件内容 */}
                    <div style={{ flex: 1, overflow: 'auto', background: '#1e1e1e' }}>
                      {previewFile ? (
                        <div style={{ fontSize: 12 }}>
                          <div style={{ padding: '6px 12px', background: '#2d2d2d', color: '#ccc', fontSize: 11, borderBottom: '1px solid #444', display: 'flex', justifyContent: 'space-between', position: 'sticky', top: 0 }}>
                            <span>{previewFile.name}</span>
                            {previewFile.size !== undefined && (
                              <span style={{ color: '#999' }}>
                                {previewFile.size < 1024 ? previewFile.size + ' B' : (previewFile.size / 1024).toFixed(1) + ' KB'}
                              </span>
                            )}
                          </div>
                          <pre style={{ margin: 0, padding: '10px 14px', color: '#d4d4d4', fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', Consolas, monospace", lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', tabSize: 4 }}>{previewFile.content}</pre>
                        </div>
                      ) : (
                        <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#666', fontSize: 13 }}>
                          ← 点击左侧文件查看内容
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>

            <div className="detail-section">
              <h4>流水线 ({detailPipelines.length})</h4>
              {detailPipelines.length === 0 ? (
                <div style={{ padding: '14px 16px', background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: 8, fontSize: 13 }}>
                  <div style={{ color: '#92400e', fontWeight: 600, marginBottom: 6 }}>
                    ⚠️ 暂无流水线
                  </div>
                  <div style={{ color: '#a16207', marginBottom: 10 }}>
                    构建和部署必须通过流水线触发，请先创建至少一条流水线。
                  </div>
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => { setDetailProject(null); navigate('/pipelines') }}
                    style={{ fontSize: 12 }}
                  >
                    🔧 创建流水线
                  </button>
                </div>
              ) : (
                <div className="table-container">
                  <table>
                    <thead><tr><th>名称</th><th>触发方式</th><th>操作</th></tr></thead>
                    <tbody>
                      {detailPipelines.map(pl => (
                        <tr key={pl.id}>
                          <td>{pl.name}</td>
                          <td>{pl.trigger || 'MANUAL'}</td>
                          <td>
                            <button className="btn btn-success btn-sm" onClick={() => handleTriggerBuild(pl)}>▶ 触发构建</button>
                            {' '}
                            <button className="btn btn-outline btn-sm" onClick={() => { setDetailProject(null); navigate(`/projects/${detailProject.id}/pipelines`) }}>🔧</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="detail-section">
              <h4>最近构建 ({detailBuilds.length})</h4>
              {detailBuilds.length === 0 ? <p style={{color:'#9ca3af',fontSize:13}}>暂无构建记录</p> : (
                <div className="table-container">
                  <table>
                    <thead><tr><th>编号</th><th>状态</th><th>时间</th></tr></thead>
                    <tbody>
                      {detailBuilds.slice(0, 5).map(b => (
                        <tr key={b.id}>
                          <td>#{b.buildNumber}</td>
                          <td><span className={`badge badge-${b.status?.toLowerCase()}`}>{b.status}</span></td>
                          <td>{b.startTime ? new Date(b.startTime).toLocaleString() : '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="detail-section">
              <h4>环境 ({detailEnvs.length})</h4>
              {detailEnvs.length === 0 ? <p style={{color:'#9ca3af',fontSize:13}}>暂无环境</p> : (
                detailEnvs.map(env => (
                  <span key={env.id} className={`badge badge-${env.protectedEnv ? 'failed' : 'developer'}`} style={{ marginRight: 6 }}>
                    {env.name}
                  </span>
                ))
              )}
            </div>

            <div style={{ textAlign: 'right', marginTop: 12 }}>
              <button className="btn btn-outline" onClick={() => setDetailProject(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}

      {/* ===== 构建触发确认弹窗 ===== */}
      {showBuildModal && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', zIndex: 9999,
          display: 'flex', alignItems: 'center', justifyContent: 'center'
        }} onClick={() => setShowBuildModal(false)}>
          <div style={{
            background: '#fff', borderRadius: 12, padding: '24px 28px', width: 420, maxWidth: '92vw',
            boxShadow: '0 8px 30px rgba(0,0,0,0.18)'
          }} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 4px', fontSize: 18, color: '#1f2937' }}>⚡ 触发构建</h3>
            <p style={{ margin: '0 0 16px', fontSize: 13, color: '#6b7280' }}>
              流水线: <strong>{buildPendingPipeline?.name}</strong>
            </p>

            <div style={{ background: '#f9fafb', borderRadius: 8, padding: '14px 16px' }}>
              <label style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0',
                cursor: 'pointer', borderBottom: '1px solid #e5e7eb'
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipDocker}
                  onChange={e => setBuildSkipDocker(e.target.checked)}
                  style={{ width: 18, height: 18, cursor: 'pointer', accentColor: '#6366f1' }}
                />
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#374151' }}>🐳 跳过 Docker</div>
                  <div style={{ fontSize: 11, color: '#9ca3af' }}>不构建和推送 Docker 镜像</div>
                </div>
              </label>
              <label style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0',
                cursor: 'pointer'
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipK8s}
                  onChange={e => setBuildSkipK8s(e.target.checked)}
                  style={{ width: 18, height: 18, cursor: 'pointer', accentColor: '#6366f1' }}
                />
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#374151' }}>☸️ 跳过 Kubectl</div>
                  <div style={{ fontSize: 11, color: '#9ca3af' }}>不执行 Kubernetes 部署</div>
                </div>
              </label>
            </div>

            {/* 数据库隔离配置 */}
            <div style={{ marginTop: 12, background: '#f0f9ff', borderRadius: 8, padding: '10px 12px' }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#0369a1', marginBottom: 8 }}>
                🗄 数据库隔离配置（部署时自动创建独立库）
              </div>
              <label style={{ display: 'block', marginBottom: 8 }}>
                <span style={{ fontSize: 11, color: '#6b7280' }}>数据库名</span>
                <input
                  type="text"
                  value={buildDbName}
                  onChange={e => setBuildDbName(e.target.value)}
                  placeholder={`devops_${detailProject?.code || 'app'}`}
                  style={{
                    width: '100%', padding: '6px 10px', marginTop: 2,
                    border: '1px solid #d1d5db', borderRadius: 6, fontSize: 13,
                    boxSizing: 'border-box'
                  }}
                />
                <span style={{ fontSize: 11, color: '#047857' }}>
                  📌 将创建数据库: <strong>{effectiveDbPreview}</strong>
                </span>
              </label>
            </div>

            <p style={{
              margin: '12px 0 0', fontSize: 11, color: '#9ca3af', textAlign: 'center'
            }}>
              将执行: 编译 + 测试
              {!buildSkipDocker ? ' + Docker' : ''}
              {!buildSkipK8s ? ' + K8s 部署' : ''}
            </p>

            <div style={{ display: 'flex', gap: 10, marginTop: 16, justifyContent: 'flex-end' }}>
              <button
                className="btn btn-outline"
                onClick={() => setShowBuildModal(false)}
                style={{ fontSize: 13 }}
              >
                取消
              </button>
              <button
                className="btn btn-success"
                onClick={handleConfirmBuild}
                style={{ fontSize: 13 }}
              >
                ▶ 确认构建
              </button>
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
