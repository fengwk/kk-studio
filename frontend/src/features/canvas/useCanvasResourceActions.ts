import { useCallback, useEffect, useRef, useState } from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'

type OriginalIntent = 'open' | 'download'

/**
 * 原件 open/download 动作的共享 hook：首次触发时才签名 original URL，
 * 签名完成后自动执行等待中的意图（下载/新窗口打开），失败可再次点击重试。
 * 供右键菜单的打开/下载入口复用，避免在各处复制签名状态机。
 */
export function useCanvasResourceActions(resource: Resource) {
  const { canvasId, id } = resource
  const [requested, setRequested] = useState(false)
  const {
    url,
    loading,
    error,
    refresh,
  } = useCanvasResourceUrl({
    canvasId,
    resourceId: id,
    kind: 'original',
    enabled: requested,
  })
  const pendingRef = useRef<OriginalIntent | null>(null)
  const [fulfilled, setFulfilled] = useState(false)

  useEffect(() => {
    if (!url || !pendingRef.current) {
      return
    }
    const intent = pendingRef.current
    pendingRef.current = null
    triggerAnchor(intent, url, resource.name)
    setFulfilled(true)
  }, [resource.name, url])

  const ensureSigned = useCallback((intent: OriginalIntent) => {
    if (url) {
      pendingRef.current = null
      setFulfilled(false)
      triggerAnchor(intent, url, resource.name)
      return
    }
    pendingRef.current = intent
    setFulfilled(false)
    if (requested && error) {
      void refresh()
    } else if (!requested) {
      setRequested(true)
    }
  }, [error, refresh, requested, resource.name, url])

  return { url, loading, error, fulfilled, ensureSigned }
}

function triggerAnchor(intent: OriginalIntent, url: string, name: string): void {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.rel = 'noreferrer'
  if (intent === 'download') {
    anchor.download = name
  } else {
    anchor.target = '_blank'
  }
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}
