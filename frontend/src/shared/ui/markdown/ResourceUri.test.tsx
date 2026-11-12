import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchSessionResource } from '@/shared/api/resource-service'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { ResourceSessionContext } from '@/shared/ui/markdown/ResourceUriContext'
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
})

describe('resource URI markdown', () => {
  it('renders a resource link as authenticated preview and download actions, never as navigation', async () => {
    vi.mocked(fetchSessionResource).mockResolvedValueOnce(new Blob(['preview'], { type: 'text/plain' }))
    render(
      <ResourceSessionContext.Provider value={SESSION_ID}>
        <MarkdownRenderer content={`[notes](${RESOURCE_URI})`} />
      </ResourceSessionContext.Provider>,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(await screen.findByText('preview')).toBeInTheDocument()
    expect(fetchSessionResource).toHaveBeenCalledWith(SESSION_ID, RESOURCE_ID, false)
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument()
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
    vi.unstubAllGlobals()
  })

  it('renders unknown resource schemes as inert text', () => {
    render(<MarkdownRenderer content="[unsafe](resource:untrusted)" />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText(/unsafe.*Resource is unavailable/)).toBeInTheDocument()
    expect(fetchSessionResource).not.toHaveBeenCalled()
  })
})
