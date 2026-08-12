import { afterEach, describe, expect, it, vi } from 'vitest'
import { probeCanvasFileMetadata } from '@/features/canvas/canvas-file-metadata'

/**
 * 本地文件媒体元数据探测：IMAGE 走 createImageBitmap，VIDEO 走 media element
 * loadedmetadata（3s 超时兜底），AUDIO 无尺寸信息返回 nulls。
 */
function fakeVideoElement(options: {
  width?: number
  height?: number
  fail?: boolean
  never?: boolean
}) {
  const listeners = new Map<string, Set<() => void>>()
  const element = {
    videoWidth: options.width ?? 1920,
    videoHeight: options.height ?? 1080,
    preload: '',
    addEventListener(type: string, listener: () => void) {
      const set = listeners.get(type) ?? new Set()
      set.add(listener)
      listeners.set(type, set)
    },
    set src(value: string) {
      if (options.never || !value) {
        return
      }
      queueMicrotask(() => {
        const type = options.fail ? 'error' : 'loadedmetadata'
        for (const listener of listeners.get(type) ?? []) {
          listener()
        }
      })
    },
  }
  return element
}

describe('probeCanvasFileMetadata', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('decodes IMAGE dimensions through createImageBitmap and closes the bitmap', async () => {
    const close = vi.fn()
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({
      width: 1122,
      height: 1402,
      close,
    })))

    await expect(probeCanvasFileMetadata(
      new File(['png'], 'upload.png', { type: 'image/png' }),
      'IMAGE',
    )).resolves.toEqual({ width: 1122, height: 1402 })
    expect(close).toHaveBeenCalledOnce()
  })

  it('falls back to nulls when IMAGE decoding fails or is unavailable', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn(async () => {
      throw new Error('decode failed')
    }))
    await expect(probeCanvasFileMetadata(
      new File(['png'], 'broken.png', { type: 'image/png' }),
      'IMAGE',
    )).resolves.toEqual({ width: null, height: null })

    vi.unstubAllGlobals()
    await expect(probeCanvasFileMetadata(
      new File(['png'], 'plain.png', { type: 'image/png' }),
      'IMAGE',
    )).resolves.toEqual({ width: null, height: null })
  })

  it('reads VIDEO dimensions from loadedmetadata and revokes the object URL', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-video')
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(document, 'createElement').mockReturnValue(
      fakeVideoElement({ width: 1920, height: 1080 }) as unknown as HTMLVideoElement,
    )

    await expect(probeCanvasFileMetadata(
      new File(['mp4'], 'clip.mp4', { type: 'video/mp4' }),
      'VIDEO',
    )).resolves.toEqual({ width: 1920, height: 1080 })
    expect(createObjectURL).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:fake-video')
  })

  it('falls back to nulls when the VIDEO element errors or never reports metadata', async () => {
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-video-2')
    vi.spyOn(document, 'createElement').mockReturnValue(
      fakeVideoElement({ fail: true }) as unknown as HTMLVideoElement,
    )
    await expect(probeCanvasFileMetadata(
      new File(['mp4'], 'broken.mp4', { type: 'video/mp4' }),
      'VIDEO',
    )).resolves.toEqual({ width: null, height: null })

    vi.useFakeTimers()
    vi.spyOn(document, 'createElement').mockReturnValue(
      fakeVideoElement({ never: true }) as unknown as HTMLVideoElement,
    )
    const pending = probeCanvasFileMetadata(
      new File(['mp4'], 'silent.mp4', { type: 'video/mp4' }),
      'VIDEO',
    )
    await vi.advanceTimersByTimeAsync(3000)
    await expect(pending).resolves.toEqual({ width: null, height: null })
    expect(revokeObjectURL).toHaveBeenCalledTimes(2)
  })

  it('returns nulls for AUDIO without probing media APIs', async () => {
    const createElement = vi.spyOn(document, 'createElement')

    await expect(probeCanvasFileMetadata(
      new File(['mp3'], 'track.mp3', { type: 'audio/mpeg' }),
      'AUDIO',
    )).resolves.toEqual({ width: null, height: null })
    expect(createElement).not.toHaveBeenCalled()
  })
})
