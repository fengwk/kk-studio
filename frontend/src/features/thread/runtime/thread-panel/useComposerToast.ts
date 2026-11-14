import { useCallback, useEffect, useRef, useState } from 'react'

/** 上传失败等一次性提示的展示时长。 */
const TOAST_DURATION_MS = 5000

/**
 * Composer 区域的单条瞬时提示。
 *
 * 同一时刻只显示一条：新提示覆盖旧的并重置计时。卸载时清理计时器，
 * 避免定时器在组件卸载后写状态。
 */
export function useComposerToast() {
  const [toast, setToast] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const showToast = useCallback((message: string) => {
    clearTimer()
    setToast(message)
    timerRef.current = setTimeout(() => {
      setToast(null)
      timerRef.current = null
    }, TOAST_DURATION_MS)
  }, [clearTimer])

  const dismissToast = useCallback(() => {
    clearTimer()
    setToast(null)
  }, [clearTimer])

  useEffect(() => clearTimer, [clearTimer])

  return { toast, showToast, dismissToast }
}
