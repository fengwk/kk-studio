import { Navigate, Route, Routes } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { InteractionsPage } from '@/app/pages/InteractionsPage'
import { TerminalPanel } from '@/features/shell/TerminalPanel'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

export function AppRouter() {
  const bottomPanel = <TerminalPanel />
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chats" replace />} />
      <Route
        path="/interactions"
        element={
          <WorkbenchShell navItems={PRIMARY_NAV_ITEMS} bottomPanel={bottomPanel}>
            <InteractionsPage />
          </WorkbenchShell>
        }
      />
      <Route
        path="/*"
        element={<WorkbenchShell navItems={PRIMARY_NAV_ITEMS} bottomPanel={bottomPanel} />}
      />
    </Routes>
  )
}
