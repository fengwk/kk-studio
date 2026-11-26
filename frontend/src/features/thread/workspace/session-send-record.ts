import { isImageTier } from '@/features/thread/runtime/state/thread-message-validation'
import type { SessionCreateDTO } from '@/shared/api/generated/contracts'
import { hasExactKeys, isRecord, nonBlank } from '@/shared/lib/value'

type SessionSend = { key: string; body: SessionCreateDTO; rejected: boolean }
export type SessionSendRecord = { value: SessionSend; raw: string }

function isMessagePart(value: unknown): boolean {
  if (!isRecord(value)) return false
  if (value.type === 'TEXT') return hasExactKeys(value, ['type', 'text']) && typeof value.text === 'string'
  const tier = Object.hasOwn(value, 'imageTier') ? ['imageTier'] : []
  if (value.imageTier != null && !isImageTier(value.imageTier)) return false
  if (value.type === 'ATTACHMENT') {
    return hasExactKeys(value, ['type', 'uploadId', ...tier]) && nonBlank(value.uploadId)
  }
  const preview = Object.hasOwn(value, 'preview') ? ['preview'] : []
  return value.type === 'RESOURCE' && hasExactKeys(value, ['type', 'blobId', 'name', ...tier, ...preview])
    && nonBlank(value.blobId) && nonBlank(value.name) && (value.preview == null || typeof value.preview === 'string')
}

function isSessionSend(value: unknown, draftId: string): value is SessionSend {
  if (!isRecord(value) || !hasExactKeys(value, ['key', 'body', 'rejected'])
    || value.key !== draftId || typeof value.rejected !== 'boolean' || !isRecord(value.body)) return false
  const { body } = value
  const { message, folderId, environmentId, modelSelection, yoloEnabled } = body
  return Object.keys(body).every((key) => ['message', 'folderId', 'environmentId', 'modelSelection', 'yoloEnabled'].includes(key))
    && Array.isArray(message) && message.length > 0 && message.every(isMessagePart)
    && (folderId == null || nonBlank(folderId)) && (environmentId == null || nonBlank(environmentId))
    && (yoloEnabled == null || typeof yoloEnabled === 'boolean')
    && (modelSelection == null || (isRecord(modelSelection) && nonBlank(modelSelection.modelName)
      && hasExactKeys(modelSelection, Object.hasOwn(modelSelection, 'variant') ? ['modelName', 'variant'] : ['modelName'])
      && (modelSelection.variant == null || typeof modelSelection.variant === 'string')))
}

export function loadSessionSend(storageKey: string, draftId: string): SessionSendRecord | null {
  const raw = sessionStorage.getItem(storageKey)
  if (raw == null) return null
  const value: unknown = JSON.parse(raw)
  if (!isSessionSend(value, draftId)) throw new Error('Invalid session send record')
  return { value, raw }
}

export function saveSessionSend(
  storageKey: string, draftId: string, previous: SessionSendRecord | null, value: SessionSend,
): SessionSendRecord {
  if (!isSessionSend(value, draftId) || sessionStorage.getItem(storageKey) !== (previous?.raw ?? null)) {
    throw new Error('Cannot replace session send record')
  }
  const raw = JSON.stringify(value)
  sessionStorage.setItem(storageKey, raw)
  if (sessionStorage.getItem(storageKey) !== raw) throw new Error('Session send record was not saved')
  return { value, raw }
}

export function clearSessionSend(storageKey: string, record: SessionSendRecord): boolean {
  if (sessionStorage.getItem(storageKey) !== record.raw) return false
  sessionStorage.removeItem(storageKey)
  return true
}
