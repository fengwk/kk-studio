import { useEffect } from 'react'

/** toast 在 2.8s 后自动消失；新 toast 会重置计时。 */
export function useCanvasToast(toast: string | null, clearToast: () => void): void {
  useEffect(() => {
    if (!toast) {
      return undefined
    }
    const timer = window.setTimeout(clearToast, 2800)
    return () => window.clearTimeout(timer)
  }, [clearToast, toast])
}
