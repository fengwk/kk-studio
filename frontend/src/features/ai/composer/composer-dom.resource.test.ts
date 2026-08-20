import { describe, expect, it } from 'vitest'
import {
  createAttachmentPart,
  createResourcePart,
  createTextPart,
  partsToMessageContents,
} from '@/features/ai/composer/composer-parts'
import {
  extractPartsFromEditor,
  normalizeEditorDom,
  renderPartsToEditor,
} from '@/features/ai/composer/composer-dom'

describe('composer durable resource pills', () => {
  it('round-trips attachment and resource identities through the editor DOM', () => {
    const root = document.createElement('div')
    const parts = [
      createTextPart('before'),
      createAttachmentPart('upload-1', 'upload.txt'),
      createResourcePart(
        '00000000-0000-0000-0000-000000000001',
        'resource.txt',
        'preview',
      ),
      createTextPart('after'),
    ]

    renderPartsToEditor(root, parts)
    normalizeEditorDom(root)
    const restored = extractPartsFromEditor(root)

    expect(partsToMessageContents(restored)).toEqual([
      { type: 'TEXT', text: 'before' },
      { type: 'ATTACHMENT', uploadId: 'upload-1' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'resource.txt',
        preview: 'preview',
      },
      { type: 'TEXT', text: 'after' },
    ])
    expect(
      root.querySelector('[data-part-type="resource"]')?.getAttribute('data-blob-id'),
    ).toBe('00000000-0000-0000-0000-000000000001')
  })
})
