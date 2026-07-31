import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

export function getToolAttachmentLabel(attachment: ToolAttachment): string {
  return (
    attachment.name
    || attachment.mime
    || translate('ai.runtime.message.attachment', { type: attachment.type })
  )
}

export function toToolAttachmentSrc(attachment: ToolAttachment): string | null {
  const data = attachment.data.trim()
  if (!data) {
    return null
  }
  if (/^(data:|blob:|https?:\/\/|\/)/i.test(data)) {
    return data
  }
  const mime = attachment.mime.trim() || 'application/octet-stream'
  return `data:${mime};base64,${data.replace(/\s+/g, '')}`
}

export function formatToolAttachmentFallback(attachment: ToolAttachment): string {
  const suffix = attachment.name || attachment.mime || translate('ai.runtime.message.binaryPayload')
  return `[${attachment.type}] ${suffix}`
}
