import { useContext, useEffect, useState } from 'react'
import { fetchSessionResource } from '@/shared/api/resource-service'
import { ApiError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import {
  ResourceOpenInIdeContext,
  ResourceSessionContext,
} from '@/shared/ui/markdown/ResourceUriContext'

const FILE_RESOURCE_URI = /^resource:\/\/files\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/

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
  if (resourceId == null || sessionId == null) {
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
  const [src, setSrc] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    const controller = new AbortController()
    void fetchSessionResource(sessionId, resourceId, false, controller.signal).then((blob) => {
      if (active) {
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      }
    }).catch(() => {
      if (active) setFailed(true)
    })
    return () => {
      active = false
      controller.abort()
      if (objectUrl != null) URL.revokeObjectURL(objectUrl)
    }
  }, [sessionId, resourceId])
  if (failed) return <span role="status">{t('ai.resource.previewFailed')}</span>
  return src == null
    ? <span role="status">{t('ai.resource.loading')}</span>
    : <img src={src} alt={label} />
}

function ResourceLink({ sessionId, resourceId, label, openInIde }: {
  sessionId: string
  resourceId: string
  label: string
  openInIde?: (sessionId: string, resourceId: string) => Promise<void>
}) {
  const { t } = useI18n()
  const [preview, setPreview] = useState<Blob | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [openingInIde, setOpeningInIde] = useState(false)
  const [openError, setOpenError] = useState<'failed' | 'tooLarge' | 'unsupported' | null>(null)
  const filename = label.replace(/[\\/:*?"<>|]/g, '_') || 'resource'

  async function loadPreview() {
    setBusy(true)
    setError(false)
    try {
      setPreview(await fetchSessionResource(sessionId, resourceId, false))
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
      const blob = await fetchSessionResource(sessionId, resourceId, true)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filename
      anchor.click()
      URL.revokeObjectURL(url)
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
      {preview != null ? <ResourcePreview blob={preview} label={label} /> : null}
    </span>
  )
}

function ResourcePreview({ blob, label }: { blob: Blob; label: string }) {
  const { t } = useI18n()
  const [text, setText] = useState<string | null>(null)
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const isImage = blob.type.startsWith('image/')
  const isText = blob.type.startsWith('text/') || blob.type.includes('json') || blob.type.includes('xml')
  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    setText(null)
    setImageUrl(null)
    if (isImage) {
      objectUrl = URL.createObjectURL(blob)
      setImageUrl(objectUrl)
    } else if (isText) {
      void blob.text().then((value) => {
        if (active) setText(value)
      })
    }
    return () => {
      active = false
      if (objectUrl != null) URL.revokeObjectURL(objectUrl)
    }
  }, [blob, isImage, isText])
  if (isImage) {
    return imageUrl == null
      ? <span role="status">{t('ai.resource.loading')}</span>
      : <img src={imageUrl} alt={label} />
  }
  if (text != null) return <span className="md-resource-text-preview">{text}</span>
  return <span role="status">{t('ai.resource.previewUnsupported')}</span>
}
