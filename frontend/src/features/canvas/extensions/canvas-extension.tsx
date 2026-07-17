/* eslint-disable react-refresh/only-export-components */
import { lazy, Suspense } from 'react'
import type { ExtensionComponentProps, TrustedReactExtension } from '@/platform/extensions/types'

const CanvasPage = lazy(async () => {
  const module = await import('@/features/canvas/CanvasPage')
  return { default: module.CanvasPage }
})

function CanvasRoutePage({ children }: ExtensionComponentProps) {
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">正在加载画布</div>}>
        <CanvasPage />
      </Suspense>
      {children}
    </>
  )
}

export const canvasExtension: TrustedReactExtension = {
  id: 'builtin.canvas',
  pages: [
    { id: 'canvas.home', path: 'canvas', component: CanvasRoutePage, priority: 90 },
  ],
}
