import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'

function attachment(overrides: Partial<ToolAttachment> = {}): ToolAttachment {
  return {
    id: 'a1',
    name: 'report.pdf',
    mime: 'application/pdf',
    type: 'file',
    blobId: 'blob-1',
    ...overrides,
  }
}

function urls(overrides: Partial<ResourceBlobUrls> = {}): ResourceBlobUrls {
  return { original: 'https://s3.test/orig', preview: 'https://s3.test/prev', ...overrides }
}

describe('ResourceAttachmentChip', () => {
  it('resolves original + preview lazily and renders preview + download for image attachments', async () => {
    const resolveBlobUrls = vi.fn(async () => urls())
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(resolveBlobUrls).toHaveBeenCalledWith('blob-1')
    const link = await screen.findByRole('link', { name: /下载 report\.pdf/ })
    expect(link).toHaveAttribute('href', 'https://s3.test/orig')
    expect(screen.getByTitle('report.pdf')).toBeInTheDocument()
    expect(document.querySelector('img.resource-attachment-preview')).toHaveAttribute('src', 'https://s3.test/prev')
    // 解析失败时降级为不可用提示且不出现下载链接。
    resolveBlobUrls.mockRejectedValueOnce(new Error('gone'))
    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={{ ...attachment(), blobId: 'blob-2' }} />
      </ResourceBlobUrlContext.Provider>,
    )
    await waitFor(() => expect(screen.getByText('资源不可用')).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: /下载 report\.pdf/ })).not.toBeInTheDocument()
  })

  it('keeps the download link when only the original resolves', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({ preview: null }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    const link = await screen.findByRole('link', { name: /下载 report\.pdf/ })
    expect(link).toHaveAttribute('href', 'https://s3.test/orig')
    expect(document.querySelector('img.resource-attachment-preview')).toBeNull()
    expect(screen.queryByText('资源不可用')).not.toBeInTheDocument()
  })

  it('shows the unavailable hint when no resolver is provided (portable default)', () => {
    render(<ResourceAttachmentChip attachment={attachment()} />)
    expect(screen.getByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
