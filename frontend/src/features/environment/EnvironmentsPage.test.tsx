import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/environment/EnvironmentsPage'
import { environmentService } from '@/features/environment/environment-service'
import { ApiError } from '@/shared/api/client'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  EnvironmentDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'
import {
  testDaemonConfig,
  testEnvironment as fixtureEnvironment,
  testRuntimeStatus,
} from '@/test-support/environment-test-fixtures'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironment: vi.fn(),
    getEnvironmentRuntime: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
    createEnvironment: vi.fn(),
    updateEnvironment: vi.fn(),
    rotateToken: vi.fn(),
    deleteEnvironment: vi.fn(),
    listEnvironmentEvents: vi.fn(),
  },
}))

vi.mock('@/app/ai-console/AiNavigation', () => ({
  AiNavigation: () => null,
}))

type TestUser = ReturnType<typeof userEvent.setup>

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <EnvironmentsPage />
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { queryClient, view, sockets }
}

function environment(overrides: Partial<EnvironmentDTO>): EnvironmentDTO {
  return fixtureEnvironment({
    id: 'env-id-1',
    name: 'env',
    lastSeenAt: null,
    ...overrides,
  })
}

function runtimeStatus(
  daemonVersion: string,
  update: EnvironmentUpdateProgressDTO | null = null,
  revision = '1',
): EnvironmentRuntimeStatusDTO {
  return testRuntimeStatus({ daemonVersion, update, revision })
}

function runtimeUnavailableError(): ApiError {
  return new ApiError('environment runtime unavailable', 409, 'unavailable', undefined,
    'ENVIRONMENT_UNAVAILABLE', 'environment runtime snapshot is unavailable; wait for READY or reconnect')
}

/** 卡片以标题定位：卡片本身只保留状态、版本与更新状态。 */
async function cardFor(name: string): Promise<HTMLElement> {
  const heading = await screen.findByRole('heading', { name })
  const card = heading.closest('article')
  if (card == null) {
    throw new Error(`environment card not found: ${name}`)
  }
  return card
}

/** 卡片只保留管理入口：动作与诊断都在管理弹窗里。 */
async function openManagement(user: TestUser, name: string): Promise<HTMLElement> {
  await user.click(within(await cardFor(name)).getByRole('button', { name: `管理 ${name}` }))
  return screen.findByRole('dialog', { name: `管理环境：${name}` })
}

/** 管理动作执行前先关闭管理弹窗，再打开原有的确认/命令流程。 */
async function runManagementAction(user: TestUser, name: string, label: string): Promise<void> {
  const management = await openManagement(user, name)
  await user.click(within(management).getByRole('button', { name: label }))
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: `管理环境：${name}` })).toBeNull(),
  )
}

/**
 * jsdom 不提供 Clipboard API；测试显式注入 stub，使复制成功/失败都可断言。
 *
 * 每个用例安装独立 stub，避免共享可变状态导致断言互相污染。
 */
function installClipboard(): { writeText: ReturnType<typeof vi.fn> } {
  const writeText = vi.fn(async () => undefined)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
  return { writeText }
}

/**
 * 在 fake timers 下推进微任务直到条件成立。
 *
 * `waitFor` / `findBy*` 的超时依赖真实定时器，启用 fake timers 后会挂起，因此截止点相关用例显式按轮次推进。
 */
async function flushUntil(condition: () => boolean, rounds = 20): Promise<void> {
  for (let round = 0; round < rounds && !condition(); round += 1) {
    await act(async () => {
      // 同时清空微任务队列并执行零延迟定时器（react-query 的读取完成与提交调度都在其中）。
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

describe('EnvironmentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue(environment({}))
    // 默认没有本代 READY 运行快照；具体用例按需覆盖为 live runtime 或更新进度。
    vi.mocked(environmentService.getEnvironmentRuntime).mockRejectedValue(runtimeUnavailableError())
  })

  /**
   * 测试意图：卡片只呈现当前状态、版本来源与更新状态，动作收敛为一个管理入口；
   * 能力清单、最近活动等诊断事实不再铺在卡片上。
   */
  it('renders cards with status, note subtitle, version row and only the manage entry', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        id: 'env-1',
        name: 'local-dev',
        note: 'dev workstation',
        daemonVersion: '1.2.3',
        statusExpiresAt: 1784509383000,
        lastSeenAt: 1784509323000,
        capabilities: [{ id: 'process.exec' }],
      }),
      environment({ id: 'env-2', name: 'stale-box', ready: false, daemonVersion: 'development' }),
      environment({ id: 'env-3', name: 'connecting-box', status: 'CONNECTING', ready: false }),
      environment({ id: 'env-4', name: 'offline-unknown', status: 'OFFLINE', ready: false }),
      environment({
        id: 'env-5',
        name: 'offline-known',
        status: 'OFFLINE',
        ready: false,
        daemonVersion: '0.9.0',
      }),
    ])
    renderPage()

    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(5)
    // 状态徽标以可读名称展示连接状态；stale-box 必须显示不可用而非就绪，卡面不出现英文枚举码。
    expect(screen.getAllByText('就绪')).toHaveLength(1)
    expect(screen.getByText('不可用')).toBeInTheDocument()
    expect(screen.getByText('连接中')).toBeInTheDocument()
    expect(screen.getAllByText('离线')).toHaveLength(2)
    for (const code of ['READY', 'CONNECTING', 'OFFLINE', 'UNAVAILABLE']) {
      expect(screen.queryByText(code)).toBeNull()
    }

    // 备注是副标题；UUID、能力清单与最近活动只在诊断详情里出现。
    const localDev = await cardFor('local-dev')
    expect(within(localDev).getByText('dev workstation')).toBeInTheDocument()
    expect(within(localDev).queryByText('env-1')).toBeNull()
    expect(within(localDev).queryByText('process.exec')).toBeNull()
    expect(within(localDev).queryByText('Capabilities')).toBeNull()
    expect(screen.queryByText(/最近活动/u)).toBeNull()

    // 每张卡只有两条元信息行：版本来源 + 更新状态。
    for (const name of [
      'local-dev',
      'stale-box',
      'connecting-box',
      'offline-unknown',
      'offline-known',
    ]) {
      expect((await cardFor(name)).querySelectorAll('.resource-card-meta-row')).toHaveLength(2)
    }
    // 同一信息行在兄弟卡片间共用同一标签；有版本记录的卡片与从未上报的卡片不改写行名。
    expect(screen.getAllByText('Daemon 版本')).toHaveLength(5)
    expect(screen.queryByText('上次 Daemon 版本')).toBeNull()
    expect(screen.queryByText('当前 Daemon 版本')).toBeNull()
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('development')).toBeInTheDocument()
    expect(screen.getByText('0.9.0')).toBeInTheDocument()
    expect(screen.getAllByText('未上报')).toHaveLength(2)

    // 默认没有可用运行快照：明确展示状态不可用，不把缺失的 update 伪造成无更新。
    expect(await screen.findAllByText('状态不可用，等待 Daemon 重连')).toHaveLength(5)
    expect(screen.queryByText('无进行中的更新')).toBeNull()

    // 动作只剩管理；只有既离线又没有版本记录的卡片才额外提供安装入口。
    expect(within(localDev).getAllByRole('button')).toHaveLength(1)
    expect(within(localDev).getByRole('button', { name: '管理 local-dev' })).toBeInTheDocument()
    const offlineUnknown = await cardFor('offline-unknown')
    expect(within(offlineUnknown).getAllByRole('button')).toHaveLength(2)
    expect(
      within(offlineUnknown).getByRole('button', { name: '安装 / 覆盖 offline-unknown' }),
    ).toBeInTheDocument()
    expect(within(await cardFor('offline-known')).queryByRole('button', { name: /安装/u })).toBeNull()
  })

  it('shows empty state when no environments configured', async () => {
    // 测试意图：验证环境列表为空时首项呈现 CreateCard，且不展示多余的空态文本
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '新建环境' })).toHaveClass('create-card')
    })
    expect(screen.queryByText('当前没有 Environment')).not.toBeInTheDocument()
    // 空列表不是“没有匹配”：搜索框存在也不渲染无匹配空态。
    expect(screen.queryByText('暂无匹配环境')).not.toBeInTheDocument()
  })

  /**
   * 测试意图：搜索只过滤卡片，并按名称或 id 匹配；卡片与运行快照先配对再过滤，
   * 因此剩下的卡片仍展示自己的快照，运行读取也仍覆盖完整列表。
   */
  it('filters only the cards by name or id and keeps runtime pairing on the full list', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-zeta', name: 'zeta-box', status: 'READY', ready: true }),
      environment({ id: 'env-alpha', name: 'alpha-box', status: 'READY', ready: true }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockImplementation(async (id: string) =>
      testRuntimeStatus({ daemonVersion: id === 'env-zeta' ? '9.9.9' : '1.1.1' }),
    )
    renderPage()

    expect(await screen.findByText('alpha-box')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(2)
    expect(within(await cardFor('alpha-box')).getByText('1.1.1')).toBeInTheDocument()
    expect(await screen.findByText('9.9.9')).toBeInTheDocument()

    const search = screen.getByRole('textbox', { name: '搜索环境' })
    await user.type(search, 'zeta')
    await waitFor(() => expect(screen.queryAllByRole('article')).toHaveLength(1))
    // 配对未被过滤打乱：留下的卡片只展示自己的运行版本。
    const zeta = await cardFor('zeta-box')
    expect(within(zeta).getByText('9.9.9')).toBeInTheDocument()
    expect(within(zeta).queryByText('1.1.1')).toBeNull()

    // id 与名称一样可搜索。
    await user.clear(search)
    await user.type(search, 'env-alpha')
    await waitFor(() => expect(screen.queryAllByRole('article')).toHaveLength(1))
    expect(screen.getByRole('heading', { name: 'alpha-box' })).toBeInTheDocument()

    // 运行读取仍按完整列表发出，过滤没有取消或增加运行查询。
    expect(
      vi.mocked(environmentService.getEnvironmentRuntime).mock.calls.map(([id]) => id).sort(),
    ).toEqual(['env-alpha', 'env-zeta'])

    // 无匹配时使用共享空态，新建入口保留。
    await user.clear(search)
    await user.type(search, 'no-such-env')
    expect(await screen.findByText('暂无匹配环境')).toBeInTheDocument()
    expect(screen.queryAllByRole('article')).toHaveLength(0)
    expect(screen.getByRole('button', { name: '新建环境' })).toBeInTheDocument()

    // 清空关键字后恢复完整列表。
    await user.clear(search)
    await waitFor(() => expect(screen.queryAllByRole('article')).toHaveLength(2))
    expect(screen.queryByText('暂无匹配环境')).not.toBeInTheDocument()
  })

  it('refetches the registry on a committed environment change event instead of polling', async () => {
    // 测试意图：环境事实（注册表/租约）变化只由服务端事件提示回读：一次 changed 恰好触发一次权威回读，
    // 因此页面不需要固定轮询就能保持状态闭环。
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'local-dev' }),
    ])
    const { sockets } = renderPage()
    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()

    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-2', name: 'fresh-box' }),
    ])
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'environments' },
        name: 'changed',
        data: {},
      })
    })

    expect(await screen.findByText('fresh-box')).toBeInTheDocument()
    expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
  })

  it('creates a full card, then opens the install command without showing credentials', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(environmentService.createEnvironment).mockResolvedValue(
      environment({
        id: 'env-new',
        name: 'new-env',
        registrationToken: 'secret-token-12345',
        status: 'CONNECTING',
        ready: false,
      }),
    )
    const clipboard = installClipboard()
    const { queryClient } = renderPage()

    await user.click(await screen.findByRole('button', { name: '新建环境' }))
    const form = await screen.findByRole('dialog', { name: '新建环境' })
    await user.type(within(form).getByRole('textbox', { name: /环境名称/ }), 'new-env')
    await user.type(within(form).getByRole('textbox', { name: '备注' }), 'fresh card')
    await user.click(within(form).getByRole('button', { name: '创建' }))

    expect(environmentService.createEnvironment).toHaveBeenCalledWith({
      name: 'new-env',
      note: 'fresh card',
      daemonConfig: {
        studioUrl: window.location.origin,
        bashExecutable: null,
        lsp: null,
        proxyUrl: null,
      },
    })
    expect(await screen.findByRole('dialog', { name: '安装/覆盖环境' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: '新建环境' })).toBeNull()
    expect(screen.queryByText('secret-token-12345')).not.toBeInTheDocument()
    expect(clipboard.writeText).not.toHaveBeenCalled()
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map(mutation => mutation.state.data))).not.toContain('secret-token-12345')
  })

  it('edits a card configuration from the management modal with CAS and keeps the draft when the version conflicts', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        id: 'env-1',
        name: 'my-box',
        note: 'before',
        version: '3',
        daemonConfig: testDaemonConfig({
          studioUrl: 'https://saved.example.com',
          bashExecutable: '/bin/bash',
          proxyUrl: null,
        }),
      }),
    ])
    vi.mocked(environmentService.updateEnvironment)
      .mockRejectedValueOnce(
        new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'card changed'),
      )
      .mockResolvedValue(environment({ id: 'env-1', name: 'my-box', version: '5' }))
    vi.mocked(environmentService.getEnvironment).mockResolvedValue(
      environment({
        id: 'env-1',
        name: 'my-box',
        version: '4',
        daemonConfig: testDaemonConfig({ studioUrl: 'https://saved.example.com' }),
      }),
    )
    renderPage()

    await screen.findByText('my-box')
    await runManagementAction(user, 'my-box', '编辑配置')
    const modal = await screen.findByRole('dialog', { name: '编辑环境：my-box' })
    expect(within(modal).getByRole('textbox', { name: '环境名称' })).toBeDisabled()
    expect(within(modal).getByRole('textbox', { name: 'Studio 地址' })).toHaveValue(
      'https://saved.example.com',
    )
    await user.clear(within(modal).getByRole('textbox', { name: '备注' }))
    await user.type(within(modal).getByRole('textbox', { name: '备注' }), 'after')
    const proxy = within(modal).getByRole('textbox', { name: 'Daemon 代理' })
    await user.type(proxy, 'http://127.0.0.1:7890')
    await user.click(within(modal).getByRole('button', { name: '保存配置' }))

    expect(environmentService.updateEnvironment).toHaveBeenCalledWith('env-1', {
      expectedVersion: '3',
      note: 'after',
      daemonConfig: {
        studioUrl: 'https://saved.example.com',
        bashExecutable: '/bin/bash',
        lsp: null,
        proxyUrl: 'http://127.0.0.1:7890',
      },
    })
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(within(modal).getByRole('textbox', { name: '备注' })).toHaveValue('after')
    expect(proxy).toHaveValue('http://127.0.0.1:7890')
    await user.click(within(modal).getByRole('button', { name: '保存配置' }))
    await waitFor(() =>
      expect(vi.mocked(environmentService.updateEnvironment).mock.calls[1]![1].expectedVersion).toBe('4'),
    )
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '编辑环境：my-box' })).toBeNull())
  })

  /**
   * 测试意图：卡片只为“待应用/应用失败”让出状态位，并以配置状态徽标取代更新状态；
   * 已应用的卡片仍显示更新状态，离线卡片没有运行快照时既不读端点也不展示配置徽标。
   */
  it('shows pending and failed configuration states on the card, and never fakes a state for offline cards', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-applied', name: 'applied-box', version: '3' }),
      environment({ id: 'env-pending', name: 'pending-box', version: '3' }),
      environment({ id: 'env-failed', name: 'failed-box', version: '3' }),
      environment({ id: 'env-offline', name: 'offline-box', status: 'OFFLINE', ready: false }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockImplementation(async (id) => {
      if (id === 'env-pending') {
        return testRuntimeStatus({
          configuration: {
            appliedVersion: '2',
            proxyUrl: 'http://127.0.0.1:7890',
            error: null,
          },
        })
      }
      if (id === 'env-failed') {
        return testRuntimeStatus({
          configuration: {
            appliedVersion: '2',
            proxyUrl: null,
            error: { version: '3', code: 'VALIDATION_FAILED', field: 'daemonConfig.proxyUrl' },
          },
        })
      }
      return testRuntimeStatus({
        configuration: { appliedVersion: '3', proxyUrl: null, error: null },
      })
    })
    renderPage()

    expect(await screen.findByText('applied-box')).toBeInTheDocument()
    expect(await screen.findByText('待应用')).toBeInTheDocument()
    expect(await screen.findByText('应用失败')).toBeInTheDocument()
    expect(screen.getAllByText('配置状态')).toHaveLength(2)
    expect(screen.getAllByText('更新状态')).toHaveLength(2)
    // 已应用的卡片不额外加配置徽标。
    expect(within(await cardFor('applied-box')).queryByText('已应用')).toBeNull()
    expect(
      await within(await cardFor('applied-box')).findByText('无进行中的更新'),
    ).toBeInTheDocument()
    // 失败详情（错误码与字段）属于诊断详情，不在卡片上展开。
    expect(screen.queryByText(/VALIDATION_FAILED/u)).toBeNull()

    // 离线卡片没有当前运行快照：不读 runtime 端点，也不把缓存包装成待连接/直连。
    expect(environmentService.getEnvironmentRuntime).not.toHaveBeenCalledWith('env-offline')
    expect(within(await cardFor('offline-box')).queryByText('待连接')).toBeNull()
    expect(
      within(await cardFor('offline-box')).getByText('状态不可用，等待 Daemon 重连'),
    ).toBeInTheDocument()
  })

  it('reads the runtime snapshot while CONNECTING and shows the live Daemon version', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        id: 'env-connecting',
        name: 'connecting-box',
        status: 'CONNECTING',
        ready: false,
        version: '2',
      }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(
      testRuntimeStatus({
        configuration: { appliedVersion: '2', proxyUrl: null, error: null },
      }),
    )
    renderPage()

    expect(await screen.findByText('connecting-box')).toBeInTheDocument()
    expect(await screen.findByText('Daemon 版本')).toBeInTheDocument()
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(await screen.findByText('无进行中的更新')).toBeInTheDocument()
    expect(environmentService.getEnvironmentRuntime).toHaveBeenCalledWith('env-connecting')
  })

  it('ignores a stale configuration error once the current version is applied', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'stale-error-box', version: '4' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(
      testRuntimeStatus({
        configuration: {
          appliedVersion: '4',
          proxyUrl: null,
          error: { version: '3', code: 'PERSIST_FAILED' },
        },
      }),
    )
    renderPage()

    expect(await screen.findByText('无进行中的更新')).toBeInTheDocument()
    expect(screen.queryByText('配置状态')).toBeNull()
    expect(screen.queryByText('应用失败')).toBeNull()
  })

  // 确认后轮换并刷新列表，不展示或缓存凭据。
  it('rotates registration token from the management modal without displaying or fetching credentials', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.rotateToken).mockResolvedValue(
      environment({ id: 'env-1', name: 'my-box', registrationToken: 'rotated-tok-999', version: '2' }),
    )
    installClipboard()
    const { queryClient } = renderPage()

    await screen.findByText('my-box')
    await runManagementAction(user, 'my-box', '重新生成 Token')

    // 管理动作打开原有的确认弹窗，确认标签与被点动作一致。
    const modal = await screen.findByRole('alertdialog', { name: '重新生成 Token' })
    const confirmBtn = within(modal).getByRole('button', { name: '重新生成 Token' })
    await user.click(confirmBtn)

    expect(environmentService.rotateToken).toHaveBeenCalledWith('env-1', '1')
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: '重新生成 Token' })).toBeNull())
    expect(screen.queryByText('rotated-tok-999')).not.toBeInTheDocument()
    // 轮换不应顺带读取 token
    await waitFor(() => expect(environmentService.listEnvironments).toHaveBeenCalledTimes(2))
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map(mutation => mutation.state.data))).not.toContain('rotated-tok-999')
  })

  it('deletes an environment card from the management modal after confirmation', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'to-delete', version: '1' }),
    ])
    vi.mocked(environmentService.deleteEnvironment).mockResolvedValue(undefined)
    renderPage()

    await screen.findByText('to-delete')
    await runManagementAction(user, 'to-delete', '删除环境')

    const modal = await screen.findByRole('alertdialog', { name: '删除环境' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除环境' })
    await user.click(confirmBtn)

    expect(environmentService.deleteEnvironment).toHaveBeenCalledWith('env-1', '1')
  })

  it('starts a managed Daemon update from the management modal after confirmation and applies the returned runtime snapshot', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', daemonVersion: '1.2.0', version: '1' }),
    ])
    const started = runtimeStatus(
      '1.2.0',
      { targetVersion: '1.3.0', downloadedBytes: '0', totalBytes: null },
      '2',
    )
    vi.mocked(environmentService.getEnvironmentRuntime)
      .mockResolvedValueOnce(runtimeStatus('1.2.0'))
      .mockResolvedValue(started)
    vi.mocked(environmentService.startEnvironmentUpdate).mockResolvedValue({
      commandStatus: 'SENT_UNCONFIRMED',
      runtimeStatus: started,
    })
    const { queryClient } = renderPage()

    const card = await cardFor('my-box')
    expect(await within(card).findByText('无进行中的更新')).toBeInTheDocument()

    // 管理弹窗承载全部动作，标签与确认流程一致。
    const management = await openManagement(user, 'my-box')
    const managementLabels = ['编辑配置', '安装 / 覆盖', '更新 Daemon', '卸载', '重新生成 Token', '删除环境']
    for (const label of managementLabels) {
      expect(within(management).getByRole('button', { name: label })).toBeInTheDocument()
    }
    await user.click(within(management).getByRole('button', { name: '更新 Daemon' }))

    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    expect(within(modal).getByText(/my-box/)).toBeInTheDocument()
    const confirmBtn = within(modal).getByRole('button', { name: '更新 Daemon' })
    await user.click(confirmBtn)

    expect(environmentService.startEnvironmentUpdate).toHaveBeenCalledWith('env-1')
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: '更新 Daemon' })).toBeNull())
    await waitFor(() => expect(environmentService.listEnvironments).toHaveBeenCalledTimes(2))
    expect(
      await within(card).findByText('正在更新至 1.3.0 · 已下载 0 B · 总大小未知'),
    ).toBeInTheDocument()
    expect(queryClient.getQueryData(queryKeys.environments.runtime('env-1'))).toEqual(started)
  })

  it.each([
    ['SENT_UNCONFIRMED', '更新请求已发送，待确认'],
    ['CURRENT', 'Daemon 已是最新版本'],
  ] as const)('shows a %s receipt on only the commanded card until runtime changes', async (commandStatus, message) => {
    // 无进度的 HTTP 回执与后续权威快照是两条独立事实。
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box' }),
      environment({ id: 'env-2', name: 'other-box' }),
    ])
    const beforeProgress = runtimeStatus('1.2.0')
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(beforeProgress)
    vi.mocked(environmentService.startEnvironmentUpdate).mockResolvedValue({ commandStatus, runtimeStatus: beforeProgress })
    const { queryClient } = renderPage()

    const card = await cardFor('my-box')
    expect(await within(card).findByText('无进行中的更新')).toBeVisible()
    await runManagementAction(user, 'my-box', '更新 Daemon')
    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    await user.click(within(modal).getByRole('button', { name: '更新 Daemon' }))

    expect(environmentService.startEnvironmentUpdate).toHaveBeenCalledWith('env-1')
    expect(await within(card).findByText(message)).toBeVisible()
    expect(within(card).queryByText('无进行中的更新')).not.toBeInTheDocument()
    expect(within(await cardFor('other-box')).getByText('无进行中的更新')).toBeVisible()

    // 回执不锁住动作：再次打开管理仍然可以发起更新。
    const management = await openManagement(user, 'my-box')
    expect(within(management).getByRole('button', { name: '更新 Daemon' })).not.toBeDisabled()
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '管理环境：my-box' })).toBeNull(),
    )

    const key = queryKeys.environments.runtime('env-1')
    const progress = runtimeStatus('1.2.0', { targetVersion: '1.3.0', downloadedBytes: '0', totalBytes: null }, '2')
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(progress)
    await act(async () => { await queryClient.invalidateQueries({ queryKey: key }) })
    expect(await within(card).findByText('正在更新至 1.3.0 · 已下载 0 B · 总大小未知')).toBeVisible()
    expect(within(card).queryByText(message)).not.toBeInTheDocument()

    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(runtimeStatus('1.3.0'))
    await act(async () => { await queryClient.invalidateQueries({ queryKey: key }) })
    expect(await within(card).findByText('1.3.0')).toBeVisible()
    expect(within(card).getByText('无进行中的更新')).toBeVisible()
    expect(within(card).queryByText(message)).not.toBeInTheDocument()
  })

  it('closes the update confirmation without starting a command when canceled', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'cancel-box', daemonVersion: '1.2.0' }),
    ])
    renderPage()

    await screen.findByText('cancel-box')
    await runManagementAction(user, 'cancel-box', '更新 Daemon')

    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    await user.click(within(modal).getByRole('button', { name: '取消' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: '更新 Daemon' })).toBeNull())
    expect(environmentService.startEnvironmentUpdate).not.toHaveBeenCalled()
  })

  it('shows the live Daemon version and known download progress without treating 100% download as success', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'live-box', daemonVersion: '1.2.0' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(
      runtimeStatus('1.2.1', {
        targetVersion: '1.3.0',
        downloadedBytes: '1048576',
        totalBytes: '1048576',
      }),
    )
    renderPage()

    expect(await screen.findByText('Daemon 版本')).toBeInTheDocument()
    expect(screen.getByText('1.2.1')).toBeInTheDocument()
    expect(screen.queryByText('1.2.0')).not.toBeInTheDocument()
    expect(
      screen.getByText('正在更新至 1.3.0 · 1 MB / 1 MB（100%）'),
    ).toBeInTheDocument()
    expect(screen.queryByText('更新成功')).not.toBeInTheDocument()
    // 当前更新快照不锁定动作；重复请求由 Server/Daemon 的互斥与幂等语义裁决。
    const management = await openManagement(user, 'live-box')
    expect(within(management).getByRole('button', { name: '更新 Daemon' })).not.toBeDisabled()
  })

  it('shows downloaded bytes without fabricating a percentage when total size is unknown', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'unknown-total-box', daemonVersion: '1.2.0' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(
      runtimeStatus('1.2.0', {
        targetVersion: '1.3.0',
        downloadedBytes: '8388608',
        totalBytes: null,
      }),
    )
    renderPage()

    const progress = await screen.findByText(
      '正在更新至 1.3.0 · 已下载 8 MB · 总大小未知',
    )
    expect(progress).not.toHaveTextContent('%')
  })

  it('shows runtime read failures instead of claiming no update is in progress', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-error', name: 'error-box', daemonVersion: '1.2.0' }),
      environment({ id: 'env-rejected', name: 'rejected-box', daemonVersion: '1.2.0' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockImplementation(async (id) => {
      if (id === 'env-error') {
        throw new Error('runtime route failed')
      }
      throw new Error('')
    })
    renderPage()

    // 卡片只给产品文案；错误原文留在诊断详情，不作为卡片文案。
    expect(await screen.findAllByText('运行状态读取失败')).toHaveLength(2)
    expect(screen.queryByText('runtime route failed')).not.toBeInTheDocument()
    expect(screen.queryByText('无进行中的更新')).not.toBeInTheDocument()
  })

  it('refreshes active update progress from the live runtime snapshot', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'progress-box', daemonVersion: '1.2.0' }),
      ])
      vi.mocked(environmentService.getEnvironmentRuntime)
        .mockResolvedValueOnce(
          runtimeStatus(
            '1.2.0',
            { targetVersion: '1.3.0', downloadedBytes: '524288', totalBytes: '1048576' },
            '2',
          ),
        )
        .mockResolvedValue(
          runtimeStatus(
            '1.2.0',
            { targetVersion: '1.3.0', downloadedBytes: '1048576', totalBytes: '1048576' },
            '3',
          ),
        )
      renderPage()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1)
      })
      await flushUntil(
        () => screen.queryByText('正在更新至 1.3.0 · 512 KB / 1 MB（50%）') != null,
      )
      expect(
        screen.getByText('正在更新至 1.3.0 · 512 KB / 1 MB（50%）'),
      ).toBeInTheDocument()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })
      await flushUntil(
        () => screen.queryByText('正在更新至 1.3.0 · 1 MB / 1 MB（100%）') != null,
      )
      expect(
        screen.getByText('正在更新至 1.3.0 · 1 MB / 1 MB（100%）'),
      ).toBeInTheDocument()
      expect(screen.queryByText('更新成功')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('marks runtime unavailable after a refetch failure instead of inferring update success or failure', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'disconnecting-box', daemonVersion: '1.2.0' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime)
      .mockResolvedValueOnce(
        runtimeStatus(
          '1.2.0',
          { targetVersion: '1.3.0', downloadedBytes: '524288', totalBytes: '1048576' },
          '2',
        ),
      )
      .mockRejectedValue(runtimeUnavailableError())
    const { queryClient } = renderPage()

    expect(
      await screen.findByText('正在更新至 1.3.0 · 512 KB / 1 MB（50%）'),
    ).toBeInTheDocument()

    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: queryKeys.environments.runtime('env-1'),
      })
    })

    expect(await screen.findByText('状态不可用，等待 Daemon 重连')).toBeInTheDocument()
    expect(screen.queryByText(/更新成功|更新失败/u)).not.toBeInTheDocument()
    expect(screen.queryByText(/正在更新至/u)).not.toBeInTheDocument()
  })

  it('keeps the update modal open with the unavailable message instead of presenting a data conflict', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'offline-box', version: '1' }),
    ])
    vi.mocked(environmentService.startEnvironmentUpdate).mockRejectedValueOnce(
      runtimeUnavailableError(),
    )
    renderPage()

    await screen.findByText('offline-box')
    await runManagementAction(user, 'offline-box', '更新 Daemon')

    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    const confirmBtn = within(modal).getByRole('button', { name: '更新 Daemon' })
    await user.click(confirmBtn)

    expect(await within(modal).findByRole('alert')).toHaveTextContent(
      '状态不可用，等待 Daemon 重连',
    )
    expect(screen.queryByRole('alertdialog', { name: '数据已发生变化' })).toBeNull()
  })

  it('shows visible error on update failure and handles 409 conflict refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'busy-box', version: '1' }),
    ])
    vi.mocked(environmentService.startEnvironmentUpdate).mockRejectedValueOnce(
      new Error('release artifact fetch failed'),
    )
    renderPage()

    await screen.findByText('busy-box')
    await runManagementAction(user, 'busy-box', '更新 Daemon')

    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    const confirmBtn = within(modal).getByRole('button', { name: '更新 Daemon' })
    await user.click(confirmBtn)

    expect(await within(modal).findByRole('alert')).toHaveTextContent('release artifact fetch failed')

    vi.mocked(environmentService.startEnvironmentUpdate).mockRejectedValueOnce(
      new ApiError('环境忙', 409, 'conflict', undefined, 'ENVIRONMENT_BUSY', 'Active tool call running'),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(within(conflictModal).getByText(/ENVIRONMENT_BUSY/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '更新 Daemon' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '数据已发生变化' })).toBeNull()
    })
  })

  it('shows the loading skeleton while the registry request is pending', async () => {
    let resolveListing: ((value: EnvironmentDTO[]) => void) | undefined
    vi.mocked(environmentService.listEnvironments).mockImplementation(
      () => new Promise<EnvironmentDTO[]>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPage()

    expect(screen.getByText('正在加载环境')).toBeInTheDocument()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()

    await act(async () => {
      resolveListing?.([environment({ name: 'box-a' })])
    })
    expect(await screen.findByText('box-a')).toBeInTheDocument()
    expect(screen.queryByText('正在加载环境')).not.toBeInTheDocument()
  })

  /**
   * 测试意图：首次读取失败没有缓存可保留，页面只给一条失败提示和一个重试入口；
   * 重试成功后同一处直接替换为列表，错误原文不作为产品文案。
   */
  it('offers an initial retry that replaces the failure with the list once the registry responds', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockRejectedValueOnce(new Error('registry down'))
    renderPage()

    expect(await screen.findByText('加载环境失败')).toBeInTheDocument()
    expect(screen.getByText('加载环境失败')).toHaveAttribute('role', 'status')
    expect(screen.queryByText('registry down')).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '重试' })).toHaveLength(1)
    // 没有权威列表时不假装空列表，也不提供创建入口。
    expect(screen.queryByRole('article')).toBeNull()
    expect(screen.queryByRole('button', { name: '新建环境' })).toBeNull()

    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'recovered-box' }),
    ])
    await user.click(screen.getByRole('button', { name: '重试' }))

    expect(await screen.findByText('recovered-box')).toBeInTheDocument()
    expect(screen.queryByText('加载环境失败')).toBeNull()
    expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
  })

  /**
   * 测试意图：后台回读失败只是“显示上次数据”，列表原地保留，不退回骨架屏；
   * 重试成功后提示消失且不重建列表。
   */
  it('keeps the cached list in place and retries a failed refresh without a skeleton', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValueOnce([
      environment({ id: 'env-1', name: 'box-cached', daemonVersion: '1.2.0' }),
    ])
    const { queryClient } = renderPage()

    expect(await screen.findByText('box-cached')).toBeInTheDocument()

    vi.mocked(environmentService.listEnvironments).mockRejectedValueOnce(new Error('background glitch'))
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.environments.list })
    })

    // 宽屏横幅与窄屏抽屉各一条，指向同一份上次数据。
    expect(await screen.findAllByText('刷新失败 · 显示上次数据')).toHaveLength(2)
    expect(screen.getByText('刷新失败')).toBeInTheDocument()
    expect(screen.getByText('box-cached')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '新建环境' })).toBeInTheDocument()
    expect(screen.queryByText('正在加载环境')).toBeNull()
    expect(screen.queryByText('加载环境失败')).toBeNull()
    expect(screen.queryByText('background glitch')).toBeNull()
    // 宽/窄两种尺寸各有一个入口，指向同一次回读。
    expect(screen.getAllByRole('button', { name: '重试' })).toHaveLength(2)

    vi.mocked(environmentService.listEnvironments).mockResolvedValueOnce([
      environment({ id: 'env-1', name: 'box-cached', daemonVersion: '1.2.1' }),
    ])
    await user.click(screen.getAllByRole('button', { name: '重试' })[0]!)

    await waitFor(() => expect(screen.queryByText('刷新失败 · 显示上次数据')).toBeNull())
    expect(screen.getByText('box-cached')).toBeInTheDocument()
    expect(screen.getByText('1.2.1')).toBeInTheDocument()
    expect(screen.queryByText('正在加载环境')).toBeNull()
  })

  /**
   * 测试意图：服务端派生 OFFLINE 后缓存里的运行快照只作为“上次版本”使用，
   * 既不能继续宣称当前版本，也不能伪装成可用状态。
   */
  it('downgrades a card to unavailable on OFFLINE while keeping only the last reported version', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'flaky-box', daemonVersion: '1.2.0' }),
    ])
    vi.mocked(environmentService.getEnvironmentRuntime).mockResolvedValue(runtimeStatus('1.2.1'))
    const { queryClient } = renderPage()

    expect(await screen.findByText('Daemon 版本')).toBeInTheDocument()
    expect(screen.getByText('1.2.1')).toBeInTheDocument()
    expect(await screen.findByText('无进行中的更新')).toBeInTheDocument()

    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        id: 'env-1',
        name: 'flaky-box',
        status: 'OFFLINE',
        ready: false,
        daemonVersion: '1.2.0',
      }),
    ])
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.environments.list })
    })

    expect(await screen.findByText('状态不可用，等待 Daemon 重连')).toBeInTheDocument()
    expect(screen.queryByText('无进行中的更新')).toBeNull()
    // 统一标签：降级后同一行仍是同一个行名，值保留上次上报的版本，不再宣称当前进程。
    expect(screen.getAllByText('Daemon 版本')).toHaveLength(1)
    expect(screen.getByText('1.2.1')).toBeInTheDocument()
    // 有版本记录后不再提供安装入口，只剩管理。
    expect(within(await cardFor('flaky-box')).getAllByRole('button')).toHaveLength(1)
  })

  // 验证 Environment rotate 失败时在确认弹窗中可见展示错误（防止静默失败），并在 409 时走共享 ConflictPresenter 语义
  it('shows visible error on rotate token failure and handles 409 conflict refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.rotateToken).mockRejectedValueOnce(
      new Error('token rotation internal failure'),
    )
    installClipboard()
    renderPage()

    await screen.findByText('my-box')
    await runManagementAction(user, 'my-box', '重新生成 Token')

    const modal = await screen.findByRole('alertdialog', { name: '重新生成 Token' })
    const confirmBtn = within(modal).getByRole('button', { name: '重新生成 Token' })
    await user.click(confirmBtn)

    // 非 409 失败在确认弹窗内可见展示
    expect(await within(modal).findByRole('alert')).toHaveTextContent('token rotation internal failure')

    // 下一次测试 409 冲突
    vi.mocked(environmentService.rotateToken).mockRejectedValueOnce(
      new ApiError('冲突', 409, 'version_conflict', undefined, undefined, 'Token rotation conflict'),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '重新生成 Token' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '数据已发生变化' })).toBeNull()
    })
  })

  // 验证 Environment delete 失败时在确认弹窗内可见展示错误，并在 409 冲突时呈现 ConflictPresenter
  it('shows visible error on delete failure and handles 409 conflict refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'to-delete', version: '1' }),
    ])
    vi.mocked(environmentService.deleteEnvironment).mockRejectedValueOnce(
      new Error('delete failed due to locked resources'),
    )
    renderPage()

    await screen.findByText('to-delete')
    await runManagementAction(user, 'to-delete', '删除环境')

    const modal = await screen.findByRole('alertdialog', { name: '删除环境' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除环境' })
    await user.click(confirmBtn)

    // 非 409 失败在弹窗内展示
    expect(await within(modal).findByRole('alert')).toHaveTextContent('delete failed due to locked resources')

    // 409 冲突切换到 ConflictPresenter
    vi.mocked(environmentService.deleteEnvironment).mockRejectedValueOnce(
      new ApiError('版本冲突', 409, 'version_conflict', undefined, undefined, 'Already deleted or modified'),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '删除环境' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '数据已发生变化' })).toBeNull()
    })
  })

  /**
   * 测试意图：验证环境列表为空时，网格首项始终为 CreateCard，子工具栏无重复创建按钮，且不展示多余的通用空态文本块。
   */
  it('places CreateCard as the first grid item with exactly one creation entry without duplicate empty state block', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    renderPage()

    // 验证整个页面仅有唯一个创建入口（即网格首张 CreateCard，子工具栏无重复创建按钮）
    const createButton = await screen.findByRole('button', { name: '新建环境' })
    expect(createButton).toHaveClass('create-card')
    expect(screen.getAllByRole('button', { name: '新建环境' })).toHaveLength(1)

    // 验证列表真正为空时不渲染多余的 StateBlock 文本
    expect(screen.queryByText('当前没有 Environment')).not.toBeInTheDocument()
  })

  /**
   * 测试意图：卡片只呈现当前状态，不内嵌历史事件；同一环境的历史 WARN 与后续 READY 事件
   * 只在管理弹窗的诊断详情里可见，折叠时不读取事件端点。
   */
  it('renders only the current status on the card and keeps event history in the management diagnostics', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-recovered', name: 'recovered-box', status: 'READY', ready: true }),
    ])
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([
      {
        occurredAt: 1784505600000,
        level: 'WARN',
        type: 'DISCONNECTED',
        message: 'daemon connection lost',
      },
      {
        occurredAt: 1784505660000,
        level: 'INFO',
        type: 'READY',
        message: 'environment ready',
      },
    ])
    renderPage()

    expect(await screen.findByText('recovered-box')).toBeInTheDocument()

    // 卡片只显示当前状态名称，不回显事件文本或裸枚举码。
    const card = await cardFor('recovered-box')
    expect(within(card).getByText('就绪')).toBeInTheDocument()
    expect(within(card).queryByText('READY')).not.toBeInTheDocument()
    expect(within(card).queryByText('daemon connection lost')).not.toBeInTheDocument()

    const management = await openManagement(user, 'recovered-box')
    expect(within(management).queryByText('daemon connection lost')).toBeNull()
    expect(environmentService.listEnvironmentEvents).not.toHaveBeenCalled()

    await user.click(within(management).getByText('诊断详情'))
    // 展开诊断后完整历史可见：历史 WARN 与后续 READY 都被保留。
    expect(await screen.findByText('daemon connection lost')).toBeInTheDocument()
    expect(screen.getByText('environment ready')).toBeInTheDocument()
  })

  /**
   * 测试意图：连接失效是时间事实，页面只按权威数据里最早的 statusExpiresAt 排一次回读，
   * 既不在截止点之前提前回读，也不在之后退化为轮询。
   */
  it('rechecks once at the earliest lease deadline instead of polling', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-07-20T00:00:00.000Z'))
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'later-box', statusExpiresAt: 1784505660000 }),
        environment({ id: 'env-2', name: 'sooner-box', statusExpiresAt: 1784505610000 }),
      ])
      renderPage()
      await flushUntil(() => screen.queryByText('later-box') != null)
      expect(screen.getByText('later-box')).toBeInTheDocument()
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)

      // 最早的截止点之前不得回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(9000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)

      // 越过最早截止点（含容差）后只回读一次；更晚的截止点不产生第二次。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)

      // 之后不再有任何定时回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(120000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  /**
   * 测试意图：后端把 statusExpiresAt 写成 epoch 毫秒整数，页面必须按真实截止点排一次回读。
   */
  it.each([
    ['非整秒的 epoch 毫秒', 1784505610250],
  ])('rechecks once at the lease deadline served as %s and never polls', async (_label, statusExpiresAt) => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-07-20T00:00:00.000Z'))
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'lease-box', statusExpiresAt }),
      ])
      renderPage()
      await flushUntil(() => screen.queryByText('lease-box') != null)
      expect(screen.getByText('lease-box')).toBeInTheDocument()
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)

      // 截止点（含 250ms 容差）之前不得回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10499)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)

      // 静默死亡：越过截止点后恰好回读一次，服务端此时已派生 OFFLINE（statusExpiresAt 为空）。
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({
          id: 'env-1',
          name: 'lease-box',
          status: 'OFFLINE',
          ready: false,
          statusExpiresAt: null,
        }),
      ])
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
      expect(screen.getByText('离线')).toBeInTheDocument()

      // 已无截止点，之后不再有任何定时回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(120000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  /**
   * 测试意图：心跳续租会把截止点推后；页面必须取消旧定时器并按新截止点重排，而不是在旧截止点提前回读。
   * 整秒与非整秒毫秒值同样如此。
   */
  it.each([
    ['整秒', 1784505610000, 1784505670000],
    ['非整秒', 1784505610500, 1784505670500],
  ])('cancels and rearms the deadline when a committed change renews the lease (%s)', async (_label, initial, renewed) => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-07-20T00:00:00.000Z'))
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'local-dev', statusExpiresAt: initial }),
      ])
      const { sockets } = renderPage()
      await flushUntil(() => screen.queryByText('local-dev') != null)
      expect(screen.getByText('local-dev')).toBeInTheDocument()
      const socket = sockets.openLatest()
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)

      // 续租提交后推送 changed：权威数据把截止点推后 70s。
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'local-dev', statusExpiresAt: renewed }),
      ])
      await act(async () => {
        socket.emitServer({
          type: 'event',
          resource: { kind: 'environments' },
          name: 'changed',
          data: {},
        })
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)

      // 旧的截止点已被取消：越过它不产生回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(20000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)

      // 新截止点到达后恰好回读一次。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  /**
   * 测试意图：Daemon 静默死亡（无断开事件）时只由租约截止点触发一次回读，
   * 回读得到 OFFLINE（statusExpiresAt 为空）后不再排新的回读。
   */
  it('stops re-reading after a silent lease expiry', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-07-20T00:00:00.000Z'))
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'dying-box', statusExpiresAt: 1784505610000 }),
      ])
      renderPage()
      await flushUntil(() => screen.queryByText('dying-box') != null)
      expect(screen.getByText('dying-box')).toBeInTheDocument()

      // 静默死亡：没有关闭帧，只有服务端在租约结束后派生的 OFFLINE。
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({
          id: 'env-1',
          name: 'dying-box',
          status: 'OFFLINE',
          ready: false,
          statusExpiresAt: null,
        }),
      ])
      await act(async () => {
        await vi.advanceTimersByTimeAsync(11000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
      expect(screen.getByText('离线')).toBeInTheDocument()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(120000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  /** 测试意图：卸载必须清掉待触发的回读定时器，页面离开后不再有环境回读。 */
  it('clears the pending deadline timer on unmount', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-07-20T00:00:00.000Z'))
      vi.mocked(environmentService.listEnvironments).mockResolvedValue([
        environment({ id: 'env-1', name: 'local-dev', statusExpiresAt: 1784505610000 }),
      ])
      const { view } = renderPage()
      await flushUntil(() => screen.queryByText('local-dev') != null)
      expect(screen.getByText('local-dev')).toBeInTheDocument()

      view.unmount()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000)
      })
      expect(vi.mocked(environmentService.listEnvironments)).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })
})
