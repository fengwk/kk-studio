import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ApplicationSettingsProvider } from '@/features/settings/application-settings'
import { settingsExtension } from '@/features/settings/settings-extension'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

describe('settings extension architecture', () => {
  it('registers the /settings page through an independent extension', () => {
    expect(settingsExtension.id).toBe('builtin.settings')
    expect(settingsExtension.pages?.map((page) => [page.id, page.path])).toEqual([
      ['settings.page', 'settings'],
    ])
  })

  it('renders the settings page through the workbench at /settings', async () => {
    const host = new ExtensionHost()
    host.register(settingsExtension)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <ExtensionHostProvider host={host}>
          <ApplicationSettingsProvider>
            <MemoryRouter initialEntries={['/settings']}>
              <Routes>
                <Route path="/*" element={<WorkbenchShell />} />
              </Routes>
            </MemoryRouter>
          </ApplicationSettingsProvider>
        </ExtensionHostProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: '设置' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '键盘快捷键' })).toBeInTheDocument()
  })
})
