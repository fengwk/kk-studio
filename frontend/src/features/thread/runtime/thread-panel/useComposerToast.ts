import { useCallback, useEffect, useRef, useState } from 'react'

/** 普通提示的展示时长。 */
const TOAST_DURATION_MS = 5000

export interface ShowToastOptions {
  /** 不自动消失，由用户关闭：用于失败原因这类必须读完的错误提示。 */
  persistent?: boolean
}

/**
 * Composer 区域的单条瞬时提示。
 *
 * 同一时刻只显示一条：新提示覆盖旧的并重置计时。普通提示按固定时长自动消失，
 * 失败原因由调用方标记为 `persistent`，保留到用户关闭。卸载时清理计时器，
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

  const showToast = useCallback((message: string, options?: ShowToastOptions) => {
    clearTimer()
    setToast(message)
    if (options?.persistent) {
      return
    }
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
