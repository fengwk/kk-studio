import { afterEach, describe, expect, it, vi } from 'vitest'
import { calculateFileSha256 } from './evidence-files'

const HASH_A = 'a'.repeat(64)
const HASH_B = 'b'.repeat(64)

describe('calculateFileSha256', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('reuses one worker across files and returns its real digest', async () => {
    // 连续文件必须复用同一个 Worker，且摘要来自 Worker 响应，不能是全零占位。
    const workers: MockWorker[] = []
    vi.stubGlobal('Worker', class extends MockWorker {
      constructor() {
        super()
        workers.push(this)
      }
    })
    const { calculateFileSha256: hash } = await import('./evidence-files')
    const first = hash(new File(['one'], 'one.bin'))
    workers[0]?.emit(HASH_A)
    const second = hash(new File(['two'], 'two.bin'))
    workers[0]?.emit(HASH_B)

    await expect(first).resolves.toBe(HASH_A)
    await expect(second).resolves.toBe(HASH_B)
    expect(workers).toHaveLength(1)
    expect(HASH_A).not.toBe('0'.repeat(64))
  })

  it('uses Web Crypto when Worker is unavailable and fails when neither capability exists', async () => {
    vi.stubGlobal('Worker', undefined)
    const digest = vi.fn(async () => new Uint8Array([1, 255]).buffer)
    vi.stubGlobal('crypto', { subtle: { digest } })
    const { calculateFileSha256: hash } = await import('./evidence-files')

    await expect(hash(new File(['plain'], 'plain.bin'))).resolves.toBe('01ff')
    expect(digest).toHaveBeenCalledOnce()

    vi.stubGlobal('crypto', {})
    await expect(hash(new File(['plain'], 'plain.bin'))).rejects.toThrow('SHA-256 is unavailable')
  })

  it('does not replace a failed hash with a zero digest', async () => {
    vi.stubGlobal('Worker', undefined)
    vi.stubGlobal('crypto', {
      subtle: {
        digest: vi.fn(async () => {
          throw new Error('digest failed')
        }),
      },
    })
    const { calculateFileSha256: hash } = await import('./evidence-files')

    await expect(hash(new File(['plain'], 'plain.bin'))).rejects.toThrow('digest failed')
  })

  it('keeps an injected hasher ahead of the shared worker', async () => {
    const custom = vi.fn(async () => 'custom-sha')
    await expect(calculateFileSha256(new File(['x'], 'x.bin'), custom)).resolves.toBe('custom-sha')
    expect(custom).toHaveBeenCalledOnce()
  })
})

class MockWorker {
  readonly listeners = new Map<string, Array<(event: { data?: { requestId: string; sha256: string } }) => void>>()
  private requestIds: string[] = []

  addEventListener(type: string, listener: (event: { data?: { requestId: string; sha256: string } }) => void) {
    const current = this.listeners.get(type) ?? []
    current.push(listener)
    this.listeners.set(type, current)
  }

  removeEventListener() {}

  postMessage(message: { requestId: string }) {
    this.requestIds.push(message.requestId)
  }

  terminate() {}

  emit(sha256: string) {
    const requestId = this.requestIds.shift()
    if (!requestId) {
      throw new Error('no pending hash request')
    }
    for (const listener of this.listeners.get('message') ?? []) {
      listener({ data: { requestId, sha256 } })
    }
  }
}
