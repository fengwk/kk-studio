import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillPackagesPage } from '@/features/ai/skills/SkillPackagesPage'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDTO } from '@/shared/api/contracts/ai-catalog'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listSkillPackages: vi.fn(),
    createSkillPackage: vi.fn(),
    editSkillPackage: vi.fn(),
    checkSkillPackage: vi.fn(),
    publishSkillPackage: vi.fn(),
    deleteSkillPackage: vi.fn(),
  },
}))

vi.mock('@/features/ai/extensions/AiNavigation', () => ({
  AiNavigation: () => <div data-testid="navigation-slot" />,
}))

function samplePackage(overrides: Partial<SkillPackageDTO> = {}): SkillPackageDTO {
  return {
    packageName: 'core-tools',
    description: 'Core developer skills',
    repositoryUrl: 'https://github.com/example/skills.git',
    branch: 'main',
    hasToken: false,
    currentCommit: '1111111111111111111111111111111111111111',
    observedHeadCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    checkStatus: 'UNCHECKED',
    skills: [
      { name: 'dev', description: 'dev skill' },
      { name: 'bash', description: 'bash runner' },
    ],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
    ...overrides,
  }
}

describe('SkillPackagesPage', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    act(() => {
      setLocale('zh-CN')
    })
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
        mutations: { retry: false },
      },
    })

    vi.mocked(agentService.listSkillPackages).mockResolvedValue([samplePackage()])
    vi.mocked(agentService.createSkillPackage).mockResolvedValue(samplePackage())
    vi.mocked(agentService.editSkillPackage).mockResolvedValue(samplePackage({ version: '2' }))
    vi.mocked(agentService.checkSkillPackage).mockResolvedValue(samplePackage())
    vi.mocked(agentService.publishSkillPackage).mockResolvedValue(samplePackage({ version: '3' }))
    vi.mocked(agentService.deleteSkillPackage).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    act(() => {
      setLocale('zh-CN')
    })
  })

  function renderPage() {
    return render(
      <QueryClientProvider client={queryClient}>
        <SkillPackagesPage />
      </QueryClientProvider>,
    )
  }

  it('renders skill packages list and filters via search', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({ packageName: 'core-tools', description: 'developer skills' }),
      samplePackage({ packageName: 'browser-tools', description: 'web navigation' }),
    ])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
      expect(screen.getByText('browser-tools')).toBeInTheDocument()
    })

    const searchInput = screen.getByPlaceholderText(/搜索|search/i)
    await user.type(searchInput, 'browser')

    expect(screen.queryByText('core-tools')).not.toBeInTheDocument()
    expect(screen.getByText('browser-tools')).toBeInTheDocument()
  })

  it('creates a new skill package without editing skills body or commit', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    const createBtn = screen.getByRole('button', { name: /创建技能包|Create Package/i })
    await user.click(createBtn)

    const modal = screen.getByRole('dialog', { name: /创建技能包|Create Package/i })
    expect(modal).toBeInTheDocument()

    const nameInput = within(modal).getByPlaceholderText('my-skills')
    await user.type(nameInput, 'custom-pkg')

    const repoInput = within(modal).getByPlaceholderText('https://github.com/org/repo.git')
    await user.type(repoInput, 'https://github.com/myorg/skills.git')

    const submitBtn = within(modal).getByRole('button', { name: /确认创建|Confirm Create/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(agentService.createSkillPackage).toHaveBeenCalledWith({
        packageName: 'custom-pkg',
        description: null,
        repositoryUrl: 'https://github.com/myorg/skills.git',
        branch: 'main',
      })
    })
  })

  it('checks branch HEAD and updates exact observed commit', async () => {
    const user = userEvent.setup()
    const pkgWithUpdate = samplePackage({
      packageName: 'core-tools',
      checkStatus: 'UPDATE_AVAILABLE',
      currentCommit: '1111111111111111111111111111111111111111',
      observedHeadCommit: '2222222222222222222222222222222222222222',
      version: '1',
    })
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([pkgWithUpdate])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // Check button
    const checkBtn = screen.getByRole('button', { name: /检查更新.*core-tools|Check.*core-tools/i })
    await user.click(checkBtn)

    expect(agentService.checkSkillPackage).toHaveBeenCalledWith('core-tools', {
      expectedVersion: '1',
    })

    // Update button is present because observedHeadCommit !== currentCommit
    const updateBtn = screen.getByRole('button', { name: /发布更新.*core-tools|Update.*core-tools/i })
    await user.click(updateBtn)

    expect(agentService.publishSkillPackage).toHaveBeenCalledWith('core-tools', {
      expectedVersion: '1',
      targetCommit: '2222222222222222222222222222222222222222',
    })
  })

  it('edits only description and branch with expectedVersion', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    const editBtn = screen.getByRole('button', { name: /编辑技能包.*core-tools|Edit Package.*core-tools/i })
    await user.click(editBtn)

    const modal = await screen.findByRole('dialog')

    // Package name and repo are read-only
    const nameInput = within(modal).getByDisplayValue('core-tools')
    expect(nameInput).toBeDisabled()

    const branchInput = within(modal).getByDisplayValue('main')
    await user.clear(branchInput)
    await user.type(branchInput, 'develop')

    const submitBtn = within(modal).getByRole('button', { name: /保存修改|Save Changes/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(agentService.editSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '1',
        description: 'Core developer skills',
        branch: 'develop',
      })
    })
  })

  it('deletes a package with CAS expectedVersion confirmation', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    const deleteBtn = screen.getByRole('button', { name: /删除技能包.*core-tools|Delete Package.*core-tools/i })
    await user.click(deleteBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    expect(confirmModal).toBeInTheDocument()

    // 确认弹窗描述中正确插值实际包名
    expect(within(confirmModal).getByText(/确认删除技能包“core-tools”？/)).toBeInTheDocument()

    const confirmBtn = within(confirmModal).getByRole('button', { name: /删除技能包|Delete Package/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(agentService.deleteSkillPackage).toHaveBeenCalledWith('core-tools', '1')
    })
  })

  it('switches between zh-CN and en-US in real-time across cards, status pills, labels and actions', async () => {
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({
        packageName: 'demo-pkg',
        checkStatus: 'UNCHECKED',
        headCheckError: 'diagnostic error from git',
        skills: [{ name: 'read', description: 'file reader' }],
      }),
    ])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('demo-pkg')).toBeInTheDocument()
    })

    // 中文断言
    expect(screen.getByRole('button', { name: '创建技能包' })).toBeInTheDocument()
    expect(screen.getByTestId('check-status-pill')).toHaveTextContent('未检查')
    expect(screen.getByText('访问令牌')).toBeInTheDocument()
    expect(screen.getByText('未配置')).toBeInTheDocument()
    expect(screen.getByText('包含技能数量')).toBeInTheDocument()
    expect(screen.getByText('技能')).toBeInTheDocument()
    expect(screen.getByText('错误')).toBeInTheDocument()
    // 上游诊断错误不翻译
    expect(screen.getByText('diagnostic error from git')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /检查更新 demo-pkg/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /编辑技能包 demo-pkg/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /删除技能包 demo-pkg/ })).toBeInTheDocument()

    // 实时切换到 en-US
    act(() => {
      setLocale('en-US')
    })

    expect(screen.getByRole('button', { name: 'Create Package' })).toBeInTheDocument()
    expect(screen.getByTestId('check-status-pill')).toHaveTextContent('Unchecked')
    expect(screen.getByText('Access Token')).toBeInTheDocument()
    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText('Skills Count')).toBeInTheDocument()
    expect(screen.getByText('Skills')).toBeInTheDocument()
    expect(screen.getByText('Error')).toBeInTheDocument()
    expect(screen.getByText('diagnostic error from git')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Check demo-pkg/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Edit Package demo-pkg/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Delete Package demo-pkg/ })).toBeInTheDocument()
  })

  it('renders all 4 status pills with localized text while retaining wire enum classes', async () => {
    const packages: SkillPackageDTO[] = [
      samplePackage({ packageName: 'p-unchecked', checkStatus: 'UNCHECKED' }),
      samplePackage({ packageName: 'p-uptodate', checkStatus: 'UP_TO_DATE' }),
      samplePackage({ packageName: 'p-updateavail', checkStatus: 'UPDATE_AVAILABLE' }),
      samplePackage({ packageName: 'p-failed', checkStatus: 'CHECK_FAILED' }),
    ]
    vi.mocked(agentService.listSkillPackages).mockResolvedValue(packages)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('p-unchecked')).toBeInTheDocument()
    })

    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(4)

    // 1. zh-CN 状态断言与 CSS class 保持
    const pill1 = within(cards[0]!).getByTestId('check-status-pill')
    expect(pill1).toHaveTextContent('未检查')
    expect(pill1).toHaveClass('status-pill is-offline')

    const pill2 = within(cards[1]!).getByTestId('check-status-pill')
    expect(pill2).toHaveTextContent('已是最新')
    expect(pill2).toHaveClass('status-pill is-ready')

    const pill3 = within(cards[2]!).getByTestId('check-status-pill')
    expect(pill3).toHaveTextContent('有更新可用')
    expect(pill3).toHaveClass('status-pill is-pending')

    const pill4 = within(cards[3]!).getByTestId('check-status-pill')
    expect(pill4).toHaveTextContent('检查失败')
    expect(pill4).toHaveClass('status-pill is-failed')

    // 2. 实时切 en-US
    act(() => {
      setLocale('en-US')
    })

    expect(within(cards[0]!).getByTestId('check-status-pill')).toHaveTextContent('Unchecked')
    expect(within(cards[1]!).getByTestId('check-status-pill')).toHaveTextContent('Up to date')
    expect(within(cards[2]!).getByTestId('check-status-pill')).toHaveTextContent('Update available')
    expect(within(cards[3]!).getByTestId('check-status-pill')).toHaveTextContent('Check failed')
  })

  it('validates package name and presents localized messages for empty or forbidden characters', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '创建技能包' }))
    const modal = screen.getByRole('dialog', { name: '创建技能包' })
    const submitBtn = within(modal).getByRole('button', { name: '确认创建' })

    // 1. 空包名校验
    await user.click(submitBtn)
    expect(within(modal).getByRole('alert')).toHaveTextContent('技能包名称不能为空')
    expect(agentService.createSkillPackage).not.toHaveBeenCalled()

    // 2. 非法符号校验 (: / @ \)
    const nameInput = within(modal).getByPlaceholderText('my-skills')
    await user.type(nameInput, 'user@pkg/invalid:name\\bad')
    await user.click(submitBtn)
    expect(within(modal).getByRole('alert')).toHaveTextContent('技能包名称不能包含 : / @ \\')

    // 3. 实时切换为 en-US 时展示英文校验提示
    act(() => {
      setLocale('en-US')
    })
    await user.click(within(modal).getByRole('button', { name: 'Create' }))
    expect(within(modal).getByRole('alert')).toHaveTextContent('Package name cannot contain : / @ \\')
  })

  it('supports cancelling create and edit modals without submitting mutations', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // 1. 取消创建 Modal
    await user.click(screen.getByRole('button', { name: '创建技能包' }))
    const createModal = screen.getByRole('dialog', { name: '创建技能包' })
    const createCancelBtn = within(createModal).getByRole('button', { name: '取消' })
    await user.click(createCancelBtn)
    expect(screen.queryByRole('dialog', { name: '创建技能包' })).not.toBeInTheDocument()
    expect(agentService.createSkillPackage).not.toHaveBeenCalled()

    // 2. 取消编辑 Modal
    await user.click(screen.getByRole('button', { name: /编辑技能包.*core-tools/ }))
    const editModal = await screen.findByRole('dialog')
    const branchInput = within(editModal).getByDisplayValue('main')
    await user.clear(branchInput)
    await user.type(branchInput, 'feature-test')
    const editCancelBtn = within(editModal).getByRole('button', { name: '取消' })
    await user.click(editCancelBtn)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(agentService.editSkillPackage).not.toHaveBeenCalled()
  })

  it('interpolates actual package name in delete confirmation modal across locales and handles cancel', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // 1. zh-CN 删除弹窗取消
    await user.click(screen.getByRole('button', { name: /删除技能包.*core-tools/ }))
    let confirmModal = await screen.findByRole('alertdialog')
    expect(within(confirmModal).getByText('确认删除技能包“core-tools”？')).toBeInTheDocument()
    await user.click(within(confirmModal).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(agentService.deleteSkillPackage).not.toHaveBeenCalled()

    // 2. en-US 删除弹窗插值验证
    act(() => {
      setLocale('en-US')
    })
    await user.click(screen.getByRole('button', { name: /Delete Package.*core-tools/ }))
    confirmModal = await screen.findByRole('alertdialog')
    expect(
      within(confirmModal).getByText('Are you sure you want to delete skill package "core-tools"?'),
    ).toBeInTheDocument()
    await user.click(within(confirmModal).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(agentService.deleteSkillPackage).not.toHaveBeenCalled()
  })

  it('creates a package including token when a non-blank token is typed', async () => {
    // 意图：验证创建技能包时，输入访问令牌后提交数据携带 token 字段，且输入框为密码类型与关闭自动填充
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /创建技能包|Create Package/i }))
    const modal = screen.getByRole('dialog', { name: /创建技能包|Create Package/i })

    const tokenInput = within(modal).getByPlaceholderText(/Personal Access Token/i)
    expect(tokenInput).toHaveAttribute('type', 'password')
    expect(tokenInput).toHaveAttribute('autocomplete', 'off')
    expect(tokenInput).toHaveValue('')

    await user.type(within(modal).getByPlaceholderText('my-skills'), 'private-pkg')
    await user.type(
      within(modal).getByPlaceholderText('https://github.com/org/repo.git'),
      'https://github.com/myorg/private-skills.git',
    )
    await user.type(tokenInput, 'ghp_secret_access_token_123')

    await user.click(within(modal).getByRole('button', { name: /确认创建|Confirm Create/i }))

    await waitFor(() => {
      expect(agentService.createSkillPackage).toHaveBeenCalledWith({
        packageName: 'private-pkg',
        description: null,
        repositoryUrl: 'https://github.com/myorg/private-skills.git',
        branch: 'main',
        token: 'ghp_secret_access_token_123',
      })
    })
  })

  it('omits token in create request when token is whitespace only', async () => {
    // 意图：验证创建时若 token 仅输入纯空白字符，按未填写处理，请求体完全省略 token
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /创建技能包|Create Package/i }))
    const modal = screen.getByRole('dialog', { name: /创建技能包|Create Package/i })

    await user.type(within(modal).getByPlaceholderText('my-skills'), 'blank-token-pkg')
    await user.type(
      within(modal).getByPlaceholderText('https://github.com/org/repo.git'),
      'https://github.com/myorg/blank-token.git',
    )
    const tokenInput = within(modal).getByPlaceholderText(/Personal Access Token/i)
    await user.type(tokenInput, '   ')

    await user.click(within(modal).getByRole('button', { name: /确认创建|Confirm Create/i }))

    await waitFor(() => {
      expect(agentService.createSkillPackage).toHaveBeenCalledWith({
        packageName: 'blank-token-pkg',
        description: null,
        repositoryUrl: 'https://github.com/myorg/blank-token.git',
        branch: 'main',
      })
    })
  })

  it('edits a package replacing token when new token is typed', async () => {
    // 意图：编辑弹窗输入新令牌时，提交数据携带新 token 进行替换；密码输入框默认空白且绝不回显已有密钥
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({ packageName: 'core-tools', hasToken: true }),
    ])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /编辑技能包.*core-tools/ }))
    const modal = await screen.findByRole('dialog')

    const tokenInput = within(modal).getByPlaceholderText(/留空保留已有令牌|Leave blank to keep existing token/i)
    expect(tokenInput).toHaveAttribute('type', 'password')
    expect(tokenInput).toHaveAttribute('autocomplete', 'off')
    expect(tokenInput).toHaveValue('')

    await user.type(tokenInput, 'ghp_replaced_token_456')
    await user.click(within(modal).getByRole('button', { name: /保存修改|Save Changes/i }))

    await waitFor(() => {
      expect(agentService.editSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '1',
        description: 'Core developer skills',
        branch: 'main',
        token: 'ghp_replaced_token_456',
      })
    })
  })

  it('edits a package sending token: null when clear token is selected', async () => {
    // 意图：三态编辑中勾选“清除令牌”时，向后端发送 token: null，并禁用密码输入框
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({ packageName: 'core-tools', hasToken: true }),
    ])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /编辑技能包.*core-tools/ }))
    const modal = await screen.findByRole('dialog')

    const tokenInput = within(modal).getByPlaceholderText(/留空保留已有令牌|Leave blank to keep existing token/i)
    const clearCheckbox = within(modal).getByRole('checkbox', { name: /清除令牌|Clear token/i })
    expect(clearCheckbox).not.toBeChecked()

    // 勾选清除令牌
    await user.click(clearCheckbox)
    expect(clearCheckbox).toBeChecked()
    expect(tokenInput).toBeDisabled()

    await user.click(within(modal).getByRole('button', { name: /保存修改|Save Changes/i }))

    await waitFor(() => {
      expect(agentService.editSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '1',
        description: 'Core developer skills',
        branch: 'main',
        token: null,
      })
    })
  })

  it('edits a package preserving token (omitting token) when left blank and clear is unchecked', async () => {
    // 意图：编辑时密码框留空且未勾选清除，请求体中完全省略 token 字段以保留既有令牌
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({ packageName: 'core-tools', hasToken: true }),
    ])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /编辑技能包.*core-tools/ }))
    const modal = await screen.findByRole('dialog')

    const tokenInput = within(modal).getByPlaceholderText(/留空保留已有令牌|Leave blank to keep existing token/i)
    await user.type(tokenInput, '   ') // 纯空白亦按省略保留处理

    await user.click(within(modal).getByRole('button', { name: /保存修改|Save Changes/i }))

    await waitFor(() => {
      const call = vi.mocked(agentService.editSkillPackage).mock.calls[0]
      expect(call?.[0]).toBe('core-tools')
      expect(call?.[1]).toEqual({
        expectedVersion: '1',
        description: 'Core developer skills',
        branch: 'main',
      })
      expect('token' in (call?.[1] ?? {})).toBe(false)
    })
  })

  it('renders card showing token configured vs unconfigured from hasToken without echoing secrets', async () => {
    // 意图：卡片元信息行只展示已配置/未配置状态文案，绝不渲染任何明文令牌，支持双语
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      samplePackage({ packageName: 'with-token', hasToken: true }),
      samplePackage({ packageName: 'without-token', hasToken: false }),
    ])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('with-token')).toBeInTheDocument()
      expect(screen.getByText('without-token')).toBeInTheDocument()
    })

    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(2)

    // zh-CN 校验
    const withTokenCard = cards[0]!
    const withoutTokenCard = cards[1]!

    expect(within(withTokenCard).getByText('访问令牌')).toBeInTheDocument()
    expect(within(withTokenCard).getByText('已配置')).toBeInTheDocument()

    expect(within(withoutTokenCard).getByText('访问令牌')).toBeInTheDocument()
    expect(within(withoutTokenCard).getByText('未配置')).toBeInTheDocument()

    // 绝不回显任何类似令牌的内容
    expect(screen.queryByText(/ghp_/i)).not.toBeInTheDocument()

    // 实时切 en-US 校验
    act(() => {
      setLocale('en-US')
    })

    expect(within(withTokenCard).getByText('Access Token')).toBeInTheDocument()
    expect(within(withTokenCard).getByText('Configured')).toBeInTheDocument()

    expect(within(withoutTokenCard).getByText('Access Token')).toBeInTheDocument()
    expect(within(withoutTokenCard).getByText('Not configured')).toBeInTheDocument()
  })
})
