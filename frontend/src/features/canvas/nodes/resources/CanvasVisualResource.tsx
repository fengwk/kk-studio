import { LoaderCircle } from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
} from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'
import { useI18n } from '@/shared/i18n'

/**
 * preview 恢复状态机（同一失败周期内不无限重试）：
 * initial -> refreshing -> refreshed -> original。
 */
type PreviewRecoveryPhase = 'initial' | 'refreshing' | 'refreshed' | 'original'

export function CanvasVisualResource({ resource }: { resource: Resource }) {
  const { t } = useI18n()
  const [recoveryPhase, setRecoveryPhase] = useState<PreviewRecoveryPhase>('initial')
  const [originalRequested, setOriginalRequested] = useState(false)
  const [playRequested, setPlayRequested] = useState(false)
  const [originalUnavailable, setOriginalUnavailable] = useState(false)
  const mediaRef = useRef<HTMLMediaElement | null>(null)
  const dimensions = resourceDimensions(resource)
  const {
    targetRef: previewTargetRef,
    url: previewUrl,
    loading: previewLoading,
    error: previewError,
    refresh: refreshPreview,
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'preview',
    lazy: true,
  })
  const {
    url: originalUrl,
    loading: originalLoading,
    error: originalError,
    refresh: refreshOriginal,
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'original',
    enabled: originalRequested,
  })

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

  if (resource.kind === 'IMAGE') {
    return (
      <div className="canvas-resource-media image" ref={previewTargetRef}>
        {recoveryPhase === 'original' && originalUrl && !originalUnavailable ? (
          <img
            src={originalUrl}
            alt={resource.name}
            width={dimensions?.width}
            height={dimensions?.height}
            draggable={false}
            loading="lazy"
            decoding="async"
            onError={() => setOriginalUnavailable(true)}
          />
        ) : recoveryPhase !== 'original' && previewUrl && recoveryPhase !== 'refreshing' ? (
          <img
            src={previewUrl}
            alt={resource.name}
            width={dimensions?.width}
            height={dimensions?.height}
            draggable={false}
            loading="lazy"
            decoding="async"
            onError={handlePreviewError}
          />
        ) : (
          <MediaPlaceholder
            loading={previewLoading || originalLoading || recoveryPhase === 'refreshing'}
            error={Boolean(
              recoveryPhase === 'original'
                ? originalError || originalUnavailable
                : previewError,
            )}
            kind="IMAGE"
            errorLabel={recoveryPhase === 'original' ? t('canvas.media.resourceUnavailable') : undefined}
          />
        )}
      </div>
    )
  }

  const shouldRenderOriginalVideo = Boolean(
    originalUrl && (playRequested || recoveryPhase === 'original'),
  )
  return (
    <div className="canvas-resource-media video" ref={previewTargetRef}>
      {shouldRenderOriginalVideo && !originalUnavailable ? (
        <video
          ref={(element) => {
            if (element) {
              mediaRef.current = element
            }
          }}
          src={originalUrl ?? undefined}
          width={dimensions?.width}
          height={dimensions?.height}
          className="nodrag nowheel"
          draggable={false}
          controls
          autoPlay={playRequested}
          playsInline
          preload="metadata"
          aria-label={t('canvas.media.playVideo', { name: resource.name })}
          onError={() => setOriginalUnavailable(true)}
        />
      ) : (
        <>
          {recoveryPhase === 'original' || originalUnavailable ? (
            <MediaPlaceholder
              loading={originalLoading}
              error={Boolean(originalError || originalUnavailable)}
              kind="VIDEO"
              errorLabel={t('canvas.media.resourceUnavailable')}
            />
          ) : previewUrl && recoveryPhase !== 'refreshing' ? (
            <img
              src={previewUrl}
              alt=""
              width={dimensions?.width}
              height={dimensions?.height}
              draggable={false}
              loading="lazy"
              decoding="async"
              onError={handlePreviewError}
            />
          ) : (
            <MediaPlaceholder
              loading={previewLoading || originalLoading || recoveryPhase === 'refreshing'}
              error={Boolean(previewError)}
              kind="VIDEO"
            />
          )}
          {!originalUnavailable ? (
            <button
              type="button"
              className="media-play-button nodrag"
              disabled={originalLoading}
              onClick={() => {
                setPlayRequested(true)
                requestOriginal()
              }}
              aria-label={t('canvas.media.playVideo', { name: resource.name })}
            >
              {originalLoading ? (
                <LoaderCircle className="media-action-spinner" aria-hidden="true" />
              ) : (
                '▶'
              )}
            </button>
          ) : null}
        </>
      )}
    </div>
  )
}

function MediaPlaceholder({
  loading,
  error,
  kind,
  errorLabel,
}: {
  loading: boolean
  error: boolean
  kind: 'IMAGE' | 'VIDEO'
  errorLabel?: string
}) {
  const { t } = useI18n()
  return (
    <div className="media-placeholder" role={error ? 'alert' : undefined}>
      <span aria-hidden="true">{kind === 'IMAGE' ? '▧' : '▶'}</span>
      <small>
        {error
          ? errorLabel ?? t('canvas.media.previewFailed')
          : loading
            ? t('canvas.media.loadingPreview')
            : t('canvas.media.waitingViewport')}
      </small>
    </div>
  )
}

function resourceDimensions(resource: Resource): { width: number; height: number } | null {
  const width = finiteNumber(resource.width)
  const height = finiteNumber(resource.height)
  return width !== null && width > 0 && height !== null && height > 0
    ? { width, height }
    : null
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
