import { Navigate, Route, Routes } from 'react-router-dom'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chats" replace />} />
      <Route path="/*" element={<WorkbenchShell />} />
    </Routes>
  )
}
