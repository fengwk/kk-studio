import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { useI18n } from '@/shared/i18n'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

const InteractionsPage = lazy(async () => {
  const module = await import('@/app/pages/InteractionsPage')
  return { default: module.InteractionsPage }
})

export function AppRouter() {
  const { t } = useI18n()

  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chats" replace />} />
      <Route
        path="/interactions"
        element={
          <WorkbenchShell navItems={PRIMARY_NAV_ITEMS}>
            <Suspense fallback={<StateBlock title={t('ai.common.loadingResources')} />}>
              <InteractionsPage />
            </Suspense>
          </WorkbenchShell>
        }
      />
      <Route path="/*" element={<WorkbenchShell navItems={PRIMARY_NAV_ITEMS} />} />
    </Routes>
  )
}
