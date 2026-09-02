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
    updateEnvironment: vi.fn(),
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
    skills: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

describe('EnvironmentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders environment cards with status capabilities and skills', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        id: 'env-1',
        name: 'local-dev',
        rootPath: '/workspace/local-dev',
        status: 'READY',
        ready: true,
        lastSeen: '2026-07-20T01:02:03.000Z',
        capabilities: [{ id: 'process.exec', version: '1' }],
        skills: [{ name: 'dev', description: 'dev skill' }],
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
        skills: [],
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
        skills: [],
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
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
    expect(screen.getAllByText('Capabilities').length).toBe(3)
    expect(screen.getAllByText('Skills').length).toBe(3)
  })

  it('shows empty state when no environments configured', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    renderPage()
    await waitFor(() => expect(screen.getByText('当前没有 Environment')).toBeInTheDocument())
  })

  it('creates an environment and shows one-time registration token dialog', async () => {
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
      skills: [],
      rootPath: null,
      version: '1',
      createTime: '2026-07-20T00:00:00.000Z',
      updateTime: '2026-07-20T00:00:00.000Z',
    })
    renderPage()

    const createBtn = await screen.findByRole('button', { name: '创建环境' })
    await user.click(createBtn)

    const input = screen.getByRole('textbox', { name: /环境名称/ })
    await user.type(input, 'new-env')
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(environmentService.createEnvironment).toHaveBeenCalledWith({ name: 'new-env' })
    // 一次性 token 展示弹窗
    expect(await screen.findByText('secret-token-12345')).toBeInTheDocument()
    expect(screen.getByText(/此 Registration Token 仅在本次创建或轮换时展示一次/)).toBeInTheDocument()

    // 复制 Token 并关闭弹窗
    const copyBtn = screen.getByRole('button', { name: '复制 Token' })
    await user.click(copyBtn)
    expect(await screen.findByText('已复制！')).toBeInTheDocument()

    const closeBtn = screen.getAllByRole('button', { name: '关闭' })[0]!
    await user.click(closeBtn)
    expect(screen.queryByText('secret-token-12345')).not.toBeInTheDocument()
  })

  it('edits and renames an environment card', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'old-name', version: '1' }),
    ])
    vi.mocked(environmentService.updateEnvironment).mockResolvedValue(
      environment({ id: 'env-1', name: 'renamed', version: '2' }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: '编辑环境' })
    await user.click(editBtn)

    const input = screen.getByRole('textbox', { name: /环境名称/ })
    await user.clear(input)
    await user.type(input, 'renamed')
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(environmentService.updateEnvironment).toHaveBeenCalledWith('env-1', '1', { name: 'renamed' })
  })

  it('rotates registration token and displays the new token', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'my-box', version: '1' }),
    ])
    vi.mocked(environmentService.rotateToken).mockResolvedValue(
      environment({ id: 'env-1', name: 'my-box', registrationToken: 'rotated-tok-999', version: '2' }),
    )
    renderPage()

    const rotateBtn = await screen.findByRole('button', { name: '重新生成 Token' })
    await user.click(rotateBtn)

    // 确认弹窗
    const modal = await screen.findByRole('alertdialog', { name: '重新生成 Token' })
    const confirmBtn = within(modal).getByRole('button', { name: '重新生成 Token' })
    await user.click(confirmBtn)

    expect(environmentService.rotateToken).toHaveBeenCalledWith('env-1', '1')
    expect(await screen.findByText('rotated-tok-999')).toBeInTheDocument()
  })

  it('deletes an environment card after confirmation', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'to-delete', version: '1' }),
    ])
    vi.mocked(environmentService.deleteEnvironment).mockResolvedValue(undefined)
    renderPage()

    const deleteBtn = await screen.findByRole('button', { name: '删除环境' })
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
      environment({ name: 'seconds-box', lastSeen: 1750000000 }),
      environment({ name: 'millis-box', lastSeen: 1750000000000 }),
      environment({ name: 'raw-box', lastSeen: 'not-a-date' }),
    ])
    renderPage()

    expect(await screen.findByText('seconds-box')).toBeInTheDocument()
    expect(screen.getAllByText(/最近查看 · /).length).toBe(3)
    expect(screen.getAllByText(/最近查看 · 2025\/06\/15/)).toHaveLength(2)
    expect(screen.getByText('最近查看 · not-a-date')).toBeInTheDocument()
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

  // 验证 Environment update 发生 HTTP 409 冲突时通过 ConflictPresenter 呈现，点击刷新后关闭过期编辑弹窗并拉取最新列表，绝不自动重试
  it('handles 409 conflict on update and resets stale edit modal on explicit refresh without auto-replay', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ id: 'env-1', name: 'old-name', version: '1' }),
    ])
    vi.mocked(environmentService.updateEnvironment).mockRejectedValue(
      new ApiError('版本冲突', 409, 'CONFLICT', {
        reason: 'version_conflict',
        detail: 'Environment version modified concurrently',
      }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: '编辑环境' })
    await user.click(editBtn)

    const input = screen.getByRole('textbox', { name: /环境名称/ })
    await user.clear(input)
    await user.type(input, 'new-name')
    await user.click(screen.getByRole('button', { name: '确认' }))

    // 冲突弹窗展示且不提供自动重试按钮
    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(within(conflictModal).getByText('Environment version modified concurrently')).toBeInTheDocument()
    expect(within(conflictModal).queryByRole('button', { name: '重试' })).toBeNull()

    // 点击刷新：重置 stale modal 并触发权威重新拉取
    const listCallsBefore = vi.mocked(environmentService.listEnvironments).mock.calls.length
    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
      expect(screen.queryByRole('dialog', { name: '编辑环境' })).toBeNull()
    })
    expect(vi.mocked(environmentService.listEnvironments).mock.calls.length).toBeGreaterThan(listCallsBefore)
    expect(environmentService.updateEnvironment).toHaveBeenCalledTimes(1)
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
    renderPage()

    const rotateBtn = await screen.findByRole('button', { name: '重新生成 Token' })
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

    const deleteBtn = await screen.findByRole('button', { name: '删除环境' })
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
})
