import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { fetchSessionResource } from '@/shared/api/resource-service'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import {
  ResourceOpenInIdeContext,
  ResourceSessionContext,
} from '@/shared/ui/markdown/ResourceUriContext'
import { ResourceUriText } from '@/shared/ui/markdown/ResourceUriText'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/resource-service', () => ({
  fetchSessionResource: vi.fn(),
}))

const RESOURCE_ID = '12345678-1234-1234-1234-123456789abc'
const RESOURCE_URI = `resource://files/${RESOURCE_ID}`
const SESSION_ID = '11111111-2222-3333-4444-555555555555'

beforeEach(() => {
  setLocale('en-US')
  vi.mocked(fetchSessionResource).mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('resource URI markdown', () => {
  it('renders a resource link as same-origin preview and download actions, never as navigation', async () => {
    vi.mocked(fetchSessionResource)
      .mockResolvedValueOnce(new Blob(['preview'], { type: 'text/plain' }))
      .mockResolvedValueOnce(new Blob(['download'], { type: 'image/png' }))
    const createObjectURL = vi.fn(() => 'blob:resource-download')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL,
      revokeObjectURL,
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`[notes](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(await screen.findByText('preview')).toBeInTheDocument()
    expect(fetchSessionResource).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, false)

    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(clickSpy).toHaveBeenCalledOnce())
    expect(fetchSessionResource).toHaveBeenNthCalledWith(2, SESSION_ID, RESOURCE_ID, true)
    const anchor = clickSpy.mock.instances[0] as HTMLAnchorElement
    expect(anchor.href).toBe('blob:resource-download')
    expect(anchor.download).toBe('notes')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:resource-download')
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
    expect(fetchSessionResource).not.toHaveBeenCalled()

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
    vi.mocked(fetchSessionResource).mockResolvedValueOnce(new Blob(['tool output'], { type: 'text/plain' }))
    const view = render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <ResourceUriText text={`Attached resource: ${RESOURCE_URI}, available.`} />
      </ResourceSessionContext.Provider>,
    )

    expect(view.container.textContent).toContain(RESOURCE_URI)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(await screen.findByText('tool output')).toBeInTheDocument()
    expect(fetchSessionResource).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, false)
  })

  it('revokes an inline image object URL when its markdown is unmounted', async () => {
    vi.mocked(fetchSessionResource).mockResolvedValueOnce(new Blob(['image'], { type: 'image/png' }))
    const createObjectURL = vi.fn(() => 'blob:resource-preview')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL,
      revokeObjectURL,
    })
    const view = render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`![diagram](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )

    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute('src', 'blob:resource-preview')
    expect(fetchSessionResource).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, false, expect.any(AbortSignal))
    view.unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:resource-preview')
  })

  it('revokes an image URL when its resource URI changes', async () => {
    const nextResourceId = '22345678-1234-1234-1234-123456789abc'
    vi.mocked(fetchSessionResource)
      .mockResolvedValueOnce(new Blob(['first'], { type: 'image/png' }))
      .mockResolvedValueOnce(new Blob(['second'], { type: 'image/png' }))
    const createObjectURL = vi.fn()
      .mockReturnValueOnce('blob:first-resource')
      .mockReturnValueOnce('blob:second-resource')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL,
      revokeObjectURL,
    })
    const renderImage = (resourceId: string) => (
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`![diagram](resource://files/${resourceId})`} />
      </ResourceSessionContext.Provider>
    )
    const view = render(renderImage(RESOURCE_ID))
    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute('src', 'blob:first-resource')

    view.rerender(renderImage(nextResourceId))

    expect(await screen.findByRole('img', { name: 'diagram' })).toHaveAttribute('src', 'blob:second-resource')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:first-resource')
    view.unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:second-resource')
  })

  it('renders unknown resource schemes as inert text', () => {
    render(<MarkdownRenderer content="[unsafe](resource:untrusted)" />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText(/unsafe.*Resource is unavailable/)).toBeInTheDocument()
    expect(fetchSessionResource).not.toHaveBeenCalled()
  })
})
