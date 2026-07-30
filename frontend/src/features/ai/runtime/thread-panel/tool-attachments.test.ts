import { describe, expect, it } from 'vitest'
import { formatToolAttachmentFallback, getToolAttachmentLabel, toToolAttachmentSrc } from '@/features/ai/runtime/thread-panel/tool-attachments'

describe('tool-attachments', () => {
  it('builds data urls for base64 payloads', () => {
    // Raw base64 is the backend contract today, so the UI must normalize it into a renderable src.
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'cover.png',
        mime: 'image/png',
        data: 'aW1n',
      }),
    ).toBe('data:image/png;base64,aW1n')
  })

  it('keeps ready-made urls unchanged', () => {
    // Existing data/blob/http URLs should stay stable instead of being wrapped a second time.
    expect(
      toToolAttachmentSrc({
        type: 'video',
        name: 'preview.mp4',
        mime: 'video/mp4',
        data: 'data:video/mp4;base64,dmlkZW8=',
      }),
    ).toBe('data:video/mp4;base64,dmlkZW8=')
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'artifact',
        mime: 'image/png',
        data: '/api/artifacts/1',
      }),
    ).toBe('/api/artifacts/1')
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'remote',
        mime: 'image/png',
        data: 'https://example.test/image.png',
      }),
    ).toBe('https://example.test/image.png')
  })

  it('exposes consistent labels and fallbacks', () => {
    // Labels should prefer human-readable names, while fallback text stays available for empty payload cases.
    const attachment = {
      type: 'audio' as const,
      name: '',
      mime: 'audio/mpeg',
      data: '',
    }

    expect(getToolAttachmentLabel(attachment)).toBe('audio/mpeg')
    expect(toToolAttachmentSrc(attachment)).toBeNull()
    expect(formatToolAttachmentFallback(attachment)).toBe('[audio] audio/mpeg')
    expect(getToolAttachmentLabel({ ...attachment, mime: '' })).toBe('audio attachment')
    expect(formatToolAttachmentFallback({ ...attachment, mime: '', name: 'preview.mp3' })).toBe('[audio] preview.mp3')
  })
})
