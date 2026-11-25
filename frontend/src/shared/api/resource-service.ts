import { request } from '@/shared/api/client'
import type { StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'

export interface SessionResourceRequestOptions {
  signal?: AbortSignal
}

export interface SessionBlobUrls {
  original: string
  preview: string | null
  mediaType: string
  sizeBytes: string | null
}

/** Sign the original object download URL for a session-held blob. */
export function getSessionBlobDownloadUrl(
  sessionId: string,
  blobId: string,
  options?: SessionResourceRequestOptions,
): Promise<StoragePresignedUrlDTO> {
  return request(
    'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/download-url',
    { path: { sessionId, blobId } },
    { signal: options?.signal },
  )
}

/** Sign the derived preview URL for a session-held blob. */
export function getSessionBlobPreviewUrl(
  sessionId: string,
  blobId: string,
  options?: SessionResourceRequestOptions,
): Promise<StoragePresignedUrlDTO> {
  return request(
    'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/preview-url',
    { path: { sessionId, blobId } },
    { signal: options?.signal },
  )
}

/** Resolve signed original and optional preview URLs for a session-held blob. */
export async function resolveSessionBlobUrls(
  sessionId: string,
  blobId: string,
  options?: SessionResourceRequestOptions,
): Promise<SessionBlobUrls | null> {
  const [original, preview] = await Promise.all([
    getSessionBlobDownloadUrl(sessionId, blobId, options).catch(() => null),
    getSessionBlobPreviewUrl(sessionId, blobId, options).catch(() => null),
  ])
  if (
    original == null
    || !original.url.trim()
    || !original.mediaType?.trim()
  ) {
    return null
  }
  return {
    original: original.url,
    preview: preview?.url?.trim() || null,
    mediaType: original.mediaType,
    sizeBytes: original.sizeBytes,
  }
}
