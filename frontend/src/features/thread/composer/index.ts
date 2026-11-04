export {
  createAttachmentPart,
  createPartId,
  createResourcePart,
  createTextPart,
  hasMessageContent,
  mergeTextParts,
  partsKey,
  partsToMessageContents,
  partsToText,
  removePartsByIds,
  removePartsByUpload,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/thread/composer/composer-parts'
export {
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  findAdjacentPill,
  insertPillsAtCaret,
  insertTextAtCaret,
  isPillElement,
  normalizeEditorDom,
  placeCaretAtEnd,
  renderPartsToEditor,
} from '@/features/thread/composer/composer-dom'
export { AttachmentStrip } from '@/features/thread/composer/attachment-strip'
export {
  canSubmitParts,
  partForUpload,
  removePartsForUpload,
  uploadOccurrence,
  useAttachmentUploads,
  type AttachmentUpload,
  type AttachmentUploadError,
  type AttachmentUploadStatus,
} from '@/features/thread/composer/use-attachment-uploads'
// 存储面类型经 composer 包中转：portable thread-panel 只允许依赖 composer 契约。
export type { StorageService } from '@/shared/api/storage-service'
