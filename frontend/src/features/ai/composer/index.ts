export {
  createAttachmentPart,
  createPartId,
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
} from '@/features/ai/composer/composer-parts'
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
} from '@/features/ai/composer/composer-dom'
export { AttachmentStrip } from '@/features/ai/composer/attachment-strip'
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
  type AttachmentUploadStatus,
  type HashFile,
  type UploadLimits,
} from '@/features/ai/composer/use-attachment-uploads'
// 存储面类型经 composer 包中转：portable thread-panel 只允许依赖 composer 契约。
export type { StorageService } from '@/shared/api/storage-service'
