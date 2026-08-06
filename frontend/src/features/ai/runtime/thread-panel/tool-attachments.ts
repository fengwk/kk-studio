import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

export function getToolAttachmentLabel(attachment: ToolAttachment): string {
  return (
    attachment.name
    || attachment.mime
    || translate('ai.runtime.message.attachment', { type: attachment.type })
  )
}

/** Canonical resource schemes the UI treats as safe link/preview targets. */
const RESOURCE_URI_PATTERN = /^(data:|file:|s3:|https?:\/\/)/i

/**
 * Auto-previewable when the URI is an inline data: resource. Remote http(s) and local
 * file:/s3: resources are NEVER placed in media src: an untrusted Tool Resource would make
 * the browser issue automatic GET requests (remote/local network). They render as a stable
 * URI text + explicit link instead.
 */
export function isPreviewableAttachment(attachment: ToolAttachment): boolean {
  return /^data:/i.test(attachment.data.trim())
}

/** True when the URI is a canonical data/file/s3/http/https resource reference. */
export function isCanonicalResourceUri(uri: string): boolean {
  return RESOURCE_URI_PATTERN.test(uri.trim())
}

/**
 * Renderable media src for data: resources only. http(s)/file:/s3: are never inlined: the
 * stable URI is shown as text with an explicit link instead (no automatic remote/local GET).
 */
export function toToolAttachmentSrc(attachment: ToolAttachment): string | null {
  const uri = attachment.data.trim()
  if (!uri) {
    return null
  }
  if (isPreviewableAttachment(attachment)) {
    return uri
  }
  return null
}

/** The stable URI as a link target; only canonical data/file/s3/http/https schemes qualify. */
export function getToolAttachmentHref(attachment: ToolAttachment): string | null {
  const uri = attachment.data.trim()
  if (!uri || !isCanonicalResourceUri(uri)) {
    return null
  }
  return uri
}

export function formatToolAttachmentFallback(attachment: ToolAttachment): string {
  const suffix = attachment.name || attachment.mime || translate('ai.runtime.message.binaryPayload')
  return `[${attachment.type}] ${suffix}`
}
