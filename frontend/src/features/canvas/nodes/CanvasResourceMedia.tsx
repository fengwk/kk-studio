import {
  Download,
  ExternalLink,
  LoaderCircle,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { useI18n } from '@/shared/i18n'

export function CanvasResourceMedia({ resource }: { resource: Resource }) {
  if (resource.kind === 'TEXT') {
    return (
      <div className="canvas-markdown-resource" data-testid="canvas-markdown-resource">
        <MarkdownRenderer content={resource.text ?? ''} />
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

function CanvasBinaryResourceMedia({ resource }: { resource: Resource }) {
  const { t } = useI18n()
  const [originalRequested, setOriginalRequested] = useState(false)
  const [playRequested, setPlayRequested] = useState(false)
  const mediaRef = useRef<HTMLMediaElement | null>(null)
  const hasPreview = resource.kind === 'IMAGE' || resource.kind === 'VIDEO'
  const dimensions = resourceDimensions(resource)
  const {
    targetRef: previewTargetRef,
    url: previewUrl,
    loading: previewLoading,
    error: previewError,
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
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'original',
    enabled: originalRequested,
  })

  useEffect(() => () => {
    const media = mediaRef.current
    if (media) {
      media.removeAttribute('src')
    }
  }, [])

  if (resource.kind === 'IMAGE') {
    return (
      <div className="canvas-resource-media image" ref={previewTargetRef}>
        {previewUrl ? (
          <img
            src={previewUrl}
            alt={resource.name}
            width={dimensions?.width}
            height={dimensions?.height}
            draggable={false}
            loading="lazy"
            decoding="async"
          />
        ) : (
          <MediaPlaceholder
            loading={previewLoading}
            error={previewError}
            kind="IMAGE"
            t={t}
          />
        )}
        <OriginalActions
          resource={resource}
          url={originalUrl}
          loading={originalLoading}
          error={originalError}
          requestLabel={t('canvas.media.fetchImage')}
          openLabel={t('canvas.media.openImage')}
          downloadLabel={t('canvas.media.downloadImage')}
          signingLabel={t('canvas.media.signing')}
          signFailedLabel={t('canvas.media.signFailed')}
          onRequest={() => setOriginalRequested(true)}
        />
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
            aria-label={t('canvas.media.play', { name: resource.name })}
          />
        ) : (
          <>
            {previewUrl ? (
              <img
                src={previewUrl}
                alt=""
                width={dimensions?.width}
                height={dimensions?.height}
                draggable={false}
                loading="lazy"
                decoding="async"
              />
            ) : (
              <MediaPlaceholder
                loading={previewLoading}
                error={previewError}
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
                setOriginalRequested(true)
              }}
              aria-label={t('canvas.media.playVideo', { name: resource.name })}
            >
              {originalLoading ? t('canvas.media.loading') : '▶'}
            </button>
          </>
        )}
        <OriginalActions
          resource={resource}
          url={originalUrl}
          loading={originalLoading}
          error={originalError}
          requestLabel={t('canvas.media.fetchVideo')}
          openLabel={t('canvas.media.openVideo')}
          downloadLabel={t('canvas.media.downloadVideo')}
          signingLabel={t('canvas.media.signing')}
          signFailedLabel={t('canvas.media.signFailed')}
          onRequest={() => setOriginalRequested(true)}
        />
      </div>
    )
  }

  const durationMs = finiteNumber(resource.metadata.durationMs)
  return (
    <div className="canvas-resource-media audio">
      {playRequested && originalUrl ? (
        <audio
          ref={(element) => {
            if (element) {
              mediaRef.current = element
            }
          }}
          src={originalUrl}
          className="nodrag nowheel"
          controls
          preload="metadata"
          aria-label={t('canvas.media.playAudio', { name: resource.name })}
        />
      ) : (
        <button
          type="button"
          className="audio-load-button nodrag"
          disabled={originalLoading}
          onClick={() => {
            setPlayRequested(true)
            setOriginalRequested(true)
          }}
        >
          <span aria-hidden="true">♪</span>
          <strong>{originalLoading ? t('canvas.media.loading') : t('canvas.media.loadAudio')}</strong>
          <small>{durationMs === null ? resource.mediaType : formatDuration(durationMs)}</small>
        </button>
      )}
      <OriginalActions
        resource={resource}
        url={originalUrl}
        loading={originalLoading}
        error={originalError}
        requestLabel={t('canvas.media.fetchAudio')}
        openLabel={t('canvas.media.openAudio')}
        downloadLabel={t('canvas.media.downloadAudio')}
        signingLabel={t('canvas.media.signing')}
        signFailedLabel={t('canvas.media.signFailed')}
        onRequest={() => setOriginalRequested(true)}
      />
    </div>
  )
}

function OriginalActions({
  resource,
  url,
  loading,
  error,
  requestLabel,
  openLabel,
  downloadLabel,
  signingLabel,
  signFailedLabel,
  onRequest,
}: {
  resource: Resource
  url: string | null
  loading: boolean
  error: unknown
  requestLabel: string
  openLabel: string
  downloadLabel: string
  signingLabel: string
  signFailedLabel: string
  onRequest: () => void
}) {
  return (
    <div className="media-original-actions nodrag">
      {url ? (
        <>
          <a
            href={url}
            target="_blank"
            rel="noreferrer"
            title={openLabel}
            aria-label={`${openLabel} ${resource.name}`}
          >
            <ExternalLink aria-hidden="true" />
          </a>
          <a
            href={url}
            download={resource.name}
            title={downloadLabel}
            aria-label={`${downloadLabel} ${resource.name}`}
          >
            <Download aria-hidden="true" />
          </a>
        </>
      ) : (
        <button
          type="button"
          disabled={loading}
          title={loading ? signingLabel : requestLabel}
          onClick={onRequest}
          aria-label={`${loading ? signingLabel : requestLabel} ${resource.name}`}
        >
          {loading ? (
            <LoaderCircle className="media-action-spinner" aria-hidden="true" />
          ) : (
            <Download aria-hidden="true" />
          )}
        </button>
      )}
      {error ? <span className="media-error">{signFailedLabel}</span> : null}
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

function resourceDimensions(resource: Resource): { width: number; height: number } | null {
  const width = finiteNumber(resource.metadata.width)
  const height = finiteNumber(resource.metadata.height)
  return width !== null && width > 0 && height !== null && height > 0
    ? { width, height }
    : null
}

function formatDuration(durationMs: number): string {
  const seconds = Math.round(durationMs / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
