import { Navigate, Route, Routes } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { InteractionsPage } from '@/features/ai/runtime/interactions/InteractionsPage'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chats" replace />} />
      <Route
        path="/interactions"
        element={
          <WorkbenchShell navItems={PRIMARY_NAV_ITEMS}>
            <InteractionsPage />
          </WorkbenchShell>
        }
      />
      <Route path="/*" element={<WorkbenchShell navItems={PRIMARY_NAV_ITEMS} />} />
    </Routes>
  )
}
