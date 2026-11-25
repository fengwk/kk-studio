import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'

const first = '780cbbd3-a765-45df-aec6-8048b6d8ce2a'
const recent = 'ace3d346-d464-4fa5-b02c-ffb019ecf824'

function renderApp(path: string) {
  const router = createMemoryRouter([{ path: '*', element: <AppProviders><App /></AppProviders> }], { initialEntries: [path] })
  render(<RouterProvider router={router} />)
  return router
}

describe('workspace routes', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const spaces = [{ id: first, title: 'First', version: '0', defaultYoloEnabled: false },
        { id: recent, title: 'Recent', version: '0', defaultYoloEnabled: false }]
      const body = path === '/api/auth/me' ? { id: 'admin', username: 'admin', role: 'ADMIN', profile: {} }
        : path === '/api/chat-spaces' ? spaces : path.endsWith('/tree') ? { treeVersion: '0', folders: [] } : []
      return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
    }))
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  })
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

  it('restores the last active workspace without creating a resource', async () => {
    localStorage.setItem('workspace-last-active:admin', recent)
    const router = renderApp('/')
    await waitFor(() => expect(router.state.location.pathname).toBe(`/chat-spaces/${recent}`))
    expect(await screen.findByRole('button', { name: 'Recent' })).toBeInTheDocument()
    expect(vi.mocked(fetch).mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('does not mount the old interactions route', async () => {
    renderApp('/interactions')
    expect(await screen.findByRole('alert')).toHaveTextContent('Resource unavailable')
    expect(fetch).not.toHaveBeenCalledWith('/api/chat-spaces', expect.anything())
  })
})
