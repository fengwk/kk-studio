import { lazy, Suspense, useEffect } from 'react'
import { notifyCloudFilesChanged } from '@/features/files/useCloudFilesInvalidation'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useApplicationEvents } from '@/shared/app-events'
import { useI18n } from '@/shared/i18n'

const FilesPage = lazy(async () => {
  const module = await import('@/features/files/FilesPage')
  return { default: module.FilesPage }
})

export function FilesRoute({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">{t('platform.loadingFiles')}</div>}>
        <FilesPage />
      </Suspense>
      {children}
    </>
  )
}

/** Bridges the global Cloud Files WebSocket resource into feature-local invalidation hooks. */
export function CloudFilesInvalidationBridge() {
  const events = useApplicationEvents()

  useEffect(
    () =>
      events.subscribe(
        { kind: 'cloud-files' },
        {
          onSubscribed: () => notifyCloudFilesChanged(),
          onResync: () => notifyCloudFilesChanged(),
          onError: () => notifyCloudFilesChanged(),
        },
      ),
    [events],
  )

  return null
}
