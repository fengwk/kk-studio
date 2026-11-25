import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'
import { useI18n } from '@/shared/i18n'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

const SettingsRoute = lazy(async () => ({ default: (await import('@/app/SettingsRoute')).SettingsRoute }))

const InteractionsPage = lazy(async () => {
  const module = await import('@/app/pages/InteractionsPage')
  return { default: module.InteractionsPage }
})

export function AppRouter() {
  const { t } = useI18n()

  return (
    <Routes>
      <Route path="/settings/:section" element={(
        <Suspense fallback={<StateBlock title={t('settings.loading')} />}>
          <SettingsRoute />
        </Suspense>
      )} />
      <Route path="/*" element={(
        <WorkbenchShell navItems={PRIMARY_NAV_ITEMS} renderContent={(registeredContent) => (
          <Routes>
            <Route path="/" element={<Navigate to="/chats" replace />} />
            <Route path="/interactions" element={(
              <>
                <Suspense fallback={<StateBlock title={t('ai.common.loadingResources')} />}>
                  <InteractionsPage />
                </Suspense>
                <OverlayHost />
              </>
            )} />
            <Route path="/*" element={registeredContent} />
          </Routes>
        )} />
      )} />
    </Routes>
  )
}
