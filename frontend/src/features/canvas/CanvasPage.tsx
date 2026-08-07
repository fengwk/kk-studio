import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasLibraryView } from '@/features/canvas/CanvasLibraryView'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeProvider, useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import '@/features/canvas/canvas.css'

function CanvasPageBody() {
  const { state, openLibrary } = useCanvasRuntime()
  const location = useLocation()
  const seenLocationKeyRef = useRef<string | null>(null)

  // 同路由下的 Canvas 首页链接会发出新的 location key，但不会重新挂载当前 feature。
  useEffect(() => {
    if (seenLocationKeyRef.current === null) {
      seenLocationKeyRef.current = location.key
      return
    }
    if (location.key === seenLocationKeyRef.current) {
      return
    }
    seenLocationKeyRef.current = location.key
    if (location.pathname.startsWith('/canvas')) {
      openLibrary()
    }
  }, [location.key, location.pathname, openLibrary])

  return (
    <div className={`canvas-feature ${state.view === 'editor' ? 'canvas-editor-active' : ''}`}>
      {state.view === 'library' ? <CanvasLibraryView /> : <CanvasEditor />}
      <CanvasOverlays />
    </div>
  )
}

export function CanvasPage() {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))

  return (
    <QueryClientProvider client={queryClient}>
      <CanvasRuntimeProvider>
        <CanvasPageBody />
      </CanvasRuntimeProvider>
    </QueryClientProvider>
  )
}
