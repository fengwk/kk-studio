import { describe, expect, it } from 'vitest'
import { toResourceAttachment } from '@/features/ai/runtime/thread-timeline/content-utils'

describe('toResourceAttachment', () => {
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
})
