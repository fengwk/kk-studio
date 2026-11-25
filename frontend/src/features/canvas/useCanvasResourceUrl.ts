import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
} from '@/features/canvas/canvas-api'
import { queryKeys } from '@/shared/lib/query-keys'

type ResourceUrlKind = 'preview' | 'original'
const LAZY_VIEWPORT_MARGIN = 160

interface ResourceUrlOptions {
  canvasId: string
  blobId: string | null
  kind: ResourceUrlKind
  enabled?: boolean
  lazy?: boolean
}

function isNearViewport(element: HTMLElement) {
  if (element.getClientRects().length === 0) {
    return false
  }
  const rect = element.getBoundingClientRect()
  const width = document.documentElement.clientWidth || window.innerWidth
  const height = document.documentElement.clientHeight || window.innerHeight
  return (
    rect.bottom >= -LAZY_VIEWPORT_MARGIN
    && rect.right >= -LAZY_VIEWPORT_MARGIN
    && rect.top <= height + LAZY_VIEWPORT_MARGIN
    && rect.left <= width + LAZY_VIEWPORT_MARGIN
  )
}

/** 媒体 URL 查询只在可见或用户明确请求后启用，observer 在换节点和卸载时断开。 */
export function useCanvasResourceUrl({
  canvasId,
  blobId,
  kind,
  enabled = true,
  lazy = false,
}: ResourceUrlOptions) {
  const visibilityKey = `${canvasId}:${blobId ?? ''}:${kind}:${lazy}`
  const observerRef = useRef<IntersectionObserver | null>(null)
  const [visibility, setVisibility] = useState({
    key: visibilityKey,
    visible: !lazy,
  })
  const visible = visibility.key === visibilityKey ? visibility.visible : !lazy
  const targetRef = useCallback((element: HTMLElement | null) => {
    observerRef.current?.disconnect()
    observerRef.current = null
    if (!element || !lazy) {
      if (element) {
        setVisibility({ key: visibilityKey, visible: true })
      }
      return
    }
    if (typeof IntersectionObserver === 'undefined') {
      setVisibility({ key: visibilityKey, visible: true })
      return
    }
    if (isNearViewport(element)) {
      setVisibility({ key: visibilityKey, visible: true })
      return
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisibility({ key: visibilityKey, visible: true })
        observer.disconnect()
        observerRef.current = null
      }
    }, { rootMargin: `${LAZY_VIEWPORT_MARGIN}px` })
    observer.observe(element)
    observerRef.current = observer
  }, [lazy, visibilityKey])

  useEffect(() => {
    return () => {
      observerRef.current?.disconnect()
      observerRef.current = null
    }
  }, [visibilityKey])

  const query = useQuery({
    queryKey: kind === 'preview'
      ? queryKeys.studio.canvasResourcePreview(canvasId, blobId ?? '')
      : queryKeys.studio.canvasResourceOriginal(canvasId, blobId ?? ''),
    queryFn: ({ signal }) => (
      kind === 'preview'
        ? getCanvasResourcePreviewUrl(canvasId, blobId!, { signal })
        : getCanvasResourceOriginalUrl(canvasId, blobId!, { signal })
    ),
    enabled: enabled && visible && Boolean(blobId),
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
    refresh: query.refetch,
  }
}
