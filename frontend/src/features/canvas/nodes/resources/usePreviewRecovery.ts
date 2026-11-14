import {
  useEffect,
  useRef,
  useState,
} from 'react'

/**
 * preview 恢复状态机（同一失败周期内不无限重试）：
 * initial -> refreshing -> refreshed -> original。
 */
type PreviewRecoveryPhase = 'initial' | 'refreshing' | 'refreshed' | 'original'

interface PreviewRecoveryInput {
  resource: { canvasId: string; id: string }
  /** original 是否已被请求过；由调用方持有，因为它决定 original URL hook 的 enabled。 */
  originalRequested: boolean
  setOriginalRequested: (requested: boolean) => void
  previewUrl: string | null
  previewError: unknown
  refreshPreview: () => Promise<{ isError: boolean }>
  originalUrl: string | null
  refreshOriginal: () => Promise<unknown>
}

/**
 * 预览图加载失败时的逐级回退：先刷新 preview，再退到 original；
 * original 也不可用时由 UI 显示降级占位。切换资源时整台状态机复位。
 */
export function usePreviewRecovery({
  resource,
  originalRequested,
  setOriginalRequested,
  previewUrl,
  previewError,
  refreshPreview,
  originalUrl,
  refreshOriginal,
}: PreviewRecoveryInput) {
  const [recoveryPhase, setRecoveryPhase] = useState<PreviewRecoveryPhase>('initial')
  const [playRequested, setPlayRequested] = useState(false)
  const [originalUnavailable, setOriginalUnavailable] = useState(false)
  const mediaRef = useRef<HTMLMediaElement | null>(null)

  useEffect(() => {
    setRecoveryPhase('initial')
    setOriginalRequested(false)
    setPlayRequested(false)
    setOriginalUnavailable(false)
  }, [resource.canvasId, resource.id])

  useEffect(() => () => {
    const media = mediaRef.current
    if (media) {
      media.removeAttribute('src')
    }
  }, [])

  const requestOriginal = () => {
    if (originalRequested) {
      void refreshOriginal()
    } else {
      setOriginalRequested(true)
    }
  }

  const recoverWithOriginal = () => {
    setRecoveryPhase('original')
    requestOriginal()
  }

  useEffect(() => {
    if (
      (recoveryPhase === 'initial' || recoveryPhase === 'refreshing')
      && previewError
      && !previewUrl
    ) {
      setRecoveryPhase('original')
      setOriginalRequested(true)
    }
  }, [previewError, previewUrl, recoveryPhase])

  const handlePreviewError = () => {
    if (recoveryPhase === 'initial') {
      setRecoveryPhase('refreshing')
      void refreshPreview()
        .then((result) => {
          if (result.isError) {
            recoverWithOriginal()
          } else {
            setRecoveryPhase('refreshed')
          }
        })
        .catch(recoverWithOriginal)
    } else if (recoveryPhase === 'refreshed') {
      recoverWithOriginal()
    }
  }

  return {
    recoveryPhase,
    originalRequested,
    playRequested,
    setPlayRequested,
    originalUnavailable,
    setOriginalUnavailable,
    mediaRef,
    originalUrl,
    requestOriginal,
    handlePreviewError,
  }
}
