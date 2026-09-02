import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import { environmentService } from '@/shared/api/environment-service'
import type {
  EnvironmentBindingDTO,
  EnvironmentDirectoryDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listDirectories: vi.fn(),
  },
}))

const readyEnvironments: LiveEnvironmentDTO[] = [
  {
    name: 'local',
    rootPath: null,
    ready: true,
    status: 'READY',
    lastSeen: null,
    capabilities: [],
    skills: [],
  },
  {
    name: 'remote',
    rootPath: null,
    ready: true,
    status: 'READY',
    lastSeen: null,
    capabilities: [],
    skills: [],
  },
  {
    name: 'connecting',
    rootPath: null,
    ready: false,
    status: 'CONNECTING',
    lastSeen: null,
    capabilities: [],
    skills: [],
  },
]

function directory(
  path: string,
  entries: Array<{ name: string; path: string }> = [],
  overrides: Partial<EnvironmentDirectoryDTO> = {},
): EnvironmentDirectoryDTO {
  return {
    path,
    displayPath: path === '.' ? '.' : path.split('/').at(-1)!,
    parentPath: path === '.' ? '.' : path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '.',
    truncated: false,
    gitBranch: null,
    entries,
    ...overrides,
  }
}

function renderPanel(options: {
  environments?: LiveEnvironmentDTO[]
  current?: EnvironmentBindingDTO | null
  pending?: boolean
  onSelect?: (binding: EnvironmentBindingDTO | null) => void
  onClose?: () => void
} = {}) {
  const onSelect = options.onSelect ?? vi.fn()
  const onClose = options.onClose ?? vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentWorkspacePanel
        environments={options.environments ?? readyEnvironments}
        current={options.current ?? null}
        pending={options.pending ?? false}
        onSelect={onSelect}
        onClose={onClose}
      />
    </QueryClientProvider>,
  )
  return { onSelect, onClose }
}

function renderPendingPanel(options: {
  environments?: LiveEnvironmentDTO[]
  current?: EnvironmentBindingDTO | null
  pending?: boolean
  onSelect?: (binding: EnvironmentBindingDTO | null) => void
  onClose?: () => void
} = {}) {
  const onSelect = options.onSelect ?? vi.fn()
  const onClose = options.onClose ?? vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentWorkspacePanel
        environments={options.environments ?? readyEnvironments}
        current={options.current ?? null}
        pending={options.pending ?? false}
        onSelect={onSelect}
        onClose={onClose}
      />
    </QueryClientProvider>,
  )
  return { onSelect, onClose, queryClient, view }
}

describe('EnvironmentWorkspacePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('browses the current binding path on open and confirms the wire path', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('proj/a', [{ name: 'app', path: 'proj/a/app' }]),
    )
    const { onSelect } = renderPanel({ current: { name: 'local', workspacePath: 'proj/a' } })

    // 已绑定：直接进入目录模式并查询当前 binding path。
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'proj/a'),
    )
    expect(within(dirPanel).getByText('当前：proj/a')).toBeInTheDocument()

    // 确认提交完整 binding（以 wire 返回的权威 path 为准）。
    await user.click(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ }))
    expect(onSelect).toHaveBeenCalledWith({ name: 'local', workspacePath: 'proj/a' })
  })

  it('starts directory browsing from the root for a freshly selected environment', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [{ name: 'proj', path: 'proj' }]),
    )
    renderPanel()

    // 未绑定：先看到 Environment 列表（仅 READY，CONNECTING 被过滤）。
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    expect(within(listPanel).getByRole('option', { name: /local/ })).toBeInTheDocument()
    expect(within(listPanel).getByRole('option', { name: /remote/ })).toBeInTheDocument()
    expect(within(listPanel).queryByRole('option', { name: /connecting/ })).not.toBeInTheDocument()

    // 选择新 Environment：从 root '.' 开始目录浏览。
    await user.click(within(listPanel).getByRole('option', { name: /local/ }))
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await waitFor(() => expect(environmentService.listDirectories).toHaveBeenCalledWith('local', '.'))
    expect(within(dirPanel).getByText('当前：.')).toBeInTheDocument()
  })

  it('enters child directories, goes up lexically, and refreshes', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (name, path) => {
      if (path === '.') {
        return directory('.', [{ name: 'proj', path: 'proj' }])
      }
      if (path === 'proj') {
        return directory('proj', [{ name: 'app', path: 'proj/app' }])
      }
      return directory(path)
    })
    const { onSelect } = renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // 进入直属子目录 proj。
    await user.click(await within(dirPanel).findByRole('option', { name: '进入 proj' }))
    await waitFor(() => expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'proj'))
    expect(await within(dirPanel).findByText('当前：proj')).toBeInTheDocument()
    expect(await within(dirPanel).findByRole('option', { name: '进入 app' })).toBeInTheDocument()

    // 上一级：lexical 回退到 '.'（root），此时 Up 禁用。
    await user.click(within(dirPanel).getByRole('button', { name: /^上一级/ }))
    await waitFor(() => expect(environmentService.listDirectories).toHaveBeenCalledWith('local', '.'))
    expect(await within(dirPanel).findByText('当前：.')).toBeInTheDocument()
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toBeDisabled()

    // 刷新：再次请求当前 path。
    const callsBefore = vi.mocked(environmentService.listDirectories).mock.calls.length
    await user.click(within(dirPanel).getByRole('button', { name: '刷新' }))
    await waitFor(() =>
      expect(vi.mocked(environmentService.listDirectories).mock.calls.length).toBeGreaterThan(callsBefore),
    )
    expect(vi.mocked(environmentService.listDirectories).mock.calls.at(-1)).toEqual(['local', '.'])
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('commits null immediately from the None option and keeps list mode intact', async () => {
    const user = userEvent.setup()
    const { onSelect } = renderPanel({ current: { name: 'local', workspacePath: '.' } })

    // 已绑定：初次打开直接进入目录模式；返回列表后可选择（无）。
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await user.click(within(dirPanel).getByRole('button', { name: '返回 Environment 列表' }))
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    await user.click(within(listPanel).getByRole('option', { name: /（无）/ }))
    expect(onSelect).toHaveBeenCalledWith(null)
    expect(environmentService.listDirectories).not.toHaveBeenCalledWith('local', 'proj/a')
  })

  it('restores the current environment as active when a directory path matches another name', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('proj'))
    renderPanel({
      environments: [
        ...readyEnvironments,
        {
          name: 'proj',
          rootPath: null,
          ready: true,
          status: 'READY',
          lastSeen: null,
          capabilities: [],
          skills: [],
        },
      ],
      current: { name: 'local', workspacePath: 'proj' },
    })

    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await user.click(within(dirPanel).getByRole('button', { name: '返回 Environment 列表' }))
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    expect(within(listPanel).getByRole('option', { name: /local/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(within(listPanel).getByRole('option', { name: /^proj$/ })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  it('closing with Escape only cancels and never selects', async () => {
    const user = userEvent.setup()
    const { onSelect, onClose } = renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onSelect).not.toHaveBeenCalled()
    // 目录模式仍打开（关闭动作由外层负责卸载面板）。
    expect(screen.getByRole('region', { name: 'local 目录' })).toBeInTheDocument()
    expect(dirPanel).toBeInTheDocument()
  })

  it('recovers from a missing directory error via the Up action and disables confirm', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories)
      .mockRejectedValueOnce(new Error('directory gone'))
      .mockResolvedValueOnce(directory('proj'))
    renderPanel({ current: { name: 'local', workspacePath: 'proj/missing' } })

    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    // 加载失败：提示错误、确认禁用，但 Up 仍可按安全 wire path 向上恢复。
    expect(await screen.findByRole('alert')).toHaveTextContent('目录加载失败')
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toBeEnabled()

    await user.click(within(dirPanel).getByRole('button', { name: /^上一级/ }))
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'proj'),
    )
    await waitFor(() =>
      expect(within(dirPanel).getByText('当前：proj')).toBeInTheDocument(),
    )
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeEnabled()
  })

  it('rejects a directory response whose path does not match the request', async () => {
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('other'))
    renderPanel({ current: { name: 'local', workspacePath: 'proj' } })

    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    expect(await within(dirPanel).findByRole('alert')).toHaveTextContent('目录加载失败')
    expect(within(dirPanel).getByText('当前：proj')).toBeInTheDocument()
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeDisabled()
  })

  it('disables stale directory actions while refreshing and after refresh failure', async () => {
    const user = userEvent.setup()
    let rejectRefresh: ((reason?: unknown) => void) | undefined
    vi.mocked(environmentService.listDirectories)
      .mockResolvedValueOnce(directory('.', [{ name: 'proj', path: 'proj' }]))
      .mockImplementationOnce(
        () => new Promise<EnvironmentDirectoryDTO>((_resolve, reject) => {
          rejectRefresh = reject
        }),
      )
    renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    const confirm = within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })
    const refresh = within(dirPanel).getByRole('button', { name: '刷新' })
    const child = await within(dirPanel).findByRole('option', { name: '进入 proj' })
    expect(confirm).toBeEnabled()

    await user.click(refresh)
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledTimes(2),
    )
    expect(refresh).toBeDisabled()
    expect(child).toBeDisabled()
    expect(confirm).toBeDisabled()

    await act(async () => {
      rejectRefresh?.(new Error('refresh failed'))
    })
    expect(await within(dirPanel).findByRole('alert')).toHaveTextContent('目录加载失败')
    // React Query 保留上一次成功数据；失败后也绝不能确认该陈旧结果。
    expect(confirm).toBeDisabled()
  })

  it('disables every interaction while pending', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [{ name: 'proj', path: 'proj' }]),
    )
    const { onSelect } = renderPanel({
      current: { name: 'local', workspacePath: '.' },
      pending: true,
    })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    expect(await within(dirPanel).findByRole('option', { name: '进入 proj' })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: '刷新' })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: '返回 Environment 列表' })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeDisabled()

    await user.click(within(dirPanel).getByRole('option', { name: '进入 proj' }))
    expect(environmentService.listDirectories).not.toHaveBeenCalledWith('local', 'proj')
    await user.click(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ }))
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('focuses the listbox per mode and navigates with Arrow/Enter', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (name, path) => {
      if (path === '.') {
        return directory('.', [{ name: 'proj', path: 'proj' }])
      }
      return directory(path)
    })
    renderPanel()

    // 列表模式：listbox 获得焦点；ArrowDown 从（无）移到 local；Enter 进入目录模式。
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    const listbox = within(listPanel).getByRole('listbox')
    await waitFor(() => expect(listbox).toHaveFocus())
    await user.keyboard('{ArrowDown}')
    expect(within(listPanel).getByRole('option', { name: /local/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Enter}')
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // 目录模式：listbox 重新聚焦；Enter 进入当前 active 目录条目。
    const dirListbox = within(dirPanel).getByRole('listbox')
    await waitFor(() => expect(dirListbox).toHaveFocus())
    await user.keyboard('{Enter}')
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'proj'),
    )
    expect(await within(dirPanel).findByText('当前：proj')).toBeInTheDocument()
  })

  it('uses Backspace to go up and Ctrl+Enter to confirm the highlighted or current directory', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (_name, path) => {
      if (path === '.') {
        return directory('.', [{ name: 'proj', path: 'proj' }])
      }
      if (path === 'proj') {
        return directory('proj')
      }
      return directory(path)
    })
    const { onSelect } = renderPanel({ current: { name: 'local', workspacePath: 'proj' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await waitFor(() => expect(within(dirPanel).getByText('当前：proj')).toBeInTheDocument())
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toHaveTextContent('(Backspace)')
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toHaveTextContent('(Ctrl+Enter)')

    await user.keyboard('{Backspace}')
    await waitFor(() => expect(environmentService.listDirectories).toHaveBeenCalledWith('local', '.'))
    expect(await within(dirPanel).findByText('当前：.')).toBeInTheDocument()

    await user.keyboard('{Control>}{Enter}{/Control}')
    expect(onSelect).toHaveBeenCalledWith({ name: 'local', workspacePath: 'proj' })

    onSelect.mockClear()
    await user.keyboard('{Enter}')
    await waitFor(() => expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'proj'))
    expect(await within(dirPanel).findByText('当前：proj')).toBeInTheDocument()
    await user.keyboard('{Control>}{Enter}{/Control}')
    expect(onSelect).toHaveBeenCalledWith({ name: 'local', workspacePath: 'proj' })
  })

  it('shows loading while the directory request is pending', async () => {
    let resolveListing: ((value: EnvironmentDirectoryDTO) => void) | undefined
    vi.mocked(environmentService.listDirectories).mockImplementation(
      () => new Promise<EnvironmentDirectoryDTO>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPanel({ current: { name: 'local', workspacePath: '.' } })

    // 首次请求未返回前：目录模式展示 loading 占位，且没有条目与确认入口。
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    expect(within(dirPanel).getByText('正在加载目录…')).toBeInTheDocument()
    expect(within(dirPanel).queryByRole('option')).not.toBeInTheDocument()
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeDisabled()

    await act(async () => {
      resolveListing?.(directory('.'))
    })
    expect(await within(dirPanel).findByText('该目录没有子目录')).toBeInTheDocument()
  })

  it('shows an empty message for a directory without subdirectories and commits it via Ctrl+Enter', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('.'))
    const { onSelect } = renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // 空目录：无条目、无“进入”操作，展示空提示；Ctrl+Enter 直接确认当前路径。
    expect(await within(dirPanel).findByText('该目录没有子目录')).toBeInTheDocument()
    expect(within(dirPanel).queryByRole('option')).not.toBeInTheDocument()
    await user.keyboard('{Control>}{Enter}{/Control}')
    expect(onSelect).toHaveBeenCalledWith({ name: 'local', workspacePath: '.' })
  })

  it('shows the git branch and truncated notice and hides them on error', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories)
      .mockResolvedValueOnce(
        directory('.', [{ name: 'app', path: 'app' }], { gitBranch: 'main', truncated: true }),
      )
      .mockRejectedValueOnce(new Error('gone'))
    renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // 成功加载：展示 git 分支与截断提示。
    expect(await within(dirPanel).findByText('分支：main')).toBeInTheDocument()
    expect(within(dirPanel).getByText('目录条目过多，仅显示前 1000 个')).toBeInTheDocument()

    // 刷新失败：错误态下分支与截断提示都必须消失（陈旧元数据不再展示）。
    await user.click(within(dirPanel).getByRole('button', { name: '刷新' }))
    expect(await within(dirPanel).findByRole('alert')).toHaveTextContent('目录加载失败')
    expect(within(dirPanel).queryByText('分支：main')).not.toBeInTheDocument()
    expect(within(dirPanel).queryByText('目录条目过多，仅显示前 1000 个')).not.toBeInTheDocument()
  })

  it('selects the first active directory entry, moves with Arrow and enters with Enter', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [
        { name: 'app', path: 'app' },
        { name: 'docs', path: 'docs' },
      ]),
    )
    renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // 数据就绪后首个条目自动成为 active（键盘默认位置）。
    const first = await within(dirPanel).findByRole('option', { name: '进入 app' })
    expect(first).toHaveAttribute('aria-selected', 'true')

    // ArrowDown 移到下一个条目；Enter 进入该目录。
    await user.keyboard('{ArrowDown}')
    expect(within(dirPanel).getByRole('option', { name: '进入 docs' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Enter}')
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'docs'),
    )
    expect(await within(dirPanel).findByText('当前：docs')).toBeInTheDocument()
  })

  it('moves to the last entry with ArrowUp, wraps around and falls back when entries change', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (name, path) => {
      if (path === '.') {
        return directory('.', [
          { name: 'app', path: 'app' },
          { name: 'docs', path: 'docs' },
        ])
      }
      if (path === 'docs') {
        return directory('docs', [{ name: 'lib', path: 'docs/lib' }])
      }
      return directory(path)
    })
    renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    const app = await within(dirPanel).findByRole('option', { name: '进入 app' })

    // ArrowUp：首项上移环绕到末项 docs。
    await user.keyboard('{ArrowUp}')
    expect(within(dirPanel).getByRole('option', { name: '进入 docs' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // ArrowUp 再次上移：环绕回首项 app。
    await user.keyboard('{ArrowUp}')
    expect(app).toHaveAttribute('aria-selected', 'true')

    // Enter：进入当前 active 目录 docs。
    await user.keyboard('{ArrowDown}')
    expect(within(dirPanel).getByRole('option', { name: '进入 docs' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Enter}')
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'docs'),
    )
    expect(await within(dirPanel).findByRole('option', { name: '进入 lib' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('disables actions while the directory is fetching and ignores goUp', async () => {
    const user = userEvent.setup()
    let resolveListing: ((value: EnvironmentDirectoryDTO) => void) | undefined
    vi.mocked(environmentService.listDirectories).mockImplementation(
      () => new Promise<EnvironmentDirectoryDTO>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPanel({ current: { name: 'local', workspacePath: 'proj/app' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })

    // fetching 期间 Up/Refresh/Confirm 全部禁用，Backspace 与 Ctrl+Enter 也不生效。
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: '刷新' })).toBeDisabled()
    expect(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ })).toBeDisabled()
    await user.keyboard('{Backspace}')
    await user.keyboard('{Control>}{Enter}{/Control}')

    // 查询完成前不应发起任何额外的目录请求或选择。
    expect(vi.mocked(environmentService.listDirectories)).toHaveBeenCalledTimes(1)
    await act(async () => {
      resolveListing?.(directory('proj/app'))
    })
    expect(await within(dirPanel).findByText('该目录没有子目录')).toBeInTheDocument()
  })

  it('goes up once per Backspace keypress at most', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (name, path) => {
      if (path === 'proj') {
        return directory('proj')
      }
      if (path === '.') {
        return directory('.')
      }
      return directory(path)
    })
    renderPanel({ current: { name: 'local', workspacePath: 'proj' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    expect(await within(dirPanel).findByText('当前：proj')).toBeInTheDocument()

    // 根路径 Backspace 无效（parentPathOf 返回 null），不发起任何请求。
    await user.keyboard('{Backspace}')
    await waitFor(() => expect(within(dirPanel).getByText('当前：.')).toBeInTheDocument())
    expect(within(dirPanel).getByRole('button', { name: /^上一级/ })).toBeDisabled()

    // root 的 Backspace 不再有效：仍是同一次 keypress，不产生额外请求。
    const callsBefore = vi.mocked(environmentService.listDirectories).mock.calls.length
    await user.keyboard('{Backspace}')
    expect(vi.mocked(environmentService.listDirectories).mock.calls.length).toBe(callsBefore)
    expect(within(dirPanel).getByText('当前：.')).toBeInTheDocument()
  })

  it('blocks list navigation and selection while pending', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('.'))
    const { onSelect } = renderPanel({ pending: true })
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    const none = within(listPanel).getByRole('option', { name: /（无）/ })
    expect(none).toBeDisabled()

    // pending 时 Enter 提交无效，方向键也不得改变 active。
    await user.keyboard('{ArrowDown}')
    expect(within(listPanel).getByRole('option', { name: /local/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Enter}')
    expect(onSelect).not.toHaveBeenCalled()
    await user.click(none)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('keeps keyboard navigation disabled when pending flips on while in list mode', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('.'))
    const { onSelect, queryClient, view } = renderPendingPanel()
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })

    // 初始未 pending：ArrowDown 移到 local 后 Enter 不提交 onSelect，而是进入目录模式（root 查询）。
    await user.keyboard('{ArrowDown}')
    expect(within(listPanel).getByRole('option', { name: /local/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    const callsBeforeEnter = vi.mocked(environmentService.listDirectories).mock.calls.length
    await user.keyboard('{Enter}')
    expect(onSelect).not.toHaveBeenCalled()
    await waitFor(() =>
      expect(vi.mocked(environmentService.listDirectories).mock.calls.length).toBeGreaterThan(
        callsBeforeEnter,
      ),
    )
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    expect(vi.mocked(environmentService.listDirectories).mock.calls.at(-1)).toEqual(['local', '.'])

    // pending 翻转后（仍处于目录模式）：Enter/方向键都不再产生选择或新请求。
    view.rerender(
      <QueryClientProvider client={queryClient}>
        <EnvironmentWorkspacePanel
          environments={readyEnvironments}
          current={{ name: 'local', workspacePath: '.' }}
          pending
          onSelect={onSelect}
          onClose={vi.fn()}
        />
      </QueryClientProvider>,
    )
    const pendingDirPanel = await screen.findByRole('region', { name: 'local 目录' })
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{Enter}')
    expect(onSelect).not.toHaveBeenCalled()
    const callsAfterFlip = vi.mocked(environmentService.listDirectories).mock.calls.length
    await user.keyboard('{Control>}{Enter}{/Control}')
    await user.click(within(pendingDirPanel).getByRole('button', { name: '返回 Environment 列表' }))
    expect(onSelect).not.toHaveBeenCalled()
    expect(vi.mocked(environmentService.listDirectories).mock.calls.length).toBe(callsAfterFlip)
    expect(dirPanel).toBeInTheDocument()
  })

  it('focuses the environment option and activates it with mouse hover', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [
        { name: 'app', path: 'app' },
        { name: 'docs', path: 'docs' },
      ]),
    )
    renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    const first = await within(dirPanel).findByRole('option', { name: '进入 app' })
    expect(first).toHaveAttribute('aria-selected', 'true')

    // 鼠标悬停激活 docs：onFocus 与 onMouseMove 都会更新 active 条目。
    await user.hover(within(dirPanel).getByRole('option', { name: '进入 docs' }))
    expect(within(dirPanel).getByRole('option', { name: '进入 docs' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(first).toHaveAttribute('aria-selected', 'false')
    await user.click(within(dirPanel).getByRole('option', { name: '进入 docs' }))
    await waitFor(() =>
      expect(environmentService.listDirectories).toHaveBeenCalledWith('local', 'docs'),
    )
    expect(await within(dirPanel).findByText('当前：docs')).toBeInTheDocument()

    // 返回列表模式：hover 列表项同样激活；焦点落在 local 上。
    await user.click(within(dirPanel).getByRole('button', { name: '返回 Environment 列表' }))
    const listPanel = await screen.findByRole('region', { name: '选择 Environment' })
    await user.hover(within(listPanel).getByRole('option', { name: /local/ }))
    expect(within(listPanel).getByRole('option', { name: /local/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('ignores IME composition keydowns and does not close or navigate', async () => {
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [{ name: 'proj', path: 'proj' }]),
    )
    const { onClose } = renderPanel({ current: { name: 'local', workspacePath: '.' } })
    const dirPanel = await screen.findByRole('region', { name: 'local 目录' })
    const dirListbox = within(dirPanel).getByRole('listbox')

    // 组合输入（keyCode 229）被视为不完整字符：Escape/Enter/方向键全部放行，不得关闭或导航。
    const projOption = await within(dirPanel).findByRole('option', { name: '进入 proj' })
    fireEvent.keyDown(dirPanel, { key: 'Escape', keyCode: 229 })
    fireEvent.keyDown(dirListbox, { key: 'ArrowDown', keyCode: 229 })
    fireEvent.keyDown(dirListbox, { key: 'Enter', keyCode: 229 })
    expect(onClose).not.toHaveBeenCalled()
    expect(vi.mocked(environmentService.listDirectories)).toHaveBeenCalledTimes(1)
    expect(projOption).toHaveAttribute('aria-selected', 'true')
  })
})
