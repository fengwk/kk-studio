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
    expect(pill3).toHaveClass('status-pill is-warning')

    const pill4 = within(cards[3]!).getByTestId('check-status-pill')
    expect(pill4).toHaveTextContent('检查失败')
    expect(pill4).toHaveClass('status-pill is-error')

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
})
