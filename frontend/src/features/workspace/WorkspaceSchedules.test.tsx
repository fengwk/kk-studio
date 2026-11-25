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
import { workspaceKeys } from './queries'
import {
  buildTargetFromDraft,
  buildTimeZoneOptions,
  buildTriggerFromDraft,
  extractTurnCuts,
  flattenFolders,
  formatScheduleFullTime,
  formatScheduleNextRun,
  formatScheduleRunTime,
  getLocalTimeZone,
  INITIAL_HISTORY_CURSORS,
  listTreeSessions,
  popNewerCursor,
  pushOlderCursor,
  resolveRunReasonText,
  todayInTimeZone,
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
    expect(formatScheduleRunTime(ts, 'Asia/Shanghai', 'en-US')).toBe('2026-11-26 Thu 09:00')
    expect(formatScheduleFullTime(ts, 'Asia/Shanghai', 'en-US')).toBe('2026-11-26 09:00 (Asia/Shanghai)')
    expect(formatScheduleNextRun(ts, 'Invalid/Zone', 'en-US')).toBe('2026-11-26 01:00')
    expect(formatScheduleRunTime(ts, 'Invalid/Zone', 'en-US')).toBe('2026-11-26 01:00')
    expect(formatScheduleFullTime(ts, 'Invalid/Zone', 'en-US')).toContain('(Invalid/Zone)')
    expect(todayInTimeZone('UTC', ts)).toBe('2026-11-26')
    expect(todayInTimeZone('Invalid/Zone', ts)).toBe('2026-11-26')
    expect(getLocalTimeZone()).toBeTruthy()
    expect(buildTimeZoneOptions('UTC', 'Europe/Paris')).toContain('Europe/Paris')
    expect(popNewerCursor(INITIAL_HISTORY_CURSORS)).toEqual(INITIAL_HISTORY_CURSORS)

    // extractTurnCuts handles CUSTOM_MESSAGE, truncation >35 chars, and TURN_END without TURN_START
    const longText = 'x'.repeat(40)
    expect(extractTurnCuts([
      {
        entryId: cut1Id,
        sessionId,
        parentEntryId: null,
        entryType: 'CUSTOM_MESSAGE',
        payload: { message: { role: 'USER', contents: [{ type: 'TEXT', text: longText }] } },
        createdAt: 1,
        usageCost: null,
      },
      {
        entryId: cut2Id,
        sessionId,
        parentEntryId: cut1Id,
        entryType: 'TURN_END',
        payload: { outcome: 'COMPLETED' },
        createdAt: 2,
        usageCost: null,
      },
    ])).toEqual([
      { entryId: cut2Id, turnNumber: 1, summary: `${'x'.repeat(35)}…` },
    ])

    // buildTriggerFromDraft covers ONCE & all REPEAT frequencies & invalid fields
    const baseTrigger = {
      triggerType: 'REPEAT' as const,
      frequency: 'DAILY' as const,
      weekday: '',
      repeatTime: '09:00',
      minute: '15',
      cron: '0 9 * * 1-5',
      onceDate: '2026-12-01',
      onceTime: '10:30',
    }
    expect(buildTriggerFromDraft({ ...baseTrigger, triggerType: 'ONCE', onceDate: 'bad' })).toEqual({ ok: false, field: 'onceDate', errorKey: 'workspace.schedule.dateRequired' })
    expect(buildTriggerFromDraft({ ...baseTrigger, triggerType: 'ONCE', onceTime: 'bad' })).toEqual({ ok: false, field: 'onceTime', errorKey: 'workspace.schedule.timeRequired' })
    expect(buildTriggerFromDraft({ ...baseTrigger, triggerType: 'ONCE' })).toEqual({ ok: true, trigger: { type: 'ONCE', localDate: '2026-12-01', time: '10:30' } })
    expect(buildTriggerFromDraft({ ...baseTrigger, repeatTime: '' })).toEqual({ ok: false, field: 'repeatTime', errorKey: 'workspace.schedule.timeRequired' })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'WEEKDAYS' })).toEqual({ ok: true, trigger: { type: 'REPEAT', frequency: 'WEEKDAYS', time: '09:00' } })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'WEEKLY', repeatTime: '' })).toEqual({ ok: false, field: 'repeatTime', errorKey: 'workspace.schedule.timeRequired' })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'WEEKLY' })).toEqual({ ok: true, trigger: { type: 'REPEAT', frequency: 'WEEKLY', weekday: 'MONDAY', time: '09:00' } })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'HOURLY', minute: 'abc' })).toEqual({ ok: false, field: 'minute', errorKey: 'workspace.schedule.minuteInvalid' })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'HOURLY', minute: '60' })).toEqual({ ok: false, field: 'minute', errorKey: 'workspace.schedule.minuteInvalid' })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'HOURLY', minute: '5' })).toEqual({ ok: true, trigger: { type: 'REPEAT', frequency: 'HOURLY', minute: 5 } })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'CRON', cron: '0 9 *' })).toEqual({ ok: false, field: 'cron', errorKey: 'workspace.schedule.cronRequired' })
    expect(buildTriggerFromDraft({ ...baseTrigger, frequency: 'CRON', cron: '0  9  *  *  1-5' })).toEqual({ ok: true, trigger: { type: 'REPEAT', frequency: 'CRON', cron: '0 9 * * 1-5' } })

    // buildTargetFromDraft covers all 3 target types and missing fields
    const baseTarget = {
      targetType: 'NEW_SESSION' as const,
      folderId: rootFolderId,
      agentName: 'Coder',
      sessionId,
      sourceThreadId: thread1Id,
      turnEndEntryId: cut1Id,
      threadId: thread1Id,
    }
    expect(buildTargetFromDraft({ ...baseTarget, folderId: '' })).toEqual({ ok: false, field: 'folderId', errorKey: 'workspace.schedule.folderRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, agentName: '' })).toEqual({ ok: false, field: 'agentName', errorKey: 'workspace.schedule.agentRequired' })
    expect(buildTargetFromDraft(baseTarget)).toEqual({ ok: true, target: { type: 'NEW_SESSION', folderId: rootFolderId, agentName: 'Coder' } })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'BRANCH', sessionId: '' })).toEqual({ ok: false, field: 'sessionId', errorKey: 'workspace.schedule.sessionRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'BRANCH', sourceThreadId: '' })).toEqual({ ok: false, field: 'sourceThreadId', errorKey: 'workspace.schedule.threadRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'BRANCH', turnEndEntryId: '' })).toEqual({ ok: false, field: 'turnEndEntryId', errorKey: 'workspace.schedule.turnEndRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'BRANCH' })).toEqual({ ok: true, target: { type: 'BRANCH', sessionId, sourceThreadId: thread1Id, turnEndEntryId: cut1Id } })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'CONTINUE', sessionId: '' })).toEqual({ ok: false, field: 'sessionId', errorKey: 'workspace.schedule.sessionRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'CONTINUE', threadId: '' })).toEqual({ ok: false, field: 'threadId', errorKey: 'workspace.schedule.threadRequired' })
    expect(buildTargetFromDraft({ ...baseTarget, targetType: 'CONTINUE' })).toEqual({ ok: true, target: { type: 'CONTINUE', sessionId, threadId: thread1Id } })

    // resolveRunReasonText covers all known reasons + custom + default fallbacks
    const tr = (k: string) => k
    expect(resolveRunReasonText('SKIPPED', 'EXPIRED', tr)).toBe('workspace.schedule.reasonExpired')
    expect(resolveRunReasonText('SKIPPED', 'EXECUTION_NOT_SETTLED', tr)).toBe('workspace.schedule.reasonNotSettled')
    expect(resolveRunReasonText('NOT_SUBMITTED', 'RESOURCE_NOT_FOUND', tr)).toBe('workspace.schedule.reasonTargetUnavailable')
    expect(resolveRunReasonText('NOT_SUBMITTED', 'INVALID_TURN_END', tr)).toBe('workspace.schedule.reasonTargetUnavailable')
    expect(resolveRunReasonText('NOT_SUBMITTED', 'CUSTOM_REASON', tr)).toBe('CUSTOM_REASON')
    expect(resolveRunReasonText('SKIPPED', '', tr)).toBe('workspace.schedule.skipped')
    expect(resolveRunReasonText('NOT_SUBMITTED', null, tr)).toBe('workspace.schedule.notSubmitted')
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

  it('creates schedule across modes, surfaces preview/submit errors, and handles list/history/action failures', async () => {
    const created: ScheduleDTO[] = []
    let previewBehavior: 'ok' | 'empty' | 'bad_cron' | 'server_err' = 'ok'
    let createBehavior: 'invalid_turn' | 'invalid_sched' | 'generic' | 'ok' = 'invalid_turn'

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'GET') {
        return jsonResponse(created)
      }
      if (url.pathname === '/api/platform/agent-summaries' && method === 'GET') {
        return jsonResponse([{ name: 'Coder', description: '' }, { name: 'Reviewer', description: '' }])
      }
      if (url.pathname === `/api/harness/threads/${thread1Id}` && method === 'GET') {
        return jsonResponse({
          version: '1',
          thread: {
            threadId: thread1Id, parentThreadId: null, name: 'main', sessionId, headEntryId: cut2Id,
            rootThreadId: thread1Id, yoloEnabled: false, nextCommandSequence: '1', version: '1',
            status: 'IDLE', executionControl: 'IDLE', branchSettings: { agentName: 'Coder', environmentId: null, yoloEnabled: false },
            createdAt: 1, updatedAt: 2,
          },
          baseEntryId: null, entries: createHarnessEntries(), queuedCommands: [], activeAssistantOutput: null,
          generationFailures: [], toolInvocations: [], stopReceipts: [], unconfirmedExecutions: [],
        })
      }
      if (url.pathname === `/api/harness/threads/${thread2Id}` && method === 'GET') {
        return jsonResponse({
          version: '1',
          thread: {
            threadId: thread2Id, parentThreadId: null, name: 'alt', sessionId, headEntryId: cut1Id,
            rootThreadId: thread2Id, yoloEnabled: false, nextCommandSequence: '1', version: '1',
            status: 'IDLE', executionControl: 'IDLE', branchSettings: { agentName: 'Coder', environmentId: null, yoloEnabled: false },
            createdAt: 1, updatedAt: 2,
          },
          baseEntryId: null, entries: createHarnessEntries().slice(0, 3), queuedCommands: [], activeAssistantOutput: null,
          generationFailures: [], toolInvocations: [], stopReceipts: [], unconfirmedExecutions: [],
        })
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/preview` && method === 'POST') {
        if (previewBehavior === 'empty') return jsonResponse({ next: [] })
        if (previewBehavior === 'bad_cron') return jsonResponse({ reason: 'INVALID_SCHEDULE', detail: 'bad' }, 400)
        if (previewBehavior === 'server_err') return jsonResponse({ detail: 'boom' }, 500)
        return jsonResponse({ next: [Date.UTC(2026, 11, 1, 9, 0)] })
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'POST') {
        if (createBehavior === 'invalid_turn') {
          createBehavior = 'invalid_sched'
          return jsonResponse({ reason: 'INVALID_TURN_END', detail: 'bad turn' }, 400)
        }
        if (createBehavior === 'invalid_sched') {
          createBehavior = 'generic'
          return jsonResponse({ reason: 'INVALID_SCHEDULE', detail: 'bad schedule detail' }, 400)
        }
        if (createBehavior === 'generic') {
          createBehavior = 'ok'
          return jsonResponse({ detail: 'server exploded' }, 500)
        }
        const body = JSON.parse(String(init?.body)) as { title: string; message: string; trigger: ScheduleDTO['trigger']; timeZone: string; target: ScheduleDTO['target'] }
        const item: ScheduleDTO = {
          id: schedule1Id,
          title: body.title,
          message: body.message,
          enabled: true,
          trigger: body.trigger,
          timeZone: body.timeZone,
          target: body.target,
          nextDueAt: Date.now() + 60_000,
          version: '1',
        }
        created.push(item)
        return jsonResponse(item, 201)
      }
      return jsonResponse({}, 404)
    })

    const report = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <WorkspaceSchedules space={sampleSpace} tree={sampleTree} open={vi.fn()} report={report} />
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '新建定时任务' }))
    const dialog = await screen.findByRole('dialog', { name: '新建定时任务' })

    // Switch target to CONTINUE and BRANCH, then back to NEW_SESSION
    fireEvent.click(within(dialog).getByLabelText('继续 Thread'))
    fireEvent.click(within(dialog).getByLabelText('从某一轮分支'))
    await waitFor(() => expect(within(dialog).getByLabelText(/回合切点/)).toHaveTextContent(/第 1 轮/))

    // Switch frequency to HOURLY, CRON, WEEKLY, and mode to ONCE
    fireEvent.click(within(dialog).getByRole('button', { name: '重复' }))
    fireEvent.click(within(dialog).getByLabelText(/频率/))
    fireEvent.click(await screen.findByRole('option', { name: '每小时' }))
    fireEvent.change(within(dialog).getByLabelText(/每小时的第几分钟/), { target: { value: '25' } })

    fireEvent.click(within(dialog).getByLabelText(/频率/))
    fireEvent.click(await screen.findByRole('option', { name: '每周' }))
    expect(within(dialog).getByLabelText(/星期/)).toHaveTextContent('周一')

    previewBehavior = 'bad_cron'
    fireEvent.click(within(dialog).getByLabelText(/频率/))
    fireEvent.click(await screen.findByRole('option', { name: '自定义 cron' }))
    expect(await within(dialog).findByText('cron 表达式无效或近期不会运行。')).toBeInTheDocument()

    previewBehavior = 'empty'
    fireEvent.click(within(dialog).getByRole('button', { name: '一次' }))
    expect(await within(dialog).findByText('这个时间已经过去，请选之后的时间。')).toBeInTheDocument()

    previewBehavior = 'server_err'
    fireEvent.click(within(dialog).getByRole('button', { name: '重复' }))
    fireEvent.click(within(dialog).getByLabelText(/频率/))
    fireEvent.click(await screen.findByRole('option', { name: '每天' }))
    expect(await within(dialog).findByText('请求失败')).toBeInTheDocument()

    // Fill valid title/message with preview ok (switch to WEEKDAYS to trigger fresh preview) and exercise submit error branches
    previewBehavior = 'ok'
    fireEvent.click(within(dialog).getByLabelText(/频率/))
    fireEvent.click(await screen.findByRole('option', { name: '工作日（周一至周五）' }))
    fireEvent.click(within(dialog).getByLabelText('新建会话'))
    fireEvent.change(within(dialog).getByLabelText(/名称/), { target: { value: 'Daily Summary' } })
    fireEvent.change(within(dialog).getByLabelText(/发送的消息/), { target: { value: 'Summarize updates' } })
    expect(await within(dialog).findByText(/接下来：/)).toBeInTheDocument()

    // Submit 1 -> INVALID_TURN_END (switches to BRANCH momentarily? No, dialog renders error for turnEndEntryId or alert)
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    // Wait for second submit -> INVALID_SCHEDULE
    await waitFor(() => expect(createBehavior).toBe('invalid_sched'))
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('bad schedule detail')

    // Submit 3 -> 500 error
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('server exploded')

    // Submit 4 -> succeeds
    fireEvent.click(within(dialog).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建定时任务' })).toBeNull())
    expect(await screen.findByText('Daily Summary')).toBeInTheDocument()
  })

  it('reports toggle, runNow, delete conflict, and list/history fetch errors', async () => {
    const schedule: ScheduleDTO = {
      id: schedule1Id,
      title: 'Err Task',
      message: 'msg',
      enabled: true,
      trigger: { type: 'REPEAT', frequency: 'DAILY', time: '09:00' },
      timeZone: 'UTC',
      target: { type: 'NEW_SESSION', folderId: rootFolderId, agentName: 'Coder' },
      nextDueAt: Date.now() + 60_000,
      version: '1',
    }
    let listFails = false

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'GET') {
        if (listFails) return jsonResponse({ detail: 'down' }, 500)
        return jsonResponse([schedule])
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}` && method === 'PATCH') {
        return jsonResponse({ code: 'VERSION_CONFLICT', reason: 'VERSION_CONFLICT', detail: 'stale' }, 409)
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}/runs` && method === 'POST') {
        return jsonResponse({ detail: 'run failed' }, 500)
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}/runs` && method === 'GET') {
        return jsonResponse({ detail: 'history failed' }, 500)
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/${schedule1Id}` && method === 'DELETE') {
        return jsonResponse({ code: 'VERSION_CONFLICT', reason: 'VERSION_CONFLICT', detail: 'stale' }, 409)
      }
      if (url.pathname === '/api/platform/agent-summaries') {
        return jsonResponse([{ name: 'Coder', description: '' }])
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/preview`) {
        return jsonResponse({ next: [Date.UTC(2026, 11, 1, 9, 0)] })
      }
      return jsonResponse({}, 404)
    })

    const report = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <WorkspaceSchedules space={sampleSpace} tree={sampleTree} open={vi.fn()} report={report} />
      </QueryClientProvider>,
    )

    const sw = await screen.findByRole('switch', { name: '启用「Err Task」' })
    fireEvent.click(sw)
    await waitFor(() => expect(report).toHaveBeenCalledWith('工作区已变更，请检查后重试。'))

    fireEvent.click(screen.getByRole('button', { name: 'Err Task的操作' }))
    fireEvent.click(screen.getByRole('menuitem', { name: '立即运行' }))
    await waitFor(() => expect(report).toHaveBeenCalledWith('run failed'))

    fireEvent.click(screen.getByRole('button', { name: '「Err Task」的执行记录' }))
    expect(await screen.findByText('请求失败')).toBeInTheDocument()

    // Delete 409 shows conflict error inside confirm modal
    fireEvent.click(screen.getByRole('button', { name: 'Err Task的操作' }))
    fireEvent.click(screen.getByRole('menuitem', { name: '编辑' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: '编辑定时任务' })).getByRole('button', { name: '删除任务' }))
    const modal = await screen.findByRole('alertdialog')
    fireEvent.click(within(modal).getByRole('button', { name: '删除任务' }))
    expect(await within(modal).findByText('工作区已变更，请检查后重试。')).toBeInTheDocument()
    fireEvent.click(within(modal).getByRole('button', { name: '取消' }))

    // List fetch error shows unavailable
    listFails = true
    await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(spaceId) })
    expect(await screen.findByText('资源不可用')).toBeInTheDocument()
  })
})
