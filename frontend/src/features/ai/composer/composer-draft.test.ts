import { describe, expect, it } from 'vitest'
import {
  composerDraftStorageKey,
  loadStoredComposerDraft,
  restoreComposerDraft,
  storeComposerDraft,
} from '@/features/ai/composer/composer-draft'
import {
  createAttachmentPart,
  createTextPart,
  partsToText,
} from '@/features/ai/composer/composer-parts'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()

  get length(): number {
    return this.values.size
  }

  clear(): void {
    this.values.clear()
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }
}

describe('composer draft storage', () => {
  it('round-trips the exact non-empty text draft', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t1'

    // 前后空白和换行属于用户草稿，持久化后必须逐字恢复。
    storeComposerDraft(scope, [createTextPart('  first\nsecond  ')], storage)

    expect(storage.getItem(composerDraftStorageKey(scope))).toBe('  first\nsecond  ')
    expect(partsToText(loadStoredComposerDraft(scope, storage))).toBe('  first\nsecond  ')
  })

  it('clears storage for blank or attachment-bearing drafts', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t1'
    storeComposerDraft(scope, [createTextPart('stale')], storage)

    // File 与上传注册表不可从 localStorage 安全恢复，不能留下不完整的文本影子。
    storeComposerDraft(
      scope,
      [
        createTextPart('with file'),
        createAttachmentPart('upload-1', 'a.txt'),
      ],
      storage,
    )
    expect(storage.getItem(composerDraftStorageKey(scope))).toBeNull()

    storeComposerDraft(scope, [createTextPart(' \n ')], storage)
    expect(storage.getItem(composerDraftStorageKey(scope))).toBeNull()
  })

  it('prefers an explicit recovery draft over a stored draft', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t1'
    storeComposerDraft(scope, [createTextPart('stored')], storage)

    // 发送失败恢复是更强的当前状态，不能被上一次浏览器草稿覆盖。
    expect(
      partsToText(
        restoreComposerDraft(scope, [createTextPart('recovered')], storage),
      ),
    ).toBe('recovered')
    expect(partsToText(restoreComposerDraft(scope, [], storage))).toBe('stored')
  })
})
