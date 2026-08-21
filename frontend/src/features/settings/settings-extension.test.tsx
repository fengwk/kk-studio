import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { settingsExtension } from '@/features/settings/settings-extension'
import { systemSettingsService } from '@/shared/api/system-settings-service'
import { makeSettingsDto, makeSettingsSchema } from '@/features/settings/settings-test-fixtures'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

vi.mock('@/shared/api/system-settings-service', () => ({
  systemSettingsService: { get: vi.fn(), getSchema: vi.fn(), update: vi.fn() },
  createSystemSettingsService: () => ({ get: vi.fn(), getSchema: vi.fn(), update: vi.fn() }),
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listTools: vi.fn(async () => []),
    listModels: vi.fn(async () => ({ pageNumber: 1, pageSize: 50, totalCount: 0, results: [] })),
  },
}))

describe('settings extension architecture', () => {
  beforeEach(() => {
    vi.mocked(systemSettingsService.get).mockResolvedValue(makeSettingsDto())
    vi.mocked(systemSettingsService.getSchema).mockResolvedValue(makeSettingsSchema())
  })

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
          <BrowserPreferencesProvider>
            <MemoryRouter initialEntries={['/settings']}>
              <Routes>
                <Route path="/*" element={<WorkbenchShell />} />
              </Routes>
            </MemoryRouter>
          </BrowserPreferencesProvider>
        </ExtensionHostProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: '设置' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '键盘快捷键' })).toBeInTheDocument()
  })
})
