import { trimMessageParts, type ComposerPart } from '@/features/thread/composer/composer-parts'
import type { AttachmentUpload } from '@/features/thread/composer'

/** 附件 part 的上传记录与档位：草稿自带档位优先，否则取上传注册表的值。 */
function attachmentSource(
  part: Extract<ComposerPart, { type: 'attachment' }>,
  uploads: AttachmentUpload[],
) {
  const record = uploads.find(
    (upload) => upload.localId === part.uploadId || upload.uploadId === part.uploadId,
  )
  return { record, imageTier: part.imageTier ?? record?.imageTier }
}

/**
 * 提交 payload：把 attachment parts 的客户端 localId 解析为服务端 upload 句柄。
 *
 * 草稿始终引用客户端 localId；序列化前统一解析，避免「上传完成异步改写 parts」
 * 与用户编辑竞态。尚未拿到服务端句柄的条目保持原 localId。
 */
export function resolveSubmissionPayload(
  parts: ComposerPart[],
  uploads: AttachmentUpload[],
): ComposerPart[] {
  return parts.map((part) => {
    if (part.type !== 'attachment') {
      return part
    }
    const { record, imageTier } = attachmentSource(part, uploads)
    if (record?.uploadId) {
      return {
        ...part,
        uploadId: record.uploadId,
        ...(imageTier ? { imageTier } : {}),
      }
    }
    return {
      ...part,
      ...(imageTier ? { imageTier } : {}),
    }
  })
}

/**
 * 本地草稿快照（客户端 localId，trim 后）。
 *
 * 与提交 payload 分开保存：发送失败的恢复比对只可能命中本地 id 草稿。
 */
export function localDraftSnapshot(
  parts: ComposerPart[],
  uploads: AttachmentUpload[],
): ComposerPart[] {
  return trimMessageParts(
    parts.map((part) => {
      if (part.type !== 'attachment') {
        return part
      }
      const { imageTier } = attachmentSource(part, uploads)
      return {
        ...part,
        ...(imageTier ? { imageTier } : {}),
      }
    }),
  )
}
