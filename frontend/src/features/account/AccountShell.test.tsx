import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountMenu } from '@/features/account/AccountMenu'
import { AccountProvider } from '@/features/account/AccountProvider'
import { UsersPage } from '@/features/account/UsersPage'
import { AccountContext, useAccount } from '@/features/account/account-context'
import { ApiError, request } from '@/shared/api/client'
import type { StudioAccountDTO, StudioUserDTO } from '@/shared/api/generated/contracts'
import { sessionExpiry } from '@/shared/api/session-expiry'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

const ADMIN_ACCOUNT: StudioAccountDTO = {
  id: 'u-admin',
  username: 'admin',
  role: 'ADMIN',
  profile: {},
}

const USER_LIST: StudioUserDTO[] = [
  { id: 'u-admin', username: 'admin', role: 'ADMIN', enabled: true, createdAt: 0 },
  { id: 'u-bob', username: 'bob', role: 'USER', enabled: false, createdAt: 0 },
]

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    queryClient,
    ...render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>),
  }
}

describe('AccountProvider, AccountMenu and UsersPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('AccountProvider handles signed-out, load error retry, sign-in, session expiry relogin, and sign-out', async () => {
    const user = userEvent.setup()
    let phase: 'error' | 'signed-out' | 'signed-in' = 'error'
    mockedRequest.mockImplementation(async (endpoint: string) => {
      if (endpoint === 'GET /api/auth/me') {
        if (phase === 'error') throw new Error('network down')
        if (phase === 'signed-out') throw new ApiError('unauthorized', 401, 'NOT_AUTHENTICATED', undefined, 'NOT_AUTHENTICATED')
        return ADMIN_ACCOUNT
      }
      if (endpoint === 'POST /api/auth/login') {
        phase = 'signed-in'
        return undefined
      }
      if (endpoint === 'POST /api/auth/logout') {
        phase = 'signed-out'
        return undefined
      }
      throw new Error(`Unexpected: ${endpoint}`)
    })

    function Probe() {
      const { user: account, signOut, refresh } = useAccount()
      return (
        <div>
          <span data-testid="who">{account?.username}</span>
          <button type="button" onClick={() => void refresh()}>refresh</button>
          <button type="button" onClick={() => void signOut()}>logout</button>
        </div>
      )
    }

    renderWithQuery(<AccountProvider><Probe /></AccountProvider>)

    // Initial load error -> retry -> signed-out form
    expect(await screen.findByRole('alert')).toHaveTextContent('network down')
    phase = 'signed-out'
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('button', { name: '登录' })).toBeInTheDocument()

    // Sign in -> ready
    await user.type(screen.getByLabelText('用户名'), 'admin')
    await user.type(screen.getByLabelText('密码'), 'password123')
    await user.click(screen.getByRole('button', { name: '登录' }))
    expect(await screen.findByTestId('who')).toHaveTextContent('admin')

    // Session expiry event opens locked relogin dialog
    act(() => { sessionExpiry.dispatchEvent(new Event('expired')) })
    expect(await screen.findByRole('dialog', { name: '登录已失效' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('密码'), 'password123')
    await user.click(screen.getByRole('button', { name: '登录' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '登录已失效' })).toBeNull())

    // Refresh when 401 signs out
    phase = 'signed-out'
    await user.click(screen.getByRole('button', { name: 'refresh' }))
    expect(await screen.findByRole('button', { name: '登录' })).toBeInTheDocument()

    // Sign back in and test explicit signOut
    await user.type(screen.getByLabelText('用户名'), 'admin')
    await user.type(screen.getByLabelText('密码'), 'password123')
    await user.click(screen.getByRole('button', { name: '登录' }))
    expect(await screen.findByTestId('who')).toHaveTextContent('admin')
    await user.click(screen.getByRole('button', { name: 'logout' }))
    expect(await screen.findByRole('button', { name: '登录' })).toBeInTheDocument()
  })

  it('AccountMenu validates password change, handles WRONG_PASSWORD/overall errors, and confirms logout when unsent', async () => {
    const user = userEvent.setup()
    const signOut = vi.fn().mockRejectedValueOnce(new Error('logout failed')).mockResolvedValueOnce(undefined)
    mockedRequest
      .mockRejectedValueOnce(new ApiError('wrong', 400, 'WRONG_PASSWORD', undefined, 'WRONG_PASSWORD'))
      .mockRejectedValueOnce(new Error('unexpected failure'))
      .mockResolvedValueOnce(undefined)

    const { rerender } = renderWithQuery(
      <AccountContext.Provider value={{ user: ADMIN_ACCOUNT, refresh: async () => {}, signOut }}>
        <AccountMenu hasUnsentContent={false} />
      </AccountContext.Provider>,
    )

    // Direct sign-out without unsent content shows error if signOut throws
    await user.click(screen.getByRole('button', { name: 'admin' }))
    await user.click(screen.getByRole('menuitem', { name: '退出登录' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('logout failed')

    // Change password validations and server error handling
    await user.click(screen.getByRole('button', { name: 'admin' }))
    await user.click(screen.getByRole('menuitem', { name: '修改密码' }))
    const dlg = screen.getByRole('dialog', { name: '修改密码' })
    expect(dlg).toBeInTheDocument()

    await user.type(screen.getByLabelText('当前密码'), 'oldpass123')
    await user.type(screen.getByLabelText('新密码'), 'short')
    await user.type(screen.getByLabelText('确认新密码'), 'short')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByText('密码长度须为 8–128 个字符。')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('新密码'))
    await user.type(screen.getByLabelText('新密码'), 'newpassword1')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByText('两次输入的密码不一致。')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('确认新密码'))
    await user.type(screen.getByLabelText('确认新密码'), 'newpassword1')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('当前密码错误。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('unexpected failure')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '修改密码' })).toBeNull())

    // With unsent content, logout opens confirmation dialog
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <AccountContext.Provider value={{ user: ADMIN_ACCOUNT, refresh: async () => {}, signOut }}>
          <AccountMenu hasUnsentContent />
        </AccountContext.Provider>
      </QueryClientProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'admin' }))
    await user.click(screen.getByRole('menuitem', { name: '退出登录' }))
    const logoutDlg = await screen.findByRole('dialog', { name: '退出登录' })
    expect(logoutDlg).toHaveTextContent('尚有未发送的内容')
    await user.click(within(logoutDlg).getByRole('button', { name: '退出登录' }))
    expect(signOut).toHaveBeenCalledTimes(2)
  })

  it('UsersPage lists users, retries errors, validates/creates user, handles USERNAME_EXISTS/LAST_ADMIN, and confirms disable', async () => {
    const user = userEvent.setup()
    const refresh = vi.fn().mockResolvedValue(undefined)
    let listShouldFail = true
    mockedRequest.mockImplementation((async (endpoint: string) => {
      if (endpoint === 'GET /api/users') {
        if (listShouldFail) throw new Error('users load failed')
        return USER_LIST
      }
      if (endpoint === 'POST /api/users') {
        throw new ApiError('exists', 409, 'USERNAME_EXISTS', undefined, 'USERNAME_EXISTS')
      }
      if (endpoint === 'PATCH /api/users/{id}') {
        throw new ApiError('last admin', 409, 'LAST_ADMIN', undefined, 'LAST_ADMIN')
      }
      return {}
    }) as unknown as typeof request)

    renderWithQuery(
      <AccountContext.Provider value={{ user: ADMIN_ACCOUNT, refresh, signOut: async () => {} }}>
        <UsersPage />
      </AccountContext.Provider>,
    )

    expect(await screen.findByText('users load failed')).toBeInTheDocument()
    listShouldFail = false
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('admin')).toBeInTheDocument()
    expect(screen.getByText('已停用')).toBeInTheDocument()

    // Create user: invalid username & short password validation, then USERNAME_EXISTS
    await user.click(screen.getByRole('button', { name: '新建用户' }))
    await user.type(screen.getByLabelText('用户名'), 'Invalid User!')
    await user.type(screen.getByLabelText('密码'), '123')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByText(/输入 1–32 个小写字母/)).toBeInTheDocument()
    expect(screen.getByText('密码长度须为 8–128 个字符。')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('用户名'))
    await user.type(screen.getByLabelText('用户名'), 'alice')
    await user.clear(screen.getByLabelText('密码'))
    await user.type(screen.getByLabelText('密码'), 'password123')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('该用户名已存在。')).toBeInTheDocument()

    // Successful create refreshes list
    mockedRequest.mockImplementation((async (endpoint: string) => {
      if (endpoint === 'POST /api/users') {
        return { id: 'u-alice', username: 'alice', role: 'USER', enabled: true, createdAt: 0 }
      }
      if (endpoint === 'GET /api/users') return USER_LIST
      if (endpoint === 'PATCH /api/users/{id}') {
        throw new ApiError('last admin', 409, 'LAST_ADMIN', undefined, 'LAST_ADMIN')
      }
      return {}
    }) as unknown as typeof request)
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建用户' })).toBeNull())

    // Edit admin user -> disable triggers confirmation dialog -> LAST_ADMIN error
    await user.click(screen.getByRole('button', { name: '编辑 admin' }))
    await chooseSelectOption(user, '账号状态', '停用')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByRole('alertdialog', { name: '停用账号' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认停用' }))
    expect(await screen.findByText('至少保留一名启用的管理员。')).toBeInTheDocument()

    // Successful self-update calls refresh()
    mockedRequest.mockImplementation((async (endpoint: string) => {
      if (endpoint === 'PATCH /api/users/{id}') {
        return { id: 'u-admin', username: 'admin', role: 'ADMIN', enabled: true, createdAt: 0 }
      }
      if (endpoint === 'GET /api/users') return USER_LIST
      return {}
    }) as unknown as typeof request)
    await chooseSelectOption(user, '账号状态', '启用')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    fireEvent.keyDown(document, { key: 'Escape' })
  })
})
