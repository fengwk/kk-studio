import { useCallback, useEffect, type RefObject } from 'react'
import type { ComposerEditorHandle } from '@/features/thread/runtime/thread-panel/ComposerEditor'
import type { ComposerPart, ImageInputTier } from '@/features/thread/composer/composer-parts'
import type { AttachmentUpload } from '@/features/thread/composer'

/**
 * 附件图片档位（imageTier）的写回与对齐。
 *
 * 档位同时存在于上传注册表与受控草稿的 attachment part。用户改档位时先就地更新已
 * 渲染 pill 的属性，再回流草稿——整树重建会丢光标；上传完成补齐档位时同样就地写入
 * 尚未带档位的 pill，避免受控回流重排 DOM。
 */
export function useAttachmentImageTiers({
  parts,
  uploads,
  updateImageTier,
  changeDraft,
  editorApiRef,
}: {
  parts: ComposerPart[]
  uploads: AttachmentUpload[]
  updateImageTier: (localId: string, tier: ImageInputTier) => void
  changeDraft: (parts: ComposerPart[]) => void
  editorApiRef: RefObject<ComposerEditorHandle | null>
}) {
  const handleTierChange = useCallback(
    (upload: AttachmentUpload, tier: ImageInputTier) => {
      updateImageTier(upload.localId, tier)
      const next = parts.map((part) => {
        if (
          part.type === 'attachment'
          && (part.uploadId === upload.localId || (upload.uploadId && part.uploadId === upload.uploadId))
        ) {
          return { ...part, imageTier: tier }
        }
        return part
      })
      // 先就地更新已渲染 pill 的档位属性，再做受控回流；否则整树重建会丢光标。
      editorApiRef.current?.updateAttachmentImageTier(
        upload.uploadId ? [upload.localId, upload.uploadId] : [upload.localId],
        tier,
      )
      changeDraft(next)
    },
    [changeDraft, editorApiRef, parts, updateImageTier],
  )

  useEffect(() => {
    for (const part of parts) {
      if (part.type !== 'attachment' || part.imageTier) {
        continue
      }
      const upload = uploads.find((item) => item.localId === part.uploadId || item.uploadId === part.uploadId)
      if (upload?.imageTier) {
        editorApiRef.current?.updateAttachmentImageTier([part.uploadId], upload.imageTier)
      }
    }
  }, [editorApiRef, parts, uploads])

  return { handleTierChange }
}
