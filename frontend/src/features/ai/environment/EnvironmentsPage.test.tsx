import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { ApiError } from '@/shared/api/client'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    createEnvironment: vi.fn(),
    getRegistrationToken: vi.fn(),
    rotateToken: vi.fn(),
    deleteEnvironment: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentsPage />
    </QueryClientProvider>,
  )
  return { queryClient, view }
}

function environment(overrides: Partial<EnvironmentCardDTO>): EnvironmentCardDTO {
  return {
    id: 'env-id-1',
    name: 'env',
    rootPath: null,
    status: 'READY',
    ready: true,
    lastSeen: null,
    capabilities: [],
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

describe('EnvironmentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 验证渲染环境卡片及其状态与能力，底部提供「复制 Token / 管理 / 编辑 / 删除」动作
  it('renders environment cards with status and capabilities, with manage, copy token, edit and delete actions in footer', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        id: 'env-1',
        name: 'local-dev',
        rootPath: '/workspace/local-dev',
        status: 'READY',
        ready: true,
        lastSeen: '2026-07-20T01:02:03.000Z',
        capabilities: [{ id: 'process.exec', version: '1' }],
        version: '1',
        createTime: '2026-07-20T00:00:00.000Z',
        updateTime: '2026-07-20T00:00:00.000Z',
      },
      {
        id: 'env-2',
        name: 'stale-box',
        rootPath: '/workspace/stale-box',
        status: 'READY',
        ready: false,
        lastSeen: '2026-07-19T00:00:00.000Z',
        capabilities: [],
        version: '1',
        createTime: '2026-07-19T00:00:00.000Z',
        updateTime: '2026-07-19T00:00:00.000Z',
      },
      {
        id: 'env-3',
        name: 'connecting-box',
        rootPath: '/workspace/connecting-box',
        status: 'CONNECTING',
        ready: false,
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
    // 卡片不展示 skills 字段
    expect(screen.queryByText('dev')).toBeNull()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
    expect(screen.getAllByText('Capabilities').length).toBe(3)

    // 验证每个环境卡片底部有「复制 Token / 管理 / 重新生成 Token / 删除」四个动作；
    // 且加载列表后不得预取 token（点击才请求）。
    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(3)
    for (const card of cards) {
      const footerButtons = within(card).getAllByRole('button')
      expect(footerButtons).toHaveLength(4)
      expect(within(card).getByRole('button', { name: /复制 Token/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /管理/ })).toBeInTheDocument()
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

  it('creates an environment and shows the new registration token dialog', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(environmentService.createEnvironment).mockResolvedValue({
      id: 'env-new',
      name: 'new-env',
      registrationToken: 'secret-token-12345',
      status: 'CONNECTING',
      ready: false,
      lastSeen: null,
      capabilities: [],
      rootPath: null,
      version: '1',
      createTime: '2026-07-20T00:00:00.000Z',
      updateTime: '2026-07-20T00:00:00.000Z',
    })
    const clipboard = installClipboard()
    renderPage()

    const createBtn = await screen.findByRole('button', { name: '创建环境' })
    await user.click(createBtn)

    const input = screen.getByRole('textbox', { name: /环境名称/ })
    await user.type(input, 'new-env')
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(environmentService.createEnvironment).toHaveBeenCalledWith({ name: 'new-env' })
    // create 响应中的 token 展示弹窗；文案说明之后仍可从卡片复制当前 Token
    expect(await screen.findByText('secret-token-12345')).toBeInTheDocument()
    expect(screen.getByText(/之后可随时从环境卡片复制当前 Token/)).toBeInTheDocument()

    // 复制 Token 并关闭弹窗（弹窗内复制直接使用内存值，不发起额外请求）
    const copyBtn = screen.getByRole('button', { name: '复制 Token' })
    await user.click(copyBtn)
    expect(await screen.findByText('已复制！')).toBeInTheDocument()
    expect(clipboard.writeText).toHaveBeenCalledWith('secret-token-12345')
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()

    const closeBtn = screen.getAllByRole('button', { name: '关闭' })[0]!
    await user.click(closeBtn)
    expect(screen.queryByText('secret-token-12345')).not.toBeInTheDocument()
  })

  /**
   * 验证「复制 Token」是按需读取：列表渲染与轮换都不预取，只有点击卡片动作时才调用
   * getRegistrationToken，且该动作绝不触发 rotateToken（不轮换、不断开已有连接）。
   */
  it('copies the current token only on demand without rotating', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.getRegistrationToken).mockResolvedValue({
      id: 'env-1',
      registrationToken: 'current-tok-777',
      version: '1',
    })
    const clipboard = installClipboard()
    renderPage()

    const card = await screen.findByRole('article')
    // 渲染列表时绝不预取 token
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()

    await user.click(within(card).getByRole('button', { name: /复制 Token/ }))

    await waitFor(() => {
      expect(environmentService.getRegistrationToken).toHaveBeenCalledWith('env-1')
    })
    expect(clipboard.writeText).toHaveBeenCalledWith('current-tok-777')
    // 读取是只读动作：绝不轮换
    expect(environmentService.rotateToken).not.toHaveBeenCalled()
    expect(await within(card).findByText('已复制！')).toBeInTheDocument()
    // 复制反馈不得写入 LocalStorage（凭据不落盘）
    expect(JSON.stringify(localStorage)).not.toContain('current-tok-777')
  })

  /** 验证按需复制失败时在卡片上可见报错，且不伪称已复制。 */
  it('shows a visible error when the on-demand token copy fails', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.getRegistrationToken).mockRejectedValue(
      new Error('environment not found'),
    )
    installClipboard()
    renderPage()

    const card = await screen.findByRole('article')
    await user.click(within(card).getByRole('button', { name: /复制 Token/ }))

    expect(await within(card).findByRole('alert')).toHaveTextContent('environment not found')
    expect(within(card).queryByText('已复制！')).toBeNull()
  })

  // 验证从卡片中直接调用「重新生成 Token」动作：确认后成功轮换并展示新 token 弹窗
  it('rotates registration token and displays the new token', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.rotateToken).mockResolvedValue(
      environment({ id: 'env-1', name: 'my-box', registrationToken: 'rotated-tok-999', version: '2' }),
    )
    installClipboard()
    renderPage()

    const card = await screen.findByRole('article')
    const rotateBtn = within(card).getByRole('button', { name: /重新生成 Token/ })
    await user.click(rotateBtn)

    // 确认弹窗
    const modal = await screen.findByRole('alertdialog', { name: '重新生成 Token' })
    const confirmBtn = within(modal).getByRole('button', { name: '重新生成 Token' })
    await user.click(confirmBtn)

    expect(environmentService.rotateToken).toHaveBeenCalledWith('env-1', '1')
    expect(await screen.findByText('rotated-tok-999')).toBeInTheDocument()
    // 轮换不应顺带读取 token
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
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

  it('shows the loading state while the registry request is pending', async () => {
    let resolveListing: ((value: EnvironmentCardDTO[]) => void) | undefined
    vi.mocked(environmentService.listEnvironments).mockImplementation(
      () => new Promise<EnvironmentCardDTO[]>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPage()

    expect(screen.getByText('正在加载 Environments')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()

    await act(async () => {
      resolveListing?.([environment({ name: 'box-a' })])
    })
    expect(await screen.findByText('box-a')).toBeInTheDocument()
    expect(screen.queryByText('正在加载 Environments')).not.toBeInTheDocument()
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
    const capabilityRow = within(card!).getByText('Capabilities').closest('.env-tag-row')
    expect(capabilityRow).not.toBeNull()
    expect(capabilityRow!.querySelectorAll('.meta-chip')).toHaveLength(4)
    expect(within(capabilityRow!).getByText('+2')).toBeInTheDocument()
    expect(capabilityRow!.querySelector('.meta-chips')).toHaveAttribute('title', 'a, b, c, d, e')
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

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/stale_version/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '重新生成 Token' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
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

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '删除环境' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
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
})
