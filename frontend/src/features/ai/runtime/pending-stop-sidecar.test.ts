import { describe, expect, it } from 'vitest'
import {
  clearPendingStop,
  loadPendingStop,
  pendingStopStorageKey,
  storePendingStop,
} from '@/features/ai/runtime/pending-stop-sidecar'

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

describe('pending Stop sidecar', () => {
  it('round-trips one operation per Thread and clears explicitly', () => {
    const storage = new MemoryStorage()
    const operation = {
      stopRequestId: 'stop-1',
      expectedVersion: '7',
      requestHeadEntryId: 'head-1',
      basisVersion: '7',
    }

    storePendingStop('thread-1', operation, storage)
    expect(loadPendingStop('thread-1', storage)).toEqual(operation)
    expect(loadPendingStop('thread-2', storage)).toBeNull()

    clearPendingStop('thread-1', storage)
    expect(loadPendingStop('thread-1', storage)).toBeNull()
  })

  it('fails closed and removes malformed data', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      pendingStopStorageKey('thread-1'),
      '{"stopRequestId":"stop-1","expectedVersion":"7","requestHeadEntryId":"","basisVersion":"7"}',
    )

    expect(loadPendingStop('thread-1', storage)).toBeNull()
    expect(storage.getItem(pendingStopStorageKey('thread-1'))).toBeNull()
  })
})
