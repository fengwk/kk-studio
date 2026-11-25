import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  getSessionBlobDownloadUrl,
  getSessionBlobPreviewUrl,
} from '@/shared/api/resource-service'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import {
  ResourceOpenInIdeContext,
  ResourceSessionContext,
} from '@/shared/ui/markdown/ResourceUriContext'
import { ResourceUriText } from '@/shared/ui/markdown/ResourceUriText'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/resource-service', () => ({
  getSessionBlobDownloadUrl: vi.fn(),
  getSessionBlobPreviewUrl: vi.fn(),
}))

const RESOURCE_ID = '12345678-1234-1234-1234-123456789abc'
const RESOURCE_URI = `resource://files/${RESOURCE_ID}`
const SESSION_ID = '11111111-2222-3333-4444-555555555555'

function signedUrl(
  url: string,
  mediaType: string | null = 'text/plain',
  headers: Record<string, string> = {},
) {
  return {
    method: 'GET' as const,
    url,
    headers,
    expiresAt: 1786492800000,
    mediaType,
    sizeBytes: '12',
  }
}

beforeEach(() => {
  setLocale('en-US')
  vi.mocked(getSessionBlobDownloadUrl).mockReset()
  vi.mocked(getSessionBlobPreviewUrl).mockReset()
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('resource URI markdown', () => {
  it('renders a resource link as signed preview and download actions, never as navigation', async () => {
    vi.mocked(getSessionBlobDownloadUrl).mockResolvedValueOnce(
      signedUrl('https://s3.test/orig.txt', 'text/plain'),
    )
    vi.mocked(fetch).mockResolvedValueOnce(new Response('preview', { status: 200 }))
    render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`[notes](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(await screen.findByText('preview')).toBeInTheDocument()
    expect(getSessionBlobDownloadUrl).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID)
    expect(getSessionBlobPreviewUrl).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledWith('https://s3.test/orig.txt', {
      method: 'GET',
      headers: {},
      signal: undefined,
    })

    vi.mocked(getSessionBlobDownloadUrl).mockResolvedValueOnce(
      signedUrl('https://s3.test/download.txt', 'text/plain'),
    )
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(clickSpy).toHaveBeenCalledOnce())
    const anchor = clickSpy.mock.instances[0] as HTMLAnchorElement
    expect(anchor.href).toBe('https://s3.test/download.txt')
    expect(anchor.download).toBe('notes')
  })

  it('shows Open in IDE only when the Chat host supplies an eligible action, with pending and localized limit errors', async () => {
    let finishOpen = () => {}
    const open = vi.fn(() => new Promise<void>((resolve) => { finishOpen = resolve }))
    render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <ResourceOpenInIdeContext.Provider value={{ open }}>
          <MarkdownRenderer content={`[notes](${RESOURCE_URI})`} />
        </ResourceOpenInIdeContext.Provider>
      </ResourceSessionContext.Provider>,
    )

    const button = screen.getByRole('button', { name: 'Open in IDE' })
    expect(button).toHaveAttribute('title', expect.stringContaining('read-only UTF-8 text copy'))
    fireEvent.click(button)
    expect(screen.getByRole('status')).toHaveTextContent('Opening in IDE…')
    expect(button).toBeDisabled()
    expect(open).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID)
    expect(getSessionBlobDownloadUrl).not.toHaveBeenCalled()
    expect(getSessionBlobPreviewUrl).not.toHaveBeenCalled()

    finishOpen()
    await waitFor(() => expect(button).toBeEnabled())
    open.mockRejectedValueOnce(new ApiError('too large', 413))
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('This text attachment exceeds the IDE preview size limit.')
  })

  it('does not show Open in IDE without a matching Chat IDE action', () => {
    render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`[notes](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )
    expect(screen.queryByRole('button', { name: 'Open in IDE' })).not.toBeInTheDocument()
  })

  it('resolves resource references embedded in tool output while preserving the URI text', async () => {
    vi.mocked(getSessionBlobDownloadUrl).mockResolvedValueOnce(
      signedUrl('https://s3.test/tool.txt', 'text/plain'),
    )
    vi.mocked(fetch).mockResolvedValueOnce(new Response('tool output', { status: 200 }))
    const view = render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <ResourceUriText text={`Attached resource: ${RESOURCE_URI}, available.`} />
      </ResourceSessionContext.Provider>,
    )

    expect(view.container.textContent).toContain(RESOURCE_URI)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(await screen.findByText('tool output')).toBeInTheDocument()
    expect(getSessionBlobDownloadUrl).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID)
  })

  it('signs inline image preview and falls back to signed original on error, aborting on unmount', async () => {
    vi.mocked(getSessionBlobDownloadUrl).mockResolvedValueOnce(
      signedUrl('https://s3.test/orig.png', 'image/png'),
    )
    vi.mocked(getSessionBlobPreviewUrl).mockResolvedValueOnce(
      signedUrl('https://s3.test/preview.webp', null),
    )
    const view = render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`![diagram](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )

    const img = await screen.findByRole('img', { name: 'diagram' })
    expect(img).toHaveAttribute('src', 'https://s3.test/preview.webp')
    expect(getSessionBlobDownloadUrl).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, {
      signal: expect.any(AbortSignal),
    })
    expect(getSessionBlobPreviewUrl).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, {
      signal: expect.any(AbortSignal),
    })

    const signal = vi.mocked(getSessionBlobDownloadUrl).mock.calls[0]?.[2]?.signal
    expect(signal?.aborted).toBe(false)

    fireEvent.error(img)
    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute(
      'src',
      'https://s3.test/orig.png',
    )

    view.unmount()
    expect(signal?.aborted).toBe(true)
  })

  it('updates and aborts pending signing when the resource URI changes', async () => {
    const nextResourceId = '22345678-1234-1234-1234-123456789abc'
    vi.mocked(getSessionBlobDownloadUrl)
      .mockResolvedValueOnce(signedUrl('https://s3.test/first.png', 'image/png'))
      .mockResolvedValueOnce(signedUrl('https://s3.test/second.png', 'image/png'))
    vi.mocked(getSessionBlobPreviewUrl)
      .mockRejectedValueOnce(new Error('no preview'))
      .mockResolvedValueOnce(signedUrl('https://s3.test/second.webp', null))

    const renderImage = (resourceId: string) => (
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`![diagram](resource://files/${resourceId})`} />
      </ResourceSessionContext.Provider>
    )
    const view = render(renderImage(RESOURCE_ID))
    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute(
      'src',
      'https://s3.test/first.png',
    )

    view.rerender(renderImage(nextResourceId))
    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute(
      'src',
      'https://s3.test/second.webp',
    )
    expect(getSessionBlobDownloadUrl).toHaveBeenLastCalledWith(SESSION_ID, nextResourceId, {
      signal: expect.any(AbortSignal),
    })
  })

  it('renders unknown resource schemes as inert text', () => {
    render(<MarkdownRenderer content="[unsafe](resource:untrusted)" />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText(/unsafe.*Resource is unavailable/)).toBeInTheDocument()
    expect(getSessionBlobDownloadUrl).not.toHaveBeenCalled()
    expect(getSessionBlobPreviewUrl).not.toHaveBeenCalled()
  })
})
