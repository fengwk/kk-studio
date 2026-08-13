import { useCallback, useEffect, useRef, useState } from 'react'
import type { Resource } from '@/features/canvas/domain'
import { getCanvasResourceOriginalUrl } from '@/shared/api/studio-service'

type OriginalIntent = 'open' | 'download'

/**
 * 原件 open/download 动作的共享 hook：每次触发都获取新的 original 签名 URL，
 * 签名完成后执行下载/新窗口打开；失败可再次点击重试。
 */
export function useCanvasResourceActions(resource: Resource) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [fulfilled, setFulfilled] = useState(false)
  const requestIdRef = useRef(0)

  useEffect(() => () => {
    requestIdRef.current += 1
  }, [])

  const ensureSigned = useCallback((intent: OriginalIntent) => {
    const requestId = requestIdRef.current + 1
    requestIdRef.current = requestId
    setLoading(true)
    setError(null)
    setFulfilled(false)
    void getCanvasResourceOriginalUrl(resource.canvasId, resource.id)
      .then(({ url }) => {
        if (requestIdRef.current !== requestId) {
          return
        }
        triggerAnchor(intent, url, resource.name)
        setFulfilled(true)
      })
      .catch((reason: unknown) => {
        if (requestIdRef.current === requestId) {
          setError(reason)
        }
      })
      .finally(() => {
        if (requestIdRef.current === requestId) {
          setLoading(false)
        }
      })
  }, [resource.canvasId, resource.id, resource.name])

  return { loading, error, fulfilled, ensureSigned }
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
