import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { settingsExtension } from '@/features/settings/settings-extension'
import { systemSettingsService } from '@/shared/api/system-settings-service'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

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
    // WorkbenchShell 通过唯一应用事件连接读取全局 pending：测试注入替身 WebSocket。
    const sockets = new FakeWebSocketHarness()
    render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <ExtensionHostProvider host={host}>
            <BrowserPreferencesProvider>
              <MemoryRouter initialEntries={['/settings']}>
                <Routes>
                  <Route path="/*" element={<WorkbenchShell navItems={[]} />} />
                </Routes>
              </MemoryRouter>
            </BrowserPreferencesProvider>
          </ExtensionHostProvider>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: '设置' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '键盘快捷键' })).toBeInTheDocument()
  })
})
