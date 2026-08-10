import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { Navigate, useParams } from 'react-router'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasLibraryView } from '@/features/canvas/CanvasLibraryView'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeProvider, useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { DecimalString } from '@/shared/api/contracts/studio'
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

  if (canvasId !== undefined && !/^[1-9][0-9]*$/.test(canvasId)) {
    return <Navigate to="/canvas" replace />
  }
  const initialCanvasId = canvasId as DecimalString | undefined

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
