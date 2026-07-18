import type { ToolAttachment } from '@/features/ai/thread-events'

export function getToolAttachmentLabel(attachment: ToolAttachment): string {
  return attachment.name || attachment.mime || `${attachment.type} attachment`
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
  const suffix = attachment.name || attachment.mime || 'binary payload'
  return `[${attachment.type}] ${suffix}`
}
