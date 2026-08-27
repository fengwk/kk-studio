import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type {
  SystemSettingsDTO,
  SystemSettingsUpdateDTO,
} from '@/shared/api/contracts/system-settings'
import { queryKeys } from '@/shared/lib/query-keys'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'

const mocks = vi.hoisted(() => ({ get: vi.fn(), getSchema: vi.fn(), update: vi.fn() }))

vi.mock('@/shared/api/system-settings-service', () => ({
  systemSettingsService: { get: mocks.get, getSchema: mocks.getSchema, update: mocks.update },
  createSystemSettingsService: () => ({ get: vi.fn(), getSchema: vi.fn(), update: vi.fn() }),
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listTools: vi.fn(async () => [
      {
        id: 'base.bash',
        name: 'bash',
        version: '1',
        description: null,
        backend: 'HOST',
      },
      {
        id: 'base.write',
        name: 'write',
        version: '1',
        description: null,
        backend: 'HOST',
      },
      {
        id: 'base.edit',
        name: 'edit',
        version: '1',
        description: null,
        backend: 'HOST',
      },
    ]),
    listModels: vi.fn(async () => ({ pageNumber: 1, pageSize: 50, totalCount: 0, results: [] })),
  },
}))

/** 测试级「真实后端」：GET 返回当前状态，PUT 按 expectedVersion CAS 推进版本。 */
function createBackend(initial: SystemSettingsDTO = makeSettingsDto()) {
  let state = initial
  const get = vi.fn(async () => state)
  const update = vi.fn(async (update: SystemSettingsUpdateDTO) => {
    if (update.expectedVersion !== state.version) {
      throw new ApiError('conflict', 409, 'version_conflict', {
        resource: 'system_settings',
        reason: 'VERSION_CONFLICT',
        expectedVersion: update.expectedVersion,
        actualVersion: state.version,
        detail: `system settings version conflict: expected=${update.expectedVersion} actual=${state.version}`,
      })
    }
    state = {
      ...makeSettingsDto({ version: String(Number(state.version) + 1) }),
      tool: update.tool,
      aiRuntime: update.aiRuntime,
      environment: update.environment,
      integrations: update.integrations,
      storageMedia: update.storageMedia,
      advanced: update.advanced,
    }
    return state
  })
  const setState = (next: SystemSettingsDTO) => {
    state = next
  }
  return { get, update, setState }
}

function renderSettings(reloadPage?: () => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <BrowserPreferencesProvider>
        <SettingsPage reloadPage={reloadPage} />
      </BrowserPreferencesProvider>
    </QueryClientProvider>,
  )
  return { queryClient, ...result }
}

async function serverTab(name: string) {
  return await screen.findByRole('tab', { name })
}

describe('system settings server editor', () => {
  beforeEach(() => {
    mocks.get.mockReset()
    mocks.getSchema.mockReset()
    mocks.getSchema.mockResolvedValue(makeSettingsSchema())
    mocks.update.mockReset()
  })

  it('hydrates the authoritative GET aggregate into the section editors', async () => {
    mocks.get.mockResolvedValue(makeSettingsDto())
    renderSettings()

    await userEvent.click(await serverTab('AI 运行时'))
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue('3')
    expect(screen.getByLabelText('基础延迟（毫秒）')).toHaveValue('2000')
    expect(screen.getByLabelText('保留最近 token')).toHaveValue('20000')
  })

  it('labels permission as next-invocation and default YOLO as new-chat timing', async () => {
    mocks.get.mockResolvedValue(makeSettingsDto())
    renderSettings()

    await userEvent.click(await serverTab('工具与权限'))
    expect(await screen.findAllByText('下次调用生效')).toHaveLength(2)
    expect(screen.getByText('新建对话生效')).toBeInTheDocument()
    expect(screen.queryByText('重启后生效')).not.toBeInTheDocument()
  })

  it('does not expose unvalidated server tabs while the aggregate is pending', async () => {
    let resolveGet: (dto: SystemSettingsDTO) => void
    mocks.get.mockImplementation(
      () => new Promise<SystemSettingsDTO>((resolve) => {
        resolveGet = resolve
      }),
    )
    renderSettings()

    expect(await screen.findByRole('tab', { name: '常规' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'AI 运行时' })).not.toBeInTheDocument()

    resolveGet!(makeSettingsDto())
    await userEvent.click(await serverTab('AI 运行时'))
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue('3')
  })

  it('shows a load error with retry and recovers on retry', async () => {
    const backend = createBackend()
    mocks.get.mockRejectedValueOnce(new Error('network down')).mockImplementation(backend.get)
    renderSettings()

    expect(await screen.findByText('设置加载失败。')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await userEvent.click(await serverTab('AI 运行时'))
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue('3')
  })

  it('does not build any server tab when the schema fails validation', async () => {
    // 让 schema 无效（sections 为空）：SettingsPage 不得渲染任何 server tab，且只显示 General。
    mocks.get.mockResolvedValue(makeSettingsDto())
    mocks.getSchema.mockResolvedValue({ sections: [] })
    renderSettings()

    expect(await screen.findByRole('tab', { name: '常规' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'AI 运行时' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '工具与权限' })).not.toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '设置表单元数据无效，无法渲染编辑器。',
    )
    expect(screen.getByRole('tabpanel')).toHaveTextContent('键盘快捷键')
  })

  it('saves the complete aggregate with expectedVersion and clears the dirty state on success', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    renderSettings()

    await userEvent.click(await serverTab('AI 运行时'))
    const field = await screen.findByLabelText('基础延迟（毫秒）')
    await userEvent.clear(field)
    await userEvent.type(field, '3000')

    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(screen.getByText('已是最新')).toBeInTheDocument())
    expect(mocks.update).toHaveBeenCalledTimes(1)
    const sent = mocks.update.mock.calls[0]![0] as SystemSettingsUpdateDTO
    expect(sent.expectedVersion).toBe('0')
    expect(sent.aiRuntime.retryBaseDelayMillis).toBe('3000')
    // 完整聚合：六个 section 全部在请求体中。
    expect(sent.tool).toBeDefined()
    expect(sent.aiRuntime).toBeDefined()
    expect(sent.environment).toBeDefined()
    expect(sent.integrations).toBeDefined()
    expect(sent.storageMedia).toBeDefined()
    expect(sent.advanced).toBeDefined()
    // 成功后 refetch：GET 至少被再次调用。
    await waitFor(() => expect(mocks.get.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('sends Long fields as decimal strings and Integer fields as numbers on save', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    renderSettings()

    await userEvent.click(await serverTab('高级'))
    const lease = await screen.findByLabelText('处理器租约时长（毫秒）')
    await userEvent.clear(lease)
    await userEvent.type(lease, '45000')
    const tasks = screen.getByLabelText('最大分发任务数')
    await userEvent.clear(tasks)
    await userEvent.type(tasks, '32')

    await userEvent.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.getByText('已是最新')).toBeInTheDocument())
    const sent = mocks.update.mock.calls[0]![0] as SystemSettingsUpdateDTO
    expect(sent.advanced.processorLeaseDurationMillis).toBe('45000')
    expect(sent.advanced.dispatcherMaxDispatchTasks).toBe(32)
  })

  it('shows a conflict dialog on 409 and reloads only after user confirmation', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    const reloadPage = vi.fn()
    renderSettings(reloadPage)

    await userEvent.click(await serverTab('工具与权限'))
    const yolo = screen.getByRole('switch', { name: '默认 YOLO' })
    expect(yolo).toHaveAttribute('aria-checked', 'false')
    await userEvent.click(yolo)
    expect(yolo).toHaveAttribute('aria-checked', 'true')

    // 页面仍持有 version=0 时，另一个写入者把权威聚合推进到 version=1。
    backend.setState(makeSettingsDto({ version: '1' }))
    await userEvent.click(screen.getByRole('button', { name: '保存' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('持久状态已变化')
    expect(dialog).toHaveTextContent('原因：VERSION_CONFLICT')
    expect(dialog).toHaveTextContent('expected=0 actual=1')
    // 用户确认前不 refetch、不刷新、不覆盖 draft。
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(reloadPage).not.toHaveBeenCalled()
    expect(yolo).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()

    // 取消只关闭说明，保留本地草稿；再次保存仍会得到冲突弹窗。
    await userEvent.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(yolo).toHaveAttribute('aria-checked', 'true')
    await userEvent.click(screen.getByRole('button', { name: '保存' }))
    const reopened = await screen.findByRole('alertdialog')
    expect(reopened).toHaveTextContent('原因：VERSION_CONFLICT')

    await userEvent.click(within(reopened).getByRole('button', { name: '刷新' }))
    expect(reloadPage).toHaveBeenCalledOnce()
  })

  it('sends the draft snapshot base version after a background refetch advanced the authoritative version', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    const { queryClient } = renderSettings()

    // 以 v0 权威 hydration，然后编辑一个字段。
    await userEvent.click(await serverTab('AI 运行时'))
    const delay = await screen.findByLabelText('基础延迟（毫秒）')
    await userEvent.clear(delay)
    await userEvent.type(delay, '3000')

    // 另一写入者把后端推进到 v1，并改动了用户未触碰的字段（最大重试次数 3 -> 9）。
    const concurrent = makeSettingsDto({ version: '1' })
    concurrent.aiRuntime.retryMaxRetries = 9
    backend.setState(concurrent)

    // 后台 refetch：客户端权威变为 v1，但 draft 仍是 v0 快照（未触碰字段仍显示 v0 值）。
    await queryClient.refetchQueries({ queryKey: queryKeys.systemSettings.all })
    await waitFor(() => expect(mocks.get).toHaveBeenCalledTimes(2))
    expect((await backend.get()).version).toBe('1')
    expect(screen.getByLabelText('最大重试次数')).toHaveValue('3')
    expect(delay).toHaveValue('3000')

    // 保存必须携带 draft 快照的 baseVersion=0，而不是权威的 v1，否则会静默覆盖并发修改。
    await userEvent.click(screen.getByRole('button', { name: '保存' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('持久状态已变化')
    expect(dialog).toHaveTextContent('原因：VERSION_CONFLICT')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    const sent = mocks.update.mock.calls[0]![0] as SystemSettingsUpdateDTO
    expect(sent.expectedVersion).toBe('0')
    // 409 拒绝后后端未被旧 draft 覆盖：并发写入的 v1 字段仍然存在，版本未推进。
    const backendState = await backend.get()
    expect(backendState.aiRuntime.retryMaxRetries).toBe(9)
    expect(backendState.version).toBe('1')
  })

  it('resets the draft back to the authoritative aggregate', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(await serverTab('环境'))
    const field = await screen.findByLabelText('最大资源字节数')
    await userEvent.clear(field)
    await userEvent.type(field, '99999999')
    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重置' }))
    expect(screen.getByText('已是最新')).toBeInTheDocument()
    expect(field).toHaveValue('8388608')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('blocks saving with a client-side validation error when a required numeric field is empty', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(await serverTab('环境'))
    const field = await screen.findByLabelText('最大资源字节数')
    await userEvent.clear(field)

    await userEvent.click(screen.getByRole('button', { name: '保存' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('存在为空的其他必需数值字段')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('merges a newly added tool group onto an existing catalog tool instead of duplicating it', async () => {
    const initial = makeSettingsDto()
    initial.tool.permission = {
      'base.write': [{ pattern: '*', action: 'ask' }],
      'base.edit': [{ pattern: '*', action: 'ask' }],
      'base.bash': [{ pattern: '*', action: 'ask' }],
    }
    const backend = createBackend(initial)
    mocks.get.mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(await serverTab('工具与权限'))
    expect(await screen.findAllByLabelText(/^权限分组/)).toHaveLength(3)
    await userEvent.click(screen.getByRole('button', { name: '添加工具' }))
    const groups = screen.getAllByLabelText(/^权限分组/)
    expect(groups).toHaveLength(4)
    await chooseSelectOption(userEvent.setup(), '工具', 'base.write', within(groups[3]!))
    expect(screen.getAllByLabelText(/^权限分组/)).toHaveLength(3)
    expect(screen.getByLabelText('权限分组 base.write')).toBeInTheDocument()
  })
})
