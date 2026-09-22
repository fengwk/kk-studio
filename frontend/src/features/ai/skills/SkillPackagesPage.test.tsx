import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillPackagesPage } from '@/features/ai/skills/SkillPackagesPage'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDTO } from '@/shared/api/contracts/ai-catalog'

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

    const createBtn = screen.getByRole('button', { name: /创建 Package|Create Package/i })
    await user.click(createBtn)

    const modal = screen.getByRole('dialog', { name: /创建 Package|Create Package/i })
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

    const editBtn = screen.getByRole('button', { name: /编辑.*core-tools|Edit.*core-tools/i })
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

    const deleteBtn = screen.getByRole('button', { name: /删除.*core-tools|Delete.*core-tools/i })
    await user.click(deleteBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    expect(confirmModal).toBeInTheDocument()

    const confirmBtn = within(confirmModal).getByRole('button', { name: /删除|Delete/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(agentService.deleteSkillPackage).toHaveBeenCalledWith('core-tools', '1')
    })
  })
})
