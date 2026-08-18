import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type {
  SystemSettingsDTO,
  SystemSettingsUpdateDTO,
} from '@/shared/api/contracts/system-settings'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { makeSettingsDto } from '@/features/settings/settings-test-fixtures'
import { SettingsPage } from '@/features/settings/SettingsPage'

const mocks = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))

vi.mock('@/shared/api/system-settings-service', () => ({
  systemSettingsService: { get: mocks.get, update: mocks.update },
  createSystemSettingsService: () => ({ get: vi.fn(), update: vi.fn() }),
}))

/** 测试级「真实后端」：GET 返回当前状态，PUT 按 expectedVersion CAS 推进版本。 */
function createBackend(initial: SystemSettingsDTO = makeSettingsDto()) {
  let state = initial
  const get = vi.fn(async () => state)
  const update = vi.fn(async (update: SystemSettingsUpdateDTO) => {
    if (update.expectedVersion !== state.version) {
      throw new ApiError('conflict', 409, 'version_conflict', {
        resource: 'system_settings',
        expectedVersion: update.expectedVersion,
        actualVersion: state.version,
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

function serverTab(name: string) {
  return screen.getByRole('tab', { name })
}

describe('system settings server editor', () => {
  beforeEach(() => {
    mocks.get.mockReset()
    mocks.update.mockReset()
  })

  it('hydrates the authoritative GET aggregate into the section editors', async () => {
    mocks.get.mockResolvedValue(makeSettingsDto())
    renderSettings()

    await userEvent.click(serverTab('AI 运行时'))
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue(3)
    expect(screen.getByLabelText('基础延迟（毫秒）')).toHaveValue(2000)
    expect(screen.getByLabelText('保留最近 token')).toHaveValue(20000)
  })

  it('labels permission as next-invocation and default YOLO as new-chat timing', async () => {
    mocks.get.mockResolvedValue(makeSettingsDto())
    renderSettings()

    await userEvent.click(serverTab('工具与权限'))
    expect(await screen.findByText('下次调用生效')).toBeInTheDocument()
    expect(screen.getByText('新建对话生效')).toBeInTheDocument()
    expect(screen.getByText('重启后生效')).toBeInTheDocument()
  })

  it('shows a loading state while the aggregate is pending and renders content after it resolves', async () => {
    let resolveGet: (dto: SystemSettingsDTO) => void
    mocks.get.mockImplementation(
      () => new Promise<SystemSettingsDTO>((resolve) => {
        resolveGet = resolve
      }),
    )
    renderSettings()

    await userEvent.click(serverTab('AI 运行时'))
    expect(screen.getByRole('status')).toHaveTextContent('正在加载设置…')

    resolveGet!(makeSettingsDto())
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue(3)
  })

  it('shows a load error with retry and recovers on retry', async () => {
    const backend = createBackend()
    mocks.get.mockRejectedValueOnce(new Error('network down')).mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(serverTab('AI 运行时'))
    expect(await screen.findByText('设置加载失败。')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByLabelText('最大重试次数')).toHaveValue(3)
  })

  it('saves the complete aggregate with expectedVersion and clears the dirty state on success', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    renderSettings()

    await userEvent.click(serverTab('AI 运行时'))
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

    await userEvent.click(serverTab('高级'))
    const lease = await screen.findByLabelText('处理器租约时长（毫秒）')
    await userEvent.clear(lease)
    await userEvent.type(lease, '45000')
    const steps = screen.getByLabelText('Thread 步数上限')
    await userEvent.clear(steps)
    await userEvent.type(steps, '32')

    await userEvent.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.getByText('已是最新')).toBeInTheDocument())
    const sent = mocks.update.mock.calls[0]![0] as SystemSettingsUpdateDTO
    expect(sent.advanced.processorLeaseDurationMillis).toBe('45000')
    expect(sent.advanced.threadStepLimit).toBe(32)
  })

  it('shows a conflict dialog on 409 and reloads only after user confirmation', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    const reloadPage = vi.fn()
    renderSettings(reloadPage)

    await userEvent.click(serverTab('工具与权限'))
    const yolo = screen.getByRole('switch', { name: '默认 YOLO' })
    expect(yolo).toHaveAttribute('aria-checked', 'false')
    await userEvent.click(yolo)
    expect(yolo).toHaveAttribute('aria-checked', 'true')

    // 页面仍持有 version=0 时，另一个写入者把权威聚合推进到 version=1。
    backend.setState(makeSettingsDto({ version: '1' }))
    await userEvent.click(screen.getByRole('button', { name: '保存' }))

    const dialog = await screen.findByRole('alertdialog', { name: '设置已在其他位置修改' })
    expect(dialog).toHaveTextContent('当前未保存的修改将会丢失')
    expect(dialog.querySelector('.lucide-refresh-cw')).toBeInTheDocument()
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
    const reopened = await screen.findByRole('alertdialog', { name: '设置已在其他位置修改' })

    await userEvent.click(within(reopened).getByRole('button', { name: '重新加载' }))
    expect(reloadPage).toHaveBeenCalledOnce()
  })

  it('resets the draft back to the authoritative aggregate', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(serverTab('环境'))
    const field = await screen.findByLabelText('最大资源字节数')
    await userEvent.clear(field)
    await userEvent.type(field, '99999999')
    expect(screen.getByText('有未保存的更改')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重置' }))
    expect(screen.getByText('已是最新')).toBeInTheDocument()
    expect(field).toHaveValue(8388608)
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('blocks saving with a client-side validation error when a required numeric field is empty', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    renderSettings()

    await userEvent.click(serverTab('环境'))
    const field = await screen.findByLabelText('最大资源字节数')
    await userEvent.clear(field)

    await userEvent.click(screen.getByRole('button', { name: '保存' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('存在为空的其他必需数值字段')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('blocks saving when two permission groups collide to the same canonical tool name after trim', async () => {
    const backend = createBackend()
    mocks.get.mockImplementation(backend.get)
    mocks.update.mockImplementation(backend.update)
    renderSettings()

    await userEvent.click(serverTab('工具与权限'))
    await userEvent.click(screen.getByRole('button', { name: '添加工具' }))
    const toolInputs = screen.getAllByLabelText('工具')
    // 新分组输入一个与既有 write 在 trim 后相同的名称：保存必须拒绝而非静默覆盖前一个分组的规则。
    await userEvent.type(toolInputs[toolInputs.length - 1]!, '  write  ')

    await userEvent.click(screen.getByRole('button', { name: '保存' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('两个权限分组在去空格后使用了相同的工具名')
    expect(mocks.update).not.toHaveBeenCalled()
  })
})
