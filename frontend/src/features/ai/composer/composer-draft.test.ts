import { describe, expect, it } from 'vitest'
import {
  clearUnknownUploads,
  composerDraftStorageKey,
  loadStoredComposerDraft,
  loadUnknownUploads,
  persistPendingUnknownUpload,
  removeStoredUnknownUpload,
  restoreComposerDraft,
  storeComposerDraft,
  storeUnknownUploads,
} from '@/features/ai/composer/composer-draft'
import {
  createAttachmentPart,
  createResourcePart,
  createTextPart,
  partsToMessageContents,
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

    expect(JSON.parse(storage.getItem(composerDraftStorageKey(scope)) ?? '')).toEqual({
      version: 1,
      parts: [{ type: 'text', text: '  first\nsecond  ' }],
    })
    expect(partsToText(loadStoredComposerDraft(scope, storage))).toBe('  first\nsecond  ')
  })

  it('round-trips ordered text and durable resources with fresh part ids', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t1'
    const source = [
      createTextPart('before'),
      createResourcePart('00000000-0000-0000-0000-000000000001', 'a.txt', ''),
      createTextPart('after'),
    ]

    storeComposerDraft(scope, source, storage)
    const restored = loadStoredComposerDraft(scope, storage)

    expect(partsToMessageContents(restored)).toEqual([
      { type: 'TEXT', text: 'before' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'a.txt',
        preview: '',
      },
      { type: 'TEXT', text: 'after' },
    ])
    expect(restored.map((part) => part.partId)).not.toEqual(
      source.map((part) => part.partId),
    )
  })

  it('round-trips resource parts with explicit imageTier', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t-tier'
    const source = [
      createResourcePart('00000000-0000-0000-0000-000000000001', 'photo.png', '', '1080P'),
      createResourcePart('00000000-0000-0000-0000-000000000002', 'orig.png', undefined, 'ORIGINAL'),
    ]

    storeComposerDraft(scope, source, storage)
    const restored = loadStoredComposerDraft(scope, storage)

    expect(partsToMessageContents(restored)).toEqual([
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'photo.png',
        preview: '',
        imageTier: '1080P',
      },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000002',
        name: 'orig.png',
        imageTier: 'ORIGINAL',
      },
    ])
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

  it('fails closed and removes malformed persisted drafts', () => {
    const storage = new MemoryStorage()
    const scope = 'thread:t1'
    storage.setItem(
      composerDraftStorageKey(scope),
      '{"version":1,"parts":[{"type":"resource","blobId":"b","name":"n","unknown":true}]}',
    )

    expect(loadStoredComposerDraft(scope, storage)).toEqual([])
    expect(storage.getItem(composerDraftStorageKey(scope))).toBeNull()
  })

  it('stores and restores unknown uploads metadata without storing File bytes', () => {
    // 测试意图：验证未知结果下的 upload 元数据能够与现有 composer draft scope 绑定持久化，reload 后可完整恢复
    const storage = new MemoryStorage()
    const scope = 'thread:t1'
    const unknownUpload = {
      localId: 'loc-1',
      uploadId: 'up-1',
      filename: 'image.png',
      mediaType: 'image/png',
      sizeBytes: 1024,
      sha256: 'abc123',
      imageTier: '1080P' as const,
    }

    storeUnknownUploads(scope, [unknownUpload], storage)
    const loaded = loadUnknownUploads(scope, storage)
    expect(loaded).toEqual([unknownUpload])

    clearUnknownUploads(scope, storage)
    expect(loadUnknownUploads(scope, storage)).toEqual([])
  })

  it('fails closed on storage quota exceeded or write errors and preserves existing data', () => {
    // 测试意图：验证存储满额或写入异常时 fail closed 抛出错误，阻止上层继续发送，且不误删已有数据
    const storage = new MemoryStorage()
    const scope = 'thread:t-quota'
    const existing = {
      localId: 'loc-0',
      uploadId: 'up-0',
      filename: 'old.png',
      mediaType: 'image/png',
      sizeBytes: 100,
      sha256: 'h0',
    }
    storeUnknownUploads(scope, [existing], storage)

    // 模拟配额满
    storage.setItem = () => {
      const err = new DOMException('The quota has been exceeded.', 'QuotaExceededError')
      throw err
    }

    const nextUpload = {
      localId: 'loc-1',
      uploadId: 'up-1',
      filename: 'new.png',
      mediaType: 'image/png',
      sizeBytes: 200,
      sha256: 'h1',
    }

    expect(() => persistPendingUnknownUpload(scope, nextUpload, storage)).toThrow(
      'The quota has been exceeded.',
    )
    // 恢复正常 setItem 检查旧数据未被 removeItem 误删
    storage.setItem = (key, val) => storage['values'].set(key, val)
    expect(loadUnknownUploads(scope, storage)).toEqual([existing])
  })

  it('throws on readback verification mismatch', () => {
    // 测试意图：验证写入后若 readback 读回内容不匹配时 fail-closed 抛错
    const storage = new MemoryStorage()
    const scope = 'thread:t-readback'
    storage.getItem = () => 'corrupted-readback'

    expect(() =>
      storeUnknownUploads(
        scope,
        [
          {
            localId: 'loc-1',
            uploadId: 'up-1',
            filename: 'test.png',
            mediaType: 'image/png',
            sizeBytes: 10,
            sha256: 'h',
          },
        ],
        storage,
      ),
    ).toThrow('readback verification failed')
  })

  it('atomically merges and removes single identity without dropping other pending unknown uploads', () => {
    // 测试意图：验证多附件场景下按 identity 原子更新与移除，一个完成绝不误删其他未知附件
    const storage = new MemoryStorage()
    const scope = 'thread:multi'

    const uploadA = {
      localId: 'loc-a',
      uploadId: 'up-a',
      filename: 'a.png',
      mediaType: 'image/png',
      sizeBytes: 100,
      sha256: 'ha',
    }
    const uploadB = {
      localId: 'loc-b',
      uploadId: 'up-b',
      filename: 'b.png',
      mediaType: 'image/png',
      sizeBytes: 200,
      sha256: 'hb',
    }

    persistPendingUnknownUpload(scope, uploadA, storage)
    persistPendingUnknownUpload(scope, uploadB, storage)

    expect(loadUnknownUploads(scope, storage)).toEqual([uploadA, uploadB])

    // 仅移除 uploadA
    removeStoredUnknownUpload(scope, 'loc-a', storage)
    expect(loadUnknownUploads(scope, storage)).toEqual([uploadB])
  })
})
