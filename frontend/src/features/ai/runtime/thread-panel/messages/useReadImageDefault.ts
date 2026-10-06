import { useContext, useEffect, useState } from 'react'
import { ResourceBlobUrlContext } from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import type {
  ToolAttachment,
  ToolContent,
} from '@/features/ai/runtime/thread-timeline-types'

/** 权威 MIME 是否为图片；绝不用扩展名或 URL 猜测。 */
export function isImageMime(mime: string | null | undefined): boolean {
  return typeof mime === 'string' && mime.trim().toLowerCase().startsWith('image/')
}

function resourceAttachments(contents: ToolContent[]): ToolAttachment[] {
  return contents.flatMap((content) => content.type === 'resource' ? [content.attachment] : [])
}

/**
 * read 结果是否默认展示图片预览。
 *
 * 权威 MIME 优先取 resource 自带的 mediaType（URI 资源），其次取 storage_blob 的
 * 渲染期解析结果（durable RESOURCE 不携带 mediaType）；两者都未知时保持收起，
 * 绝不用文件名或 URL 猜测。只有 read 图片默认展开，其他工具/MCP 的结果默认折叠，
 * 未知附件也不例外。
 */
export function useReadImageDefault(toolName: string, contents: ToolContent[]): boolean {
  const resolveBlobUrls = useContext(ResourceBlobUrlContext)
  const isRead = toolName.trim().toLowerCase() === 'read'
  const attachments = isRead ? resourceAttachments(contents) : []
  const inlineMime = attachments.find((attachment) => attachment.mime.trim())?.mime ?? null
  const blobId = !inlineMime && attachments.some((attachment) => attachment.blobId)
    ? attachments.find((attachment) => attachment.blobId)?.blobId ?? null
    : null
  const [resolvedMime, setResolvedMime] = useState<string | null>(null)

  useEffect(() => {
    setResolvedMime(null)
    if (!blobId || !resolveBlobUrls) {
      return
    }
    let cancelled = false
    resolveBlobUrls(blobId)
      .then((urls) => {
        // 解析失败（null）视为未知 MIME：保持收起，不猜测类型。
        if (!cancelled) {
          setResolvedMime(urls?.mediaType ?? '')
        }
      })
      .catch(() => {
        if (!cancelled) {
          setResolvedMime('')
        }
      })
    return () => {
      cancelled = true
    }
  }, [blobId, resolveBlobUrls])

  if (!isRead) {
    return false
  }
  // blob 的权威 MIME 解析完成前不提前展开，避免先展开再收起。
  return isImageMime(inlineMime ?? (resolvedMime === '' ? null : resolvedMime))
}
