import {
  LoaderCircle,
  Pause,
  Play,
  Volume2,
  VolumeX,
} from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
} from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { useI18n } from '@/shared/i18n'

export function CanvasResourceMedia({ resource }: { resource: Resource }) {
  if (resource.kind === 'TEXT') {
    return (
      <div className="canvas-markdown-resource" data-testid="canvas-markdown-resource">
        <MarkdownRenderer content={resource.textContent ?? ''} />
      </div>
    )
  }
  return <CanvasBinaryResourceMedia key={resource.id} resource={resource} />
}

export function CanvasResourceThumbnail({ resource }: { resource: Resource }) {
  const dimensions = resourceDimensions(resource)
  const {
    targetRef,
    url,
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'preview',
    lazy: true,
    enabled: resource.kind === 'IMAGE' || resource.kind === 'VIDEO',
  })
  return (
    <span className="canvas-resource-thumbnail" ref={targetRef} data-kind={resource.kind}>
      {url ? (
        <img
          src={url}
          alt=""
          width={dimensions?.width}
          height={dimensions?.height}
          draggable={false}
          loading="lazy"
          decoding="async"
        />
      ) : (
        <span aria-hidden="true">{resourceIcon(resource.kind)}</span>
      )}
    </span>
  )
}

/**
 * preview 恢复状态机（同一失败周期内不无限重试）：
 * initial -> (img onError) -> refreshing（强制重新签名）-> refreshed -> (新 URL 再错)
 * -> original（请求并渲染原件）；刷新失败或原件请求失败都停在 original/failed。
 */
type PreviewRecoveryPhase = 'initial' | 'refreshing' | 'refreshed' | 'original'

function CanvasBinaryResourceMedia({ resource }: { resource: Resource }) {
  const { t } = useI18n()
  const hasPreview = resource.kind === 'IMAGE' || resource.kind === 'VIDEO'
  const [recoveryPhase, setRecoveryPhase] = useState<PreviewRecoveryPhase>('initial')
  const [originalRequested, setOriginalRequested] = useState(false)
  const [playRequested, setPlayRequested] = useState(false)
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
    enabled: hasPreview,
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

  // 画布或资源切换时重置恢复状态机。
  useEffect(() => {
    setRecoveryPhase('initial')
    setOriginalRequested(false)
    setPlayRequested(false)
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
    // refreshing（等待重新签名）与 original（最终兜底）期间忽略重复 error。
  }

  if (resource.kind === 'IMAGE') {
    return (
      <div className="canvas-resource-media image" ref={previewTargetRef}>
        {recoveryPhase === 'original' && originalUrl ? (
          <img
            src={originalUrl}
            alt={resource.name}
            width={dimensions?.width}
            height={dimensions?.height}
            draggable={false}
            loading="lazy"
            decoding="async"
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
            error={recoveryPhase === 'original' ? originalError : previewError}
            kind="IMAGE"
            t={t}
          />
        )}
      </div>
    )
  }

  if (resource.kind === 'VIDEO') {
    return (
      <div className="canvas-resource-media video" ref={previewTargetRef}>
        {playRequested && originalUrl ? (
          <video
            ref={(element) => {
              if (element) {
                mediaRef.current = element
              }
            }}
            src={originalUrl}
            width={dimensions?.width}
            height={dimensions?.height}
            className="nodrag nowheel"
            draggable={false}
            controls
            autoPlay
            playsInline
            preload="metadata"
            aria-label={t('canvas.media.playVideo', { name: resource.name })}
          />
        ) : (
          <>
            {recoveryPhase === 'original' && originalUrl ? (
              <img
                src={originalUrl}
                alt=""
                width={dimensions?.width}
                height={dimensions?.height}
                draggable={false}
                loading="lazy"
                decoding="async"
              />
            ) : recoveryPhase !== 'original' && previewUrl && recoveryPhase !== 'refreshing' ? (
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
                error={recoveryPhase === 'original' ? originalError : previewError}
                kind="VIDEO"
                t={t}
              />
            )}
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
          </>
        )}
      </div>
    )
  }

  return (
    <div className="canvas-resource-media audio">
      <CanvasAudioPlayer resource={resource} />
    </div>
  )
}

function CanvasAudioPlayer({ resource }: { resource: Resource }) {
  const { t } = useI18n()
  const [originalRequested, setOriginalRequested] = useState(false)
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
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const pendingPlayRef = useRef(false)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(finiteSeconds(resource.durationMs) ?? 0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [playError, setPlayError] = useState(false)

  useEffect(() => {
    if (!originalUrl || !audioRef.current) {
      return
    }
    if (!pendingPlayRef.current) {
      return
    }
    pendingPlayRef.current = false
    setPlayError(false)
    const audio = audioRef.current
    const result = audio.play()
    if (result && typeof result.then === 'function') {
      result
        .then(() => setPlaying(true))
        .catch(() => {
          setPlaying(false)
          setPlayError(true)
        })
    } else {
      setPlaying(true)
    }
  }, [originalUrl])

  const requestOriginal = () => {
    if (originalRequested) {
      void refreshOriginal()
    } else {
      setOriginalRequested(true)
    }
  }

  const togglePlay = () => {
    const audio = audioRef.current
    if (!audio || !originalUrl) {
      pendingPlayRef.current = true
      setPlayError(false)
      requestOriginal()
      return
    }
    setPlayError(false)
    if (audio.paused) {
      const result = audio.play()
      if (result && typeof result.then === 'function') {
        result
          .then(() => setPlaying(true))
          .catch(() => {
            setPlaying(false)
            setPlayError(true)
          })
      } else {
        setPlaying(true)
      }
    } else {
      audio.pause()
      setPlaying(false)
    }
  }

  const handleSeek = (event: ChangeEvent<HTMLInputElement>) => {
    const next = Number(event.target.value)
    setCurrentTime(next)
    const audio = audioRef.current
    if (audio && Number.isFinite(audio.duration)) {
      audio.currentTime = Math.min(next, audio.duration)
    }
  }

  const handleVolume = (event: ChangeEvent<HTMLInputElement>) => {
    const next = Number(event.target.value)
    setVolume(next)
    setMuted(false)
    const audio = audioRef.current
    if (audio) {
      audio.volume = next
      audio.muted = false
    }
  }

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    const audio = audioRef.current
    if (audio) {
      audio.muted = next
    }
  }

  const signing = originalLoading && !originalUrl
  return (
    <div className="audio-player nodrag nowheel">
      <button
        type="button"
        className="audio-player-play"
        onClick={togglePlay}
        disabled={signing}
        aria-label={playing
          ? t('canvas.audio.pause', { name: resource.name })
          : t('canvas.audio.play', { name: resource.name })}
      >
        {signing ? (
          <LoaderCircle className="media-action-spinner" aria-hidden="true" />
        ) : playing ? (
          <Pause aria-hidden="true" />
        ) : (
          <Play aria-hidden="true" />
        )}
      </button>
      <div className="audio-player-main">
        <strong className="audio-player-name" title={resource.name}>{resource.name}</strong>
        <div className="audio-player-row">
          <span className="audio-player-time">
            {formatTime(currentTime)}
            {' / '}
            {formatTime(duration)}
          </span>
          <input
            type="range"
            className="audio-player-seek"
            min={0}
            max={Math.max(duration, 0.001)}
            step={0.1}
            value={Math.min(currentTime, Math.max(duration, 0.001))}
            onChange={handleSeek}
            aria-label={t('canvas.audio.seekAria', { name: resource.name })}
          />
          <input
            type="range"
            className="audio-player-volume"
            min={0}
            max={1}
            step={0.05}
            value={muted ? 0 : volume}
            onChange={handleVolume}
            aria-label={t('canvas.audio.volumeAria', { name: resource.name })}
          />
          <button
            type="button"
            className="audio-player-mute"
            onClick={toggleMute}
            aria-label={muted
              ? t('canvas.audio.unmute', { name: resource.name })
              : t('canvas.audio.mute', { name: resource.name })}
            aria-pressed={muted}
          >
            {muted ? <VolumeX aria-hidden="true" /> : <Volume2 aria-hidden="true" />}
          </button>
        </div>
      </div>
      {playError ? <span className="audio-player-error" role="alert">{t('canvas.audio.playFailed')}</span> : null}
      {!playError && originalError ? <span className="audio-player-error" role="alert">{t('canvas.media.signFailed')}</span> : null}
      {originalUrl ? (
        <audio
          ref={(element) => {
            if (element) {
              audioRef.current = element
            }
          }}
          src={originalUrl}
          preload="metadata"
          className="nodrag nowheel"
          aria-label={t('canvas.audio.play', { name: resource.name })}
          onLoadedMetadata={(event) => {
            const audio = event.currentTarget
            if (Number.isFinite(audio.duration)) {
              setDuration(audio.duration)
            }
            setCurrentTime(audio.currentTime)
          }}
          onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
          onEnded={() => setPlaying(false)}
          onPause={() => setPlaying(false)}
          onPlay={() => setPlaying(true)}
        />
      ) : null}
    </div>
  )
}

function MediaPlaceholder({
  loading,
  error,
  kind,
  t,
}: {
  loading: boolean
  error: unknown
  kind: 'IMAGE' | 'VIDEO'
  t: (key: string, values?: Record<string, string | number>) => string
}) {
  return (
    <div className="media-placeholder" role={error ? 'alert' : undefined}>
      <span aria-hidden="true">{resourceIcon(kind)}</span>
      <small>
        {error
          ? t('canvas.media.previewFailed')
          : loading
            ? t('canvas.media.loadingPreview')
            : t('canvas.media.waitingViewport')}
      </small>
    </div>
  )
}

function resourceIcon(kind: Resource['kind']): string {
  if (kind === 'IMAGE') {
    return '▧'
  }
  if (kind === 'VIDEO') {
    return '▶'
  }
  if (kind === 'AUDIO') {
    return '♪'
  }
  return 'T'
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function finiteSeconds(value: unknown): number | null {
  const ms = finiteNumber(value)
  return ms !== null ? ms / 1000 : null
}

function resourceDimensions(resource: Resource): { width: number; height: number } | null {
  const width = finiteNumber(resource.width)
  const height = finiteNumber(resource.height)
  return width !== null && width > 0 && height !== null && height > 0
    ? { width, height }
    : null
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00'
  }
  const total = Math.floor(seconds)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}
