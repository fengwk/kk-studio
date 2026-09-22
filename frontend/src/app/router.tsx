import { Navigate, Route, Routes } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chats" replace />} />
      <Route path="/*" element={<WorkbenchShell navItems={PRIMARY_NAV_ITEMS} />} />
    </Routes>
  )
}
