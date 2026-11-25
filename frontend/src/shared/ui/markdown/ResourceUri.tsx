import { useContext, useEffect, useState } from 'react'
import { ApiError } from '@/shared/api/client'
import type { StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'
import {
  getSessionBlobDownloadUrl,
  getSessionBlobPreviewUrl,
} from '@/shared/api/resource-service'
import { FORBIDDEN_UPLOAD_HEADERS } from '@/shared/api/storage-service'
import { useI18n } from '@/shared/i18n'
import {
  ResourceOpenInIdeContext,
  ResourceSessionContext,
} from '@/shared/ui/markdown/ResourceUriContext'

const FILE_RESOURCE_URI = /^resource:\/\/files\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/

type ResourcePreviewState =
  | { kind: 'image'; previewUrl: string | null; originalUrl: string }
  | { kind: 'text'; text: string }
  | { kind: 'unsupported' }

function isImageMediaType(mediaType: string): boolean {
  return mediaType.startsWith('image/')
}

function isTextMediaType(mediaType: string): boolean {
  return mediaType.startsWith('text/') || mediaType.includes('json') || mediaType.includes('xml')
}

async function fetchSignedText(
  signed: StoragePresignedUrlDTO,
  signal?: AbortSignal,
): Promise<string> {
  const headers: Record<string, string> = {}
  for (const [name, value] of Object.entries(signed.headers ?? {})) {
    if (!FORBIDDEN_UPLOAD_HEADERS.has(name.toLowerCase())) {
      headers[name] = value
    }
  }
  const response = await fetch(signed.url, {
    method: signed.method,
    headers,
    signal,
  })
  if (!response.ok) {
    throw new Error(`Resource fetch failed with HTTP ${response.status}`)
  }
  return response.text()
}

/** A private resource URI is never handed to the browser as a navigable URL. */
export function ResourceUri({ uri, label, image = false }: {
  uri: string
  label?: string
  image?: boolean
}) {
  const sessionId = useContext(ResourceSessionContext)
  const openInIde = useContext(ResourceOpenInIdeContext)
  const { t } = useI18n()
  const match = FILE_RESOURCE_URI.exec(uri)
  const resourceId = match?.[1]

  if (uri.startsWith('resource://skills/')) {
    return <span className="md-resource-unavailable">{label ?? uri} — {t('ai.resource.unsupported')}</span>
  }
  if (resourceId == null || !sessionId) {
    return <span className="md-resource-unavailable">{label ?? uri} — {t('ai.resource.unavailable')}</span>
  }
  return image
    ? <ResourceImage key={`${sessionId}:${resourceId}`} sessionId={sessionId} resourceId={resourceId} label={label ?? t('ai.resource.image')} />
    : <ResourceLink
        key={`${sessionId}:${resourceId}`}
        sessionId={sessionId}
        resourceId={resourceId}
        label={label ?? uri}
        openInIde={openInIde?.open}
      />
}

function ResourceImage({ sessionId, resourceId, label }: { sessionId: string; resourceId: string; label: string }) {
  const { t } = useI18n()
  const [urls, setUrls] = useState<{ original: string; preview: string | null } | null>(null)
  const [useOriginal, setUseOriginal] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    const controller = new AbortController()
    setUrls(null)
    setUseOriginal(false)
    setFailed(false)
    void Promise.all([
      getSessionBlobDownloadUrl(sessionId, resourceId, { signal: controller.signal }),
      getSessionBlobPreviewUrl(sessionId, resourceId, { signal: controller.signal }).catch(() => null),
    ]).then(([original, preview]) => {
      if (!active) return
      if (!original.url.trim()) {
        setFailed(true)
        return
      }
      setUrls({
        original: original.url,
        preview: preview?.url?.trim() || null,
      })
    }).catch(() => {
      if (active) setFailed(true)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [sessionId, resourceId])

  if (failed) return <span role="status">{t('ai.resource.previewFailed')}</span>
  if (urls == null) return <span role="status">{t('ai.resource.loading')}</span>
  const src = useOriginal ? urls.original : (urls.preview ?? urls.original)
  return (
    <img
      src={src}
      alt={label}
      onError={() => {
        if (!useOriginal && urls.preview && src !== urls.original) {
          setUseOriginal(true)
        } else {
          setFailed(true)
        }
      }}
    />
  )
}

function ResourceLink({ sessionId, resourceId, label, openInIde }: {
  sessionId: string
  resourceId: string
  label: string
  openInIde?: (sessionId: string, resourceId: string) => Promise<void>
}) {
  const { t } = useI18n()
  const [preview, setPreview] = useState<ResourcePreviewState | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [openingInIde, setOpeningInIde] = useState(false)
  const [openError, setOpenError] = useState<'failed' | 'tooLarge' | 'unsupported' | null>(null)
  const filename = label.replace(/[\\/:*?"<>|]/g, '_') || 'resource'

  async function loadPreview() {
    setBusy(true)
    setError(false)
    try {
      const original = await getSessionBlobDownloadUrl(sessionId, resourceId)
      if (!original.url.trim()) {
        setError(true)
        return
      }
      const mediaType = original.mediaType?.trim() ?? ''
      if (isImageMediaType(mediaType)) {
        const previewSigned = await getSessionBlobPreviewUrl(sessionId, resourceId).catch(() => null)
        setPreview({
          kind: 'image',
          previewUrl: previewSigned?.url?.trim() || null,
          originalUrl: original.url,
        })
      } else if (isTextMediaType(mediaType)) {
        const text = await fetchSignedText(original)
        setPreview({ kind: 'text', text })
      } else {
        setPreview({ kind: 'unsupported' })
      }
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  async function openAttachmentInIde() {
    if (openInIde == null) return
    setOpeningInIde(true)
    setOpenError(null)
    try {
      await openInIde(sessionId, resourceId)
    } catch (error) {
      setOpenError(error instanceof ApiError && error.status === 413
        ? 'tooLarge'
        : error instanceof ApiError && (error.status === 415 || error.status === 422)
          ? 'unsupported'
          : 'failed')
    } finally {
      setOpeningInIde(false)
    }
  }

  async function download() {
    setBusy(true)
    setError(false)
    try {
      const original = await getSessionBlobDownloadUrl(sessionId, resourceId)
      if (!original.url.trim()) {
        setError(true)
        return
      }
      const anchor = document.createElement('a')
      anchor.href = original.url
      anchor.rel = 'noreferrer'
      anchor.download = filename
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <span className="md-resource-link">
      <span>{label}</span>{' '}
      <button type="button" onClick={() => void loadPreview()} disabled={busy}>{t('ai.resource.preview')}</button>{' '}
      <button type="button" onClick={() => void download()} disabled={busy}>{t('ai.resource.download')}</button>
      {openInIde != null ? (
        <button
          type="button"
          onClick={() => void openAttachmentInIde()}
          disabled={openingInIde}
          title={t('ai.resource.openInIdeLimit')}
        >
          {openingInIde ? t('ai.resource.openInIdePending') : t('ai.resource.openInIde')}
        </button>
      ) : null}
      {busy ? <span role="status">{t('ai.resource.loading')}</span> : null}
      {openingInIde ? <span role="status">{t('ai.resource.openInIdePending')}</span> : null}
      {error ? <span role="alert">{t('ai.resource.previewFailed')}</span> : null}
      {openError != null ? (
        <span role="alert">{t(`ai.resource.openInIde.${openError}`)}</span>
      ) : null}
      {preview != null ? <ResourcePreview preview={preview} label={label} /> : null}
    </span>
  )
}

function ResourcePreview({ preview, label }: { preview: ResourcePreviewState; label: string }) {
  const { t } = useI18n()
  const [useOriginal, setUseOriginal] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setUseOriginal(false)
    setFailed(false)
  }, [preview])

  if (preview.kind === 'image') {
    if (failed) return <span role="status">{t('ai.resource.previewFailed')}</span>
    const src = useOriginal ? preview.originalUrl : (preview.previewUrl ?? preview.originalUrl)
    return (
      <img
        src={src}
        alt={label}
        onError={() => {
          if (!useOriginal && preview.previewUrl && src !== preview.originalUrl) {
            setUseOriginal(true)
          } else {
            setFailed(true)
          }
        }}
      />
    )
  }
  if (preview.kind === 'text') return <span className="md-resource-text-preview">{preview.text}</span>
  return <span role="status">{t('ai.resource.previewUnsupported')}</span>
}
