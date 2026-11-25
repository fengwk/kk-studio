import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { Navigate, useParams } from 'react-router'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeProvider } from '@/features/canvas/CanvasRuntimeContext'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import '@/features/canvas/canvas.css'

function CanvasPageBody() {
  return (
    <div className="canvas-feature canvas-editor-active">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
}

export function CanvasPage() {
  const { canvasId } = useParams<{ canvasId?: string }>()
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))

  // 只接受 canonical UUID 深链；Canvas 不提供独立列表页。
  if (!canvasId || !isCanonicalUuid(canvasId)) {
    return <Navigate to="/" replace />
  }
  const initialCanvasId = canvasId

  return (
    <QueryClientProvider client={queryClient}>
      <CanvasRuntimeProvider
        key={initialCanvasId}
        initialCanvasId={initialCanvasId}
      >
        <CanvasPageBody />
      </CanvasRuntimeProvider>
    </QueryClientProvider>
  )
}
