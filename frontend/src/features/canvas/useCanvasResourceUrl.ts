import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { DecimalString } from '@/shared/api/contracts/studio'
import {
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'

type ResourceUrlKind = 'preview' | 'original'

interface ResourceUrlOptions {
  canvasId: DecimalString
  resourceId: DecimalString
  kind: ResourceUrlKind
  enabled?: boolean
  lazy?: boolean
}

/** 媒体 URL 查询只在可见或用户明确请求后启用，observer 在换节点和卸载时断开。 */
export function useCanvasResourceUrl({
  canvasId,
  resourceId,
  kind,
  enabled = true,
  lazy = false,
}: ResourceUrlOptions) {
  const observerRef = useRef<IntersectionObserver | null>(null)
  const [visible, setVisible] = useState(!lazy)
  const targetRef = useCallback((element: HTMLElement | null) => {
    observerRef.current?.disconnect()
    observerRef.current = null
    if (!element || !lazy) {
      if (element) {
        setVisible(true)
      }
      return
    }
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true)
        observer.disconnect()
        observerRef.current = null
      }
    }, { rootMargin: '160px' })
    observer.observe(element)
    observerRef.current = observer
  }, [lazy])

  useEffect(() => {
    setVisible(!lazy)
    return () => {
      observerRef.current?.disconnect()
      observerRef.current = null
    }
  }, [canvasId, lazy, resourceId])

  const query = useQuery({
    queryKey: kind === 'preview'
      ? queryKeys.studio.canvasResourcePreview(canvasId, resourceId)
      : queryKeys.studio.canvasResourceOriginal(canvasId, resourceId),
    queryFn: ({ signal }) => (
      kind === 'preview'
        ? getCanvasResourcePreviewUrl(canvasId, resourceId, { signal })
        : getCanvasResourceOriginalUrl(canvasId, resourceId, { signal })
    ),
    enabled: enabled && visible,
    staleTime: 60_000,
    gcTime: 5 * 60_000,
    retry: 1,
  })

  return {
    targetRef,
    url: query.data?.url ?? null,
    headers: query.data?.headers ?? {},
    loading: query.isLoading || query.isFetching,
    error: query.error,
  }
}
