import { describe, expect, it, vi } from 'vitest'
import { readOriginalImageMetadata } from './image-metadata'

describe('readOriginalImageMetadata', () => {
  it('reports the decoded dimensions for the original file and closes the bitmap', async () => {
    // 原文件上传不缩放，四个尺寸都等于浏览器实际解码结果。
    const close = vi.fn()
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 640, height: 480, close })))

    await expect(readOriginalImageMetadata(new File(['heic'], 'photo.heic', { type: 'image/heic' })))
      .resolves.toEqual({
        format: 'heic',
        originalWidth: 640,
        originalHeight: 480,
        storedWidth: 640,
        storedHeight: 480,
      })
    expect(close).toHaveBeenCalledOnce()
  })

  it('fails closed when the browser cannot decode the image', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn(async () => {
      throw new Error('unsupported image')
    }))

    await expect(readOriginalImageMetadata(new File(['bad'], 'bad.png', { type: 'image/png' })))
      .rejects.toThrow('unsupported image')
  })

  it('rejects dimensions outside the shared decodable pixel budget', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({
      width: 8000,
      height: 5001,
      close: vi.fn(),
    })))

    await expect(readOriginalImageMetadata(new File(['big'], 'big.png', { type: 'image/png' })))
      .rejects.toThrow('exceeds the decodable pixel budget')
  })
})
