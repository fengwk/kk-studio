import { describe, expect, it } from 'vitest'
import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'

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
    expect(isPreviewableAttachment({
      type: 'image',
      name: 'local',
      mime: 'image/png',
      data: 'file:///tmp/cover.png',
    })).toBe(false)
    // http(s) 仍保持为规范的 LINK 目标（只能显式打开原始内容）。
    expect(getToolAttachmentHref({
      type: 'image',
      name: 'remote',
      mime: 'image/png',
      data: 'https://example.test/image.png',
    })).toBe('https://example.test/image.png')
  })

  it('returns null src for file:/s3: URIs but keeps href pointing to the URI', () => {
    const fileAttachment = {
      type: 'file' as const,
      name: 'result.json',
      mime: 'application/json',
      data: 'file:///tmp/result.json',
    }
    expect(toToolAttachmentSrc(fileAttachment)).toBeNull()
    expect(isPreviewableAttachment(fileAttachment)).toBe(false)
    expect(getToolAttachmentHref(fileAttachment)).toBe('file:///tmp/result.json')
    expect(formatToolAttachmentFallback(fileAttachment)).toBe('[file] result.json')

    const s3Attachment = {
      type: 'file' as const,
      name: 'blob.bin',
      mime: 'application/octet-stream',
      data: 's3://bucket/obj',
    }
    expect(toToolAttachmentSrc(s3Attachment)).toBeNull()
    expect(isPreviewableAttachment(s3Attachment)).toBe(false)
    expect(getToolAttachmentHref(s3Attachment)).toBe('s3://bucket/obj')
    expect(formatToolAttachmentFallback(s3Attachment)).toBe('[file] blob.bin')
  })

  it('only links canonical data/file/s3/http/https schemes', () => {
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: 'https://x/y' })).toBe('https://x/y')
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: 'file:///tmp/a' })).toBe('file:///tmp/a')
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: 's3://b/a' })).toBe('s3://b/a')
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: 'javascript:alert(1)' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: 'ftp://x/y' })).toBeNull()
    expect(getToolAttachmentHref({ type: 'file', name: 'a', mime: null, data: '' })).toBeNull()
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
    expect(getToolAttachmentLabel({ ...attachment, mime: '' })).toBe('audio attachment')
    expect(formatToolAttachmentFallback({ ...attachment, mime: '', name: 'preview.mp3' })).toBe('[audio] preview.mp3')
  })
})
