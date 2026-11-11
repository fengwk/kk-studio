import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import { SkillPackagesPage } from '@/features/catalog/skills/SkillPackagesPage'
import { setLocale } from '@/shared/i18n'
import type {
  SkillPackageDetailDTO,
  SkillPackageFileDTO,
  SkillPackageSummaryDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listSkillPackages: vi.fn(),
    getSkillPackage: vi.fn(),
    listSkillPackageFiles: vi.fn(),
    createSkillPackage: vi.fn(),
    editSkillPackage: vi.fn(),
    checkSkillPackage: vi.fn(),
    publishSkillPackage: vi.fn(),
    deleteSkillPackage: vi.fn(),
    getSkillPackageArchiveUrl: vi.fn(),
    listAgents: vi.fn(),
  },
}))

vi.mock('@/app/ai-console/AiNavigation', () => ({
  AiNavigation: () => <div data-testid="navigation-slot" />,
}))

const PACKAGE_VERSION = '1111111111111111111111111111111111111111'
const HEAD_COMMIT = '2222222222222222222222222222222222222222'

function summary(overrides: Partial<SkillPackageSummaryDTO> = {}): SkillPackageSummaryDTO {
  return {
    packageName: 'core-tools',
    description: 'Core developer skills',
    packageVersion: PACKAGE_VERSION,
    publishedAt: 1784505600000,
    skills: [
      { name: 'dev', description: 'developer workflow' },
      { name: 'bash', description: 'bash runner' },
    ],
    source: 'GIT',
    updateAvailable: false,
    version: '1',
    createdAt: 1784505600000,
    updatedAt: 1784509200000,
    ...overrides,
  }
}

function detail(overrides: Partial<SkillPackageDetailDTO> = {}): SkillPackageDetailDTO {
  return {
    ...summary(),
    git: {
      repositoryUrl: 'https://github.com/example/skills.git',
      branch: 'main',
      token: { configured: false },
    },
    headCommit: null,
    headCheckedAt: 1784509200000,
    headCheckError: null,
    artifactSha256: 'a'.repeat(64),
    artifactSizeBytes: '2048',
    version: '2',
    ...overrides,
  }
}

function uploadDetail(overrides: Partial<SkillPackageDetailDTO> = {}): SkillPackageDetailDTO {
  return detail({ source: 'UPLOAD', git: null, headCheckedAt: null, ...overrides })
}

function archiveFiles(): SkillPackageFileDTO[] {
  return [
    { path: 'dev/SKILL.md', size: '2048' },
    { path: 'dev/scripts/run.sh', size: '64' },
    { path: 'bash/SKILL.md', size: '512' },
  ]
}

describe('SkillPackagesPage', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    act(() => setLocale('zh-CN'))
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
    })

    vi.mocked(agentService.listSkillPackages).mockResolvedValue([summary()])
    vi.mocked(agentService.getSkillPackage).mockResolvedValue(detail())
    vi.mocked(agentService.listSkillPackageFiles).mockResolvedValue(archiveFiles())
    vi.mocked(agentService.checkSkillPackage).mockResolvedValue(detail())
    vi.mocked(agentService.publishSkillPackage).mockResolvedValue(detail({ version: '3' }))
    vi.mocked(agentService.editSkillPackage).mockResolvedValue(detail({ version: '4' }))
    vi.mocked(agentService.deleteSkillPackage).mockResolvedValue(undefined)
    vi.mocked(agentService.getSkillPackageArchiveUrl).mockResolvedValue({
      filename: 'core-tools-1111111.zip',
      sha256: 'a'.repeat(64),
      sizeBytes: '2048',
      download: {
        method: 'GET',
        url: 'http://s3.example/archive',
        headers: {},
        expiresAt: 1784509200000,
        mediaType: 'application/zip',
        sizeBytes: '2048',
      },
    })
    vi.mocked(agentService.listAgents).mockResolvedValue([])
  })

  afterEach(() => {
    queryClient.clear()
    act(() => setLocale('zh-CN'))
  })

  function renderPage() {
    return render(
      <QueryClientProvider client={queryClient}>
        <SkillPackagesPage />
      </QueryClientProvider>,
    )
  }

  function card(name: string): HTMLElement {
    const element = screen
      .getAllByRole('article')
      .find((article) => article.querySelector('h3')?.textContent === name)
    if (!element) {
      throw new Error(`card not found: ${name}`)
    }
    return element as HTMLElement
  }

  it('renders version, source, skill count and filters by search', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      summary({ packageName: 'core-tools', description: 'developer skills' }),
      summary({ packageName: 'browser-tools', description: 'web navigation', source: 'UPLOAD' }),
    ])
    vi.mocked(agentService.getSkillPackage).mockImplementation(async (name: string) =>
      name === 'browser-tools'
        ? uploadDetail({ packageName: 'browser-tools', description: 'web navigation' })
        : detail())

    renderPage()

    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())
    const coreCard = card('core-tools')
    expect(within(coreCard).getByText('1111111111')).toBeInTheDocument()
    expect(within(coreCard).getByText('Git 仓库')).toBeInTheDocument()
    expect(within(coreCard).getByText('2')).toBeInTheDocument()

    await waitFor(() => expect(within(card('browser-tools')).getByText('上传压缩包')).toBeInTheDocument())

    await user.type(screen.getByPlaceholderText(/搜索/), 'browser')
    expect(screen.queryByText('core-tools')).not.toBeInTheDocument()
    expect(screen.getByText('browser-tools')).toBeInTheDocument()
  })

  it('offers exactly one primary action per card', async () => {
    vi.mocked(agentService.getSkillPackage).mockImplementation(async (name: string) =>
      name === 'with-update'
        ? detail({ packageName: 'with-update', updateAvailable: true, headCommit: HEAD_COMMIT })
        : detail({ packageName: 'no-update' }))
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      summary({ packageName: 'with-update', updateAvailable: true }),
      summary({ packageName: 'no-update' }),
    ])

    renderPage()

    await waitFor(() => expect(within(card('with-update')).getByText('有更新可用')).toBeInTheDocument())
    const updateCard = card('with-update')
    const updateButtons = updateCard.querySelectorAll('button.btn-primary')
    expect(updateButtons).toHaveLength(1)
    expect(updateButtons[0]).toHaveTextContent('更新到 2222222222')
    expect(within(updateCard).queryByRole('button', { name: /检查更新/ })).not.toBeInTheDocument()

    const checkCard = card('no-update')
    const checkButtons = checkCard.querySelectorAll('button.btn-primary')
    expect(checkButtons).toHaveLength(1)
    expect(checkButtons[0]).toHaveTextContent('检查更新')
    expect(within(checkCard).queryByRole('button', { name: /更新到/ })).not.toBeInTheDocument()
  })

  it('uses the detail version and observed head when checking and publishing', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      summary({ updateAvailable: false }),
    ])
    renderPage()

    await waitFor(() => expect(within(card('core-tools')).getByText('已是最新')).toBeInTheDocument())

    await user.click(within(card('core-tools')).getByRole('button', { name: /检查更新 core-tools/ }))
    await waitFor(() => {
      expect(agentService.checkSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '2',
      })
    })

    vi.mocked(agentService.getSkillPackage).mockResolvedValue(
      detail({ updateAvailable: true, headCommit: HEAD_COMMIT }),
    )
    await act(async () => {
      await queryClient.invalidateQueries()
    })

    const updateButton = await within(card('core-tools')).findByRole('button', {
      name: /更新到 2222222222 core-tools/,
    })
    await user.click(updateButton)

    await waitFor(() => {
      expect(agentService.publishSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '2',
        targetCommit: HEAD_COMMIT,
      })
    })
  })

  it('shows the check failure reason and keeps upload packages without a check action', async () => {
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([
      summary({ packageName: 'broken-git' }),
      summary({ packageName: 'uploaded', source: 'UPLOAD' }),
    ])
    vi.mocked(agentService.getSkillPackage).mockImplementation(async (name: string) =>
      name === 'broken-git'
        ? detail({ packageName: 'broken-git', headCheckError: 'fatal: repository not reachable' })
        : uploadDetail({ packageName: 'uploaded' }))

    renderPage()

    await waitFor(() => expect(within(card('broken-git')).getByText('检查失败')).toBeInTheDocument())
    expect(within(card('broken-git')).getByText('fatal: repository not reachable')).toBeInTheDocument()

    const uploadedCard = card('uploaded')
    expect(within(uploadedCard).queryByText('检查状态')).not.toBeInTheDocument()
    expect(within(uploadedCard).queryByRole('button', { name: /检查更新/ })).not.toBeInTheDocument()
    expect(uploadedCard.querySelectorAll('button.btn-primary')[0]).toHaveTextContent('详情')
  })

  it('edits Git packages from the detail dialog with a read-only repository', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())
    await user.click(within(card('core-tools')).getByRole('button', { name: /详情 core-tools/ }))
    const detailDialog = await screen.findByRole('dialog', { name: /详情：core-tools/ })
    await user.click(await within(detailDialog).findByRole('button', { name: '编辑技能包' }))

    const modal = await screen.findByRole('dialog', { name: /编辑技能包/ })
    expect(within(modal).getByDisplayValue('https://github.com/example/skills.git')).toBeDisabled()
    expect(within(modal).getByLabelText(/访问令牌/)).toBeInTheDocument()

    const branchInput = within(modal).getByDisplayValue('main')
    await user.clear(branchInput)
    await user.type(branchInput, 'develop')
    const description = within(modal).getByDisplayValue('Core developer skills')
    await user.clear(description)
    await user.type(description, 'Updated description')
    await user.click(within(modal).getByRole('button', { name: /保存修改/ }))

    await waitFor(() => {
      expect(agentService.editSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '2',
        description: 'Updated description',
        branch: 'develop',
        token: { operation: 'KEEP' },
      })
    })
  })

  it('edits uploaded packages by description only', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([summary({ source: 'UPLOAD' })])
    vi.mocked(agentService.getSkillPackage).mockResolvedValue(uploadDetail())
    renderPage()

    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())
    await user.click(within(card('core-tools')).getByRole('button', { name: /详情 core-tools/ }))
    const detailDialog = await screen.findByRole('dialog', { name: /详情：core-tools/ })
    await user.click(await within(detailDialog).findByRole('button', { name: '编辑技能包' }))

    const modal = await screen.findByRole('dialog', { name: /编辑技能包/ })
    expect(within(modal).queryByLabelText(/访问令牌/)).not.toBeInTheDocument()
    expect(within(modal).queryByText('分支')).not.toBeInTheDocument()

    const description = within(modal).getByDisplayValue('Core developer skills')
    await user.clear(description)
    await user.type(description, 'Only description')
    await user.click(within(modal).getByRole('button', { name: /保存修改/ }))

    await waitFor(() => {
      expect(agentService.editSkillPackage).toHaveBeenCalledWith('core-tools', {
        expectedVersion: '2',
        description: 'Only description',
        token: { operation: 'KEEP' },
      })
    })
  })

  it('deletes from the detail dialog and surfaces the backend reason when in use', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.deleteSkillPackage).mockRejectedValue(
      new ApiError('Request failed with status 409', 409, 'in_use', undefined, undefined,
        'Skill package is still referenced by Agent demo'),
    )
    renderPage()

    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())
    await user.click(within(card('core-tools')).getByRole('button', { name: /详情 core-tools/ }))
    const detailDialog = await screen.findByRole('dialog', { name: /详情：core-tools/ })
    await user.click(await within(detailDialog).findByRole('button', { name: '删除技能包 core-tools' }))

    const modal = await screen.findByRole('alertdialog')
    expect(within(modal).getByText('确认删除技能包“core-tools”？')).toBeInTheDocument()
    await user.click(within(modal).getByRole('button', { name: '删除技能包' }))

    // 删除使用详情里的当前版本，而不是列表摘要里的旧版本。
    await waitFor(() => {
      expect(agentService.deleteSkillPackage).toHaveBeenCalledWith('core-tools', '2')
    })
    expect(
      await within(modal).findByText('Skill package is still referenced by Agent demo'),
    ).toBeInTheDocument()
    expect(card('core-tools')).toBeInTheDocument()
  })

  it('shows the real archive tree and download from the detail dialog', async () => {
    const user = userEvent.setup()
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderPage()

    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())
    await user.click(within(card('core-tools')).getByRole('button', { name: /详情 core-tools/ }))

    const modal = await screen.findByRole('dialog', { name: /详情：core-tools/ })
    // 文件树来自服务端归档索引，目录与文件都按路径展示。
    expect(await within(modal).findByText('run.sh')).toBeInTheDocument()
    expect(within(modal).getByText('scripts')).toBeInTheDocument()
    expect(within(modal).getAllByText('SKILL.md')).toHaveLength(2)
    expect(within(modal).getByText('共 3 个文件')).toBeInTheDocument()

    expect(within(modal).queryByText('环境同步')).not.toBeInTheDocument()

    await user.click(within(modal).getByRole('button', { name: '下载归档' }))
    await waitFor(() => {
      expect(agentService.getSkillPackageArchiveUrl).toHaveBeenCalledWith('core-tools')
    })
    expect(clickSpy).toHaveBeenCalled()
    clickSpy.mockRestore()
  })

  it('switches list labels between zh-CN and en-US', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('core-tools')).toBeInTheDocument())

    expect(within(card('core-tools')).getByText('检查更新')).toBeInTheDocument()
    expect(within(card('core-tools')).getByText('来源')).toBeInTheDocument()

    act(() => setLocale('en-US'))

    expect(within(card('core-tools')).getByText('Check')).toBeInTheDocument()
    expect(within(card('core-tools')).getByText('Source')).toBeInTheDocument()
    expect(within(card('core-tools')).getByText('Git repository')).toBeInTheDocument()
  })
})
