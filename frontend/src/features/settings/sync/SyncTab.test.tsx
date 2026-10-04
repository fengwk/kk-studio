import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ConfigSyncImportCheckDTO,
  ConfigSyncItem,
} from '@/shared/api/contracts/config-sync'
import { SyncTab } from '@/features/settings/sync/SyncTab'

const mocks = vi.hoisted(() => ({
  getInventory: vi.fn(),
  exportConfig: vi.fn(),
  checkImport: vi.fn(),
  importConfig: vi.fn(),
}))

vi.mock('@/shared/api/config-sync-service', () => ({
  configSyncService: {
    getInventory: mocks.getInventory,
    exportConfig: mocks.exportConfig,
    checkImport: mocks.checkImport,
    importConfig: mocks.importConfig,
  },
  createConfigSyncService: () => ({
    getInventory: vi.fn(),
    exportConfig: vi.fn(),
    checkImport: vi.fn(),
    importConfig: vi.fn(),
  }),
}))

const INVENTORY_ITEMS: ConfigSyncItem[] = [
  {
    kind: 'agents',
    name: 'reviewer',
    dependencies: [
      { kind: 'models', name: 'openai/gpt' },
      { kind: 'providers', name: 'openai' },
      { kind: 'skillPackages', name: 'core-tools' },
    ],
  },
  { kind: 'models', name: 'openai/gpt', dependencies: [{ kind: 'providers', name: 'openai' }] },
  { kind: 'providers', name: 'openai', dependencies: [] },
  { kind: 'skillPackages', name: 'core-tools', dependencies: [] },
  { kind: 'environments', name: 'dev-env', dependencies: [] },
  { kind: 'mcpServers', name: 'fetch', dependencies: [] },
  { kind: 'settings', name: 'settings', dependencies: [] },
]

const KIND_TOGGLES = ['Agent', '模型', '提供商', '技能包', '环境', 'MCP 服务', '设置']
const ITEM_LABELS = [
  'Agent: reviewer',
  '模型: openai/gpt',
  '提供商: openai',
  '技能包: core-tools',
  '环境: dev-env',
  'MCP 服务: fetch',
  '设置: settings',
]

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** 预检查默认全量可导入；用例按需覆盖 created/updated/skipped 表达四种确认形态。 */
function makePreview(overrides: Partial<ConfigSyncImportCheckDTO> = {}): ConfigSyncImportCheckDTO {
  return {
    created: [{ kind: 'providers', name: 'openai' }],
    updated: [],
    skipped: [],
    ...overrides,
  }
}

function renderSyncTab(
  options: { settingsDirty?: boolean; items?: ConfigSyncItem[] } = {},
) {
  if (options.items) {
    mocks.getInventory.mockResolvedValue({ items: options.items })
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const reloadSettings = vi.fn()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <SyncTab
        reloadSettings={reloadSettings}
        settingsDirty={options.settingsDirty ?? false}
      />
    </QueryClientProvider>,
  )
  return { ...view, queryClient, reloadSettings }
}

function fileInput(container: HTMLElement): HTMLInputElement {
  const input = container.querySelector('input[type="file"]')
  if (input == null) {
    throw new Error('file input not found')
  }
  return input as HTMLInputElement
}

async function openExportModal() {
  const exportButton = await screen.findByRole('button', { name: '导出' })
  await waitFor(() => expect(exportButton).toBeEnabled())
  await userEvent.setup().click(exportButton)
  return screen.getByRole('dialog', { name: '导出配置' })
}

async function clearAllSelections() {
  const user = userEvent.setup()
  for (const label of KIND_TOGGLES) {
    await user.click(screen.getByRole('checkbox', { name: label }))
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getInventory.mockResolvedValue({ items: INVENTORY_ITEMS })
  mocks.exportConfig.mockResolvedValue({ yaml: 'providers: []' })
  mocks.checkImport.mockResolvedValue(makePreview())
  mocks.importConfig.mockResolvedValue({ imported: [], skipped: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('SyncTab', () => {
  it('shows only the two operations with a brief description', async () => {
    renderSyncTab()
    expect(screen.getByText('导入或导出服务端已保存的配置。')).toBeInTheDocument()
    const importButton = screen.getByRole('button', { name: '导入' })
    const exportButton = await screen.findByRole('button', { name: '导出' })
    // 库存加载完成后才能导出；导入不依赖库存。
    await waitFor(() => expect(exportButton).toBeEnabled())
    expect(importButton).toBeEnabled()
  })

  it('disables export while inventory is loading and surfaces load failures with retry', async () => {
    const pending = deferred<{ items: ConfigSyncItem[] }>()
    mocks.getInventory.mockReturnValue(pending.promise)
    renderSyncTab()
    const exportButton = screen.getByRole('button', { name: '导出' })
    expect(exportButton).toBeDisabled()
    expect(screen.getByText('正在加载配置…')).toBeInTheDocument()

    pending.reject(new Error('offline'))
    await waitFor(() => expect(screen.getByText('配置加载失败。')).toBeInTheDocument())
    mocks.getInventory.mockResolvedValue({ items: INVENTORY_ITEMS })
    await userEvent.setup().click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '导出' })).toBeEnabled())
  })

  it('defaults to selecting every item across all seven kinds with no credentials switch', async () => {
    renderSyncTab()
    const dialog = await openExportModal()
    expect(within(dialog).getByText('配置文件包含密钥，请妥善保管。')).toBeInTheDocument()
    // 7 个种类开关 + 7 个条目，恰好 14 个 checkbox：不存在额外凭据开关。
    expect(within(dialog).getAllByRole('checkbox')).toHaveLength(14)
    for (const label of ITEM_LABELS) {
      expect(within(dialog).getByRole('checkbox', { name: label })).toBeChecked()
    }
    expect(
      within(dialog).getByText('共 7 类 · 7 项（含依赖）'),
    ).toBeInTheDocument()
  })

  it('adds dependency closure automatically and locks dependencies to the selected source', async () => {
    renderSyncTab()
    const dialog = await openExportModal()
    await clearAllSelections()
    await userEvent.setup().click(within(dialog).getByRole('checkbox', { name: 'Agent: reviewer' }))

    for (const label of ['模型: openai/gpt', '提供商: openai', '技能包: core-tools']) {
      const checkbox = within(dialog).getByRole('checkbox', { name: label })
      expect(checkbox).toBeChecked()
      // 依赖不能独立移除。
      expect(checkbox).toBeDisabled()
    }
    expect(within(dialog).getAllByText('依赖')).toHaveLength(3)
    expect(within(dialog).getByText('共 4 类 · 4 项（含依赖）')).toBeInTheDocument()

    // 移除源选择后依赖随之释放。
    await userEvent.setup().click(within(dialog).getByRole('checkbox', { name: 'Agent: reviewer' }))
    for (const label of ['模型: openai/gpt', '提供商: openai', '技能包: core-tools']) {
      expect(within(dialog).getByRole('checkbox', { name: label })).not.toBeChecked()
    }
  })

  it('sends only the directly selected roots so the backend resolves dependencies from current config', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderSyncTab()
    const dialog = await openExportModal()
    await clearAllSelections()
    await userEvent.setup().click(within(dialog).getByRole('checkbox', { name: 'Agent: reviewer' }))
    // UI 仍展示完整闭包。
    expect(within(dialog).getByText('共 4 类 · 4 项（含依赖）')).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: '导出' }))
    await waitFor(() => expect(mocks.exportConfig).toHaveBeenCalledTimes(1))
    // 只提交用户直接勾选的 root；依赖由后端按当时权威配置补齐，避免导出陈旧依赖。
    expect(mocks.exportConfig).toHaveBeenCalledWith([{ kind: 'agents', name: 'reviewer' }])
    expect(click).toHaveBeenCalled()
  })

  it('moves focus into the modal and restores it on close', async () => {
    const user = userEvent.setup()
    renderSyncTab()
    const exportButton = await screen.findByRole('button', { name: '导出' })
    await waitFor(() => expect(exportButton).toBeEnabled())
    await user.click(exportButton)

    const dialog = screen.getByRole('dialog', { name: '导出配置' })
    expect(dialog.contains(document.activeElement)).toBe(true)

    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(document.activeElement).toBe(exportButton)
  })

  it('cycles Tab focus within the modal in both directions', async () => {
    const user = userEvent.setup()
    renderSyncTab()
    const dialog = await openExportModal()
    const first = within(dialog).getByRole('button', { name: '关闭' })
    const last = within(dialog).getByRole('button', { name: '导出' })
    // 弹窗首尾互相续接，不让键盘焦点落到背景设置页。
    first.focus()
    await user.tab({ shift: true })
    expect(last).toHaveFocus()
    await user.tab()
    expect(first).toHaveFocus()
    dialog.focus()
    await user.tab({ shift: true })
    expect(last).toHaveFocus()
  })

  it('keeps focus on the modal when every control is disabled during import', async () => {
    const user = userEvent.setup()
    const pending = deferred<{ imported: []; skipped: [] }>()
    mocks.importConfig.mockReturnValue(pending.promise)
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))
    // 正在导入时控件均禁用，Tab 仍留在弹窗而不是穿透到后台。
    await user.tab()
    expect(dialog).toHaveFocus()
    pending.resolve({ imported: [], skipped: [] })
    await within(dialog).findByText('没有可导入的配置。')
  })

  it('blocks export when the selection is empty', async () => {
    renderSyncTab()
    const dialog = await openExportModal()
    await clearAllSelections()
    expect(within(dialog).getByText('请至少选择一项。')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '导出' })).toBeDisabled()
  })

  it('exports the effective scope and downloads the returned YAML as kk-studio-config.yaml', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sync')
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const anchors: HTMLAnchorElement[] = []
    const realCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(((
      tagName: string,
      options?: ElementCreationOptions,
    ) => {
      const element = realCreateElement(tagName, options)
      if (tagName === 'a') {
        anchors.push(element as HTMLAnchorElement)
      }
      return element
    }) as typeof document.createElement)

    renderSyncTab()
    const dialog = await openExportModal()
    fireEvent.click(within(dialog).getByRole('button', { name: '导出' }))

    await waitFor(() => expect(mocks.exportConfig).toHaveBeenCalledTimes(1))
    expect(mocks.exportConfig).toHaveBeenCalledWith(
      INVENTORY_ITEMS.map((item) => ({ kind: item.kind, name: item.name })),
    )
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(anchors[0]?.download).toBe('kk-studio-config.yaml')
    expect(click).toHaveBeenCalledTimes(1)
    // objectURL 在下载后立即撤销，不长期占用。
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:sync')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByText('已导出 kk-studio-config.yaml')).toBeInTheDocument()
  })

  it('reports an export failure without downloading', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL')
    mocks.exportConfig.mockRejectedValue(new Error('export boom'))
    renderSyncTab()
    const dialog = await openExportModal()
    fireEvent.click(within(dialog).getByRole('button', { name: '导出' }))

    await waitFor(() => expect(within(dialog).getByRole('alert')).toHaveTextContent('export boom'))
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: '导出配置' })).toBeInTheDocument()
  })

  it('cancels export without posting', async () => {
    renderSyncTab()
    const dialog = await openExportModal()
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.exportConfig).not.toHaveBeenCalled()
  })

  it('re-selects a whole kind after clearing it', async () => {
    renderSyncTab()
    const dialog = await openExportModal()
    await clearAllSelections()
    await userEvent.setup().click(within(dialog).getByRole('checkbox', { name: '提供商' }))
    expect(within(dialog).getByRole('checkbox', { name: '提供商: openai' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'Agent: reviewer' })).not.toBeChecked()
  })

  it('omits groups whose kinds have no inventory items', async () => {
    renderSyncTab({
      items: [
        { kind: 'agents', name: 'reviewer', dependencies: [] },
        { kind: 'settings', name: 'settings', dependencies: [] },
      ],
    })
    const dialog = await openExportModal()
    expect(within(dialog).getByText('Agent、模型与提供商')).toBeInTheDocument()
    expect(within(dialog).getByText('设置')).toBeInTheDocument()
    expect(within(dialog).queryByText('环境')).not.toBeInTheDocument()
    expect(within(dialog).queryByText('MCP 服务')).not.toBeInTheDocument()
    expect(within(dialog).queryByText('技能包')).not.toBeInTheDocument()
  })

  it('stays open while exporting and ignores backdrop dismissal', async () => {
    const pending = deferred<{ yaml: string }>()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    mocks.exportConfig.mockReturnValue(pending.promise)
    renderSyncTab()
    const dialog = await openExportModal()
    fireEvent.click(within(dialog).getByRole('button', { name: '导出' }))

    expect(within(dialog).getByRole('button', { name: '导出中…' })).toBeDisabled()
    fireEvent.mouseDown(document.querySelector('.modal-backdrop')!)
    expect(screen.getByRole('dialog', { name: '导出配置' })).toBeInTheDocument()

    pending.resolve({ yaml: 'providers: []' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(click).toHaveBeenCalled()
  })

  it('closes the export modal on Escape', async () => {
    renderSyncTab()
    await openExportModal()
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})

describe('SyncTab import', () => {
  it('checks on selection, previews created/updated/skipped and imports only after explicit partial confirmation', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockResolvedValue(
      makePreview({
        created: [{ kind: 'providers', name: 'openai' }],
        updated: [{ kind: 'models', name: 'openai/gpt' }],
        skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
      }),
    )
    mocks.importConfig.mockResolvedValue({
      imported: [
        { kind: 'providers', name: 'openai' },
        { kind: 'models', name: 'openai/gpt' },
      ],
      skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
    })
    const { container, queryClient, reloadSettings } = renderSyncTab()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const file = new File(['providers: []'], 'kk.yaml', { type: 'application/yaml' })

    await user.upload(fileInput(container), file)
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    // 只读选择即预检查，尚未确认前绝不执行导入。
    expect(mocks.checkImport).toHaveBeenCalledWith('providers: []')
    expect(mocks.importConfig).not.toHaveBeenCalled()
    expect(within(dialog).getByText('导入前请确认以下变更计划。')).toBeInTheDocument()
    expect(within(dialog).getByText('文件：kk.yaml')).toBeInTheDocument()
    expect(within(dialog).getByText('将新增')).toBeInTheDocument()
    expect(within(dialog).getByText('将覆盖')).toBeInTheDocument()
    expect(within(dialog).getByText('将跳过')).toBeInTheDocument()
    expect(within(dialog).getByText('Agent: broken — unsupported tool')).toBeInTheDocument()
    // 存在跳过项时只提供部分导入入口，没有全量确认按钮。
    expect(within(dialog).queryByRole('button', { name: '确认导入' })).not.toBeInTheDocument()
    // YAML 原文不渲染。
    expect(dialog.textContent).not.toContain('providers: []')

    await user.click(within(dialog).getByRole('button', { name: '仅导入可用配置' }))

    // 只有用户点击部分导入才显式授权 allowPartial。
    expect(mocks.importConfig).toHaveBeenCalledWith('providers: []', true)
    expect(await within(dialog).findByText('已导入')).toBeInTheDocument()
    expect(within(dialog).getByText('提供商: openai')).toBeInTheDocument()
    expect(within(dialog).getByText('已跳过')).toBeInTheDocument()
    expect(within(dialog).getByText('Agent: broken — unsupported tool')).toBeInTheDocument()
    // 结果含跳过条目时标记为部分结果，不当作纯成功。
    expect(dialog.querySelector('.settings-sync-result')).toHaveAttribute('data-state', 'partial')

    // 导入写入后刷新目录查询与设置权威数据。
    await waitFor(() => {
      for (const key of [
        ['providers'],
        ['models'],
        ['agents'],
        ['skills'],
        ['environments'],
        ['mcp-servers'],
        ['config-sync', 'inventory'],
      ]) {
        expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: key })
      }
    })
    expect(reloadSettings).toHaveBeenCalledTimes(1)
  })

  it('does not treat a backend failure as success', async () => {
    const user = userEvent.setup()
    mocks.importConfig.mockRejectedValue(new Error('malformed yaml'))
    const { container, queryClient, reloadSettings } = renderSyncTab()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const file = new File(['bad'], 'kk.yaml', { type: 'application/yaml' })

    await user.upload(fileInput(container), file)
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('malformed yaml')
    expect(within(dialog).queryByText('已导入')).not.toBeInTheDocument()
    expect(reloadSettings).not.toHaveBeenCalled()
    expect(invalidateSpy).not.toHaveBeenCalled()
  })

  it('cancels without posting', async () => {
    const user = userEvent.setup()
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.importConfig).not.toHaveBeenCalled()
  })

  it('disables the operation while an import is in progress', async () => {
    const user = userEvent.setup()
    const pending = deferred<{ imported: []; skipped: [] }>()
    mocks.importConfig.mockReturnValue(pending.promise)
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })

    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))
    const importing = within(dialog).getByRole('button', { name: '导入中…' })
    expect(importing).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '取消' })).toBeDisabled()

    pending.resolve({ imported: [], skipped: [] })
    expect(await within(dialog).findByText('没有可导入的配置。')).toBeInTheDocument()
  })

  it('resets the file input so the same file can be selected again', async () => {
    const user = userEvent.setup()
    const { container } = renderSyncTab()
    const input = fileInput(container)
    const file = new File(['a: 1'], 'same.yaml')

    await user.upload(input, file)
    expect(input.value).toBe('')
    expect(await screen.findByRole('dialog', { name: '导入配置' })).toBeInTheDocument()
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '取消' }))

    await user.upload(fileInput(container), file)
    expect(await screen.findByRole('dialog', { name: '导入配置' })).toBeInTheDocument()
  })

  it('rejects a non-YAML file before reading it', async () => {
    const { container } = renderSyncTab()
    const input = fileInput(container)
    fireEvent.change(input, { target: { files: [new File(['x'], 'notes.txt')] } })

    expect(await screen.findByText('请选择 .yaml 或 .yml 文件。')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.importConfig).not.toHaveBeenCalled()
  })

  it('opens the file picker from the import button and clears the previous error', async () => {
    const user = userEvent.setup()
    const { container } = renderSyncTab()
    const input = fileInput(container)
    const clickSpy = vi.spyOn(input, 'click').mockImplementation(() => undefined)

    fireEvent.change(input, { target: { files: [new File(['x'], 'notes.txt')] } })
    expect(await screen.findByText('请选择 .yaml 或 .yml 文件。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '导入' }))
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('请选择 .yaml 或 .yml 文件。')).not.toBeInTheDocument()
  })

  it('ignores an empty file selection', () => {
    const { container } = renderSyncTab()
    fireEvent.change(fileInput(container), { target: { files: [] } })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('reports a file read failure without opening the confirm modal', async () => {
    const { container } = renderSyncTab()
    const unreadable = {
      name: 'broken.yaml',
      text: () => Promise.reject(new Error('io')),
    } as unknown as File
    fireEvent.change(fileInput(container), { target: { files: [unreadable] } })

    expect(await screen.findByText('文件读取失败。')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('ignores a second file selected while the first is still being checked', async () => {
    const { container } = renderSyncTab()
    const input = fileInput(container)
    const checking = deferred<ConfigSyncImportCheckDTO>()
    mocks.checkImport.mockReturnValueOnce(checking.promise)
    const secondText = vi.fn(async () => 'second: 1')
    const firstFile = { name: 'first.yaml', text: async () => 'first: 1' } as unknown as File
    const secondFile = { name: 'second.yaml', text: secondText } as unknown as File

    fireEvent.change(input, { target: { files: [firstFile] } })
    // 检查期间入口禁用，第二次选择被忽略，不会覆盖首个文件。
    expect(screen.getByRole('button', { name: '导入' })).toBeDisabled()
    fireEvent.change(input, { target: { files: [secondFile] } })
    expect(secondText).not.toHaveBeenCalled()

    checking.resolve(makePreview())
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    expect(within(dialog).getByText('文件：first.yaml')).toBeInTheDocument()
    expect(fileInput(container)).toBeEnabled()
  })

  it('rejects the file when the precheck fails and can be retried with another file', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockRejectedValueOnce(new Error('unsupported file structure'))
    const { container } = renderSyncTab()

    await user.upload(fileInput(container), new File(['bad'], 'kk.yaml'))
    // 检查失败走既有错误通知，不进入可导入确认状态。
    expect(await screen.findByRole('alert')).toHaveTextContent('unsupported file structure')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.importConfig).not.toHaveBeenCalled()
    expect(fileInput(container)).toBeEnabled()

    // 重新选择可再次触发检查，成功后正常进入确认弹窗。
    mocks.checkImport.mockResolvedValueOnce(makePreview())
    await user.upload(fileInput(container), new File(['ok'], 'good.yaml'))
    expect(await screen.findByRole('dialog', { name: '导入配置' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('falls back to the local check-failure message when the rejection has no readable error', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockRejectedValueOnce('opaque')
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['bad'], 'kk.yaml'))

    expect(await screen.findByRole('alert')).toHaveTextContent('配置检查失败。')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('offers no execute action when the precheck has nothing importable', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockResolvedValue(
      makePreview({
        created: [],
        updated: [],
        skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
      }),
    )
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })

    expect(within(dialog).getByText('此文件没有可导入的配置。')).toBeInTheDocument()
    // 无可用项时既没有全量也没有部分执行按钮，只保留取消。
    expect(within(dialog).queryByRole('button', { name: '确认导入' })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: '仅导入可用配置' })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '取消' })).toBeInTheDocument()
    expect(within(dialog).getByText('Agent: broken — unsupported tool')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mocks.importConfig).not.toHaveBeenCalled()
  })

  it('confirms a fully importable plan without partial authorization', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockResolvedValue(
      makePreview({
        created: [{ kind: 'providers', name: 'openai' }],
        updated: [{ kind: 'models', name: 'openai/gpt' }],
      }),
    )
    mocks.importConfig.mockResolvedValue({
      imported: [
        { kind: 'providers', name: 'openai' },
        { kind: 'models', name: 'openai/gpt' },
      ],
      skipped: [],
    })
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })

    // 无跳过项时使用全量确认按钮，部分导入入口不出现。
    expect(within(dialog).queryByRole('button', { name: '仅导入可用配置' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))

    expect(mocks.importConfig).toHaveBeenCalledWith('a: 1', false)
    expect(await within(dialog).findByText('已导入')).toBeInTheDocument()
    expect(within(dialog).queryByText('已跳过')).not.toBeInTheDocument()
  })

  it('falls back to the local message when the backend rejects without a readable error', async () => {
    const user = userEvent.setup()
    mocks.importConfig.mockRejectedValue('opaque')
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('导入失败。')
  })

  it('closes the import confirm modal on Escape but not while importing', async () => {
    const user = userEvent.setup()
    const pending = deferred<{ imported: []; skipped: [] }>()
    mocks.importConfig.mockReturnValue(pending.promise)
    const { container } = renderSyncTab()
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    await screen.findByRole('dialog', { name: '导入配置' })

    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '确认导入' }))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.getByRole('dialog', { name: '导入配置' })).toBeInTheDocument()

    pending.resolve({ imported: [], skipped: [] })
    await waitFor(() =>
      expect(within(screen.getByRole('dialog')).getByText('没有可导入的配置。')).toBeInTheDocument(),
    )
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('keeps an unsaved settings draft and offers an explicit reload on request', async () => {
    const user = userEvent.setup()
    mocks.checkImport.mockResolvedValue(
      makePreview({ created: [{ kind: 'settings', name: 'settings' }] }),
    )
    mocks.importConfig.mockResolvedValue({
      imported: [{ kind: 'settings', name: 'settings' }],
      skipped: [],
    })
    const { container, reloadSettings } = renderSyncTab({ settingsDirty: true })
    await user.upload(fileInput(container), new File(['a: 1'], 'kk.yaml'))
    const dialog = await screen.findByRole('dialog', { name: '导入配置' })
    await user.click(within(dialog).getByRole('button', { name: '确认导入' }))

    expect(await within(dialog).findByText('设置中有未保存的更改，将保持原样。')).toBeInTheDocument()
    // 存在 draft 时不自动刷新设置。
    expect(reloadSettings).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: '重新加载设置' }))
    expect(reloadSettings).toHaveBeenCalledTimes(1)
  })
})
