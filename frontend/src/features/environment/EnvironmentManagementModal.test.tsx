import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentManagementModal } from '@/features/environment/EnvironmentManagementModal'
import { environmentService } from '@/features/environment/environment-service'
import { ApiError } from '@/shared/api/client'
import type { EnvironmentDTO, EnvironmentEventDTO } from '@/shared/api/generated/contracts'
import {
  testEnvironment as fixtureEnvironment,
  testRuntimeStatus,
} from '@/test-support/environment-test-fixtures'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    listEnvironmentEvents: vi.fn(),
    getEnvironmentRuntime: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

interface ManageAction {
  label: string
  run: () => void
  danger?: boolean
}

function testEnvironment(overrides: Partial<EnvironmentDTO> = {}): EnvironmentDTO {
  return fixtureEnvironment({
    id: 'env-test-1',
    name: 'test-environment',
    userName: 'dev-user',
    homeDirectory: '/home/dev',
    operatingSystem: 'linux',
    timeZone: 'Asia/Shanghai',
    note: 'Production host',
    daemonVersion: '1.2.3',
    lastSeenAt: 1784512800000,
    ...overrides,
  })
}

interface RenderOptions {
  onClose?: () => void
  actions?: ManageAction[]
  queryClient?: QueryClient
}

function renderModal(
  environment: EnvironmentDTO = testEnvironment(),
  options: RenderOptions = {},
) {
  const onClose = options.onClose ?? vi.fn()
  const queryClient = options.queryClient ?? new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentManagementModal
        environment={environment}
        onClose={onClose}
        actions={options.actions}
      />
    </QueryClientProvider>,
  )
  return { queryClient, view, onClose }
}

function manageDialog(): HTMLElement {
  return screen.getByRole('dialog', { name: '管理环境：test-environment' })
}

/** 诊断详情默认折叠：展开后才渲染宿主、配置与事件。 */
async function expandDiagnostics(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByText('诊断详情'))
  return screen.findByText('宿主信息')
}

/** 手动控制落定时机的事件响应，用于观察请求仍在途时的渲染。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function eventsRegion(): HTMLElement {
  return screen.getByRole('region', { name: '事件记录' })
}

describe('EnvironmentManagementModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(testRuntimeStatus())
  })

  /**
   * 测试意图：管理面是一组纯文字标签的动作入口加一段折叠的诊断详情；
   * 折叠状态不渲染宿主/配置/事件，也不读取事件端点。
   */
  it('renders management actions with plain labels and keeps diagnostics collapsed', () => {
    const actions: ManageAction[] = [
      { label: '编辑配置', run: vi.fn() },
      { label: '更新 Daemon', run: vi.fn() },
      { label: '删除环境', run: vi.fn(), danger: true },
    ]
    renderModal(testEnvironment(), { actions })
    const dialog = manageDialog()

    for (const action of actions) {
      const button = within(dialog).getByRole('button', { name: action.label })
      // 动作只用标签表达，不额外挂图标或说明。
      expect(button).toHaveTextContent(action.label)
      expect(button.querySelector('svg')).toBeNull()
    }
    expect(within(dialog).getByRole('button', { name: '删除环境' })).toHaveClass('danger')

    const diagnostics = within(dialog).getByText('诊断详情').closest('details') as HTMLElement | null
    expect(diagnostics).not.toBeNull()
    expect(diagnostics).not.toHaveAttribute('open')
    expect(within(dialog).queryByText('宿主信息')).toBeNull()
    expect(within(dialog).queryByText('配置')).toBeNull()
    expect(within(dialog).queryByText('事件记录')).toBeNull()
    expect(environmentService.listEnvironmentEvents).not.toHaveBeenCalled()
  })

  /** 测试意图：动作先关闭管理弹窗再打开原确认流，避免两层弹窗叠加。 */
  it('closes the modal before running the selected action', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const run = vi.fn()
    renderModal(testEnvironment(), { onClose, actions: [{ label: '重新生成 Token', run }] })

    await user.click(within(manageDialog()).getByRole('button', { name: '重新生成 Token' }))

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(run).toHaveBeenCalledTimes(1)
    expect(onClose.mock.invocationCallOrder[0]!).toBeLessThan(run.mock.invocationCallOrder[0]!)
  })

  /**
   * 测试意图：展开诊断后呈现宿主事实（含 UUID、最近一次 Daemon 版本、能力清单）
   * 与配置摘要，两者与连接状态是不同事实。
   */
  it('expands diagnostics with host facts and the configuration summary', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment({
      capabilities: [{ id: 'process.exec' }, { id: 'shell.exec' }],
    }))

    await expandDiagnostics(user)

    // 宿主信息：UUID 是卡片身份，不冒充名称。
    expect(screen.getByText('UUID')).toBeInTheDocument()
    expect(screen.getByText('env-test-1')).toBeInTheDocument()
    // 宿主信息展示最近一次 Daemon 版本，与 CAS 配置版本不是同一事实。
    expect(screen.getByText('上次 Daemon 版本')).toBeInTheDocument()
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('linux')).toBeInTheDocument()
    expect(screen.getByText('Asia/Shanghai')).toBeInTheDocument()
    expect(screen.getByText('dev-user')).toBeInTheDocument()
    expect(screen.getByText('/home/dev')).toBeInTheDocument()
    expect(screen.getByText('Production host')).toBeInTheDocument()
    expect(screen.getByText('process.exec · shell.exec')).toBeInTheDocument()
    // lastSeen 使用共享固定格式（分钟精度），不直接铺 UTC 原文。
    expect(screen.getByText(/2026-07-20 \d{2}:\d{2}/)).toBeInTheDocument()
    expect(screen.queryByText('2026-07-20T02:00:00.000Z')).toBeNull()

    // 配置摘要：状态徽标 + 卡片版本/已应用版本/代理，不把 READY 当成配置已应用。
    expect(screen.getByText('配置')).toBeInTheDocument()
    expect(await screen.findByText('已应用')).toBeInTheDocument()
    expect(screen.getByText('已应用版本')).toBeInTheDocument()
    expect(screen.getByText('直连')).toBeInTheDocument()

    expect(screen.queryByText('异步操作记录')).toBeNull()
    expect(screen.queryByText('Operations')).toBeNull()
  })

  /** 测试意图：最近活动按 epoch 毫秒（含亚秒值）用共享固定格式展示，不铺 UTC 原文。 */
  it.each([
    ['整秒', 1750000000000],
    ['亚秒', 1750000000500],
  ])('formats the %s lastSeen value with the shared minute format', async (_label, lastSeenAt) => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment({ lastSeenAt }))

    await expandDiagnostics(user)

    const hostSection = screen.getByRole('region', { name: '宿主信息' })
    expect(within(hostSection).getByText(/2025-06-15 \d{2}:\d{2}/)).toBeInTheDocument()
  })

  /** 测试意图：运行快照失败时配置状态明确未知，并在诊断里说明读取失败，不冒充已应用或直连。 */
  it('reports an unknown configuration state when the runtime snapshot fails', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    vi.mocked(environmentService.getEnvironmentRuntime).mockRejectedValue(
      new Error('runtime route failed'),
    )
    renderModal(testEnvironment({ version: '3' }))

    await expandDiagnostics(user)

    expect(await screen.findByText('运行状态读取失败')).toBeInTheDocument()
    expect(screen.getByText('未知')).toBeInTheDocument()
    expect(screen.queryByText('已应用')).toBeNull()
    expect(screen.queryByText('直连')).toBeNull()
  })

  /** 测试意图：离线环境没有有效租约，诊断里只呈现待连接与代理未知，且不读运行快照端点。 */
  it('reports pending connection and an unknown proxy for an offline environment', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment({ status: 'OFFLINE', ready: false }))

    await expandDiagnostics(user)

    const configSection = screen.getByRole('region', { name: '配置' })
    expect(within(configSection).getByText('待连接')).toBeInTheDocument()
    // 已应用版本与代理都未知：代理不能把卡片上的直连意图当成已生效。
    expect(within(configSection).getAllByText('—')).toHaveLength(2)
    expect(within(configSection).queryByText('直连')).toBeNull()
    expect(environmentService.getEnvironmentRuntime).not.toHaveBeenCalled()
  })

  /** 测试意图：当前版本的配置失败规则与错误字段在诊断中可读。 */
  it('surfaces the current configuration error code and field', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(
      testRuntimeStatus({
        configuration: {
          appliedVersion: '2',
          proxyUrl: null,
          error: { version: '3', code: 'VALIDATION_FAILED', field: 'daemonConfig.proxyUrl' },
        },
      }),
    )
    renderModal(testEnvironment({ version: '3' }))

    await expandDiagnostics(user)

    expect(await screen.findByText('应用失败')).toBeInTheDocument()
    expect(screen.getByText('VALIDATION_FAILED · daemonConfig.proxyUrl')).toBeInTheDocument()
  })

  /** 测试意图：展开后按时间倒序呈现事件，级别可区分且 WARN 有视觉区分样式。 */
  it('renders events with distinguishable levels once diagnostics is expanded', async () => {
    const user = userEvent.setup()
    const mockEvents: EnvironmentEventDTO[] = [
      {
        occurredAt: 1784509200000,
        level: 'INFO',
        type: 'READY',
        message: 'Host connected successfully',
      },
      {
        occurredAt: 1784509500000,
        level: 'WARN',
        type: 'UPDATE_FAILED',
        message: 'Daemon update failed',
      },
    ]
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue(mockEvents)
    renderModal(testEnvironment())

    await expandDiagnostics(user)

    expect(await screen.findByText('Daemon update failed')).toBeInTheDocument()
    expect(screen.getByText('Host connected successfully')).toBeInTheDocument()
    expect(screen.getByText('UPDATE_FAILED')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
    expect(screen.getByText('WARN')).toHaveClass('is-warn')
    // 倒序：最新事件在前。
    const messages = screen.getAllByText(/Host connected successfully|Daemon update failed/)
    expect(messages.map((node) => node.textContent)).toEqual([
      'Daemon update failed',
      'Host connected successfully',
    ])
  })

  /**
   * 测试意图：只有「成功且为空」才给出空态；请求在途时给出读取中，不能把还没有结果写成暂无事件记录。
   */
  it('separates the in-flight events read from the empty state', async () => {
    const user = userEvent.setup()
    const pending = deferred<EnvironmentEventDTO[]>()
    vi.mocked(environmentService.listEnvironmentEvents).mockReturnValue(pending.promise)
    renderModal(testEnvironment())

    await expandDiagnostics(user)

    const region = eventsRegion()
    expect(within(region).getByText('正在读取事件记录')).toBeInTheDocument()
    expect(within(region).queryByText('暂无事件记录')).toBeNull()

    await act(async () => {
      pending.resolve([
        { occurredAt: 1784509500000, level: 'WARN', type: 'UPDATE_FAILED', message: 'Daemon update failed' },
      ])
    })

    expect(await within(eventsRegion()).findByText('Daemon update failed')).toBeInTheDocument()
    expect(within(eventsRegion()).queryByText('正在读取事件记录')).toBeNull()
    expect(within(eventsRegion()).queryByText('暂无事件记录')).toBeNull()
  })

  /**
   * 测试意图：事件读取失败（HTTP 500）就地给出失败与重试，不冒充空态；重试成功后渲染权威事件。
   */
  it('reports a failed events read and recovers through the in-place retry', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents)
      .mockRejectedValueOnce(new ApiError('Internal Server Error', 500, 'INTERNAL_ERROR'))
      .mockResolvedValueOnce([
        { occurredAt: 1784509500000, level: 'WARN', type: 'UPDATE_FAILED', message: 'Daemon update failed' },
      ])
    renderModal(testEnvironment())

    await expandDiagnostics(user)

    expect(await screen.findByText('事件记录读取失败')).toBeInTheDocument()
    expect(within(eventsRegion()).queryByText('暂无事件记录')).toBeNull()

    await user.click(within(eventsRegion()).getByRole('button', { name: '重试' }))

    expect(await within(eventsRegion()).findByText('Daemon update failed')).toBeInTheDocument()
    expect(within(eventsRegion()).queryByText('事件记录读取失败')).toBeNull()
    expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(2)
  })

  /** 测试意图：成功回读到零条事件时，仍保留事件分区并给出空态。 */
  it('renders the empty events state once diagnostics is expanded', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment())

    await expandDiagnostics(user)

    expect(await screen.findByText('暂无事件记录')).toBeInTheDocument()
    expect(within(eventsRegion()).queryByText('正在读取事件记录')).toBeNull()
  })

  /** 测试意图：展开期间每 10 秒回读一次事件端点，折叠时完全不请求。 */
  it('polls environment events every 10 seconds only while diagnostics is expanded', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
      renderModal(testEnvironment())

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(environmentService.listEnvironmentEvents).not.toHaveBeenCalled()

      // jsdom 把 details 的 open 变化转成异步 toggle，React 的 onToggle 再据此渲染诊断内容。
      await act(async () => {
        fireEvent.click(screen.getByText('诊断详情'))
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(screen.getByText('宿主信息')).toBeInTheDocument()
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(1)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(2)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  /** 测试意图：底部关闭与标题关闭都可关闭弹窗。 */
  it('triggers onClose when the close button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment(), { onClose })

    const closeButtons = within(manageDialog()).getAllByRole('button', { name: '关闭' })
    expect(closeButtons).toHaveLength(2)
    await user.click(closeButtons[1]!)
    expect(onClose).toHaveBeenCalled()
  })
})
