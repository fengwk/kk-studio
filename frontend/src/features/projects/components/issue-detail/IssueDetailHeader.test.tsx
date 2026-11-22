import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ProjectWorkflowDTO } from '@/shared/api/generated/contracts'
import type { StorageService } from '@/shared/api/storage-service'
import { setLocale } from '@/shared/i18n'
import type { ProjectsApi } from '../../projects-api'
import { toStateNames } from '../../issue-labels'
import { IssueDetailModal } from '../IssueDetailModal'
import { IssueDetailHeader } from './IssueDetailHeader'

const issueId = 'issue-00000000-0000-0000-0000-000000000001'

const workflow: ProjectWorkflowDTO = {
  states: [
    {
      state: 'IN_PROGRESS',
      name: '进行中',
      next: [],
      agent: null,
      environment: null,
      instructions: null,
      maxRuns: null,
    },
  ],
}

const storageService = {
  getBlobDownloadUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
} as unknown as StorageService

function renderHeader(
  props: Partial<Parameters<typeof IssueDetailHeader>[0]> = {},
) {
  return render(
    <IssueDetailHeader
      issueNumber="12"
      state="IN_PROGRESS"
      stateNames={new Map([['IN_PROGRESS', '进行中']])}
      isBlocked={false}
      blockedReason={null}
      pauseReason={null}
      deleteDisabled={false}
      onDelete={vi.fn()}
      onClose={vi.fn()}
      {...props}
    />,
  )
}

describe('Issue 详情阶段徽标', () => {
  beforeEach(() => {
    window.localStorage.clear()
    // 清空存储后必须显式恢复语言，否则 i18n 会回落到默认 en-US 导致中文断言失效。
    setLocale('zh-CN')
    vi.clearAllMocks()
  })

  afterEach(() => {
    setLocale('zh-CN')
  })

  it('uses the workflow name of the BLOCKED stage instead of the generic blocked label', () => {
    // 同一阶段在看板列头与详情里必须是同一个名字：阻塞阶段的自定义名称优先于通用说法。
    const stateNames = toStateNames([
      { state: 'IN_PROGRESS', name: '进行中' },
      { state: 'BLOCKED', name: '等待外部依赖' },
    ])
    renderHeader({
      state: 'BLOCKED',
      stateNames,
      isBlocked: true,
      blockedReason: 'Waiting on the vendor API',
    })

    const badge = screen.getByTitle('BLOCKED · Waiting on the vendor API')
    expect(badge).toHaveTextContent('等待外部依赖')
    expect(screen.queryByText('业务阻塞')).toBeNull()
  })

  it('keeps the generic blocked label when the workflow declares no blocked stage name', () => {
    // 工作流没有声明 BLOCKED 名称时退回通用阻塞说法，而不是露出编码。
    renderHeader({
      state: 'BLOCKED',
      stateNames: toStateNames([{ state: 'IN_PROGRESS', name: '进行中' }]),
      isBlocked: true,
      blockedReason: 'Waiting on the vendor API',
    })

    const badge = screen.getByTitle('BLOCKED · Waiting on the vendor API')
    expect(badge).toHaveTextContent('业务阻塞')
    expect(screen.queryByText('BLOCKED')).toBeNull()
  })

  it('does not present an undeclared state code as visible stage text', () => {
    // 工作流未声明的编码只留在提示里，可见文案退回通用说法。
    renderHeader({
      state: 'LEGACY_REVIEW',
      stateNames: toStateNames([{ state: 'IN_PROGRESS', name: '进行中' }]),
    })

    expect(screen.getByTitle('LEGACY_REVIEW')).toHaveTextContent('未知阶段')
    expect(screen.queryByText('LEGACY_REVIEW')).toBeNull()
  })

  it('renders no stage badge before the issue snapshot arrives', async () => {
    // 详情尚未加载时快照里没有任何阶段事实，不能把内部编码 INIT 当成当前阶段展示。
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const api = {
      getIssue: vi.fn().mockReturnValue(new Promise(() => {})),
      listIssueEvidence: vi.fn().mockResolvedValue([]),
    } as unknown as ProjectsApi
    render(
      <QueryClientProvider client={client}>
        <IssueDetailModal
          isOpen
          issueId={issueId}
          projectId="proj-00000000-0000-0000-0000-000000000001"
          workflow={workflow}
          onClose={vi.fn()}
          api={api}
          storageService={storageService}
        />
      </QueryClientProvider>,
    )

    const header = document.querySelector('.issue-detail-header-left') as HTMLElement
    expect(header).not.toBeNull()
    expect(header.querySelector('.ui-badge')).toBeNull()
    expect(within(header).queryByText('INIT')).toBeNull()
    expect(within(header).queryByText('未知阶段')).toBeNull()
  })
})
