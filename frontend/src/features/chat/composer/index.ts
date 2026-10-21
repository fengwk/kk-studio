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
} from '@/features/chat/composer/composer-parts'
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
} from '@/features/chat/composer/composer-dom'
export { AttachmentStrip } from '@/features/chat/composer/attachment-strip'
export {
  canSubmitParts,
  createWorkerHasher,
  formatFileSize,
  mediaKindOf,
  partForUpload,
  removePartsForUpload,
  UPLOAD_LIMITS,
  uploadOccurrence,
  useAttachmentUploads,
  validateUploadFile,
  type AttachmentUpload,
  type AttachmentUploadError,
  type AttachmentUploadStatus,
  type HashFile,
  type UploadLimits,
} from '@/features/chat/composer/use-attachment-uploads'
// 存储面类型经 composer 包中转：portable thread-panel 只允许依赖 composer 契约。
export type { StorageService } from '@/shared/api/storage-service'
