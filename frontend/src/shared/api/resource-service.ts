import { executeFetch } from '@/shared/api/client'

/** Fetch a session-owned resource through the authenticated same-origin API. */
export function fetchSessionResource(
  sessionId: string,
  resourceId: string,
  download = false,
  signal?: AbortSignal,
): Promise<Blob> {
  const path = `/api/harness/sessions/${encodeURIComponent(sessionId)}/resources/${encodeURIComponent(resourceId)}`
  const query = new URLSearchParams({ download: String(download) })
  return executeFetch<Blob>(`${path}?${query}`, {
    signal,
    responseType: 'blob',
  })
}
