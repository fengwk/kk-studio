import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentIdeDirectoryListingDTO } from '@/shared/api/generated/contracts'
import {
  OpenDirectoryModal,
  type OpenDirectoryModalProps,
} from '@/features/environment/ide/OpenDirectoryModal'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listIdeDirectories: vi.fn() },
}))

const listIdeDirectories = vi.mocked(environmentService.listIdeDirectories)

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const HOME = '/home/user'
const PROJECTS = '/home/user/projects'
const WORKSPACE = '/home/user/projects/kk-studio'

function listing(
  path: string,
  parentPath: string | null,
  children: Array<{ name: string; path: string }> = [],
  truncated = false,
): EnvironmentIdeDirectoryListingDTO {
  return { path, parentPath, children, truncated }
}

const HOME_LISTING = listing(HOME, '/home', [
  { name: 'projects', path: PROJECTS },
  { name: 'notes', path: `${HOME}/notes` },
])
const PROJECTS_LISTING = listing(PROJECTS, HOME, [{ name: 'kk-studio', path: WORKSPACE }])
const USER_DIRECTORY_LISTING = listing('/home', '/', [{ name: 'user', path: HOME }])
const ROOT_LISTING = listing('/', null, [{ name: 'home', path: '/home' }])

function baseProps(overrides: Partial<OpenDirectoryModalProps> = {}): OpenDirectoryModalProps {
  return {
    environmentId: ENV_A,
    currentDirectory: PROJECTS,
    recentDirectories: [],
    onConfirm: vi.fn(),
    onClose: vi.fn(),
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

describe('OpenDirectoryModal', () => {
  beforeEach(() => {
    listIdeDirectories.mockReset()
  })

  it('starts at the home directory and loads a child only when it is expanded', async () => {
    // 首屏只请求主目录；展开前不会请求任何子目录，收起后丢弃已加载的下一层。
    const user = userEvent.setup()
    listIdeDirectories.mockResolvedValueOnce(HOME_LISTING)
    render(<OpenDirectoryModal {...baseProps()} />)

    await screen.findByTestId('ide-directory-location')
    expect(listIdeDirectories).toHaveBeenCalledTimes(1)
    expect(listIdeDirectories).toHaveBeenCalledWith(ENV_A, null, expect.anything())
    expect(screen.getByTestId('ide-directory-location').textContent).toBe(HOME)
    expect(screen.getByText('projects')).toBeVisible()
    expect(screen.queryByText('kk-studio')).not.toBeInTheDocument()

    listIdeDirectories.mockResolvedValueOnce(PROJECTS_LISTING)
    const toggle = screen.getAllByTestId('ide-directory-toggle')[0]!
    await user.click(toggle)
    expect(await screen.findByText('kk-studio')).toBeVisible()
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_A, PROJECTS, expect.anything())

    await user.click(toggle)
    expect(screen.queryByText('kk-studio')).not.toBeInTheDocument()
    expect(listIdeDirectories).toHaveBeenCalledTimes(2)
  })

  it('walks up to the filesystem root and disables the parent action there', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    listIdeDirectories
      .mockResolvedValueOnce(HOME_LISTING)
      .mockResolvedValueOnce(USER_DIRECTORY_LISTING)
      .mockResolvedValueOnce(ROOT_LISTING)
    render(<OpenDirectoryModal {...baseProps({ onConfirm })} />)

    await screen.findByTestId('ide-directory-location')
    const up = screen.getByTestId('ide-directory-up')
    expect(up).toBeEnabled()

    await user.click(up)
    await waitFor(() => {
      expect(screen.getByTestId('ide-directory-location').textContent).toBe('/home')
    })
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_A, '/home', expect.anything())

    await user.click(up)
    await waitFor(() => {
      expect(screen.getByTestId('ide-directory-location').textContent).toBe('/')
    })
    expect(up).toBeDisabled()

    // 换根本身就是一次选择：确认直接切换到当前根目录。
    await user.click(screen.getByTestId('ide-directory-confirm'))
    expect(onConfirm).toHaveBeenCalledWith('/')
  })

  it('jumps to a recently opened directory and keeps it selected', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    listIdeDirectories
      .mockResolvedValueOnce(HOME_LISTING)
      .mockResolvedValueOnce(PROJECTS_LISTING)
    render(
      <OpenDirectoryModal
        {...baseProps({ onConfirm, recentDirectories: [PROJECTS, HOME] })}
      />,
    )

    await screen.findByTestId('ide-directory-location')
    const recent = screen.getAllByTestId('ide-recent-directory')
    expect(recent).toHaveLength(2)

    await user.click(recent[0]!)
    await waitFor(() => {
      expect(screen.getByTestId('ide-directory-location').textContent).toBe(PROJECTS)
    })
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_A, PROJECTS, expect.anything())
    expect(screen.getByText('kk-studio')).toBeVisible()

    await user.click(screen.getByTestId('ide-directory-confirm'))
    expect(onConfirm).toHaveBeenCalledWith(PROJECTS)
  })

  it('marks a truncated listing without hiding the entries it did return', async () => {
    listIdeDirectories.mockResolvedValueOnce(listing(HOME, '/home', HOME_LISTING.children, true))
    render(<OpenDirectoryModal {...baseProps()} />)

    expect(await screen.findByTestId('ide-directory-truncated')).toHaveTextContent('仅显示前 2 项')
    expect(screen.getByText('projects')).toBeVisible()
  })

  it('shows the backend reason for an unreadable directory and retries the same request', async () => {
    // 权限不足与目录不存在都来自宿主，必须展示后端原因而不是笼统失败或空列表。
    const user = userEvent.setup()
    listIdeDirectories
      .mockRejectedValueOnce(new ApiError('permission denied: /home', 403))
      .mockResolvedValueOnce(HOME_LISTING)
    render(<OpenDirectoryModal {...baseProps()} />)

    const error = await screen.findByTestId('ide-directory-load-error')
    expect(within(error).getByText('目录加载失败')).toBeVisible()
    expect(within(error).getByText('permission denied: /home')).toBeVisible()

    await user.click(within(error).getByRole('button', { name: '重试' }))
    await waitFor(() => {
      expect(screen.getByTestId('ide-directory-location').textContent).toBe(HOME)
    })
    expect(screen.queryByTestId('ide-directory-load-error')).not.toBeInTheDocument()
    expect(listIdeDirectories).toHaveBeenCalledTimes(2)
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_A, null, expect.anything())
  })

  it('reports an expanded directory failure inline and retries only that directory', async () => {
    const user = userEvent.setup()
    listIdeDirectories
      .mockResolvedValueOnce(HOME_LISTING)
      .mockRejectedValueOnce(new ApiError('not a directory', 400))
      .mockResolvedValueOnce(PROJECTS_LISTING)
    render(<OpenDirectoryModal {...baseProps()} />)

    await screen.findByTestId('ide-directory-location')
    await user.click(screen.getAllByTestId('ide-directory-toggle')[0]!)

    const nodeError = await screen.findByTestId('ide-directory-node-error')
    expect(within(nodeError).getByText('not a directory')).toBeVisible()

    await user.click(within(nodeError).getByRole('button', { name: '重试' }))
    expect(await screen.findByText('kk-studio')).toBeVisible()
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_A, PROJECTS, expect.anything())
  })

  it('ignores a directory response that arrives after the environment changed', async () => {
    // 切换 Environment 后迟到的主目录响应不得覆盖新的树。
    const stale = deferred<EnvironmentIdeDirectoryListingDTO>()
    listIdeDirectories
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(listing('/srv/b', null, [{ name: 'work', path: '/srv/b/work' }]))
    const { rerender } = render(<OpenDirectoryModal {...baseProps()} />)
    rerender(<OpenDirectoryModal {...baseProps({ environmentId: ENV_B })} />)

    await waitFor(() => {
      expect(screen.getByTestId('ide-directory-location').textContent).toBe('/srv/b')
    })
    expect(listIdeDirectories).toHaveBeenLastCalledWith(ENV_B, null, expect.anything())

    await act(async () => {
      stale.resolve(HOME_LISTING)
      await stale.promise
    })
    expect(screen.getByTestId('ide-directory-location').textContent).toBe('/srv/b')
    expect(screen.queryByText('projects')).not.toBeInTheDocument()
  })

  it('aborts in-flight directory requests when the dialog closes', async () => {
    const pending = deferred<EnvironmentIdeDirectoryListingDTO>()
    listIdeDirectories.mockReturnValueOnce(pending.promise)
    const { unmount } = render(<OpenDirectoryModal {...baseProps()} />)

    const signal = listIdeDirectories.mock.calls[0]![2] as AbortSignal
    expect(signal.aborted).toBe(false)

    unmount()
    expect(signal.aborted).toBe(true)
    await act(async () => {
      pending.resolve(HOME_LISTING)
      await pending.promise
    })
  })
})
