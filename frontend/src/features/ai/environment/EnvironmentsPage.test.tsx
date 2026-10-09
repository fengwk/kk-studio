import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { ApiError } from '@/shared/api/client'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironment: vi.fn(),
    getEnvironmentUpdate: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
    saveInstallConfig: vi.fn(),
    createEnvironment: vi.fn(),
    getRegistrationToken: vi.fn(),
    rotateToken: vi.fn(),
    deleteEnvironment: vi.fn(),
    listEnvironmentEvents: vi.fn(),
  },
}))

vi.mock('@/features/ai/extensions/AiNavigation', () => ({
  AiNavigation: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <EnvironmentsPage />
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { queryClient, view, sockets }
}

function environment(overrides: Partial<EnvironmentCardDTO>): EnvironmentCardDTO {
  return {
    id: 'env-id-1',
    name: 'env',
    status: 'READY',
    ready: true,
    statusExpiresAt: null,
    lastSeen: null,
    capabilities: [],
    userName: null,
    homeDirectory: null,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
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
  })

  // 验证渲染环境卡片及其状态与能力，底部提供「安装 / 卸载 / 管理 / 轮换 / 删除」动作
  it('renders environment cards with status and capabilities, with manage, install, uninstall, rotate and delete actions in footer', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        id: 'env-1',
        name: 'local-dev',
        userName: 'dev-user',
        homeDirectory: '/home/dev',
        status: 'READY',
        ready: true,
        statusExpiresAt: '2026-07-20T01:03:03.000Z',
        lastSeen: '2026-07-20T01:02:03.000Z',
        capabilities: [{ id: 'process.exec', version: '2' }],
        daemonVersion: '1.2.3',
        version: '1',
        createTime: '2026-07-20T00:00:00.000Z',
        updateTime: '2026-07-20T00:00:00.000Z',
      },
      {
        id: 'env-2',
        name: 'stale-box',
        userName: 'ops-user',
        homeDirectory: '/srv/operations',
        status: 'READY',
        ready: false,
        statusExpiresAt: null,
        lastSeen: '2026-07-19T00:00:00.000Z',
        capabilities: [],
        daemonVersion: 'development',
        version: '1',
        createTime: '2026-07-19T00:00:00.000Z',
        updateTime: '2026-07-19T00:00:00.000Z',
      },
      {
        id: 'env-3',
        name: 'connecting-box',
        userName: null,
        homeDirectory: null,
        status: 'CONNECTING',
        ready: false,
        statusExpiresAt: '2026-07-20T00:01:00.000Z',
        lastSeen: null,
        capabilities: [],
        version: '1',
        createTime: '2026-07-20T00:00:00.000Z',
        updateTime: '2026-07-20T00:00:00.000Z',
      },
    ])
    renderPage()
    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    // 环境状态 pill；stale-box 必须显示 UNAVAILABLE 而非 READY。
    expect(screen.getAllByText('READY').length).toBe(1)
    expect(screen.getByText('UNAVAILABLE')).toBeInTheDocument()
    expect(screen.getByText('process.exec')).toBeInTheDocument()
    // 卡片展示最近一次 READY 的宿主进程用户，且不再展示任何 Root 路径。
    expect(screen.getByText('dev-user')).toBeInTheDocument()
    expect(screen.getByText('ops-user')).toBeInTheDocument()
    // 卡片展示实际 daemon 构建版本；development 如实展示而不是伪装成发布版本。
    expect(screen.getAllByText('Daemon 版本')).toHaveLength(2)
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('development')).toBeInTheDocument()
    expect(screen.queryByText('/workspace/local-dev')).toBeNull()
    expect(screen.queryByText('Root 路径')).toBeNull()
    // 卡片不展示 skills 字段
    expect(screen.queryByText('dev')).toBeNull()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
    expect(screen.getAllByText('Capabilities').length).toBe(3)

    // 验证每个环境卡片底部有「安装 / 卸载 / 管理 / 重新生成 Token / 删除」五个动作；
    // 加载列表和打开表单都不得预取 token，仅显式生成安装命令时读取。
    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(3)
    for (const card of cards) {
      const footerButtons = within(card).getAllByRole('button')
      expect(footerButtons).toHaveLength(6)
      expect(within(card).getByRole('button', { name: /安装 \/ 覆盖/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /卸载/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /管理/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /更新 Daemon/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /重新生成 Token/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /删除环境/ })).toBeInTheDocument()
    }
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
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

  it('creates an environment and opens installation settings without showing credentials', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(environmentService.createEnvironment).mockResolvedValue({
      id: 'env-new',
      name: 'new-env',
      registrationToken: 'secret-token-12345',
      status: 'CONNECTING',
      ready: false,
      statusExpiresAt: null,
      lastSeen: null,
      capabilities: [],
      userName: null,
      homeDirectory: null,
      version: '1',
      createTime: '2026-07-20T00:00:00.000Z',
      updateTime: '2026-07-20T00:00:00.000Z',
    })
    const clipboard = installClipboard()
    const { queryClient } = renderPage()

    const createBtn = await screen.findByRole('button', { name: '创建环境' })
    await user.click(createBtn)

    const input = screen.getByRole('textbox', { name: /环境名称/ })
    await user.type(input, 'new-env')
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(environmentService.createEnvironment).toHaveBeenCalledWith({ name: 'new-env' })
    expect(await screen.findByRole('dialog', { name: '安装/覆盖环境' })).toBeInTheDocument()
    expect(screen.queryByText('secret-token-12345')).not.toBeInTheDocument()
    expect(clipboard.writeText).not.toHaveBeenCalled()
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map(mutation => mutation.state.data))).not.toContain('secret-token-12345')
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
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
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

  it('starts a managed Daemon update after confirmation and invalidates environment queries', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', daemonVersion: '1.2.0', version: '1' }),
    ])
    vi.mocked(environmentService.startEnvironmentUpdate).mockResolvedValue(
      environment({
        id: 'env-1',
        name: 'my-box',
        daemonVersion: '1.2.0',
        update: {
          operationId: 'op-1',
          targetVersion: '1.3.0',
          phase: 'PENDING',
          createdAt: '2026-10-09T00:00:00.000Z',
          updatedAt: '2026-10-09T00:00:00.000Z',
        },
        version: '1',
      }),
    )
    renderPage()

    const card = await screen.findByRole('article')
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
  })

  it('disables the update action for in-progress phases and renders update status and failure error on the card', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        id: 'env-running',
        name: 'box-running',
        update: {
          operationId: 'op-run',
          targetVersion: '1.3.0',
          phase: 'RUNNING',
          createdAt: '2026-10-09T00:00:00.000Z',
          updatedAt: '2026-10-09T00:00:01.000Z',
        },
      }),
      environment({
        id: 'env-unknown',
        name: 'box-unknown',
        update: {
          operationId: 'op-unk',
          targetVersion: '1.3.0',
          phase: 'UNKNOWN',
          createdAt: '2026-10-09T00:00:00.000Z',
          updatedAt: '2026-10-09T00:00:02.000Z',
        },
      }),
      environment({
        id: 'env-failed',
        name: 'box-failed',
        update: {
          operationId: 'op-fail',
          targetVersion: '1.3.0',
          phase: 'FAILED',
          error: 'checksum mismatch',
          createdAt: '2026-10-09T00:00:00.000Z',
          updatedAt: '2026-10-09T00:00:03.000Z',
        },
      }),
      environment({
        id: 'env-succeeded',
        name: 'box-succeeded',
        update: {
          operationId: 'op-ok',
          targetVersion: '1.3.0',
          phase: 'SUCCEEDED',
          createdAt: '2026-10-09T00:00:00.000Z',
          updatedAt: '2026-10-09T00:00:04.000Z',
        },
      }),
    ])
    renderPage()

    expect(await screen.findByText('box-running')).toBeInTheDocument()
    expect(screen.getAllByText('更新状态')).toHaveLength(4)
    expect(screen.getByText('更新中')).toBeInTheDocument()
    expect(screen.getByText('等待重连确认')).toBeInTheDocument()
    expect(screen.getByText('更新失败: checksum mismatch')).toBeInTheDocument()
    expect(screen.getByText('更新成功')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: '更新 Daemon box-running' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '更新 Daemon box-unknown' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '更新 Daemon box-failed' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: '更新 Daemon box-succeeded' })).not.toBeDisabled()
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
      new ApiError('环境忙', 409, 'CONFLICT', { reason: 'environment_busy', detail: 'Active tool call running' }),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(within(conflictModal).getByText(/environment_busy/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '更新 Daemon' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '数据已发生变化' })).toBeNull()
    })
  })

  it('shows the loading state while the registry request is pending', async () => {
    let resolveListing: ((value: EnvironmentCardDTO[]) => void) | undefined
    vi.mocked(environmentService.listEnvironments).mockImplementation(
      () => new Promise<EnvironmentCardDTO[]>((resolve) => {
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

  it('formats numeric epoch lastSeen as seconds and milliseconds and falls back to raw text', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-id-sec', name: 'seconds-box', lastSeen: 1750000000 }),
      environment({ id: 'env-id-mil', name: 'millis-box', lastSeen: 1750000000000 }),
      environment({ id: 'env-id-raw', name: 'raw-box', lastSeen: 'not-a-date' }),
    ])
    renderPage()

    expect(await screen.findByText('seconds-box')).toBeInTheDocument()
    expect(screen.getAllByText(/最近活动 · /).length).toBe(3)
    expect(screen.getAllByText(/最近活动 · 2025\/06\/15/)).toHaveLength(2)
    expect(screen.getByText('最近活动 · not-a-date')).toBeInTheDocument()
  })

  it('truncates tag rows to three chips and shows the +N remainder', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        name: 'fat-box',
        capabilities: [
          { id: 'a', version: '1' },
          { id: 'b', version: '1' },
          { id: 'c', version: '1' },
          { id: 'd', version: '1' },
          { id: 'e', version: '1' },
        ],
      }),
    ])
    renderPage()

    const card = (await screen.findByText('fat-box')).closest('article')
    expect(card).not.toBeNull()
    const capabilityRow = within(card!).getByText('Capabilities').closest('.resource-card-meta-row')
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
      new ApiError('冲突', 409, 'CONFLICT', { reason: 'stale_version', detail: 'Token rotation conflict' }),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(within(conflictModal).getByText(/stale_version/)).toBeInTheDocument()
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
      new ApiError('版本冲突', 409, 'CONFLICT', { reason: 'version_conflict', detail: 'Already deleted or modified' }),
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
        time: '2026-07-20T00:00:00.000Z',
        level: 'WARN',
        type: 'DISCONNECTED',
        message: 'daemon connection lost',
      },
      {
        time: '2026-07-20T00:01:00.000Z',
        level: 'INFO',
        type: 'READY',
        message: 'environment ready',
      },
    ])
    renderPage()

    expect(await screen.findByText('recovered-box')).toBeInTheDocument()

    // 卡片只显示当前 READY 状态，不再内嵌任何历史事件区域。
    const card = screen.getByRole('heading', { name: 'recovered-box' }).closest('article')!
    expect(within(card).getByText('READY')).toBeInTheDocument()
    expect(card.querySelector('.env-last-event')).toBeNull()
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
        environment({ id: 'env-1', name: 'later-box', statusExpiresAt: '2026-07-20T00:01:00.000Z' }),
        environment({ id: 'env-2', name: 'sooner-box', statusExpiresAt: '2026-07-20T00:00:10.000Z' }),
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
   * 测试意图：后端 StrictJackson 把 statusExpiresAt 写成数字时间戳（epoch 秒，可带小数）时，
   * 页面必须按真实截止点排一次回读；ISO、数字毫秒与数字 epoch 秒三种 wire 形态行为一致。
   */
  it.each([
    ['ISO 字符串', '2026-07-20T00:00:10.250Z'],
    ['数字毫秒', 1784505610250],
    ['数字 epoch 秒（带小数）', 1784505610.25],
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
   * 数字 epoch 秒形态同样如此，否则毫秒/秒混淆会让旧截止点立即触发一次回读。
   */
  it.each([
    ['ISO 字符串', '2026-07-20T00:00:10.000Z', '2026-07-20T00:01:10.000Z'],
    ['数字 epoch 秒', 1784505610, 1784505670],
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
        environment({ id: 'env-1', name: 'dying-box', statusExpiresAt: '2026-07-20T00:00:10.000Z' }),
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
        environment({ id: 'env-1', name: 'local-dev', statusExpiresAt: '2026-07-20T00:00:10.000Z' }),
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
