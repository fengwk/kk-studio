import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setLocale, translate } from '@/shared/i18n'
import { EditProjectModal } from './EditProjectModal'
import { IssueDetailModal } from './IssueDetailModal'
import type { IssueDetailDTO, ProjectDTO } from '../types'
import type { ProjectsApi } from '../projects-api'
import type { StorageService } from '@/shared/api/storage-service'

// Agent 目录/环境列表是工作流编辑器的共享查询依赖；这里固定内容以保证双语渲染断言稳定。
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn().mockResolvedValue({
      results: [{ name: 'architect' }, { name: 'backend-dev' }],
      totalCount: 2,
    }),
  },
}))

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn().mockResolvedValue([{ name: 'macos' }, { name: 'ubuntu' }]),
    getEnvironmentUpdate: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

const project: ProjectDTO = {
  id: 'a0000000-0000-0000-0000-000000000001',
  title: 'Existing Project',
  description: 'Project desc',
  workflow: {
    states: [
      { state: 'INIT', name: '待开始', next: ['DONE'] },
      { state: 'BLOCKED', name: '业务阻塞' },
      { state: 'DONE', name: '完成' },
    ],
  },
  yoloEnabled: true,
  nextIssueNumber: '1',
  version: '1',
  archivedAt: null,
  createdAt: '2026-09-14T00:00:00Z',
  updatedAt: '2026-09-14T00:00:00Z',
}

const issueDetail: IssueDetailDTO = {
  issue: {
    id: 'issue-00000000-0000-0000-0000-000000000001',
    projectId: project.id,
    number: '12',
    title: 'OAuth2 login',
    description: 'Authorization code flow with PKCE',
    state: 'IN_PROGRESS',
    blockedFromState: null,
    blockReason: null,
    pauseReason: null,
    pauseDetail: null,
    version: '3',
    archivedAt: null,
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-20T11:00:00Z',
  },
  activities: [],
  nextActivityCursor: null,
  runs: [],
  currentRun: null,
  latestRun: null,
  stageBudgets: [],
  agentThreads: [],
}

const storageService = {
  getBlobDownloadUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
} as unknown as StorageService

function renderWorkflowEditor() {
  const api = { updateWorkflow: vi.fn() } as unknown as ProjectsApi
  const rendered = renderWithClient(
    <EditProjectModal
      isOpen={true}
      project={project}
      snapshot={{ project, issues: [], referencedStateCodes: [] }}
      onClose={vi.fn()}
      onSuccess={vi.fn()}
      api={api}
    />,
  )
  return { api, unmount: rendered.unmount }
}

afterEach(() => {
  // 语言是模块级状态，避免污染后续依赖 zh-CN 的用例。
  setLocale('zh-CN')
})

describe('Projects 双语渲染', () => {
  it('工作流编辑器在 en-US 下渲染完整英文文案与共享阶段标签', async () => {
    setLocale('en-US')
    const user = userEvent.setup()
    renderWorkflowEditor()

    await user.click(screen.getByRole('tab', { name: 'Workflow' }))
    expect(await screen.findByRole('list', { name: 'Workflow stages' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add stage' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save Workflow' })).toBeInTheDocument()
    // 阶段标签走共享 catalog 模板：名称保持项目数据，包裹格式随语言切换。
    expect(screen.getByRole('checkbox', { name: '完成 (DONE)' })).toBeInTheDocument()
  })

  it('同一校验失败在 zh-CN 与 en-US 下语义一致且不出现未渲染占位符', async () => {
    const user = userEvent.setup()

    const zhEditor = renderWorkflowEditor()
    await user.click(screen.getByRole('tab', { name: '工作流' }))
    // INIT 默认选中：取消唯一后续阶段后不再存在到 DONE 的正常路径。
    await user.click(screen.getByRole('checkbox', { name: '完成（DONE）' }))
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(await screen.findByText('工作流必须提供从 INIT 到 DONE 的正常路径')).toBeInTheDocument()

    zhEditor.unmount()
    setLocale('en-US')
    renderWorkflowEditor()
    await user.click(screen.getByRole('tab', { name: 'Workflow' }))
    await user.click(screen.getByRole('checkbox', { name: '完成 (DONE)' }))
    await user.click(screen.getByRole('button', { name: 'Save Workflow' }))
    expect(
      await screen.findByText('A workflow must provide a normal path from INIT to DONE'),
    ).toBeInTheDocument()
  })

  it('带参数的校验错误在两种语言下都渲染出参数（Run 额度范围）', () => {
    const params = { state: 'WORK', max: '2147483647' }
    setLocale('zh-CN')
    const zh = translate('projects.workflow.error.agentMaxRuns', params)
    setLocale('en-US')
    const en = translate('projects.workflow.error.agentMaxRuns', params)

    expect(zh).toContain('WORK')
    expect(zh).toContain('2147483647')
    expect(en).toContain('WORK')
    expect(en).toContain('2147483647')
    expect(zh).not.toBe(en)
    expect(`${zh}${en}`).not.toContain('⟦missing')
  })

  it('Issue 详情弹窗在 en-US 下使用英文标签与英文操作名', async () => {
    setLocale('en-US')
    const api = { getIssue: vi.fn().mockResolvedValue(issueDetail) } as unknown as ProjectsApi

    renderWithClient(
      <IssueDetailModal
        isOpen={true}
        issueId={issueDetail.issue.id}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={storageService}
      />,
    )

    expect(await screen.findByLabelText('Issue #12 details')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Requirements' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Activity timeline (0)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete Issue' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Close' }).length).toBeGreaterThanOrEqual(1)
  })
})
