import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ThreadPresentation } from '@/features/thread/runtime/panes/thread-presentation'

/**
 * Pane 收集子视图上报的查看层报告，只接受与当前预期视图身份一致的报告。
 *
 * 预期视图身份变化时，上一身份的迟到报告不会覆盖新视图；宿主没有 onPresentation 时，
 * 会话模式下的新报告会立即把焦点交还读入口。
 */
export function usePanePresentation({
  expectedViewKey,
  onPresentation,
}: {
  expectedViewKey: string
  onPresentation?: (report: ThreadPresentation | null) => void
}): {
  visiblePresentation: ThreadPresentation | null
  acceptPresentation: (report: ThreadPresentation) => void
} {
  const expectedKeyRef = useRef(expectedViewKey)
  useLayoutEffect(() => { expectedKeyRef.current = expectedViewKey })
  const [presentation, setPresentation] = useState<ThreadPresentation | null>(null)
  const acceptPresentation = useCallback((report: ThreadPresentation) => {
    if (report.viewKey === expectedKeyRef.current) {
      setPresentation(report)
    }
  }, [])
  const visiblePresentation = presentation?.viewKey === expectedViewKey ? presentation : null
  useLayoutEffect(() => {
    if (onPresentation == null && visiblePresentation?.mode === 'conversation') {
      visiblePresentation.act(visiblePresentation.viewKey, 'restore-focus')
    }
  }, [onPresentation, visiblePresentation])
  const presentationCallback = useRef(onPresentation)
  useLayoutEffect(() => { presentationCallback.current = onPresentation })
  useEffect(() => {
    presentationCallback.current?.(visiblePresentation)
  }, [visiblePresentation])
  return { visiblePresentation, acceptPresentation }
}
