/* eslint-disable react-refresh/only-export-components */
import { lazy, Suspense } from 'react'
import type { ExtensionComponentProps, TrustedReactExtension } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

const SettingsPage = lazy(async () => {
  const module = await import('@/features/settings/SettingsPage')
  return { default: module.SettingsPage }
})

function SettingsRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">{t('settings.loading')}</div>}>
        <SettingsPage />
      </Suspense>
      {children}
    </>
  )
}

export const settingsExtension: TrustedReactExtension = {
  id: 'builtin.settings',
  pages: [
    { id: 'settings.page', path: 'settings', component: SettingsRoute, priority: 100 },
  ],
}
