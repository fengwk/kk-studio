import {
  createAttachmentPart,
  removePartsByUpload,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/thread/composer/composer-parts'
import { ApiError } from '@/shared/api/client'
import { loadUnknownUploads } from '@/features/thread/composer/composer-draft'
import { mediaKindOf } from '@/shared/lib/upload-file'

type AttachmentUploadStatus = 'uploading' | 'ready' | 'complete_unknown' | 'failed'

export interface AttachmentUploadError {
  filename: string
  reason: string
  localId: string
}

/** 单个上传注册表条目；localId 是客户端稳定 id，uploadId 在 reserve 后填充且 complete 后不变。 */
export interface AttachmentUpload {
  localId: string
  /** 服务端 upload 句柄：USER_MESSAGE ATTACHMENT 引用它，释放也删除它。 */
  uploadId: string | null
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string | null
  status: AttachmentUploadStatus
  progress: number
  /** 本地图片/视频预览 URL；只在 Composer 生命周期内存在。 */
  previewUrl: string | null
  /** 提交后等待发送结果期间隐藏（发送失败恢复时重新挂载）。 */
  detached: boolean
  /** 图片输入档位（仅图片有效；读取系统默认设置；其它媒体为 undefined）。 */
  imageTier?: ImageInputTier
  format?: string
  originalWidth?: number
  originalHeight?: number
  storedWidth?: number
  storedHeight?: number
  /** 原始文件仅在当前 composer 生命周期内可用时才能支持重新缩放。 */
  sourceFileAvailable?: boolean
}

/** 服务端是否可能已经接受了这次调用：只有确定未被接受的错误才允许丢弃句柄。 */
export function isAmbiguousUploadOutcome(error: unknown): boolean {
  if (error instanceof ApiError) {
    if (error.status === undefined || error.status === 0) {
      return true
    }
    if (error.status === 408 || error.status === 429) {
      return true
    }
    if (error.status >= 500 && error.status <= 599) {
      return true
    }
    return false
  }
  if (error instanceof TypeError) {
    return true
  }
  return false
}

/** 记录在 parts 中的出现次数（localId 或服务端 upload 句柄均可匹配）。 */
export function uploadOccurrence(upload: AttachmentUpload, parts: ComposerPart[]): number {
  return parts.reduce(
    (count, part) =>
      part.type === 'attachment'
      && (part.uploadId === upload.localId || part.uploadId === upload.uploadId)
        ? count + 1
        : count,
    0,
  )
}

/** 创建引用该上传的 attachment part（上传完成前以 localId 占位）。 */
export function partForUpload(upload: AttachmentUpload): ComposerPart {
  return createAttachmentPart(upload.localId, upload.filename, upload.imageTier)
}

/** 移除引用指定上传的全部 attachment parts（上传注册表 X）。 */
export function removePartsForUpload(
  upload: AttachmentUpload,
  parts: ComposerPart[],
): ComposerPart[] {
  return removePartsByUpload(parts, new Set([upload.localId, upload.uploadId ?? '']))
}

/** 该消息是否可发送：有内容，且所有 attachment parts 对应的上传均已 ready。 */
export function canSubmitParts(parts: ComposerPart[], uploads: AttachmentUpload[]): boolean {
  const hasContent = parts.some((part) => part.type !== 'text' || part.text.trim() !== '')
  if (!hasContent) {
    return false
  }
  for (const part of parts) {
    if (part.type !== 'attachment') {
      continue
    }
    const record = uploads.find(
      (upload) => upload.localId === part.uploadId || upload.uploadId === part.uploadId,
    )
    if (record?.status !== 'ready') {
      return false
    }
  }
  return true
}

/** scope 上次遗留的“结果未知”上传：句柄仍有效，等待用户重试 complete；只恢复元数据。 */
export function unknownUploadsAsRecords(scope: string | undefined): AttachmentUpload[] {
  if (!scope) {
    return []
  }
  return loadUnknownUploads(scope).map((item) => ({
    localId: item.localId,
    uploadId: item.uploadId,
    filename: item.filename,
    mediaType: item.mediaType,
    sizeBytes: item.sizeBytes,
    sha256: item.sha256,
    status: 'complete_unknown' as const,
    progress: 0.8,
    previewUrl: null,
    detached: false,
    ...(item.imageTier ? { imageTier: item.imageTier } : {}),
  }))
}

/** 本地可显示/可播放的临时 URL；浏览器不支持 Blob URL 或创建失败时返回 null。 */
export function createPreviewUrl(file: File): string | null {
  const kind = mediaKindOf(file.type)
  if (
    (kind !== 'image' && kind !== 'video')
    || typeof URL === 'undefined'
    || typeof URL.createObjectURL !== 'function'
  ) {
    return null
  }
  try {
    return URL.createObjectURL(file)
  } catch {
    return null
  }
}

export function revokePreviewUrl(localId: string, urls: Map<string, string>): void {
  const url = urls.get(localId)
  if (!url) {
    return
  }
  urls.delete(localId)
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
    try {
      URL.revokeObjectURL(url)
    } catch {
      // 预览释放失败不影响上传句柄清理。
    }
  }
}
