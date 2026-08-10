import { describe, expect, it } from 'vitest'
import { canvasFileDescriptor } from '@/features/canvas/canvas-file'

describe('Canvas file descriptor fallback', () => {
  it.each([
    ['asset.jpeg', 'IMAGE', 'image/jpeg'],
    ['asset.png', 'IMAGE', 'image/png'],
    ['asset.webp', 'IMAGE', 'image/webp'],
    ['asset.heic', 'IMAGE', 'image/heic'],
    ['asset.heif', 'IMAGE', 'image/heif'],
    ['asset.mp4', 'VIDEO', 'video/mp4'],
    ['asset.mov', 'VIDEO', 'video/quicktime'],
    ['asset.wav', 'AUDIO', 'audio/wav'],
    ['asset.mp3', 'AUDIO', 'audio/mpeg'],
  ] as const)('maps an empty-MIME %s to exact kind and media type', (name, kind, mediaType) => {
    // Exact extension MIME values prevent HEIC/QuickTime/WAV from being mislabeled as generic defaults.
    expect(canvasFileDescriptor({ name, type: '' })).toEqual({ kind, mediaType })
  })

  it('prefers a supplied browser MIME and rejects unknown binary files', () => {
    // Browser-provided media types remain authoritative while arbitrary binaries stay unsupported.
    expect(canvasFileDescriptor({ name: 'capture.bin', type: 'image/avif' })).toEqual({
      kind: 'IMAGE',
      mediaType: 'image/avif',
    })
    expect(canvasFileDescriptor({ name: 'capture.bin', type: 'application/octet-stream' })).toBeNull()
  })
})
