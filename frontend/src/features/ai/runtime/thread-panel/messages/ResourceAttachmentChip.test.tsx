import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
  it('renders an image inline and opens the original in a lightbox', async () => {
    const user = userEvent.setup()
    const resolveBlobUrls = vi.fn(async () => urls({ mediaType: 'image/png' }))
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ mime: '' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(resolveBlobUrls).toHaveBeenCalledWith('blob-1')
    const preview = await screen.findByRole('button', { name: '预览 report.pdf' })
    expect(within(preview).getByRole('img', { name: 'report.pdf' })).toHaveAttribute(
      'src',
      'https://s3.test/orig',
    )
    expect(preview.querySelector('.resource-media-preview-name')).toHaveTextContent('report.pdf')

    await user.click(preview)
    const dialog = screen.getByRole('dialog', { name: '预览 report.pdf' })
    expect(within(dialog).getByRole('img', { name: 'report.pdf' })).toHaveAttribute(
      'src',
      'https://s3.test/orig',
    )
    expect(within(dialog).getByRole('link', { name: '打开原件 report.pdf' })).toHaveAttribute(
      'href',
      'https://s3.test/orig',
    )
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

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

  it('uses the authoritative original as the inline image when preview resolution fails', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({ preview: null, mediaType: 'image/png' }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    const preview = await screen.findByRole('button', { name: '预览 report.pdf' })
    expect(within(preview).getByRole('img', { name: 'report.pdf' })).toHaveAttribute(
      'src',
      'https://s3.test/orig',
    )
    expect(screen.queryByText('资源不可用')).not.toBeInTheDocument()
  })

  it('uses authoritative media type for video poster and original fallback', async () => {
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

    const poster = await screen.findByRole('button', { name: '预览 clip.mp4' })
    const posterImage = within(poster).getByRole('img', { name: 'clip.mp4' })
    expect(posterImage).toHaveAttribute(
      'src',
      'https://s3.test/clip.mp4',
    )
    fireEvent.error(posterImage)
    expect(poster.querySelector('video')).toHaveAttribute('src', 'https://s3.test/orig')

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: 'video-2', name: 'clip.mp4', mime: '' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )
    await waitFor(() => expect(resolveBlobUrls).toHaveBeenCalledWith('video-2'))
    const original = await screen.findByRole('button', { name: '预览 clip.mp4' })
    expect(original.querySelector('video')).toHaveAttribute('src', 'https://s3.test/orig')
    expect(original.querySelector('img')).toBeNull()
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

  it('lets authoritative non-media types override a stale attachment type', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({
      mediaType: 'application/pdf',
      preview: null,
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(await screen.findByTitle('report.pdf')).toHaveClass('is-file')
    expect(screen.getByRole('link', { name: '下载 report.pdf' })).toHaveTextContent('[report.pdf]')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('marks a partial resolver result without an original as unavailable', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({
      original: null,
      mediaType: 'image/png',
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(await screen.findByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
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

  it('does not retain resolved media when the next attachment has no blob id', async () => {
    const resolveBlobUrls = vi.fn(async () => urls({ mediaType: 'image/png' }))
    const { rerender } = render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip attachment={attachment({ type: 'image', mime: 'image/png' })} />
      </ResourceBlobUrlContext.Provider>,
    )
    expect(await screen.findByRole('img', { name: 'report.pdf' })).toBeInTheDocument()

    rerender(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ResourceAttachmentChip
          attachment={attachment({ blobId: undefined, name: 'missing.png', type: 'image' })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(await screen.findByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByTitle('missing.png')).toHaveTextContent('[missing.png]')
  })

  it('shows the unavailable hint when no resolver is provided (portable default)', () => {
    render(<ResourceAttachmentChip attachment={attachment()} />)
    expect(screen.getByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
