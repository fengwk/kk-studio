import {
  createTextPart,
  hasMessageContent,
  partsToText,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'

const STORAGE_PREFIX = 'kkstudio.ai.composer-draft.v1:'

export type ComposerDraftChangeSource = 'edit' | 'history'

export function composerDraftStorageKey(scope: string): string {
  return `${STORAGE_PREFIX}${scope}`
}

/**
 * 浏览器只能可靠恢复纯文本草稿；附件上传注册表与 File 不可持久化，因此附件草稿
 * 保持当前页面会话内有效，并清理旧的纯文本持久值，避免刷新后恢复错误内容。
 */
export function storeComposerDraft(
  scope: string,
  parts: ComposerPart[],
  storage: Storage = localStorage,
): void {
  if (!scope) {
    return
  }
  const textOnly = parts.every((part) => part.type === 'text')
  const text = textOnly ? partsToText(parts) : ''
  try {
    if (!textOnly || text.trim() === '') {
      storage.removeItem(composerDraftStorageKey(scope))
      return
    }
    storage.setItem(composerDraftStorageKey(scope), text)
  } catch {
    // quota/set 失败时移除旧值，避免刷新后恢复成过期草稿。
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
    const text = storage.getItem(composerDraftStorageKey(scope))
    return text != null && text.trim() !== '' ? [createTextPart(text)] : []
  } catch {
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
