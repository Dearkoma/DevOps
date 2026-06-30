const BASE = '/api'

// 获取存储的 token
function getToken() {
  return localStorage.getItem('devops_token')
}

async function request(url, options = {}) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json', ...options.headers }

  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  let res
  try {
    res = await fetch(BASE + url, { ...options, headers })
  } catch (err) {
    // 网络错误（ECONNREFUSED 等）
    if (err.message.includes('Failed to fetch') || err.name === 'TypeError') {
      throw new Error('无法连接后端服务，请确认 Spring Boot 应用已在端口 8080 启动')
    }
    throw err
  }

  if (!res.ok) {
    const text = await res.text()
    let message
    try {
      const parsed = JSON.parse(text)
      message = parsed.error || parsed.message
    } catch {
      message = text
    }

    // 401 → 清除 token 并跳转登录
    if (res.status === 401) {
      localStorage.removeItem('devops_token')
      localStorage.removeItem('devops_user')
      // 非登录接口才跳转
      if (!url.startsWith('/auth/')) {
        window.location.href = '/login'
      }
    }

    const err = new Error(message || `HTTP ${res.status}`)
    err.status = res.status
    throw err
  }

  const ct = res.headers.get('content-type')
  if (ct && ct.includes('application/json')) return res.json()
  return res.text()
}

// ==================== Auth ====================
export const loginApi = (credentials) =>
  request('/auth/login', { method: 'POST', body: JSON.stringify(credentials) })

export const registerApi = (data) =>
  request('/auth/register', { method: 'POST', body: JSON.stringify(data) })

export const fetchMe = () => request('/auth/me')

// ==================== Dashboard ====================
export const fetchDashboardStats = () => request('/dashboard/stats')
export const fetchDashboardTrend = () => request('/dashboard/trends')
export const fetchRecentBuilds = (limit = 10) => request(`/dashboard/recent-builds?limit=${limit}`)

// ==================== Projects ====================
export const fetchProjects = () => request('/projects')
export const fetchProject = (id) => request(`/projects/${id}`)
export const createProject = (data) => request('/projects', { method: 'POST', body: JSON.stringify(data) })
export const updateProject = (id, data) => request(`/projects/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteProject = (id) => request(`/projects/${id}`, { method: 'DELETE' })
export const previewProjectCode = (id) => request(`/projects/${id}/preview`)
export const previewProjectFile = (id, filePath) => request(`/projects/${id}/preview/file?path=${encodeURIComponent(filePath)}`)

// ==================== Pipelines ====================
export const fetchPipelines = (projectId) => {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return request(`/pipelines${qs}`)
}
export const createPipeline = (data) => request('/pipelines', { method: 'POST', body: JSON.stringify(data) })
export const updatePipeline = (id, data) => request(`/pipelines/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deletePipeline = (id) => request(`/pipelines/${id}`, { method: 'DELETE' })

// ==================== Builds ====================
export const fetchBuilds = (projectId, status) => {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (status) params.set('status', status)
  const qs = params.toString()
  return request(`/builds${qs ? '?' + qs : ''}`)
}
export const fetchBuild = (id) => request(`/builds/${id}`)
export const fetchBuildLog = (id) => request(`/builds/${id}/log`)
export const triggerBuild = (projectId, pipelineId, buildParams = null, branch = null, skipDocker = false, skipK8s = false) =>
  request(`/builds/trigger?projectId=${projectId}&pipelineId=${pipelineId}`, {
    method: 'POST',
    body: JSON.stringify({ buildParams, branch, skipDocker, skipK8s })
  })
export const cancelBuild = (id) => request(`/builds/${id}/cancel`, { method: 'DELETE' })
export const deleteBuild = (id) => request(`/builds/${id}`, { method: 'DELETE' })
export const checkWorkspace = (projectId) => request(`/builds/workspace-check?projectId=${projectId}`)

// ==================== Environments ====================
export const fetchEnvironments = () => request('/environments')
export const createEnvironment = (data) => request('/environments', { method: 'POST', body: JSON.stringify(data) })
export const updateEnvironment = (id, data) => request(`/environments/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteEnvironment = (id) => request(`/environments/${id}`, { method: 'DELETE' })

// ==================== Users ====================
export const fetchUsers = () => request('/users')
export const createUser = (data) => request('/users', { method: 'POST', body: JSON.stringify(data) })
export const updateUser = (id, data) => request(`/users/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteUser = (id) => request(`/users/${id}`, { method: 'DELETE' })

// ==================== Webhook ====================
export const getWebhookUrl = (projectId) => `${window.location.origin}/api/webhook/${projectId}`

// ==================== Deployments ====================
export const requestDeploy = (data) => request('/deployments/request', { method: 'POST', body: JSON.stringify(data) })
export const approveDeploy = (id, approvedBy = 'admin') =>
  request(`/deployments/approve/${id}`, { method: 'POST', body: JSON.stringify({ approvedBy }) })
export const rejectDeploy = (id, reason, approvedBy = 'admin') =>
  request(`/deployments/reject/${id}`, { method: 'POST', body: JSON.stringify({ approvedBy, reason }) })
export const fetchDeploymentsPending = () => request('/deployments/pending')
export const fetchDeploymentRequests = (projectId) =>
  request(`/deployments/requests${projectId ? '?projectId=' + projectId : ''}`)
export const fetchDeploymentHistory = (projectId, environmentId) => {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (environmentId) params.set('environmentId', environmentId)
  return request(`/deployments/history?${params}`)
}
export const fetchRollbackCandidates = (projectId, environmentId) =>
  request(`/deployments/rollback-candidates?projectId=${projectId}&environmentId=${environmentId}`)
export const rollbackDeploy = (historyId, triggeredBy = 'admin') =>
  request(`/deployments/rollback/${historyId}`, { method: 'POST', body: JSON.stringify({ triggeredBy }) })

// ==================== Notifications ====================
export const fetchNotifications = () => request('/notifications')
export const fetchUnreadCount = () => request('/notifications/unread-count')
export const markNotifRead = (id) => request(`/notifications/${id}/read`, { method: 'PUT' })
export const markAllNotifRead = () => request('/notifications/read-all', { method: 'PUT' })

// ==================== Artifacts ====================
export const fetchArtifacts = (buildId) => request(`/artifacts?buildId=${buildId}`)
export const deleteArtifact = (id) => request(`/artifacts/${id}`, { method: 'DELETE' })

// ==================== Instances ====================
export const fetchInstances = () => request('/instances')
export const fetchInstanceStats = () => request('/instances/stats')
export const fetchProjectInstances = (projectId) => request(`/instances/project/${projectId}`)
export const updateInstanceHealth = (id, data) => request(`/instances/${id}/health`, { method: 'PUT', body: JSON.stringify(data) })
export const fetchK8sStatus = () => request('/instances/k8s-status')
export const reconnectK8s = () => request('/instances/k8s-reconnect', { method: 'POST' })
export const fetchK8sNamespaces = () => request('/instances/k8s-namespaces')
export const restartInstance = (id) => request(`/instances/${id}/restart`, { method: 'POST' })
export const stopInstance = (id) => request(`/instances/${id}/stop`, { method: 'POST' })
export const deleteInstance = (id) => request(`/instances/${id}`, { method: 'DELETE' })
export const fetchAvailability = () => request('/instances/availability')
export const fetchStatsByType = () => request('/instances/stats-by-type')
export const fetchK8sDeployments = (namespace = 'devops') => request(`/instances/k8s/deployments?namespace=${namespace}`)
export const getK8sDeployment = (name, namespace = 'devops') => request(`/instances/k8s/deployments/${name}?namespace=${namespace}`)
export const deleteK8sDeployment = (name, namespace = 'devops') => request(`/instances/k8s/deployments/${name}?namespace=${namespace}`, { method: 'DELETE' })

// ==================== Templates ====================
export const fetchTemplates = (type) => request(`/templates${type ? '?type=' + type : ''}`)
export const fetchBuiltinTemplates = () => request('/templates/builtin')
export const createTemplate = (data) => request('/templates', { method: 'POST', body: JSON.stringify(data) })
export const updateTemplate = (id, data) => request(`/templates/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteTemplate = (id) => request(`/templates/${id}`, { method: 'DELETE' })

// ==================== Audit ====================
export const fetchAuditLogs = (page = 0, size = 50) => request(`/audit?page=${page}&size=${size}`)
export const fetchAuditByUser = (username) => request(`/audit/user/${username}`)
export const fetchAuditByAction = (action) => request(`/audit/action/${action}`)

// ==================== Scheduler ====================
export const fetchSchedules = () => request('/scheduler')
export const updatePipelineCron = (pipelineId, cronExpression, cronEnabled) =>
  request(`/scheduler/pipeline/${pipelineId}`, {
    method: 'PUT',
    body: JSON.stringify({ cronExpression, cronEnabled })
  })
