import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillPackagesPage } from '@/features/ai/skills/SkillPackagesPage'
import { agentService } from '@/shared/api/agent-service'
import type {
  SkillPackageDetailDTO,
  SkillPackageDTO,
} from '@/shared/api/contracts/ai-catalog'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listSkillPackages: vi.fn(),
    getSkillPackage: vi.fn(),
    createSkillPackage: vi.fn(),
    updateSkillPackage: vi.fn(),
    deleteSkillPackage: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => <div data-testid="navigation-slot" />,
}))

function samplePackage(overrides: Partial<SkillPackageDTO> = {}): SkillPackageDTO {
  return {
    name: 'core-tools',
    packageVersion: '1.0.0',
    description: 'Core developer skills',
    skills: [
      {
        name: 'dev',
        description: 'dev skill',
        packageName: 'core-tools',
        packageVersion: '1.0.0',
      },
      {
        name: 'bash',
        description: 'bash runner',
        packageName: 'core-tools',
        packageVersion: '1.0.0',
      },
    ],
    ...overrides,
  }
}

function sampleDetail(overrides: Partial<SkillPackageDetailDTO> = {}): SkillPackageDetailDTO {
  return {
    name: 'core-tools',
    packageVersion: '1.0.0',
    description: 'Core developer skills',
    skills: [
      { name: 'dev', description: 'dev skill', content: 'Run dev workflows' },
      { name: 'bash', description: 'bash runner', content: 'Execute commands' },
    ],
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
    vi.mocked(agentService.getSkillPackage).mockResolvedValue(sampleDetail())
    vi.mocked(agentService.createSkillPackage).mockResolvedValue(sampleDetail())
    vi.mocked(agentService.updateSkillPackage).mockResolvedValue(sampleDetail({ packageVersion: '1.1.0' }))
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
      samplePackage({ name: 'core-tools', description: 'developer skills' }),
      samplePackage({ name: 'browser-tools', description: 'web navigation' }),
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

  it('creates a new skill package with dynamic skill definitions', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // Click create card
    const createBtn = screen.getByRole('button', { name: /创建 Package|Create Package/i })
    await user.click(createBtn)

    const modal = screen.getByRole('dialog', { name: /创建 Package|Create Package/i })
    expect(modal).toBeInTheDocument()

    // Fill form
    const nameInput = within(modal).getByPlaceholderText('core-tools')
    await user.type(nameInput, 'custom-pkg')

    const skillNameInput = within(modal).getByPlaceholderText('browse-web')
    await user.type(skillNameInput, 'search-skill')

    const skillDescriptionInput = within(modal).getByPlaceholderText('Skill description')
    await user.type(skillDescriptionInput, 'Search the web')

    const skillContentInput = within(modal).getByPlaceholderText(/Instructions or prompt content/i)
    await user.type(skillContentInput, '  Search instructions  ')

    // Submit
    const submitBtn = within(modal).getByRole('button', { name: /保存|Save/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(agentService.createSkillPackage).toHaveBeenCalledWith({
        name: 'custom-pkg',
        packageVersion: '1.0.0',
        description: undefined,
        skills: [
          {
            name: 'search-skill',
            description: 'Search the web',
            content: '  Search instructions  ',
          },
        ],
      })
    })
  })

  it('rejects a blank skill description before creating the package', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('core-tools')
    await user.click(screen.getByRole('button', { name: /创建 Package|Create Package/i }))

    const modal = screen.getByRole('dialog', { name: /创建 Package|Create Package/i })
    await user.type(within(modal).getByPlaceholderText('core-tools'), 'custom-pkg')
    await user.type(within(modal).getByPlaceholderText('browse-web'), 'search-skill')
    await user.type(within(modal).getByPlaceholderText('Skill description'), '   ')
    await user.type(
      within(modal).getByPlaceholderText(/Instructions or prompt content/i),
      'Search instructions',
    )
    await user.click(within(modal).getByRole('button', { name: /保存|Save/i }))

    expect(agentService.createSkillPackage).not.toHaveBeenCalled()
    expect(within(modal).getByRole('alert')).toHaveTextContent(/Skill 描述|Skill description/i)
  })

  it('edits a package with full replacement and immutable package name', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // Click edit
    const editBtn = screen.getByRole('button', { name: /编辑.*core-tools|Edit.*core-tools/i })
    await user.click(editBtn)

    await waitFor(() => {
      expect(agentService.getSkillPackage).toHaveBeenCalledWith('core-tools')
    })

    const modal = await screen.findByRole('dialog')

    // Package name is immutable (readOnly and disabled)
    const nameInput = within(modal).getByDisplayValue('core-tools')
    expect(nameInput).toBeDisabled()

    // Enter new package version
    const newVersionInput = within(modal).getByPlaceholderText('1.1.0')
    await user.type(newVersionInput, '1.1.0')

    // Submit edit
    const submitBtn = within(modal).getByRole('button', { name: /保存|Save/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(agentService.updateSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedPackageVersion: '1.0.0',
        newPackageVersion: '1.1.0',
        description: 'Core developer skills',
        skills: [
          { name: 'dev', description: 'dev skill', content: 'Run dev workflows' },
          { name: 'bash', description: 'bash runner', content: 'Execute commands' },
        ],
      })
    })
  })

  it('deletes a package with expectedPackageVersion confirmation', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('core-tools')).toBeInTheDocument()
    })

    // Click delete
    const deleteBtn = screen.getByRole('button', { name: /删除.*core-tools|Delete.*core-tools/i })
    await user.click(deleteBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    expect(confirmModal).toBeInTheDocument()

    const confirmBtn = within(confirmModal).getByRole('button', { name: /删除|Delete/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(agentService.deleteSkillPackage).toHaveBeenCalledWith('core-tools', '1.0.0')
    })
  })
})
