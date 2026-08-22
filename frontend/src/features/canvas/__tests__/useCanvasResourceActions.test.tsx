import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceActions } from '@/features/canvas/useCanvasResourceActions'
import { getCanvasResourceOriginalUrl } from '@/shared/api/studio-service'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const RESOURCE_ID = 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(),
}))

function resource(overrides: Partial<Resource> = {}): Resource {
  return {
    id: RESOURCE_ID,
    canvasId: CANVAS_ID,
    ownerNodeId: '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a',
    resourceIndex: 0,
    blobId: 'blob-asset',
    name: 'image.png',
    textContent: null,
    kind: 'IMAGE',
    mediaType: 'image/png',
    sizeBytes: 3,
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** 收集被触发 anchor 的 props；jsdom 不会真正导航。 */
function anchorCalls() {
  return vi.mocked(HTMLAnchorElement.prototype.click).mock.calls.map(() => {
    const anchor = vi.mocked(HTMLAnchorElement.prototype.click).mock.instances[
      vi.mocked(HTMLAnchorElement.prototype.click).mock.instances.length - 1
    ] as HTMLAnchorElement
    return { href: anchor.href, target: anchor.target, download: anchor.download }
  })
}

describe('useCanvasResourceActions', () => {
  beforeEach(() => {
    vi.mocked(getCanvasResourceOriginalUrl).mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens the signed original in a new tab and reports fulfilled', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://s3.example/original',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
    const { result } = renderHook(() => useCanvasResourceActions(resource()))

    act(() => result.current.ensureSigned('open'))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(getCanvasResourceOriginalUrl).toHaveBeenCalledWith(CANVAS_ID, RESOURCE_ID)
    expect(result.current.fulfilled).toBe(true)
    expect(result.current.error).toBeNull()
    expect(clickSpy).toHaveBeenCalledOnce()
    expect(anchorCalls()).toEqual([{
      href: 'https://s3.example/original',
      target: '_blank',
      download: '',
    }])
  })

  it('downloads the signed original with the resource name as the file name', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://s3.example/original',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
    const { result } = renderHook(() => useCanvasResourceActions(resource({ name: 'asset.png' })))

    act(() => result.current.ensureSigned('download'))

    await waitFor(() => expect(result.current.fulfilled).toBe(true))
    expect(anchorCalls()).toEqual([{
      href: 'https://s3.example/original',
      target: '',
      download: 'asset.png',
    }])
    expect(clickSpy).toHaveBeenCalledOnce()
  })

  it('reports the failure reason and allows a retry after it', async () => {
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    vi.mocked(getCanvasResourceOriginalUrl)
      .mockRejectedValueOnce(new Error('sign failed'))
      .mockResolvedValueOnce({
        method: 'GET',
        url: 'https://s3.example/retry',
        headers: {},
        expiresAt: '2026-08-10T00:15:00Z',
      })
    const { result } = renderHook(() => useCanvasResourceActions(resource()))

    act(() => result.current.ensureSigned('open'))
    await waitFor(() => expect(result.current.error).toEqual(new Error('sign failed')))
    expect(result.current.loading).toBe(false)
    expect(result.current.fulfilled).toBe(false)

    act(() => result.current.ensureSigned('open'))
    await waitFor(() => expect(result.current.fulfilled).toBe(true))
    expect(result.current.error).toBeNull()
    expect(getCanvasResourceOriginalUrl).toHaveBeenCalledTimes(2)
  })

  it('ignores stale results after a rapid re-entry and keeps the newest intent', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const first = deferred<{ url: string }>()
    vi.mocked(getCanvasResourceOriginalUrl).mockReturnValueOnce(first.promise as never)
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValueOnce({
      method: 'GET',
      url: 'https://s3.example/second',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
    const { result } = renderHook(() => useCanvasResourceActions(resource()))

    act(() => result.current.ensureSigned('download'))
    expect(result.current.loading).toBe(true)
    // 第二次触发使第一次请求 stale：它不得触发 anchor 或修改状态。
    act(() => result.current.ensureSigned('open'))
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => first.resolve({ url: 'https://s3.example/first' }))
    await act(async () => {
      await first.promise
    })

    expect(clickSpy).toHaveBeenCalledOnce()
    expect(anchorCalls()).toEqual([{
      href: 'https://s3.example/second',
      target: '_blank',
      download: '',
    }])
    expect(result.current.fulfilled).toBe(true)
  })

  it('ignores results after unmount so no anchor is triggered', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const pending = deferred<{ url: string }>()
    vi.mocked(getCanvasResourceOriginalUrl).mockReturnValueOnce(pending.promise as never)
    const { result, unmount } = renderHook(() => useCanvasResourceActions(resource()))

    act(() => result.current.ensureSigned('open'))
    unmount()

    act(() => pending.resolve({ url: 'https://s3.example/late' }))
    await act(async () => {
      await pending.promise
    })

    expect(clickSpy).not.toHaveBeenCalled()
  })
})
