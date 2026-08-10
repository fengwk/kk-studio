import { useEffect, useRef, useState } from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

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
        <img src={url} alt="" loading="lazy" decoding="async" />
      ) : (
        <span aria-hidden="true">{resourceIcon(resource.kind)}</span>
      )}
    </span>
  )
}

function CanvasBinaryResourceMedia({ resource }: { resource: Resource }) {
  const [originalRequested, setOriginalRequested] = useState(false)
  const [playRequested, setPlayRequested] = useState(false)
  const mediaRef = useRef<HTMLMediaElement | null>(null)
  const hasPreview = resource.kind === 'IMAGE' || resource.kind === 'VIDEO'
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
            loading="lazy"
            decoding="async"
          />
        ) : (
          <MediaPlaceholder loading={previewLoading} error={previewError} kind="IMAGE" />
        )}
        <OriginalActions
          resource={resource}
          url={originalUrl}
          loading={originalLoading}
          error={originalError}
          requestLabel="获取原图"
          openLabel="打开原图"
          downloadLabel="下载原图"
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
            controls
            autoPlay
            playsInline
            preload="metadata"
            aria-label={`播放 ${resource.name}`}
          />
        ) : (
          <>
            {previewUrl ? (
              <img src={previewUrl} alt="" loading="lazy" decoding="async" />
            ) : (
              <MediaPlaceholder loading={previewLoading} error={previewError} kind="VIDEO" />
            )}
            <button
              type="button"
              className="media-play-button"
              disabled={originalLoading}
              onClick={() => {
                setPlayRequested(true)
                setOriginalRequested(true)
              }}
              aria-label={`播放视频 ${resource.name}`}
            >
              {originalLoading ? '加载中…' : '▶'}
            </button>
          </>
        )}
        <OriginalActions
          resource={resource}
          url={originalUrl}
          loading={originalLoading}
          error={originalError}
          requestLabel="获取视频原件"
          openLabel="打开视频原件"
          downloadLabel="下载视频原件"
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
          controls
          preload="metadata"
          aria-label={`播放音频 ${resource.name}`}
        />
      ) : (
        <button
          type="button"
          className="audio-load-button"
          disabled={originalLoading}
          onClick={() => {
            setPlayRequested(true)
            setOriginalRequested(true)
          }}
        >
          <span aria-hidden="true">♪</span>
          <strong>{originalLoading ? '加载中…' : '加载音频'}</strong>
          <small>{durationMs === null ? resource.mediaType : formatDuration(durationMs)}</small>
        </button>
      )}
      <OriginalActions
        resource={resource}
        url={originalUrl}
        loading={originalLoading}
        error={originalError}
        requestLabel="获取音频原件"
        openLabel="打开音频原件"
        downloadLabel="下载音频原件"
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
  onRequest,
}: {
  resource: Resource
  url: string | null
  loading: boolean
  error: unknown
  requestLabel: string
  openLabel: string
  downloadLabel: string
  onRequest: () => void
}) {
  return (
    <div className="media-original-actions">
      {url ? (
        <>
          <a href={url} target="_blank" rel="noreferrer" aria-label={`${openLabel} ${resource.name}`}>
            {openLabel}
          </a>
          <a href={url} download={resource.name} aria-label={`${downloadLabel} ${resource.name}`}>
            {downloadLabel}
          </a>
        </>
      ) : (
        <button
          type="button"
          disabled={loading}
          onClick={onRequest}
          aria-label={`${requestLabel} ${resource.name}`}
        >
          {loading ? '签名中…' : requestLabel}
        </button>
      )}
      {error ? <span className="media-error">原件签名失败</span> : null}
    </div>
  )
}

function MediaPlaceholder({
  loading,
  error,
  kind,
}: {
  loading: boolean
  error: unknown
  kind: 'IMAGE' | 'VIDEO'
}) {
  return (
    <div className="media-placeholder" role={error ? 'alert' : undefined}>
      <span aria-hidden="true">{resourceIcon(kind)}</span>
      <small>{error ? '预览加载失败' : loading ? '加载预览…' : '等待进入视口'}</small>
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

function formatDuration(durationMs: number): string {
  const seconds = Math.round(durationMs / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
