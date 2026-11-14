/**
 * composer 对 `thread/runtime` 的公开入口。
 *
 * 这里只转发 runtime（可移植 thread-panel）实际使用的成员；其余模块直接 import
 * 具体实现文件，避免维护第二套无人使用的转发路径。
 */
export {
  createAttachmentPart,
  createTextPart,
  partsToMessageContents,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
export {
  canSubmitParts,
  partForUpload,
  removePartsForUpload,
  uploadOccurrence,
  useAttachmentUploads,
  type AttachmentUpload,
  type AttachmentUploadError,
} from '@/features/thread/composer/useAttachmentUploads'
// 存储面类型经 composer 包中转：portable thread-panel 只允许依赖 composer 契约。
export type { StorageService } from '@/shared/api/storage-service'
