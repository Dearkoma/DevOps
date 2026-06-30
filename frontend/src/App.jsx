import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import ProtectedRoute from './components/ProtectedRoute'
import AppLayout from './components/AppLayout'
import Login from './components/Login'
import Register from './components/Register'
import Dashboard from './components/Dashboard'
import ProjectList from './components/ProjectList'
import PipelineList from './components/PipelineList'
import BuildList from './components/BuildList'
import EnvironmentList from './components/EnvironmentList'
import DeploymentList from './components/DeploymentList'
import InstanceList from './components/InstanceList'
import TemplateList from './components/TemplateList'
import AuditLogList from './components/AuditLogList'
import SchedulerList from './components/SchedulerList'
import ArtifactList from './components/ArtifactList'
import NotificationCenter from './components/NotificationCenter'
import UserManagement from './components/UserManagement'
import ProjectFiles from './components/ProjectFiles'
import './App.css'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          <Route path="/" element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="projects" element={<ProjectList />} />
            <Route path="projects/:projectId/pipelines" element={<PipelineList />} />
            <Route path="projects/:projectId/files" element={<ProjectFiles />} />
            <Route path="pipelines" element={<PipelineList />} />
            <Route path="builds" element={<BuildList />} />
            <Route path="deployments" element={<DeploymentList />} />
            <Route path="environments" element={<EnvironmentList />} />
            <Route path="instances" element={<InstanceList />} />
            <Route path="instances/docker" element={<InstanceList />} />
            <Route path="instances/k8s" element={<InstanceList />} />
            <Route path="templates" element={<TemplateList />} />
            <Route path="audit" element={<ProtectedRoute requireAdmin><AuditLogList /></ProtectedRoute>} />
            <Route path="schedules" element={<SchedulerList />} />
            <Route path="artifacts" element={<ArtifactList />} />
            <Route path="notifications" element={<NotificationCenter />} />
            <Route path="users" element={<ProtectedRoute requireAdmin><UserManagement /></ProtectedRoute>} />
          </Route>

          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
