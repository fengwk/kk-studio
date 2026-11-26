import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { setLocale } from '@/shared/i18n'
import type { ConfigSyncImportCheckDTO } from '@/shared/api/generated/contracts'
import { SettingsRoute } from '@/app/SettingsRoute'
import { AccountContext } from '@/features/account/account-context'

const mocks = vi.hoisted(() => ({ get: vi.fn(), getSchema: vi.fn(), update: vi.fn() }))

vi.mock('@/features/settings/system-settings-service', () => ({
  systemSettingsService: { get: mocks.get, getSchema: mocks.getSchema, update: mocks.update },
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listTools: vi.fn(async () => []),
    listModels: vi.fn(async () => []),
  },
}))

const syncMocks = vi.hoisted(() => ({
  getInventory: vi.fn(),
  exportConfig: vi.fn(),
  checkImport: vi.fn<(yaml: string) => Promise<ConfigSyncImportCheckDTO>>(),
  importConfig: vi.fn(),
}))

vi.mock('@/features/settings/config-sync-service', () => ({
  configSyncService: {
    getInventory: syncMocks.getInventory,
    exportConfig: syncMocks.exportConfig,
    checkImport: syncMocks.checkImport,
    importConfig: syncMocks.importConfig,
  },
}))

beforeEach(() => {
  mocks.update.mockReset()
  localStorage.clear()
  // 清空存储会连带清掉测试基座写入的 locale；恢复默认测试语言。
  setLocale('zh-CN')
  mocks.get.mockResolvedValue(makeSettingsDto())
  mocks.getSchema.mockResolvedValue(makeSettingsSchema())
  syncMocks.getInventory.mockResolvedValue({ items: [] })
})

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountContext.Provider value={{ user: { id: 'admin', username: 'admin', role: 'ADMIN', profile: {} }, refresh: async () => {}, signOut: async () => {} }}>
        <MemoryRouter initialEntries={['/settings/preferences']}>
          <Routes><Route path="/settings/:section" element={<SettingsRoute />} /></Routes>
        </MemoryRouter>
      </AccountContext.Provider>
    </QueryClientProvider>,
  )
}

async function tabByName(name: string) {
  return await screen.findByRole('link', { name })
}

describe('SettingsPage general tab', () => {
  // 真实页面使用服务端 schema，完整 PUT 成功后清理脏状态。
  it('saves an updated retry backoff strategy through the schema UI', async () => {
    const user = userEvent.setup()
    const dto = makeSettingsDto()
    mocks.update.mockImplementation(async (update) => {
      const saved = { ...dto, ...update, version: '1' }
      mocks.get.mockResolvedValue(saved)
      return saved
    })
    renderSettings()
    await user.click(await tabByName('AI 运行时'))
    const select = await screen.findByLabelText('退避策略')
    expect(select).toHaveTextContent('指数')
    await user.click(select)
    await user.click(screen.getByRole('option', { name: '固定' }))
    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(mocks.update).toHaveBeenCalledWith({
      tool: dto.tool,
      aiRuntime: { ...dto.aiRuntime, retryBackoffStrategy: 'FIXED' },
      environment: dto.environment, backendNetwork: dto.backendNetwork,
      storageMedia: dto.storageMedia, advanced: dto.advanced, expectedVersion: dto.version,
    })
    await waitFor(() => expect(screen.getByRole('button', { name: '保存' })).toBeDisabled())
    expect(screen.queryByText('有未保存的更改')).not.toBeInTheDocument()
  })
  // 后端网络保持 schema 顺序，同步紧随高级，不替换现有服务端设置。
  it('renders every top-level settings tab with Sync after Advanced', async () => {
    renderSettings()
    await tabByName('后端网络')
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      '偏好', 'Agent', '模型', '提供商', '技能包', '环境', 'MCP 服务',
      '常规', '用户', '插件', 'AI 运行时', '工具与权限', '环境默认值', '后端网络', '存储与媒体', '高级', '同步',
    ])
  })

  it('renders the read-only keyboard shortcut catalog section', () => {
    renderSettings()
    expect(screen.getByRole('heading', { name: '键盘快捷键' })).toBeInTheDocument()
    expect(screen.getByText('本版本支持的键盘快捷键只读目录。')).toBeInTheDocument()
    // scope 分组与条目来自唯一事实源。
    for (const title of ['应用', 'Thread', '调试', '画布']) {
      expect(screen.getByText(title)).toBeInTheDocument()
    }
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  })
})

it('activates a category from the keyboard and keeps focus on its link', async () => {
  const user = userEvent.setup()
  renderSettings()
  const category = await tabByName('高级')
  category.focus()
  await user.keyboard('{Enter}')
  expect(category).toHaveAttribute('aria-current', 'page')
  expect(category).toHaveFocus()
})

describe('settings sync tab', () => {
  it('offers only import/export and never saves the current settings draft', async () => {
    const user = userEvent.setup()
    renderSettings()
    await user.click(await tabByName('同步'))

    expect(screen.getByRole('button', { name: '导入' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出' })).toBeInTheDocument()
    // 同步只走 /api/platform/config-sync，不触发 system settings 的 CAS 保存。
    expect(mocks.update).not.toHaveBeenCalled()
  })

  // 导入后服务端已有新值：明确的「重新加载设置」必须重捕快照，否则保存仍带导入前的版本。
  it('recaptures the draft snapshot when reloading settings after an import', async () => {
    const user = userEvent.setup()
    const imported = makeSettingsDto({ version: '1' })
    imported.aiRuntime.retryBaseDelayMillis = '7000'
    syncMocks.checkImport.mockResolvedValue({
      created: [{ kind: 'settings', name: 'settings' }],
      updated: [],
      skipped: [],
      pluginCredentialResets: [],
    })
    syncMocks.importConfig.mockResolvedValue({
      imported: [{ kind: 'settings', name: 'settings' }],
      skipped: [],
    })
    const { container } = renderSettings()

    // 先留下一份未保存 draft（与导入前的权威不同）。
    await user.click(await tabByName('AI 运行时'))
    const delay = await screen.findByLabelText('基础延迟（毫秒）')
    await user.clear(delay)
    await user.type(delay, '3000')
    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()

    // 导入期间服务端换成导入后的权威值。
    mocks.get.mockResolvedValue(imported)
    await user.click(await tabByName('同步'))
    const fileInput = container.querySelector('input[type="file"]')
    expect(fileInput).not.toBeNull()
    await user.upload(fileInput as HTMLInputElement, new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))
    await within(dialog).findByText('重新加载设置')

    await user.click(within(dialog).getByRole('button', { name: '重新加载设置' }))

    // draft 以导入后的权威重建，脏状态清除，显示服务端新值。
    await user.click(await tabByName('AI 运行时'))
    await waitFor(() => expect(screen.getByLabelText('基础延迟（毫秒）')).toHaveValue('7000'))
    expect(screen.getByText('所有更改已保存')).toBeInTheDocument()

    // 下一次保存携带重新加载后捕获的版本，而不是导入前的 0。
    const reloaded = screen.getByLabelText('基础延迟（毫秒）')
    await user.clear(reloaded)
    await user.type(reloaded, '8000')
    mocks.update.mockImplementation(async (update) => ({ ...imported, ...update, version: '2' }))
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(mocks.update).toHaveBeenCalledTimes(1))
    expect((mocks.update.mock.calls[0]![0] as { expectedVersion: string }).expectedVersion).toBe('1')
  })

  // 导入后自动重新加载失败：错误必须出现在触发它的弹窗里，且不丢草稿与原版本。
  it('reports a reload failure inside the import modal and keeps the draft untouched', async () => {
    const user = userEvent.setup()
    syncMocks.checkImport.mockResolvedValue({
      created: [{ kind: 'settings', name: 'settings' }],
      updated: [],
      skipped: [],
      pluginCredentialResets: [],
    })
    syncMocks.importConfig.mockResolvedValue({
      imported: [{ kind: 'settings', name: 'settings' }],
      skipped: [],
    })
    const { container } = renderSettings()

    // 初次加载已成功；导入后的重新加载 GET 失败。
    mocks.get.mockRejectedValue(new Error('offline'))
    await user.click(await tabByName('同步'))
    const fileInput = container.querySelector('input[type="file"]')
    expect(fileInput).not.toBeNull()
    await user.upload(fileInput as HTMLInputElement, new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))

    // 失败就地呈现在弹窗错误条里，而不是只能去服务端设置页才能看到。
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('设置加载失败。')

    // draft 与 baseVersion 保持导入前的状态：页面仍无未保存更改，保存仍用版本 0。
    await user.click(await tabByName('AI 运行时'))
    const delay = await screen.findByLabelText('基础延迟（毫秒）')
    expect(delay).toHaveValue('2000')
    expect(screen.getByText('所有更改已保存')).toBeInTheDocument()
    await user.clear(delay)
    await user.type(delay, '4000')
    mocks.update.mockImplementation(async (update) => ({ ...makeSettingsDto(), ...update, version: '1' }))
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(mocks.update).toHaveBeenCalledTimes(1))
    expect((mocks.update.mock.calls[0]![0] as { expectedVersion: string }).expectedVersion).toBe('0')
  })
})
