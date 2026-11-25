import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import type {
  ChatSpaceDTO,
  HarnessSessionEntryDTO,
  ScheduleDTO,
  ScheduleRunPageDTO,
  WorkspaceTreeDTO,
} from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import type { PaneTarget } from './pane-target'
import {
  extractTurnCuts,
  flattenFolders,
  formatScheduleNextRun,
  INITIAL_HISTORY_CURSORS,
  listTreeSessions,
  popNewerCursor,
  pushOlderCursor,
  validateScheduleTitle,
} from './schedule-helpers'
import { useWorkspaceRealtime } from './useWorkspaceRealtime'
import { WorkspaceSchedules } from './WorkspaceSchedules'

const id = (suffix: number) => `10000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`
const spaceId = id(1)
const rootFolderId = id(2)
const childFolderId = id(3)
const sessionId = id(4)
const thread1Id = id(5)
const thread2Id = id(6)
const schedule1Id = id(10)
const schedule2Id = id(11)
const cut1Id = id(21)
const cut2Id = id(22)

const sampleSpace: ChatSpaceDTO = {
  id: spaceId,
  title: 'Studio',
  defaultAgentName: 'Coder',
  defaultEnvironmentId: null,
  defaultYoloEnabled: false,
  archivedAt: null,
  version: '1',
}

const sampleTree: WorkspaceTreeDTO = {
  treeVersion: '1',
  folders: [
    {
      id: rootFolderId,
      name: 'Projects',
      children: [
        {
          id: childFolderId,
          name: 'Daily',
          children: [],
          resources: [],
        },
      ],
      resources: [
        {
          id: sessionId,
          title: 'Cache Hit Rate',
          kind: 'SESSION',
          threads: [
            { id: thread1Id, title: 'main', state: 'IDLE', lastActivityAt: 1, version: '0' },
            { id: thread2Id, title: 'alt', state: 'IDLE', lastActivityAt: 2, version: '0' },
          ],
        },
      ],
    },
  ],
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function createHarnessEntries(): HarnessSessionEntryDTO[] {
  return [
    {
      entryId: id(100),
      sessionId,
      parentEntryId: null,
      entryType: 'TURN_START',
      payload: {},
      createdAt: 1,
      usageCost: null,
    },
    {
      entryId: id(101),
      sessionId,
      parentEntryId: id(100),
      entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'TEXT', text: 'Record baseline latency' }] } },
      createdAt: 2,
      usageCost: null,
    },
    {
      entryId: cut1Id,
      sessionId,
      parentEntryId: id(101),
      entryType: 'TURN_END',
      payload: { outcome: 'COMPLETED' },
      createdAt: 3,
      usageCost: null,
    },
    {
      entryId: id(102),
      sessionId,
      parentEntryId: cut1Id,
      entryType: 'TURN_START',
      payload: {},
      createdAt: 4,
      usageCost: null,
    },
    {
      entryId: id(103),
      sessionId,
      parentEntryId: id(102),
      entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'TEXT', text: 'Compare prompt cache variants' }] } },
      createdAt: 5,
      usageCost: null,
    },
    {
      entryId: cut2Id,
      sessionId,
      parentEntryId: id(103),
      entryType: 'TURN_END',
      payload: { outcome: 'COMPLETED' },
      createdAt: 6,
      usageCost: null,
    },
  ]
}

describe('schedule-helpers', () => {
  it('flattens folders, lists sessions, extracts turn cuts and tracks history cursors', () => {
    expect(flattenFolders(sampleTree.folders)).toEqual([
      { id: rootFolderId, label: 'Projects' },
      { id: childFolderId, label: 'Projects / Daily' },
    ])
    expect(listTreeSessions(sampleTree.folders)).toEqual([
      {
        id: sessionId,
        label: 'Projects / Cache Hit Rate',
        threads: [
          { id: thread1Id, title: 'main' },
          { id: thread2Id, title: 'alt' },
        ],
      },
    ])

    expect(extractTurnCuts(createHarnessEntries())).toEqual([
      { entryId: cut1Id, turnNumber: 1, summary: 'Record baseline latency' },
      { entryId: cut2Id, turnNumber: 2, summary: 'Compare prompt cache variants' },
    ])

    const p2 = pushOlderCursor(INITIAL_HISTORY_CURSORS, 'c1')
    const p3 = pushOlderCursor(p2, 'c2')
    expect(p3).toEqual({ current: 'c2', previous: [null, 'c1'] })
    expect(popNewerCursor(p3)).toEqual({ current: 'c1', previous: [null] })
    expect(popNewerCursor(popNewerCursor(p3))).toEqual(INITIAL_HISTORY_CURSORS)

    expect(validateScheduleTitle('  ')).toBe('workspace.schedule.nameRequired')
    expect(validateScheduleTitle('a'.repeat(257))).toBe('workspace.schedule.nameTooLong')
    expect(validateScheduleTitle('bad\nname')).toBe('workspace.schedule.nameControlChars')
    expect(validateScheduleTitle('Daily CI')).toBeNull()

    const ts = Date.UTC(2026, 10, 26, 1, 0, 0) // 2026-11-26 09:00 Asia/Shanghai (Thu)
    expect(formatScheduleNextRun(ts, 'Asia/Shanghai', 'zh-CN')).toBe('11-26 周四 09:00')
    expect(formatScheduleNextRun(ts, 'Asia/Shanghai', 'en-US')).toBe('11-26 Thu 09:00')
  })
})

function RealtimeProbe({
  space,
  tree,
  open,
  report,
}: {
  space: ChatSpaceDTO
  tree: WorkspaceTreeDTO
  open: (target: PaneTarget, anchor?: HTMLElement | null) => void
  report: (message: string) => void
}) {
  useWorkspaceRealtime(space.id)
  return <WorkspaceSchedules space={space} tree={tree} open={open} report={report} />
}

describe('WorkspaceSchedules', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('toggles switches with expectedVersion, enforces single expanded history, paginates by cursor, opens threads and handles run-now', async () => {
    const futureTs = Date.now() + 3_600_000
    const schedules: ScheduleDTO[] = [
      {
        id: schedule1Id,
        title: '每日构建日报',
        message: '汇总构建',
        enabled: true,
        trigger: { type: 'REPEAT', frequency: 'WEEKDAYS', time: '09:00' },
        timeZone: 'Asia/Shanghai',
        target: { type: 'NEW_SESSION', folderId: rootFolderId, agentName: 'Coder' },
        nextDueAt: futureTs,
        version: '1',
      },
      {
        id: schedule2Id,
        title: '缓存命中率回归',
        message: '检查缓存',
        enabled: false,
        trigger: { type: 'REPEAT', frequency: 'DAILY', time: '10:00' },
        timeZone: 'Asia/Shanghai',
        target: { type: 'CONTINUE', sessionId, threadId: thread1Id },
        nextDueAt: null,
        version: '4',
      },
    ]

    const runPages: Record<string, ScheduleRunPageDTO> = {
      [`${schedule1Id}:`]: {
        items: [
          {
            id: id(50),
            scheduledAt: Date.UTC(2026, 10, 24, 1, 0),
            startedAt: Date.UTC(2026, 10, 24, 1, 0),
            timeZone: 'Asia/Shanghai',
            result: 'SUBMITTED',
            reason: null,
            thread: { sessionId, id: thread1Id, title: '日报 11-24' },
          },
          {
            id: id(51),
            scheduledAt: Date.UTC(2026, 10, 23, 1, 0),
            startedAt: null,
            timeZone: 'Asia/Shanghai',
            result: 'SUBMITTED',
            reason: null,
            thread: null,
          },
        ],
        olderCursor: null,
      },
      [`${schedule2Id}:`]: {
        items: [
          {
            id: id(60),
            scheduledAt: Date.UTC(2026, 10, 24, 2, 0),
            startedAt: null,
            timeZone: 'Asia/Shanghai',
            result: 'SKIPPED',
            reason: 'EXECUTION_NOT_SETTLED',
            thread: null,
          },
        ],
        olderCursor: 'cursor-page-2',
      },
      [`${schedule2Id}:cursor-page-2`]: {
        items: [
          {
            id: id(61),
            scheduledAt: Date.UTC(2026, 10, 23, 2, 0),
            startedAt: Date.UTC(2026, 10, 23, 2, 0),
            timeZone: 'Asia/Shanghai',
            result: 'SUBMITTED',
            reason: null,
            thread: { sessionId, id: thread2Id, title: '回归 11-23' },
          },
        ],
        olderCursor: null,
      },
    }

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'GET') {
        return jsonResponse(schedules)
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}` && method === 'PATCH') {
        const body = JSON.parse(String(init?.body)) as { expectedVersion: string; enabled: boolean }
        expect(body.expectedVersion).toBe('1')
        schedules[0] = {
          ...schedules[0]!,
          enabled: body.enabled,
          nextDueAt: body.enabled ? futureTs : null,
          version: '2',
        }
        return jsonResponse(schedules[0])
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule2Id}/runs` && method === 'POST') {
        const headers = new Headers(init?.headers)
        expect(headers.get('Idempotency-Key')).toBeTruthy()
        const added = {
          id: id(70),
          scheduledAt: Date.UTC(2026, 10, 25, 2, 0),
          startedAt: Date.UTC(2026, 10, 25, 2, 0),
          timeZone: 'Asia/Shanghai',
          result: 'SUBMITTED' as const,
          reason: null,
          thread: { sessionId, id: thread2Id, title: '手动触发运行' },
        }
        runPages[`${schedule2Id}:`] = {
          items: [added, ...runPages[`${schedule2Id}:`]!.items],
          olderCursor: 'cursor-page-2',
        }
        return jsonResponse(added, 201)
      }
      const runsMatch = /\/schedules\/([^/]+)\/runs$/.exec(url.pathname)
      if (runsMatch && method === 'GET') {
        const key = `${runsMatch[1]}:${url.searchParams.get('before') ?? ''}`
        return jsonResponse(runPages[key] ?? { items: [], olderCursor: null })
      }
      return jsonResponse({ detail: `Unhandled ${method} ${url.pathname}` }, 404)
    })

    const open = vi.fn()
    const report = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <WorkspaceSchedules space={sampleSpace} tree={sampleTree} open={open} report={report} />
      </QueryClientProvider>,
    )

    // Plain-text titles are non-clickable; next run time shown for enabled schedule only.
    const title1 = await screen.findByText('每日构建日报')
    expect(title1.closest('button')).toBeNull()
    expect(screen.getByText(/下次 /)).toBeInTheDocument()

    // Independent switch sends expectedVersion and clears next run time when turned off.
    const switch1 = screen.getByRole('switch', { name: '启用「每日构建日报」' })
    expect(switch1).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(switch1)
    await waitFor(() => expect(switch1).toHaveAttribute('aria-checked', 'false'))
    expect(screen.queryByText(/下次 /)).toBeNull()

    // Expand task 1 history -> shows clickable thread and deleted-thread fallback.
    const historyBtn1 = screen.getByRole('button', { name: '「每日构建日报」的执行记录' })
    fireEvent.click(historyBtn1)
    expect(await screen.findByRole('button', { name: /日报 11-24/ })).toBeInTheDocument()
    expect(screen.getByText('Thread 已删除')).toBeInTheDocument()
    expect(screen.getByText('Thread 已删除').closest('button')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /日报 11-24/ }))
    expect(open).toHaveBeenCalledWith({ kind: 'thread', threadId: thread1Id }, expect.any(HTMLElement))

    // Expanding task 2 collapses task 1 and paginates via backend cursor.
    const historyBtn2 = screen.getByRole('button', { name: '「缓存命中率回归」的执行记录' })
    fireEvent.click(historyBtn2)
    expect(historyBtn1).toHaveAttribute('aria-expanded', 'false')
    expect(historyBtn2).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByText('跳过：目标尚未结束')).toBeInTheDocument()

    const newerBtn = screen.getByRole('button', { name: '较新' })
    const olderBtn = screen.getByRole('button', { name: '较早' })
    expect(newerBtn).toBeDisabled()
    expect(olderBtn).toBeEnabled()

    olderBtn.focus()
    fireEvent.click(olderBtn)
    expect(await screen.findByRole('button', { name: /回归 11-23/ })).toBeInTheDocument()
    expect(newerBtn).toBeEnabled()
    expect(olderBtn).toBeDisabled()
    expect(newerBtn).toHaveFocus()

    // Collapsing discards cursor and clicking again loads first page.
    fireEvent.click(historyBtn2)
    expect(historyBtn2).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(historyBtn2)
    expect(await screen.findByText('跳过：目标尚未结束')).toBeInTheDocument()

    // Run now via menu creates a run and expands newest page without opening a pane.
    open.mockClear()
    fireEvent.click(screen.getByRole('button', { name: '缓存命中率回归的操作' }))
    fireEvent.click(screen.getByRole('menuitem', { name: '立即运行' }))
    expect(await screen.findByRole('button', { name: /手动触发运行/ })).toBeInTheDocument()
    expect(open).not.toHaveBeenCalled()
  })

  it('validates create/edit dialog, previews runs, preserves inputs on 409 conflict and restores focus on delete/cancel', async () => {
    const schedules: ScheduleDTO[] = [
      {
        id: schedule1Id,
        title: '周报草稿',
        message: '整理周报',
        enabled: true,
        trigger: { type: 'REPEAT', frequency: 'WEEKLY', weekday: 'FRIDAY', time: '17:00' },
        timeZone: 'Asia/Shanghai',
        target: { type: 'BRANCH', sessionId, sourceThreadId: thread1Id, turnEndEntryId: cut1Id },
        nextDueAt: Date.now() + 7_200_000,
        version: '1',
      },
    ]
    const entries = createHarnessEntries()
    let conflictOnce = true

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'GET') {
        return jsonResponse(schedules)
      }
      if (url.pathname === '/api/platform/agent-summaries' && method === 'GET') {
        return jsonResponse([{ name: 'Coder', description: '' }])
      }
      if (url.pathname === `/api/harness/threads/${thread1Id}` && method === 'GET') {
        return jsonResponse({
          version: '1',
          thread: {
            threadId: thread1Id,
            parentThreadId: null,
            name: 'main',
            sessionId,
            headEntryId: cut2Id,
            rootThreadId: thread1Id,
            yoloEnabled: false,
            nextCommandSequence: '1',
            version: '1',
            status: 'IDLE',
            executionControl: 'IDLE',
            branchSettings: { agentName: 'Coder', environmentId: null, yoloEnabled: false },
            createdAt: 1,
            updatedAt: 2,
          },
          baseEntryId: null,
          entries,
          queuedCommands: [],
          activeAssistantOutput: null,
          generationFailures: [],
          toolInvocations: [],
          stopReceipts: [],
          unconfirmedExecutions: [],
        })
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/preview` && method === 'POST') {
        return jsonResponse({ next: [Date.UTC(2026, 10, 27, 9, 0), Date.UTC(2026, 11, 4, 9, 0)] })
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}` && method === 'PATCH') {
        if (conflictOnce) {
          conflictOnce = false
          schedules[0] = { ...schedules[0]!, version: '2' }
          return jsonResponse({ code: 'VERSION_CONFLICT', reason: 'VERSION_CONFLICT', detail: 'Stale' }, 409)
        }
        const body = JSON.parse(String(init?.body)) as { expectedVersion: string; title: string }
        expect(body.expectedVersion).toBe('2')
        schedules[0] = { ...schedules[0]!, title: body.title, version: '3' }
        return jsonResponse(schedules[0])
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}` && method === 'DELETE') {
        expect(url.searchParams.get('expectedVersion')).toBe('3')
        schedules.splice(0, 1)
        return new Response(null, { status: 204 })
      }
      return jsonResponse({ detail: `Unhandled ${method} ${url.pathname}` }, 404)
    })

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <WorkspaceSchedules space={sampleSpace} tree={sampleTree} open={vi.fn()} report={vi.fn()} />
      </QueryClientProvider>,
    )

    const menuButton = await screen.findByRole('button', { name: '周报草稿的操作' })
    fireEvent.click(menuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: '编辑' }))

    const dialog = await screen.findByRole('dialog', { name: '编辑定时任务' })
    const nameInput = within(dialog).getByLabelText(/名称/)
    expect(nameInput).toHaveValue('周报草稿')
    expect(await within(dialog).findByText(/接下来：/)).toBeInTheDocument()

    // Clear name -> inline validation error
    fireEvent.change(nameInput, { target: { value: '  ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    expect(await within(dialog).findByText('请填写名称。')).toBeInTheDocument()

    // Edit title -> 409 refreshes schedules and preserves typed title
    fireEvent.change(nameInput, { target: { value: '周报草稿 v2' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('工作区已变更，请检查后重试。')
    expect(nameInput).toHaveValue('周报草稿 v2')

    // Retry save uses refreshed version '2' and succeeds
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '编辑定时任务' })).toBeNull())
    expect(await screen.findByText('周报草稿 v2')).toBeInTheDocument()

    // Delete flow: cancel restores focus to task menu button; confirm removes task and focuses New schedule button.
    const updatedMenuButton = screen.getByRole('button', { name: '周报草稿 v2的操作' })
    fireEvent.click(updatedMenuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: '编辑' }))
    const editDialog2 = await screen.findByRole('dialog', { name: '编辑定时任务' })
    fireEvent.click(within(editDialog2).getByRole('button', { name: '删除任务' }))

    const confirmModal = await screen.findByRole('alertdialog', { name: '删除定时任务「周报草稿 v2」？' })
    fireEvent.click(within(confirmModal).getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(updatedMenuButton).toHaveFocus()

    fireEvent.click(updatedMenuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: '编辑' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: '编辑定时任务' })).getByRole('button', { name: '删除任务' }))
    const confirmModal2 = await screen.findByRole('alertdialog', { name: '删除定时任务「周报草稿 v2」？' })
    fireEvent.click(within(confirmModal2).getByRole('button', { name: '删除任务' }))

    await waitFor(() => expect(screen.getByText('还没有定时任务')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '新建定时任务' })).toHaveFocus()
  })

  it('refreshes schedules and expanded first history page on chat-space schedules WebSocket events', async () => {
    const ws = new FakeWebSocketHarness()
    let phase = 1
    const schedule: ScheduleDTO = {
      id: schedule1Id,
      title: 'WS 同步任务',
      message: '检查同步',
      enabled: true,
      trigger: { type: 'REPEAT', frequency: 'HOURLY', minute: 15 },
      timeZone: 'UTC',
      target: { type: 'NEW_SESSION', folderId: rootFolderId, agentName: 'Coder' },
      nextDueAt: Date.now() + 1_800_000,
      version: '1',
    }

    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules`) {
        return jsonResponse([{ ...schedule, title: phase === 1 ? 'WS 同步任务' : 'WS 已更新任务' }])
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}/runs`) {
        return jsonResponse({
          items: phase === 1
            ? []
            : [
                {
                  id: id(88),
                  scheduledAt: Date.UTC(2026, 10, 26, 9, 15),
                  startedAt: Date.UTC(2026, 10, 26, 9, 15),
                  timeZone: 'UTC',
                  result: 'SUBMITTED',
                  reason: null,
                  thread: { sessionId, id: thread1Id, title: 'WS 推送的新运行' },
                },
              ],
          olderCursor: null,
        })
      }
      return jsonResponse({}, 404)
    })

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider socketFactory={ws.factory}>
          <RealtimeProbe space={sampleSpace} tree={sampleTree} open={vi.fn()} report={vi.fn()} />
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(ws.sockets.length).toBe(1))
    const socket = ws.sockets[0]!
    act(() => {
      socket.open()
      socket.emitServer({
        type: 'connected',
        eventNodeId: id(90),
        connectionId: id(91),
        heartbeatIntervalMillis: '60000',
      })
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'chat-space', id: spaceId },
        cursor: '0',
      })
    })

    expect(await screen.findByText('WS 同步任务')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '「WS 同步任务」的执行记录' }))
    expect(await screen.findByText('还没有执行记录')).toBeInTheDocument()

    phase = 2
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'chat-space', id: spaceId },
        name: 'schedules',
        data: { scheduleId: schedule1Id },
      })
    })

    expect(await screen.findByText('WS 已更新任务', {}, { timeout: 2000 })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /WS 推送的新运行/ }, { timeout: 2000 })).toBeInTheDocument()
  })
})
