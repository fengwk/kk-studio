import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/environment/EnvironmentsPage'
import { environmentService } from '@/features/environment/environment-service'
import { ApiError } from '@/shared/api/client'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
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

  // 验证渲染环境卡片及其状态与能力，底部提供「安装 / 卸载 / 管理 / 轮换 / 删除」动作
  it('renders environment cards with status and capabilities, with manage, install, uninstall, rotate and delete actions in footer', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        id: 'env-1',
        name: 'local-dev',
        daemonConfig: testDaemonConfig(),
        userName: 'dev-user',
        homeDirectory: '/home/dev',
        status: 'READY',
        ready: true,
        statusExpiresAt: 1784509383000,
        lastSeenAt: 1784509323000,
        capabilities: [{ id: 'process.exec' }],
        daemonVersion: '1.2.3',
        version: '1',
        createdAt: 1784505600000,
        updatedAt: 1784505600000,
      },
      {
        id: 'env-2',
        name: 'stale-box',
        daemonConfig: testDaemonConfig(),
        userName: 'ops-user',
        homeDirectory: '/srv/operations',
        status: 'READY',
        ready: false,
        statusExpiresAt: null,
        lastSeenAt: 1784419200000,
        capabilities: [],
        daemonVersion: 'development',
        version: '1',
        createdAt: 1784419200000,
        updatedAt: 1784419200000,
      },
      {
        id: 'env-3',
        name: 'connecting-box',
        daemonConfig: testDaemonConfig(),
        userName: null,
        homeDirectory: null,
        status: 'CONNECTING',
        ready: false,
        statusExpiresAt: 1784505660000,
        lastSeenAt: null,
        capabilities: [],
        version: '1',
        createdAt: 1784505600000,
        updatedAt: 1784505600000,
      },
    ])
    renderPage()
    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    // 环境状态 pill；stale-box 必须显示 UNAVAILABLE 而非 READY。
    expect(screen.getAllByText('READY').length).toBe(1)
    expect(screen.getByText('UNAVAILABLE')).toBeInTheDocument()
    expect(screen.getByText('process.exec')).toBeInTheDocument()
    // 卡片展示最近一次 READY 的宿主进程用户，不展示主机目录。
    expect(screen.getByText('dev-user')).toBeInTheDocument()
    expect(screen.getByText('ops-user')).toBeInTheDocument()
    // 卡片展示实际 daemon 构建版本；development 如实展示，缺失时明确未上报而不是伪装成发布版本。
    expect(screen.getAllByText('Daemon 版本')).toHaveLength(3)
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('development')).toBeInTheDocument()
    expect(screen.getByText('未上报')).toBeInTheDocument()
    // 没有本代 READY 运行快照时明确展示不可用，不把缺失的 update 伪造成无更新或成功。
    expect(await screen.findAllByText('状态不可用，等待 Daemon 重连')).toHaveLength(3)
    expect(screen.queryByText('/workspace/local-dev')).toBeNull()
    // 卡片不展示 skills 字段
    expect(screen.queryByText('dev')).toBeNull()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
    expect(screen.getAllByText('Capabilities').length).toBe(3)

    // 验证每个环境卡片底部有「安装 / 卸载 / 编辑配置 / 管理 / 更新 / 重新生成 Token / 删除」七个动作；
    // 加载列表和打开表单都不得预取 token，仅显式生成安装命令时读取。
    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(3)
    for (const card of cards) {
      const footerButtons = within(card).getAllByRole('button')
      expect(footerButtons).toHaveLength(7)
      expect(within(card).getByRole('button', { name: /安装 \/ 覆盖/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /卸载/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /编辑配置/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /管理/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /更新 Daemon/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /重新生成 Token/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /删除环境/ })).toBeInTheDocument()
    }
  })

  it('shows empty state when no environments configured', async () => {
    // 测试意图：验证环境列表为空时首项呈现 CreateCard，且不展示多余的空态文本
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建环境' })).toHaveClass('create-card')
    })
    expect(screen.queryByText('当前没有 Environment')).not.toBeInTheDocument()
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

    await user.click(await screen.findByRole('button', { name: '创建环境' }))
    const form = await screen.findByRole('dialog', { name: '创建环境' })
    await user.type(within(form).getByRole('textbox', { name: /环境名称/ }), 'new-env')
    await user.type(within(form).getByRole('textbox', { name: '备注' }), 'fresh card')
    await user.click(within(form).getByRole('button', { name: '创建环境' }))

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
    expect(screen.queryByRole('dialog', { name: '创建环境' })).toBeNull()
    expect(screen.queryByText('secret-token-12345')).not.toBeInTheDocument()
    expect(clipboard.writeText).not.toHaveBeenCalled()
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map(mutation => mutation.state.data))).not.toContain('secret-token-12345')
  })

  it('edits a card configuration with CAS and keeps the draft when the version conflicts', async () => {
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

    const card = await screen.findByRole('article')
    await user.click(within(card).getByRole('button', { name: '编辑配置 my-box' }))
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

  it('derives applied, pending, failed and unavailable config states from the runtime snapshot', async () => {
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
    expect(await screen.findByText('已应用')).toBeInTheDocument()
    expect(await screen.findByText('待应用')).toBeInTheDocument()
    expect(await screen.findByText('应用失败')).toBeInTheDocument()
    expect(screen.getByText(/VALIDATION_FAILED/)).toBeInTheDocument()
    expect(await screen.findByText('待连接')).toBeInTheDocument()
    // 离线卡片没有当前运行快照，不能把卡片上的 proxy null 伪装成已直连。
    expect(environmentService.getEnvironmentRuntime).not.toHaveBeenCalledWith('env-offline')
    expect(screen.getAllByText('直连')).toHaveLength(2)
  })

  it('reads the runtime snapshot while CONNECTING instead of waiting for ready', async () => {
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
    expect(await screen.findByText('已应用')).toBeInTheDocument()
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

    expect(await screen.findByText('已应用')).toBeInTheDocument()
    expect(screen.queryByText('应用失败')).toBeNull()
  })

  // 确认后轮换并刷新列表，不展示或缓存凭据。
  it('rotates registration token without displaying or fetching credentials', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.rotateToken).mockResolvedValue(
      environment({ id: 'env-1', name: 'my-box', registrationToken: 'rotated-tok-999', version: '2' }),
    )
    installClipboard()
    const { queryClient } = renderPage()

    const card = await screen.findByRole('article')
    const rotateBtn = within(card).getByRole('button', { name: /重新生成 Token/ })
    await user.click(rotateBtn)

    // 确认弹窗
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

  it('deletes an environment card after confirmation', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'to-delete', version: '1' }),
    ])
    vi.mocked(environmentService.deleteEnvironment).mockResolvedValue(undefined)
    renderPage()

    const deleteBtn = await screen.findByRole('button', { name: /删除环境/ })
    await user.click(deleteBtn)

    const modal = await screen.findByRole('alertdialog', { name: '删除环境' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除环境' })
    await user.click(confirmBtn)

    expect(environmentService.deleteEnvironment).toHaveBeenCalledWith('env-1', '1')
  })

  it('starts a managed Daemon update after confirmation and applies the returned runtime snapshot', async () => {
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

    const card = await screen.findByRole('article')
    expect(await within(card).findByText('无进行中的更新')).toBeInTheDocument()
    const updateBtn = within(card).getByRole('button', { name: '更新 Daemon my-box' })
    expect(updateBtn).not.toBeDisabled()
    await user.click(updateBtn)

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

  it('closes the update confirmation without starting a command when canceled', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'cancel-box', daemonVersion: '1.2.0' }),
    ])
    renderPage()

    const updateBtn = await screen.findByRole('button', { name: '更新 Daemon cancel-box' })
    await user.click(updateBtn)

    const modal = await screen.findByRole('alertdialog', { name: '更新 Daemon' })
    await user.click(within(modal).getByRole('button', { name: '取消' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: '更新 Daemon' })).toBeNull())
    expect(environmentService.startEnvironmentUpdate).not.toHaveBeenCalled()
  })

  it('shows the live Daemon version and known download progress without treating 100% download as success', async () => {
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

    expect(await screen.findByText('1.2.1')).toBeInTheDocument()
    expect(screen.queryByText('1.2.0')).not.toBeInTheDocument()
    expect(
      screen.getByText('正在更新至 1.3.0 · 1 MB / 1 MB（100%）'),
    ).toBeInTheDocument()
    expect(screen.queryByText('更新成功')).not.toBeInTheDocument()
    // 当前更新快照不锁定按钮；重复请求由 Server/Daemon 的互斥与幂等语义裁决。
    expect(screen.getByRole('button', { name: '更新 Daemon live-box' })).not.toBeDisabled()
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

    expect(await screen.findByText('runtime route failed')).toBeInTheDocument()
    expect(await screen.findByText('运行状态读取失败')).toBeInTheDocument()
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

    const updateBtn = await screen.findByRole('button', { name: '更新 Daemon offline-box' })
    await user.click(updateBtn)

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

    const updateBtn = await screen.findByRole('button', { name: '更新 Daemon busy-box' })
    await user.click(updateBtn)

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

  it('shows the loading state while the registry request is pending', async () => {
    let resolveListing: ((value: EnvironmentDTO[]) => void) | undefined
    vi.mocked(environmentService.listEnvironments).mockImplementation(
      () => new Promise<EnvironmentDTO[]>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPage()

    expect(screen.getByText('正在加载 Environment')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()

    await act(async () => {
      resolveListing?.([environment({ name: 'box-a' })])
    })
    expect(await screen.findByText('box-a')).toBeInTheDocument()
    expect(screen.queryByText('正在加载 Environment')).not.toBeInTheDocument()
  })

  it('renders the query error message with the danger tone', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValue(new Error('registry down'))
    renderPage()

    expect(await screen.findByText('registry down')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.queryByText('当前没有 Environment')).not.toBeInTheDocument()
  })

  it('retains cached environments and create action when background refetch fails with an error', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValueOnce([
      environment({ name: 'box-cached' }),
    ])
    const { queryClient } = renderPage()

    expect(await screen.findByText('box-cached')).toBeInTheDocument()
    expect(screen.getByText('创建环境')).toBeInTheDocument()

    // 模拟后台 refetch 发生网络错误
    vi.mocked(environmentService.listEnvironments).mockRejectedValueOnce(new Error('background glitch'))
    await act(async () => {
      await queryClient.refetchQueries()
    })

    // 验证：既展示错误提示，又保留已有缓存数据与创建卡操作
    expect(await screen.findByText('background glitch')).toBeInTheDocument()
    expect(screen.getByText('box-cached')).toBeInTheDocument()
    expect(screen.getByText('创建环境')).toBeInTheDocument()
  })

  it('formats epoch-millisecond lastSeen, including sub-second values', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-id-sec', name: 'seconds-box', lastSeenAt: 1750000000000 }),
      environment({ id: 'env-id-frac', name: 'fraction-box', lastSeenAt: 1750000000500 }),
    ])
    renderPage()

    expect(await screen.findByText('seconds-box')).toBeInTheDocument()
    expect(screen.getAllByText(/最近活动 · 2025-06-15/)).toHaveLength(2)
  })

  it('truncates tag rows to three chips and shows the +N remainder', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        name: 'fat-box',
        capabilities: [
          { id: 'a' },
          { id: 'b' },
          { id: 'c' },
          { id: 'd' },
          { id: 'e' },
        ],
      }),
    ])
    renderPage()

    const card = (await screen.findByText('fat-box')).closest('article') as HTMLElement | null
    expect(card).not.toBeNull()
    const capabilityRow = within(card!).getByText('Capabilities').closest('.resource-card-meta-row') as HTMLElement | null
    expect(capabilityRow).not.toBeNull()
    expect(capabilityRow!.querySelectorAll('.resource-card-tag')).toHaveLength(4)
    expect(within(capabilityRow!).getByText('+2')).toBeInTheDocument()
    expect(capabilityRow!.querySelector('.resource-card-tags')).toHaveAttribute(
      'title',
      'a, b, c, d, e',
    )
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

    const card = await screen.findByRole('article')
    const rotateBtn = within(card).getByRole('button', { name: /重新生成 Token/ })
    await user.click(rotateBtn)

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

    const deleteBtn = await screen.findByRole('button', { name: /删除环境/ })
    await user.click(deleteBtn)

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
    const createButton = await screen.findByRole('button', { name: '创建环境' })
    expect(createButton).toHaveClass('create-card')
    expect(screen.getAllByRole('button', { name: '创建环境' })).toHaveLength(1)

    // 验证列表真正为空时不渲染多余的 StateBlock 文本
    expect(screen.queryByText('当前没有 Environment')).not.toBeInTheDocument()
  })

  /**
   * 测试意图：卡片只呈现当前状态，不内嵌历史事件；同一环境的历史 WARN 与后续 READY 事件
   * 只在管理弹窗的 Events 列表中可见，不以删除事件让卡片干净。
   */
  it('renders only the current status on the card and keeps event history in the management modal', async () => {
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

    // 卡片显示当前 READY 状态，不回显事件文本。
    const card = screen.getByRole('heading', { name: 'recovered-box' }).closest('article')!
    expect(within(card).getByText('READY')).toBeInTheDocument()
    expect(within(card).queryByText('daemon connection lost')).not.toBeInTheDocument()

    // 打开管理弹窗后仍可见完整历史：历史 WARN 与后续 READY 都被保留。
    const user = userEvent.setup()
    await user.click(within(card).getByRole('button', { name: /管理/ }))
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
      expect(screen.getByText('OFFLINE')).toBeInTheDocument()

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
      expect(screen.getByText('OFFLINE')).toBeInTheDocument()

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
