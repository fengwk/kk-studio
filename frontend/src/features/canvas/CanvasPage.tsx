import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { Navigate, useParams } from 'react-router'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasLibraryView } from '@/features/canvas/CanvasLibraryView'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeProvider, useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { isCanonicalUuid } from '@/features/canvas/uuid'
import type { UUIDString } from '@/shared/api/contracts/studio'
import '@/features/canvas/canvas.css'

function CanvasPageBody() {
  const { state } = useCanvasRuntime()

  return (
    <div className={`canvas-feature ${state.view === 'editor' ? 'canvas-editor-active' : ''}`}>
      {state.view === 'library' ? <CanvasLibraryView /> : <CanvasEditor />}
      <CanvasOverlays />
    </div>
  )
}

export function CanvasPage() {
  const { canvasId } = useParams<{ canvasId?: string }>()
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))

  // 只接受 canonical UUID 深链；非规范 UUID 标识一律重定向回库。
  if (canvasId !== undefined && !isCanonicalUuid(canvasId)) {
    return <Navigate to="/canvas" replace />
  }
  const initialCanvasId = canvasId as UUIDString | undefined

  return (
    <QueryClientProvider client={queryClient}>
      <CanvasRuntimeProvider
        key={initialCanvasId ?? 'library'}
        initialCanvasId={initialCanvasId}
      >
        <CanvasPageBody />
      </CanvasRuntimeProvider>
    </QueryClientProvider>
  )
}
