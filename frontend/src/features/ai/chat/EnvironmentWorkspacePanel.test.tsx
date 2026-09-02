import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import { environmentService } from '@/shared/api/environment-service'
import type {
  EnvironmentCardDTO,
  EnvironmentDirectoryDTO,
} from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listDirectories: vi.fn(),
  },
}))

const localEnvironment: EnvironmentCardDTO = {
  id: 'env-local-1',
  name: 'local',
  rootPath: null,
  ready: true,
  status: 'READY',
  lastSeen: null,
  capabilities: [],
  skills: [],
  version: '1',
  createTime: '2026-07-20T00:00:00.000Z',
  updateTime: '2026-07-20T00:00:00.000Z',
}

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
  environment?: EnvironmentCardDTO | null
  current?: string | null
  pending?: boolean
  onSelect?: (workspacePath: string | null) => void
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
        environment={options.environment !== undefined ? options.environment : localEnvironment}
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

  it('renders unbound state when current Agent has no bound Environment', async () => {
    const { onSelect, onClose } = renderPanel({ environment: null, current: 'proj/a' })
    expect(screen.getByText('当前 Agent 未绑定环境，无法选择工作区路径。')).toBeInTheDocument()

    // 允许清除工作目录
    const clearBtn = screen.getByRole('button', { name: '清除工作目录' })
    fireEvent.click(clearBtn)
    expect(onSelect).toHaveBeenCalledWith(null)

    // 关闭按钮
    const closeBtn = screen.getByRole('button', { name: '关闭' })
    fireEvent.click(closeBtn)
    expect(onClose).toHaveBeenCalled()
  })

  it('browses directory using Environment Card UUID and emits selected workspacePath', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [
        { name: 'packages', path: 'packages' },
        { name: 'src', path: 'src' },
      ]),
    )
    const { onSelect } = renderPanel({ environment: localEnvironment, current: '.' })

    expect(await screen.findByText('packages')).toBeInTheDocument()
    expect(environmentService.listDirectories).toHaveBeenCalledWith('env-local-1', '.')

    // 点击确认使用当前目录
    const confirmBtn = screen.getByRole('button', { name: /^使用当前/ })
    await user.click(confirmBtn)
    expect(onSelect).toHaveBeenCalledWith('.')
  })

  it('navigates into subdirectory and back up', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockImplementation(async (id, path) => {
      if (path === '.') {
        return directory('.', [{ name: 'src', path: 'src' }])
      }
      if (path === 'src') {
        return directory('src', [{ name: 'components', path: 'src/components' }])
      }
      return directory(path, [])
    })

    renderPanel({ environment: localEnvironment, current: '.' })

    const srcItem = await screen.findByText('src')
    await user.click(srcItem)

    expect(await screen.findByText('components')).toBeInTheDocument()
    expect(environmentService.listDirectories).toHaveBeenCalledWith('env-local-1', 'src')

    // 点击返回上一级
    const upBtn = screen.getByRole('button', { name: /^上一级/ })
    await user.click(upBtn)

    expect(await screen.findByText('src')).toBeInTheDocument()
    expect(environmentService.listDirectories).toHaveBeenCalledWith('env-local-1', '.')
  })

  it('supports keyboard navigation in directory list', async () => {
    vi.mocked(environmentService.listDirectories).mockResolvedValue(
      directory('.', [
        { name: 'app', path: 'app' },
        { name: 'pkg', path: 'pkg' },
      ]),
    )
    const { onSelect } = renderPanel({ environment: localEnvironment, current: '.' })

    await screen.findByText('app')
    const listbox = screen.getByRole('listbox')

    // 按 Ctrl+Enter 选中高亮项
    fireEvent.keyDown(listbox, { key: 'ArrowDown' })
    fireEvent.keyDown(listbox, { key: 'Enter', ctrlKey: true })

    expect(onSelect).toHaveBeenCalledWith('pkg')
  })

  it('handles directory query errors gracefully and allows navigation back up', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockRejectedValue(new Error('Directory not found'))
    renderPanel({ environment: localEnvironment, current: 'missing/dir' })

    expect(await screen.findByText('Directory not found')).toBeInTheDocument()
    const upBtn = screen.getAllByRole('button', { name: /^上一级/ })[0]!
    await user.click(upBtn)
    expect(environmentService.listDirectories).toHaveBeenCalledWith('env-local-1', 'missing')
  })

  it('allows clearing workspace path', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listDirectories).mockResolvedValue(directory('proj', []))
    const { onSelect } = renderPanel({ environment: localEnvironment, current: 'proj' })

    await screen.findByText(/^使用当前/)
    const clearBtn = screen.getByRole('button', { name: '清除工作目录' })
    await user.click(clearBtn)

    expect(onSelect).toHaveBeenCalledWith(null)
  })
})
