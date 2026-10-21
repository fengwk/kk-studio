import { describe, expect, it } from 'vitest'
import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/chat/runtime/thread-panel/tool-attachments'

describe('tool-attachments', () => {
  it('auto-preview only data: URIs; http(s)/file/s3 are never inlined media src', () => {
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'cover.png',
        mime: 'image/png',
        data: 'data:image/png;base64,aW1n',
      }),
    ).toBe('data:image/png;base64,aW1n')
    expect(isPreviewableAttachment({
      type: 'image',
      name: 'cover.png',
      mime: 'image/png',
      data: 'data:image/png;base64,aW1n',
    })).toBe(true)
    // 不可信的远程/本地 Tool 资源绝不能作为 media src（不会自动 GET），仅保留为显式链接。
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'remote',
        mime: 'image/png',
        data: 'https://example.test/image.png',
      }),
    ).toBeNull()
    expect(
      toToolAttachmentSrc({
        type: 'image',
        name: 'insecure',
        mime: 'image/png',
        data: 'http://example.test/image.png',
      }),
    ).toBeNull()
    expect(isPreviewableAttachment({
      type: 'image',
      name: 'remote',
      mime: 'image/png',
      data: 'https://example.test/image.png',
    })).toBe(false)
    // http(s) 仍保持为规范的 LINK 目标（只能显式打开原始内容）。
    expect(getToolAttachmentHref({
      type: 'image',
      name: 'remote',
      mime: 'image/png',
      data: 'https://example.test/image.png',
    })).toBe('https://example.test/image.png')
  })

  it('only links direct safe schemes', () => {
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: 'https://x/y' })).toBe('https://x/y')
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: 'file:///tmp/a' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: 's3://b/a' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: 'javascript:alert(1)' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: 'ftp://x/y' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: '', data: '' })).toBeNull()
  })

  it('exposes consistent labels and fallbacks', () => {
    const attachment = {
      type: 'audio' as const,
      name: '',
      mime: 'audio/mpeg',
      data: '',
    }

    expect(getToolAttachmentLabel(attachment)).toBe('audio/mpeg')
    expect(toToolAttachmentSrc(attachment)).toBeNull()
    expect(getToolAttachmentHref(attachment)).toBeNull()
    expect(formatToolAttachmentFallback(attachment)).toBe('[audio] audio/mpeg')
    expect(getToolAttachmentLabel({ ...attachment, mime: '' })).toBe('audio 附件')
    expect(formatToolAttachmentFallback({ ...attachment, mime: '', name: 'preview.mp3' })).toBe('[audio] preview.mp3')
  })
})
