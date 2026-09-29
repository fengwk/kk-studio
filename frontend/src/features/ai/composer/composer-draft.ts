import {
  createResourcePart,
  createTextPart,
  hasMessageContent,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/ai/composer/composer-parts'

const STORAGE_PREFIX = 'kkstudio.ai.composer-draft.v1:'

export type ComposerDraftChangeSource = 'edit' | 'history'

export function composerDraftStorageKey(scope: string): string {
  return `${STORAGE_PREFIX}${scope}`
}

/**
 * 浏览器可靠持久化 text + durable resource；attachment 依赖页面内上传注册表与 File，
 * 因此 attachment-bearing 草稿只在当前页面会话有效，并清理本地已持久化草稿。
 */
export function storeComposerDraft(
  scope: string,
  parts: ComposerPart[],
  storage: Storage = localStorage,
): void {
  if (!scope) {
    return
  }
  const persistable = parts.every((part) => part.type !== 'attachment')
  try {
    if (!persistable || !hasMessageContent(parts)) {
      storage.removeItem(composerDraftStorageKey(scope))
      return
    }
    storage.setItem(
      composerDraftStorageKey(scope),
      JSON.stringify({
        version: 1,
        parts: parts.map((part) => {
          if (part.type === 'text') {
            return { type: 'text', text: part.text }
          }
          return {
            type: 'resource',
            blobId: part.blobId,
            name: part.name,
            ...(part.preview !== undefined ? { preview: part.preview } : {}),
            ...(part.imageTier ? { imageTier: part.imageTier } : {}),
          }
        }),
      }),
    )
  } catch {
    // quota/set 失败时清理存储键，避免刷新后恢复出损坏或陈旧草稿。
    try {
      storage.removeItem(composerDraftStorageKey(scope))
    } catch {
      // localStorage 可能被浏览器策略整体禁用；草稿仍保留在 React 状态中。
    }
  }
}

export function clearStoredComposerDraft(
  scope: string,
  storage: Storage = localStorage,
): void {
  if (!scope) {
    return
  }
  try {
    storage.removeItem(composerDraftStorageKey(scope))
  } catch {
    // localStorage 清理是 best-effort。
  }
}

export function loadStoredComposerDraft(
  scope: string,
  storage: Storage = localStorage,
): ComposerPart[] {
  if (!scope) {
    return []
  }
  try {
    const key = composerDraftStorageKey(scope)
    const raw = storage.getItem(key)
    if (raw == null) {
      return []
    }
    const parts = parseStoredDraft(raw)
    if (!hasMessageContent(parts)) {
      storage.removeItem(key)
      return []
    }
    return parts
  } catch {
    try {
      storage.removeItem(composerDraftStorageKey(scope))
    } catch {
      // localStorage 可能被浏览器策略整体禁用。
    }
    return []
  }
}

export function restoreComposerDraft(
  scope: string,
  preferred: ComposerPart[],
  storage: Storage = localStorage,
): ComposerPart[] {
  return hasMessageContent(preferred)
    ? preferred
    : loadStoredComposerDraft(scope, storage)
}

function parseStoredDraft(raw: string): ComposerPart[] {
  const parsed: unknown = JSON.parse(raw)
  if (!isRecord(parsed) || parsed.version !== 1 || !Array.isArray(parsed.parts)) {
    throw new Error('invalid composer draft')
  }
  if (!hasExactKeys(parsed, ['version', 'parts'])) {
    throw new Error('invalid composer draft fields')
  }
  return parsed.parts.map((part) => {
    if (!isRecord(part) || typeof part.type !== 'string') {
      throw new Error('invalid composer draft part')
    }
    if (part.type === 'text') {
      if (!hasExactKeys(part, ['type', 'text']) || typeof part.text !== 'string') {
        throw new Error('invalid text draft part')
      }
      return createTextPart(part.text)
    }
    if (part.type === 'resource') {
      if (
        !hasOnlyKeys(part, ['type', 'blobId', 'name', 'preview', 'imageTier'])
        || typeof part.blobId !== 'string'
        || !part.blobId.trim()
        || typeof part.name !== 'string'
        || !part.name.trim()
        || (part.preview !== undefined && typeof part.preview !== 'string')
        || (part.imageTier !== undefined
          && part.imageTier !== '720P'
          && part.imageTier !== '1080P'
          && part.imageTier !== 'ORIGINAL')
      ) {
        throw new Error('invalid resource draft part')
      }
      return createResourcePart(part.blobId, part.name, part.preview, part.imageTier)
    }
    throw new Error('unknown composer draft part')
  })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(record: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(record).length === keys.length && hasOnlyKeys(record, keys)
}

function hasOnlyKeys(record: Record<string, unknown>, keys: string[]): boolean {
  const allowed = new Set(keys)
  return Object.keys(record).every((key) => allowed.has(key))
}

export interface StoredUnknownUpload {
  localId: string
  uploadId: string
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string | null
  imageTier?: ImageInputTier
}

export function unknownUploadsStorageKey(scope: string): string {
  return `${STORAGE_PREFIX}${scope}:unknown-uploads`
}

function safeResolveStorage(explicit?: Storage): Storage {
  if (explicit) {
    return explicit
  }
  if (typeof window !== 'undefined' && window.localStorage) {
    return window.localStorage
  }
  throw new Error('localStorage is not available in current environment')
}

/**
 * 确保存储写入并进行 readback 校验；若存储满额（QuotaExceededError）或写入失败，
 * 严格 fail closed 抛出异常，绝不吞错，更绝不在 catch 中 removeItem 误删已有数据。
 */
export function storeUnknownUploads(
  scope: string,
  uploads: StoredUnknownUpload[],
  explicitStorage?: Storage,
): void {
  if (!scope) {
    return
  }
  const storage = safeResolveStorage(explicitStorage)
  const key = unknownUploadsStorageKey(scope)
  if (uploads.length === 0) {
    storage.removeItem(key)
    return
  }
  const serialized = JSON.stringify({ version: 1, uploads })
  storage.setItem(key, serialized)
  // Readback 确保存储落盘一致性
  const readback = storage.getItem(key)
  if (readback !== serialized) {
    throw new Error(`Failed to persist unknown uploads for scope "${scope}": readback verification failed`)
  }
}

/**
 * 读取当前 scope 的未知上传。若读取抛错（例如 SecurityError），绝不吞成空数组
 * 误删已有数据，确保调用方知晓持久化层不可读。
 */
export function loadUnknownUploads(
  scope: string,
  explicitStorage?: Storage,
): StoredUnknownUpload[] {
  if (!scope) {
    return []
  }
  let storage: Storage
  try {
    storage = safeResolveStorage(explicitStorage)
  } catch {
    return []
  }
  const key = unknownUploadsStorageKey(scope)
  const raw = storage.getItem(key)
  if (!raw) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    // 明确的损坏 JSON 数据：清理坏数据以避免后续无法解析
    try {
      storage.removeItem(key)
    } catch {
      // ignore
    }
    return []
  }
  if (!isRecord(parsed) || parsed.version !== 1 || !Array.isArray(parsed.uploads)) {
    try {
      storage.removeItem(key)
    } catch {
      // ignore
    }
    return []
  }
  const validated: StoredUnknownUpload[] = []
  for (const item of parsed.uploads) {
    if (
      isRecord(item)
      && typeof item.localId === 'string' && item.localId.trim()
      && typeof item.uploadId === 'string' && item.uploadId.trim()
      && typeof item.filename === 'string' && item.filename.trim()
      && typeof item.mediaType === 'string'
      && typeof item.sizeBytes === 'number' && Number.isSafeInteger(item.sizeBytes) && item.sizeBytes >= 0
      && (item.sha256 === null || typeof item.sha256 === 'string')
      && (item.imageTier === undefined
        || item.imageTier === '720P'
        || item.imageTier === '1080P'
        || item.imageTier === 'ORIGINAL')
    ) {
      validated.push({
        localId: item.localId,
        uploadId: item.uploadId,
        filename: item.filename,
        mediaType: item.mediaType,
        sizeBytes: item.sizeBytes,
        sha256: item.sha256,
        ...(item.imageTier ? { imageTier: item.imageTier } : {}),
      })
    }
  }
  return validated
}

/**
 * 将处于完成前在途（in-flight）的未知上传按 identity 原子合并至当前 scope 列表。
 * Fail-Closed：若存储失败或满额（QuotaExceededError），直接抛出异常，
 * 阻止调用方发起 completeUpload！
 */
export function persistPendingUnknownUpload(
  scope: string,
  upload: StoredUnknownUpload,
  explicitStorage?: Storage,
): void {
  if (!scope) {
    return
  }
  const current = loadUnknownUploads(scope, explicitStorage)
  const remaining = current.filter(
    (item) => item.localId !== upload.localId && item.uploadId !== upload.uploadId,
  )
  remaining.push(upload)
  storeUnknownUploads(scope, remaining, explicitStorage)
}

/**
 * 仅移除已确认/取消的单个 identity (localId 或 uploadId)，保留同 scope 其它未知条目。
 */
export function removeStoredUnknownUpload(
  scope: string,
  identity: string,
  explicitStorage?: Storage,
): void {
  if (!scope || !identity) {
    return
  }
  try {
    const current = loadUnknownUploads(scope, explicitStorage)
    const remaining = current.filter(
      (item) => item.localId !== identity && item.uploadId !== identity,
    )
    storeUnknownUploads(scope, remaining, explicitStorage)
  } catch {
    // 移除为 best-effort
  }
}

export function clearUnknownUploads(
  scope: string,
  explicitStorage?: Storage,
): void {
  if (!scope) {
    return
  }
  try {
    const storage = safeResolveStorage(explicitStorage)
    storage.removeItem(unknownUploadsStorageKey(scope))
  } catch {
    // 容错
  }
}
