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
  return {
    original: 'https://s3.test/orig',
    preview: 'https://s3.test/prev',
    mediaType: 'application/pdf',
    sizeBytes: 42,
    ...overrides,
  }
}

describe('ResourceAttachmentChip', () => {
  it('resolves original + preview lazily and renders preview + download for image attachments', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({ mediaType: 'image/png' }))
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ mime: '' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(resolveBlobUrls).toHaveBeenCalledWith('blob-1')
    const link = await screen.findByRole('link', { name: /下载 report\.pdf/ })
    expect(link).toHaveAttribute('href', 'https://s3.test/orig')
    expect(screen.getByTitle('report.pdf')).toHaveClass('is-image')
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
    const resolveBlobUrls = vi.fn(async () => urls({ preview: null, mediaType: 'image/png' }))
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

  it('uses authoritative media type for video preview and icon fallback', async () => {
    const resolveBlobUrls = vi
      .fn()
      .mockResolvedValueOnce(
        urls({ mediaType: 'video/mp4', preview: 'https://s3.test/clip.mp4' }),
      )
      .mockResolvedValueOnce(urls({ mediaType: 'video/mp4', preview: null }))
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'video-1', name: 'clip.mp4', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(await screen.findByTitle('clip.mp4')).toHaveClass('is-video')
    expect(document.querySelector('video.resource-attachment-preview')).toHaveAttribute(
      'src',
      'https://s3.test/clip.mp4',
    )

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'video-2', name: 'clip.mp4', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )
    await waitFor(() => expect(resolveBlobUrls).toHaveBeenCalledWith('video-2'))
    expect(screen.getByTitle('clip.mp4')).toHaveClass('is-video')
    expect(document.querySelector('video.resource-attachment-preview')).toBeNull()
    expect(document.querySelector('.resource-attachment-icon svg')).not.toBeNull()
  })

  it('classifies authoritative audio and falls back through media type and generic labels', async () => {
    const resolveBlobUrls = vi
      .fn()
      .mockResolvedValueOnce(urls({ mediaType: 'audio/mpeg', preview: null }))
      .mockResolvedValueOnce(urls({ mediaType: 'application/pdf', preview: null }))
      .mockResolvedValueOnce(urls({ mediaType: '', preview: null }))
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'audio-1', name: 'voice.mp3', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(await screen.findByTitle('voice.mp3')).toHaveClass('is-audio')

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'file-1', name: '', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(await screen.findByTitle('application/pdf')).toHaveClass('is-file')

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'file-2', name: '', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )
    await waitFor(() => expect(resolveBlobUrls).toHaveBeenCalledWith('file-2'))
    expect(screen.getByTitle(/attachment/)).toHaveClass('is-file')
  })

  it('shows unavailable when the resolver returns no result or the attachment has no blob id', async () => {
    const resolveBlobUrls = vi.fn(async () => null)
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ blobId: 'missing' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(await screen.findByText('资源不可用')).toBeInTheDocument()

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ blobId: undefined })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(screen.getByText('资源不可用')).toBeInTheDocument()
  })

  it('shows the unavailable hint when no resolver is provided (portable default)', () => {
    render(<ResourceAttachmentChip attachment={attachment()} />)
    expect(screen.getByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
