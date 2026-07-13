import { Navigate, Route, Routes } from 'react-router-dom'
import { WorkspaceListPage } from '@/features/workspaces/WorkspaceListPage'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/workspaces" replace />} />
      <Route path="/workspaces" element={<WorkspaceListPage />} />
      <Route path="/workspaces/:workspaceId/*" element={<WorkbenchShell />} />
      <Route path="*" element={<Navigate to="/workspaces" replace />} />
    </Routes>
  )
}
