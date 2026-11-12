import { useEffect, useState } from 'react'
import { fetchSessionResource } from '@/shared/api/resource-service'
import { useI18n } from '@/shared/i18n'
import { ResourceSessionContext } from '@/shared/ui/markdown/ResourceUriContext'
import { useContext } from 'react'

const FILE_RESOURCE_URI = /^resource:\/\/files\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i

/** A private resource URI is never handed to the browser as a navigable URL. */
export function ResourceUri({ uri, label, image = false }: {
  uri: string
  label?: string
  image?: boolean
}) {
  const sessionId = useContext(ResourceSessionContext)
  const { t } = useI18n()
  const match = FILE_RESOURCE_URI.exec(uri)
  const blobId = match?.[1]

  if (uri.startsWith('resource://skills/')) {
    return <span className="md-resource-unavailable">{label ?? uri} — {t('ai.resource.unsupported')}</span>
  }
  if (blobId == null || sessionId == null) {
    return <span className="md-resource-unavailable">{label ?? uri} — {t('ai.resource.unavailable')}</span>
  }
  return image
    ? <ResourceImage sessionId={sessionId} blobId={blobId} label={label ?? t('ai.resource.image')} />
    : <ResourceLink sessionId={sessionId} blobId={blobId} label={label ?? uri} />
}

function ResourceImage({ sessionId, blobId, label }: { sessionId: string; blobId: string; label: string }) {
  const { t } = useI18n()
  const [src, setSrc] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    const controller = new AbortController()
    void fetchSessionResource(sessionId, blobId, false, controller.signal).then((blob) => {
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
  }, [sessionId, blobId])
  if (failed) return <span role="status">{t('ai.resource.previewFailed')}</span>
  return src == null
    ? <span role="status">{t('ai.resource.loading')}</span>
    : <img src={src} alt={label} />
}

function ResourceLink({ sessionId, blobId, label }: { sessionId: string; blobId: string; label: string }) {
  const { t } = useI18n()
  const [preview, setPreview] = useState<Blob | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const filename = label.replace(/[\\/:*?"<>|]/g, '_') || 'resource'

  async function loadPreview() {
    setBusy(true)
    setError(false)
    try {
      setPreview(await fetchSessionResource(sessionId, blobId, false))
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }
  async function download() {
    setBusy(true)
    setError(false)
    try {
      const blob = await fetchSessionResource(sessionId, blobId, true)
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
      {busy ? <span role="status">{t('ai.resource.loading')}</span> : null}
      {error ? <span role="alert">{t('ai.resource.previewFailed')}</span> : null}
      {preview != null ? <ResourcePreview blob={preview} label={label} /> : null}
    </span>
  )
}

function ResourcePreview({ blob, label }: { blob: Blob; label: string }) {
  const { t } = useI18n()
  const [text, setText] = useState<string | null>(null)
  useEffect(() => {
    if (blob.type.startsWith('text/') || blob.type.includes('json') || blob.type.includes('xml')) {
      void blob.text().then(setText)
    }
  }, [blob])
  if (blob.type.startsWith('image/')) {
    return <img src={URL.createObjectURL(blob)} alt={label} />
  }
  if (text != null) return <pre>{text}</pre>
  return <span role="status">{t('ai.resource.previewUnsupported')}</span>
}
