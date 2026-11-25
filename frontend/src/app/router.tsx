import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { useI18n } from '@/shared/i18n'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

const SettingsRoute = lazy(async () => ({ default: (await import('@/app/SettingsRoute')).SettingsRoute }))
const WorkspaceRoute = lazy(async () => ({ default: (await import('@/app/WorkspaceRoute')).WorkspaceRoute }))

export function AppRouter() {
  const { t } = useI18n()
  return <Routes>
    <Route path="/settings/:section" element={<Suspense fallback={<StateBlock title={t('settings.loading')} />}><SettingsRoute /></Suspense>} />
    <Route path="/" element={<Suspense fallback={<StateBlock title={t('workspace.loading')} />}><WorkspaceRoute /></Suspense>} />
    <Route path="/chat-spaces/:spaceId" element={<Suspense fallback={<StateBlock title={t('workspace.loading')} />}><WorkspaceRoute /></Suspense>} />
    <Route path="*" element={<StateBlock title={t('workspace.unavailable')} tone="danger" />} />
  </Routes>
}
