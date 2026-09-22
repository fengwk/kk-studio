/* eslint-disable react-refresh/only-export-components */
import { lazy, Suspense } from 'react'
import type { ExtensionComponentProps, TrustedReactExtension } from '@/platform/extensions/types'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { useI18n } from '@/shared/i18n'

const CanvasPage = lazy(async () => {
  const module = await import('@/features/canvas/CanvasPage')
  return { default: module.CanvasPage }
})

function CanvasRoutePage({ children }: ExtensionComponentProps) {
  const { t } = useI18n()
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">{t('canvas.loading')}</div>}>
        <CanvasPage />
      </Suspense>
      {children}
    </>
  )
}

export const canvasExtension: TrustedReactExtension = {
  id: 'builtin.canvas',
  pages: [
    {
      id: 'canvas.home',
      path: 'canvas',
      component: CanvasRoutePage,
      navGroup: 'canvas',
      priority: 90,
    },
    {
      id: 'canvas.editor',
      path: 'canvas/:canvasId',
      component: CanvasRoutePage,
      navGroup: 'canvas',
      workspace: ({ canvasId }) => Boolean(canvasId && isCanonicalUuid(canvasId)),
      priority: 90,
    },
  ],
}
