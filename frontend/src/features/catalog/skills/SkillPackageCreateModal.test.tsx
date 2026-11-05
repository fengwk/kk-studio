import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import { SkillPackageCreateModal } from '@/features/catalog/skills/SkillPackageCreateModal'
import { hashSkillArchive, uploadSkillArchive } from '@/features/catalog/skills/skill-package-upload'
import type {
  AgentDefinitionDTO,
  SkillPackageDetailDTO,
  SkillPackagePreviewDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    createSkillPackage: vi.fn(),
    getSkillPackage: vi.fn(),
    listAgents: vi.fn(),
    previewSkillPackageUpload: vi.fn(),
    publishSkillPackageUpload: vi.fn(),
    cancelSkillPackageUpload: vi.fn(),
  },
}))

vi.mock('@/features/catalog/skills/skill-package-upload', () => ({
  hashSkillArchive: vi.fn(),
  uploadSkillArchive: vi.fn(),
}))

function detail(overrides: Partial<SkillPackageDetailDTO> = {}): SkillPackageDetailDTO {
  return {
    packageName: 'core-tools',
    description: 'Core developer skills',
    packageVersion: '2026.08.1',
    publishedAt: 1784505600,
    skills: [
      { name: 'dev', description: 'developer workflow' },
      { name: 'dropped-skill', description: 'skill removed by the upload' },
    ],
    source: 'UPLOAD',
    git: null,
    headCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    updateAvailable: false,
    artifactSha256: 'a'.repeat(64),
    artifactSizeBytes: '2048',
    version: '3',
    createTime: 1784505600,
    updateTime: 1784509200,
    ...overrides,
  }
}

function preview(overrides: Partial<SkillPackagePreviewDTO> = {}): SkillPackagePreviewDTO {
  return {
    stagingUploadId: 'stage-1',
    packageName: 'core-tools',
    packageVersion: '2026.09.1',
    description: 'Core developer skills',
    skills: [
      { name: 'dev', description: 'developer workflow' },
      { name: 'browser', description: 'browser automation' },
    ],
    ignoredDirectories: ['docs'],
    archiveSha256: 'b'.repeat(64),
    archiveSizeBytes: '1024',
    contentSizeBytes: '4096',
    fileCount: 4,
    ...overrides,
  }
}

function agent(name: string, skills: { packageName: string; name: string }[]): AgentDefinitionDTO {
  return {
    name,
    type: 'USER',
    description: null,
    systemPrompt: null,
    execution: null,
    config: { tools: [], skills, subagents: [] },
    version: '1',
    createTime: 0,
    updateTime: 0,
  }
}

function archiveFile(name = 'skills.zip'): File {
  return new File([new Uint8Array(8)], name, { type: 'application/zip' })
}

describe('SkillPackageCreateModal', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
    })
    vi.mocked(agentService.createSkillPackage).mockResolvedValue(detail({ source: 'GIT', git: {
      repositoryUrl: 'https://github.com/example/skills.git',
      branch: 'main',
      hasToken: false,
    } }))
    vi.mocked(agentService.getSkillPackage).mockRejectedValue(new ApiError('not found', 404))
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: '0',
      results: [],
    })
    vi.mocked(hashSkillArchive).mockResolvedValue('c'.repeat(64))
    vi.mocked(uploadSkillArchive).mockResolvedValue('upload-1')
    vi.mocked(agentService.previewSkillPackageUpload).mockResolvedValue(preview())
    vi.mocked(agentService.publishSkillPackageUpload).mockResolvedValue(detail())
    vi.mocked(agentService.cancelSkillPackageUpload).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
  })

  function renderModal() {
    const onCreated = vi.fn()
    const onClose = vi.fn()
    render(
      <QueryClientProvider client={queryClient}>
        <SkillPackageCreateModal onClose={onClose} onCreated={onCreated} />
      </QueryClientProvider>,
    )
    return { onCreated, onClose }
  }

  it('creates a Git package from the repository source', async () => {
    const user = userEvent.setup()
    const { onCreated } = renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'custom-pkg')
    await user.type(within(modal).getByPlaceholderText('https://github.com/org/repo.git'), 'https://github.com/myorg/skills.git')
    await user.type(within(modal).getByLabelText(/访问令牌/), 'ghp_secret')
    await user.click(within(modal).getByRole('button', { name: /确认创建/ }))

    await waitFor(() => {
      expect(agentService.createSkillPackage).toHaveBeenCalledWith({
        packageName: 'custom-pkg',
        description: null,
        repositoryUrl: 'https://github.com/myorg/skills.git',
        branch: 'main',
        token: 'ghp_secret',
      })
    })
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({
      packageName: 'core-tools',
    })))
  })

  it('validates the package name before creating', async () => {
    const user = userEvent.setup()
    renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'Bad_Name')
    await user.click(within(modal).getByRole('button', { name: /确认创建/ }))

    expect(
      within(modal).getByText('技能包名称只能使用 1 到 64 个小写字母、数字或单个连字符'),
    ).toBeInTheDocument()
    expect(agentService.createSkillPackage).not.toHaveBeenCalled()
  })

  it('uploads an archive with real progress, previews it and publishes a new package', async () => {
    const user = userEvent.setup()
    const { onCreated } = renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.click(within(modal).getByRole('tab', { name: /上传压缩包/ }))
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'core-tools')
    await user.type(within(modal).getByPlaceholderText('1.0.0'), '2026.09.1')
    await user.upload(within(modal).getByLabelText('技能包压缩包'), archiveFile())

    let finishUpload: (() => void) | null = null
    vi.mocked(uploadSkillArchive).mockImplementation(async ({ onProgress }) => {
      onProgress({ loaded: 4, total: 8 })
      await new Promise<void>((resolve) => {
        finishUpload = resolve
      })
      onProgress({ loaded: 8, total: 8 })
      return 'upload-1'
    })

    await user.click(within(modal).getByRole('button', { name: /解析预览/ }))

    // 校验与上传阶段只呈现阶段状态，上传阶段额外呈现真实进度。
    await waitFor(() => expect(uploadSkillArchive).toHaveBeenCalled())
    const uploading = within(modal).getByRole('status')
    expect(uploading).toHaveTextContent('正在上传归档')
    expect(within(uploading).getByRole('progressbar', { name: '上传进度' })).toHaveAttribute('value', '4')

    finishUpload!()

    await waitFor(() => {
      expect(agentService.previewSkillPackageUpload).toHaveBeenCalledWith({
        uploadId: 'upload-1',
        packageName: 'core-tools',
        packageVersion: '2026.09.1',
        description: null,
      })
    })

    const previewDialog = await screen.findByRole('dialog', { name: /预览并发布/ })
    expect(within(previewDialog).getByText('browser automation')).toBeInTheDocument()
    expect(within(previewDialog).getByText('未打包目录：docs')).toBeInTheDocument()
    expect(within(previewDialog).getByText('这是新技能包，发布会创建它。')).toBeInTheDocument()
    expect(within(previewDialog).getByText('新增技能')).toBeInTheDocument()
    // 新包的技能全部是新增，没有任何技能被移除。
    expect(within(previewDialog).queryByText('没有新增技能')).not.toBeInTheDocument()
    expect(within(previewDialog).getByText('没有移除技能')).toBeInTheDocument()
    // 预览只有服务端清单，归档真实文件树在发布后的详情中展示。
    expect(within(previewDialog).getAllByText('SKILL.md')).toHaveLength(2)
    // 预览页只有一个 footer，发布操作只有一处。
    expect(within(previewDialog).getAllByRole('button', { name: '发布' })).toHaveLength(1)

    await user.click(within(previewDialog).getByRole('button', { name: '发布' }))

    await waitFor(() => {
      expect(agentService.publishSkillPackageUpload).toHaveBeenCalledWith({
        stagingUploadId: 'stage-1',
        packageName: 'core-tools',
        packageVersion: '2026.09.1',
        description: 'Core developer skills',
        expectedVersion: null,
      })
    })
    await waitFor(() => expect(onCreated).toHaveBeenCalled())
  })

  it('publishes an existing upload package with its current version and reports referenced skills', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.getSkillPackage).mockResolvedValue(detail())
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: '1',
      results: [agent('reviewer', [{ packageName: 'core-tools', name: 'dropped-skill' }])],
    })
    renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.click(within(modal).getByRole('tab', { name: /上传压缩包/ }))
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'core-tools')
    await user.type(within(modal).getByPlaceholderText('1.0.0'), '2026.09.1')
    await user.upload(within(modal).getByLabelText('技能包压缩包'), archiveFile())
    await user.click(within(modal).getByRole('button', { name: /解析预览/ }))

    const previewDialog = await screen.findByRole('dialog', { name: /预览并发布/ })
    // 被移除的技能同时出现在“移除的技能”与“仍被 Agent 引用的技能”两处。
    await waitFor(() => expect(within(previewDialog).getAllByText('dropped-skill')).toHaveLength(2))
    expect(
      within(previewDialog).getByText('仍有 Agent 引用这些技能时发布会失败，请先调整对应 Agent。'),
    ).toBeInTheDocument()

    await user.click(within(previewDialog).getByRole('button', { name: '发布' }))

    await waitFor(() => {
      expect(agentService.publishSkillPackageUpload).toHaveBeenCalledWith(
        expect.objectContaining({ expectedVersion: '3' }),
      )
    })
  })

  it('releases the staged archive when the preview is cancelled', async () => {
    const user = userEvent.setup()
    const { onClose } = renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.click(within(modal).getByRole('tab', { name: /上传压缩包/ }))
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'core-tools')
    await user.type(within(modal).getByPlaceholderText('1.0.0'), '2026.09.1')
    await user.upload(within(modal).getByLabelText('技能包压缩包'), archiveFile())
    await user.click(within(modal).getByRole('button', { name: /解析预览/ }))

    const previewDialog = await screen.findByRole('dialog', { name: /预览并发布/ })
    await user.click(within(previewDialog).getByRole('button', { name: /取消/ }))

    await waitFor(() => {
      expect(agentService.cancelSkillPackageUpload).toHaveBeenCalledWith('stage-1')
    })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(agentService.publishSkillPackageUpload).not.toHaveBeenCalled()
  })

  it('refuses to upload over a Git-backed package', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.getSkillPackage).mockResolvedValue(detail({
      source: 'GIT',
      git: { repositoryUrl: 'https://github.com/example/skills.git', branch: 'main', hasToken: false },
    }))
    renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.click(within(modal).getByRole('tab', { name: /上传压缩包/ }))
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'core-tools')
    await user.type(within(modal).getByPlaceholderText('1.0.0'), '2026.09.1')
    await user.upload(within(modal).getByLabelText('技能包压缩包'), archiveFile())
    await user.click(within(modal).getByRole('button', { name: /解析预览/ }))

    expect(
      await within(modal).findByText('同名技能包已由 Git 仓库发布，不能用上传覆盖'),
    ).toBeInTheDocument()
    expect(uploadSkillArchive).not.toHaveBeenCalled()
  })

  it('requires a supported archive before previewing', async () => {
    const user = userEvent.setup()
    renderModal()

    const modal = screen.getByRole('dialog', { name: /创建技能包/ })
    await user.click(within(modal).getByRole('tab', { name: /上传压缩包/ }))
    await user.type(within(modal).getByPlaceholderText('my-skills'), 'core-tools')
    await user.click(within(modal).getByRole('button', { name: /解析预览/ }))

    expect(within(modal).getByText('请选择要上传的压缩包')).toBeInTheDocument()
    expect(hashSkillArchive).not.toHaveBeenCalled()
  })
})
