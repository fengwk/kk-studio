import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

export function getToolAttachmentLabel(attachment: ToolAttachment): string {
  return (
    attachment.name
    || attachment.mime
    || translate('ai.runtime.message.attachment', { type: attachment.type })
  )
}

/** UI 视作安全的链接/预览目标的资源 scheme。 */
const RESOURCE_URI_PATTERN = /^(data:|file:|s3:|https?:\/\/)/i
const DIRECT_RESOURCE_URI_PATTERN = /^(data:|https?:\/\/)/i
const MANAGED_RESOURCE_URI_PATTERN = /^(file:|s3:)/i

/**
 * 当 URI 是内联 data: 资源时可自动预览。远程 http(s) 与本地
 * file:/s3: 资源绝不会被放进 media src：不可信的 Tool 资源会让
 * 浏览器发出自动 GET 请求（远程/本地网络）。它们仅以稳定
 * URI 文本 + 显式链接的形式渲染。
 */
export function isPreviewableAttachment(attachment: ToolAttachment): boolean {
  return /^data:/i.test(attachment.data.trim())
}

/** 当 URI 属于规范的 data/file/s3/http/https 资源引用时返回 true。 */
export function isCanonicalResourceUri(uri: string): boolean {
  return RESOURCE_URI_PATTERN.test(uri.trim())
}

/**
 * 仅 data: 资源可以作为可渲染的 media src。http(s)/file:/s3: 绝不会被内联：
 * 稳定 URI 仅以文本 + 显式链接的形式展示（不会触发自动远程/本地 GET）。
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

/**
 * 返回完整资源链接。data/http(s) 保持显式直连；file/s3 只按内容身份走同源 managed-resource 下载，
 * 绝不把宿主文件 URI 直接交给浏览器。
 */
export function getToolAttachmentHref(attachment: ToolAttachment): string | null {
  const uri = attachment.data.trim()
  if (!uri || !isCanonicalResourceUri(uri)) {
    return null
  }
  if (DIRECT_RESOURCE_URI_PATTERN.test(uri)) {
    return uri
  }
  if (MANAGED_RESOURCE_URI_PATTERN.test(uri)) {
    return attachment.downloadHref?.trim() || null
  }
  return null
}

export function formatToolAttachmentFallback(attachment: ToolAttachment): string {
  const suffix = attachment.name || attachment.mime || translate('ai.runtime.message.binaryPayload')
  return `[${attachment.type}] ${suffix}`
}
