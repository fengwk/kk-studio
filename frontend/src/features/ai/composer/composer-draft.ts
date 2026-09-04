import {
  createResourcePart,
  createTextPart,
  hasMessageContent,
  type ComposerPart,
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
        !hasOnlyKeys(part, ['type', 'blobId', 'name', 'preview'])
        || typeof part.blobId !== 'string'
        || !part.blobId.trim()
        || typeof part.name !== 'string'
        || !part.name.trim()
        || (part.preview !== undefined && typeof part.preview !== 'string')
      ) {
        throw new Error('invalid resource draft part')
      }
      return createResourcePart(part.blobId, part.name, part.preview)
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
