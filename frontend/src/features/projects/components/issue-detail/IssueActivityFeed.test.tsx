import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { IssueActivityDTO, IssueDetailDTO, ProjectWorkflowDTO } from '@/shared/api/generated/contracts'
import type { StorageService } from '@/shared/api/storage-service'
import { setLocale } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectsApi } from '../../projects-api'
import { IssueDetailModal } from '../IssueDetailModal'

const projectId = 'proj-00000000-0000-0000-0000-000000000001'
const issueId = 'issue-00000000-0000-0000-0000-000000000001'
const otherIssueId = 'issue-00000000-0000-0000-0000-000000000002'

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

function activity(issue: string, label: string, sequence: number): IssueActivityDTO {
  return {
    issueId: issue,
    sequence: String(sequence),
    kind: 'COMMENT',
    actorType: 'HUMAN',
    actorAgentName: null,
    runId: null,
    body: `${label} ${sequence}`,
    createdAt: Date.UTC(2026, 9, 1, 0, 0, sequence),
  }
}

interface WindowSpec {
  issue: string
  /** 活动正文前缀，用来区分不同 Issue 的同一 sequence。 */
  label: string
  first: number
  last: number
  next: string | null
  /** Issue 版本：写操作后的回读会带上更高的版本，快照因此不是同一份。 */
  issueVersion?: string
}

/** 服务端活动窗口：按 sequence 升序返回最早的一页，页满时才给出下一窗口游标。 */
function activityWindow({ issue, label, first, last, next, issueVersion = '3' }: WindowSpec): IssueDetailDTO {
  const sequences: number[] = []
  for (let sequence = first; sequence <= last; sequence += 1) {
    sequences.push(sequence)
  }
  return {
    issue: {
      id: issue,
      projectId,
      number: '12',
      title: 'Implement OAuth2 login',
      description: 'Support GitHub and Google OAuth2 providers',
      state: 'IN_PROGRESS',
      blockedFromState: null,
      blockReason: null,
      pauseReason: null,
      pauseDetail: null,
      version: issueVersion,
      createdAt: Date.UTC(2026, 8, 20, 10),
      updatedAt: Date.UTC(2026, 8, 20, 11),
    },
    activities: sequences.map((sequence) => activity(issue, label, sequence)),
    nextActivityCursor: next,
    runs: [],
    stageBudgets: [],
    agentThreads: [],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve
    reject = onReject
  })
  return { promise, resolve, reject }
}

const storageService = {
  getBlobDownloadUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
} as unknown as StorageService

function createApi(overrides: Partial<ProjectsApi>): ProjectsApi {
  return {
    listIssueEvidence: vi.fn().mockResolvedValue([]),
    ...overrides,
  } as unknown as ProjectsApi
}

function modalTree(client: QueryClient, api: ProjectsApi, targetIssueId: string) {
  return (
    <QueryClientProvider client={client}>
      <IssueDetailModal
        isOpen
        issueId={targetIssueId}
        projectId={projectId}
        workflow={workflow}
        onClose={vi.fn()}
        api={api}
        storageService={storageService}
      />
    </QueryClientProvider>
  )
}

function renderModal(api: ProjectsApi, targetIssueId = issueId) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rendered = render(modalTree(client, api, targetIssueId))
  return {
    ...rendered,
    /** 详情查询所在的 client：写操作后的回读就是对这个 key 的 invalidate。 */
    client,
    /** 同一弹窗实例切换到另一张 Issue：详情快照、活动第一页与游标全部换新。 */
    switchIssue: (nextIssueId: string) => rendered.rerender(modalTree(client, api, nextIssueId)),
  }
}

describe('Issue 活动窗口', () => {
  beforeEach(() => {
    window.localStorage.clear()
    // 清空存储后必须显式恢复语言，否则 i18n 会回落到默认 en-US 导致中文断言失效。
    setLocale('zh-CN')
    vi.clearAllMocks()
  })

  afterEach(() => {
    setLocale('zh-CN')
  })

  it('loads the next activity window on demand and marks the count while more remain', async () => {
    // 详情只带回第一页 50 条：标签标明还有后续，时间线末尾按游标取下一窗口，取完回到权威已加载数。
    const api = createApi({
      getIssue: vi
        .fn()
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }))
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 51, last: 60, next: null })),
    })
    renderModal(api)

    const tab = await screen.findByRole('tab', { name: '活动时间线 (50+)' })
    fireEvent.click(tab)

    expect(await screen.findByText('activity 50')).toBeInTheDocument()
    expect(screen.queryByText('activity 51')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByText('activity 60')).toBeInTheDocument()
    expect(screen.getByText('activity 1')).toBeInTheDocument()
    expect(api.getIssue).toHaveBeenLastCalledWith(issueId, '50')
    expect(screen.getByRole('tab', { name: '活动时间线 (60)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).toBeNull()
  })

  it('keeps the loaded activities and retries in place when the next window fails', async () => {
    // 后续窗口读取失败不能清空已加载事实，也不静默结束：就地说明失败并允许重试同一游标。
    const api = createApi({
      getIssue: vi
        .fn()
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }))
        .mockRejectedValueOnce(new Error('network offline'))
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 51, last: 60, next: null })),
    })
    renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))

    const timeline = document.querySelector('.activity-timeline') as HTMLElement
    const alert = await within(timeline).findByRole('alert')
    expect(alert).toHaveTextContent('活动读取失败')
    expect(screen.getByText('activity 50')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '活动时间线 (50+)' })).toBeInTheDocument()

    fireEvent.click(within(alert).getByRole('button', { name: '重试' }))

    expect(await within(timeline).findByText('activity 60')).toBeInTheDocument()
    expect(within(timeline).queryByRole('alert')).toBeNull()
    expect(api.getIssue).toHaveBeenCalledTimes(3)
  })

  it('renders the pending-count label and the load-more entry in English', async () => {
    // 计数后缀与入口文案都走 catalog：en-US 下不得留下中文。
    setLocale('en-US')
    const api = createApi({
      getIssue: vi
        .fn()
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' })),
    })
    renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: 'Activity timeline (50+)' }))

    expect(await screen.findByRole('button', { name: 'Load more' })).toBeInTheDocument()
    expect(screen.queryByText(/活动时间线/)).toBeNull()
  })

  it('drops an in-flight next window when the detail switches to another issue', async () => {
    // 切换 Issue 时详情快照换了基线：旧快照的后续窗口不能再拼到新快照的时间线上。
    const pendingWindow = deferred<IssueDetailDTO>()
    const api = createApi({
      getIssue: vi.fn((id: string, afterSequence?: string) => {
        if (id === otherIssueId) {
          return Promise.resolve(
            activityWindow({ issue: otherIssueId, label: 'other activity', first: 1, last: 50, next: '50' }),
          )
        }
        if (afterSequence) {
          return pendingWindow.promise
        }
        return Promise.resolve(
          activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }),
        )
      }),
    })
    const { switchIssue } = renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))

    switchIssue(otherIssueId)
    expect(await screen.findByText('other activity 50')).toBeInTheDocument()

    await act(async () => {
      pendingWindow.resolve(
        activityWindow({ issue: issueId, label: 'stale activity', first: 51, last: 60, next: null }),
      )
    })

    expect(screen.queryByText('stale activity 51')).toBeNull()
    // 新快照自己的游标仍然生效，入口不能被旧窗口的「已取完」覆盖。
    expect(screen.getByRole('tab', { name: '活动时间线 (50+)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '加载更多' })).toBeEnabled()
  })

  it('keeps the loaded windows and appends the newer tail when the same issue is read again', async () => {
    // 同一 Issue 回读只换首页快照：已取的后续窗口不消失，回读后仍可继续取到新追加的尾部活动。
    const api = createApi({
      getIssue: vi
        .fn()
        // 首次详情：第一页 1..50，还有后续窗口。
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }))
        // 后续窗口 51..60，此时已取到末尾。
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 51, last: 60, next: null }))
        // 回读详情：第一页 1..50 不变，但版本已经因为新活动升高。
        .mockResolvedValueOnce(
          activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50', issueVersion: '4' }),
        )
        // 尾部补取：返回与已加载窗口重叠的 55..60 与新增的 61..62。
        .mockResolvedValueOnce(activityWindow({ issue: issueId, label: 'activity', first: 55, last: 62, next: null })),
    })
    const { client } = renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await screen.findByText('activity 60')
    expect(screen.getByRole('tab', { name: '活动时间线 (60)' })).toBeInTheDocument()

    // 回读同一 Issue 的详情：已加载窗口必须原样保留，尾部入口重新可用（计数仍是已加载的 60）。
    await act(async () => {
      await client.invalidateQueries({ queryKey: queryKeys.projects.issue(projectId, issueId) })
    })
    expect(await screen.findByRole('tab', { name: '活动时间线 (60+)' })).toBeInTheDocument()
    expect(api.getIssue).toHaveBeenCalledTimes(3)
    expect(screen.getByText('activity 51')).toBeInTheDocument()
    expect(screen.getByText('activity 60')).toBeInTheDocument()

    // 回读后尾部的继续加载入口仍在：从最后一条已读序号往后补取，重叠活动不重复。
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    expect(await screen.findByText('activity 62')).toBeInTheDocument()
    expect(api.getIssue).toHaveBeenLastCalledWith(issueId, '60')
    expect(screen.getAllByText('activity 55')).toHaveLength(1)
    expect(screen.getByText('activity 1')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '活动时间线 (62)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).toBeNull()
  })

  it('clears the loaded windows when the issue switches, including switching back', async () => {
    // 切换 Issue 才作废已加载窗口：回到原 Issue 也按新快照重新开始，不复活旧窗口。
    const api = createApi({
      getIssue: vi.fn((id: string, afterSequence?: string) => {
        if (afterSequence) {
          return Promise.resolve(
            activityWindow({ issue: id, label: 'activity', first: 51, last: 60, next: null }),
          )
        }
        return Promise.resolve(
          id === otherIssueId
            ? activityWindow({ issue: otherIssueId, label: 'other activity', first: 1, last: 50, next: null })
            : activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }),
        )
      }),
    })
    const { switchIssue } = renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await screen.findByText('activity 60')

    switchIssue(otherIssueId)
    expect(await screen.findByText('other activity 50')).toBeInTheDocument()
    expect(screen.queryByText('activity 60')).toBeNull()

    switchIssue(issueId)
    expect(await screen.findByText('activity 50')).toBeInTheDocument()
    // 旧窗口不随切回而复活：计数与入口回到新快照的第一页。
    expect(screen.queryByText('activity 51')).toBeNull()
    expect(screen.getByRole('tab', { name: '活动时间线 (50+)' })).toBeInTheDocument()
  })

  it('does not let a previous issue window block or rewrite the new issue tail', async () => {
    // 旧 Issue 的在途窗口不能卡住新 Issue 的继续加载，迟到后也不能入新时间线。
    const pendingWindow = deferred<IssueDetailDTO>()
    const api = createApi({
      getIssue: vi.fn((id: string, afterSequence?: string) => {
        if (afterSequence && id === issueId) {
          return pendingWindow.promise
        }
        if (afterSequence) {
          return Promise.resolve(
            activityWindow({ issue: otherIssueId, label: 'other activity', first: 51, last: 60, next: null }),
          )
        }
        return Promise.resolve(
          id === otherIssueId
            ? activityWindow({ issue: otherIssueId, label: 'other activity', first: 1, last: 50, next: '50' })
            : activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }),
        )
      }),
    })
    const { switchIssue } = renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))

    switchIssue(otherIssueId)
    await screen.findByText('other activity 50')
    // 新 Issue 自己的窗口照常可取，不被上一个 Issue 的在途读取卡住。
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    expect(await screen.findByText('other activity 60')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '活动时间线 (60)' })).toBeInTheDocument()

    // 即使切回同一个 ID，之前打开时发起的窗口也不能复活。
    switchIssue(issueId)
    await screen.findByText('activity 50')
    await act(async () => {
      pendingWindow.resolve(
        activityWindow({ issue: issueId, label: 'stale activity', first: 51, last: 60, next: null }),
      )
    })
    expect(screen.queryByText('stale activity 51')).toBeNull()
    expect(screen.getByRole('tab', { name: '活动时间线 (50+)' })).toBeInTheDocument()
  })

  it('does not surface the failure of a window that belongs to a switched-away issue', async () => {
    // 旧快照的后续窗口失败同样属于旧基线：不能在另一张 Issue 的时间线上弹出读取失败。
    const pendingWindow = deferred<IssueDetailDTO>()
    const api = createApi({
      getIssue: vi.fn((id: string, afterSequence?: string) => {
        if (id === otherIssueId) {
          return Promise.resolve(
            activityWindow({ issue: otherIssueId, label: 'other activity', first: 1, last: 50, next: '50' }),
          )
        }
        if (afterSequence) {
          return pendingWindow.promise
        }
        return Promise.resolve(
          activityWindow({ issue: issueId, label: 'activity', first: 1, last: 50, next: '50' }),
        )
      }),
    })
    const { switchIssue } = renderModal(api)

    fireEvent.click(await screen.findByRole('tab', { name: '活动时间线 (50+)' }))
    await screen.findByText('activity 50')
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))

    switchIssue(otherIssueId)
    expect(await screen.findByText('other activity 50')).toBeInTheDocument()

    await act(async () => {
      pendingWindow.reject(new Error('network offline'))
    })

    expect(screen.queryByText('活动读取失败')).toBeNull()
    // 读取状态也要跟着快照回来：不能卡在「正在加载」，重试入口仍可用。
    expect(screen.getByRole('button', { name: '加载更多' })).toBeEnabled()
  })
})
