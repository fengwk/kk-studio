import { describe, expect, it } from 'vitest'
import {
  contentText,
  formatCompactTokens,
  numberField,
  resourceAttachmentType,
  toResourceAttachment,
} from '@/features/ai/runtime/thread-timeline/content-utils'

describe('contentText', () => {
  it('projects text/thinking and serializes every JSON value shape safely', () => {
    expect(contentText({ type: 'text', text: 'plain' })).toBe('plain')
    expect(contentText({ type: 'thinking', text: 'reasoning' })).toBe('reasoning')
    expect(contentText({ type: 'json', json: '{"ok":true}' })).toBe('{"ok":true}')
    expect(contentText({ type: 'json', json: { ok: true } })).toBe('{"ok":true}')
    expect(contentText({ type: 'json', json: null })).toBe('')
    expect(contentText({ type: 'json', json: 42 })).toBe('42')
    expect(contentText({ type: 'unknown', text: 'ignored' })).toBe('')

    const circular: Record<string, unknown> = {}
    circular.self = circular
    expect(contentText({ type: 'json', json: circular })).toBe('')
  })
})

describe('toResourceAttachment', () => {
  it('projects the lowercase durable blob resource emitted by the runtime codec', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
        name: 'result.txt',
        preview: 'excerpt',
      }),
    ).toEqual([
      {
        type: 'file',
        name: 'result.txt',
        mime: '',
        data: '',
        blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
        preview: 'excerpt',
        size: null,
      },
    ])
  })

  it('projects a complete managed resource identity into a same-origin download href', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 'file:///tmp/result.json',
        mediaType: 'application/json',
        name: 'result.json',
        size: 12,
        sha256: 'a'.repeat(64),
      }),
    ).toEqual([
      {
        type: 'file',
        name: 'result.json',
        mime: 'application/json',
        data: 'file:///tmp/result.json',
        preview: undefined,
        size: 12,
        sha256: 'a'.repeat(64),
        downloadHref:
          `/api/ai/runtime/resources/${'a'.repeat(64)}`
          + '?mediaType=application%2Fjson&size=12&name=result.json',
      },
    ])
  })

  it('does not invent a download href for incomplete or direct resources', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 's3://bucket/blob',
        mediaType: 'application/octet-stream',
        size: -1,
        sha256: 'bad',
      })[0]?.downloadHref,
    ).toBeUndefined()
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 'https://example.test/blob',
        mediaType: 'application/octet-stream',
        size: 4,
        sha256: 'b'.repeat(64),
      })[0]?.downloadHref,
    ).toBeUndefined()
  })

  it('rejects malformed durable and transient resource shapes', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
        name: '',
      }),
    ).toEqual([])
    expect(toResourceAttachment({ type: 'RESOURCE', name: 'missing-blob' })).toEqual([])
    expect(toResourceAttachment({ type: 'unknown', uri: 'file:///tmp/x' })).toEqual([])
    expect(toResourceAttachment({ type: 'resource', uri: '' })).toEqual([])
  })

  it('omits the managed-resource name query when a transient resource has no name', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 'file:///tmp/result.bin',
        mediaType: 'application/octet-stream',
        size: 3,
        sha256: 'c'.repeat(64),
      })[0]?.downloadHref,
    ).toBe(
      `/api/ai/runtime/resources/${'c'.repeat(64)}`
      + '?mediaType=application%2Foctet-stream&size=3',
    )
  })

  it('requires canonical hash and media type for managed transient resources', () => {
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 'file:///tmp/result.bin',
        mediaType: 'application/octet-stream',
        size: 3,
        sha256: 'bad',
      })[0]?.downloadHref,
    ).toBeUndefined()
    expect(
      toResourceAttachment({
        type: 'resource',
        uri: 's3://bucket/result.bin',
        mediaType: '',
        size: 3,
        sha256: 'd'.repeat(64),
      })[0]?.downloadHref,
    ).toBeUndefined()
  })
})

describe('resource helpers', () => {
  it('classifies every supported media family', () => {
    expect(resourceAttachmentType('image/png')).toBe('image')
    expect(resourceAttachmentType('audio/mpeg')).toBe('audio')
    expect(resourceAttachmentType('video/mp4')).toBe('video')
    expect(resourceAttachmentType('application/pdf')).toBe('file')
  })

  it('reads finite numeric and numeric-string fields while clamping negatives', () => {
    expect(numberField({ size: 5 }, 'size')).toBe(5)
    expect(numberField({ size: -5 }, 'size')).toBe(0)
    expect(numberField({ size: '7' }, 'size')).toBe(7)
    expect(numberField({ size: '-7' }, 'size')).toBe(0)
    expect(numberField({ size: 'bad', sizeBytes: '9' }, 'size', 'sizeBytes')).toBe(9)
    expect(numberField({ size: Number.NaN, sizeBytes: {} }, 'size', 'sizeBytes')).toBe(0)
    expect(numberField({}, 'size')).toBe(0)
  })

  it('formats every compact-token range', () => {
    expect(formatCompactTokens(Number.NaN)).toBe('0')
    expect(formatCompactTokens(0)).toBe('0')
    expect(formatCompactTokens(500)).toBe('500')
    expect(formatCompactTokens(1500)).toBe('1.5k')
    expect(formatCompactTokens(15_000)).toBe('15k')
    expect(formatCompactTokens(2_500_000)).toBe('2.5M')
  })
})
