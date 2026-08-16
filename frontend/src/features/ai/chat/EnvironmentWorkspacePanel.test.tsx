import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
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
  { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
  { name: 'remote', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
  { name: 'connecting', ready: false, status: 'CONNECTING', lastSeen: null, tools: [], skills: [] },
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
        { name: 'proj', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
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
})
