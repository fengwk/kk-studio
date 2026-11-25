import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTextPart, partsToText } from '@/features/thread/composer/composer-parts'
import { loadThreadDraft, saveThreadDraftParts } from '@/features/thread/runtime/state/thread-draft-store'
import { ThreadPaneRenderer } from '@/features/thread/workspace'
import type { PaneTarget } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import * as apiClient from '@/shared/api/client'
import type { HarnessThreadSnapshotDTO, WorkspaceTreeDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'

const THREAD = '11111111-2222-4333-8444-555555555555'
const CHILD_THREAD = '77777777-2222-4333-8444-555555555555'
const SESSION = '22222222-2222-4333-8444-555555555555'
const ROOT_ENTRY = '33333333-2222-4333-8444-555555555555'
const MESSAGE_ENTRY = '66666666-2222-4333-8444-555555555555'
const TURN_END_ENTRY = '88888888-2222-4333-8444-555555555555'

/** 一个已发送过用户消息、当前空闲的 Thread：ArrowUp 可以召回这条消息。 */
function idleThreadWithSentMessage(): HarnessThreadSnapshotDTO {
  const root = {
    entryId: ROOT_ENTRY,
    sessionId: SESSION,
    parentEntryId: null,
    entryType: 'ROOT',
    payload: {},
    createdAt: 0,
    usageCost: null,
  } satisfies HarnessThreadSnapshotDTO['entries'][number]
  return {
    version: '1',
    thread: {
      threadId: THREAD,
      name: 'Thread',
      sessionId: SESSION,
      headEntryId: MESSAGE_ENTRY,
      parentThreadId: null,
      rootThreadId: THREAD,
      yoloEnabled: false,
      nextCommandSequence: '1',
      version: '1',
      status: 'IDLE',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'agent',
        execution: { driver: 'builtin', settings: {} },
        environmentName: null,
      },
      createdAt: 0,
      updatedAt: 0,
    },
    baseEntryId: null,
    entries: [root, {
      ...root,
      entryId: MESSAGE_ENTRY,
      parentEntryId: ROOT_ENTRY,
      entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'text', text: 'sent earlier' }] } },
    }, {
      ...root,
      entryId: TURN_END_ENTRY,
      parentEntryId: MESSAGE_ENTRY,
      entryType: 'TURN_END',
      payload: { reason: 'COMPLETED' },
    }],
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

let sockets: FakeWebSocketHarness
let queryClient: QueryClient

beforeEach(() => {
  sockets = new FakeWebSocketHarness()
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fixture does not use network')))
  vi.spyOn(agentService, 'listAgents').mockResolvedValue([])
  vi.spyOn(agentService, 'listModels').mockResolvedValue([])
  vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(idleThreadWithSentMessage())
})

afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function renderThreadPane({
  threadId = THREAD,
  replace = vi.fn<(next: PaneTarget) => void>(),
  onUnsentChange = vi.fn<(unsent: boolean) => void>(),
  seedSnapshot = true,
}: {
  threadId?: string
  replace?: (next: PaneTarget) => void
  onUnsentChange?: (unsent: boolean) => void
  seedSnapshot?: boolean
} = {}) {
  const noop = () => {}
  if (seedSnapshot) {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), idleThreadWithSentMessage())
  }
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <MemoryRouter>
          <ThreadPaneRenderer
            space={{ id: 'space', title: 'Workspace', version: '1', defaultYoloEnabled: false }}
            target={{ kind: 'thread', threadId }}
            active
            replace={replace}
            open={noop}
            openBeside={noop}
            onUnsentChange={onUnsentChange}
            openSpaceSettings={noop}
          />
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { ...utils, replace, onUnsentChange }
}

function placeCaretAtStart(editor: HTMLElement) {
  const range = document.createRange()
  range.selectNodeContents(editor)
  range.collapse(true)
  window.getSelection()?.removeAllRanges()
  window.getSelection()?.addRange(range)
}

describe('ThreadPaneRenderer', () => {
  it('keeps the stored Thread draft when the user browses sent messages with ArrowUp', async () => {
    // 浏览历史只改变编辑区显示；刷新后应恢复的是用户自己写的草稿，而不是召回的旧消息。
    await saveThreadDraftParts(THREAD, [createTextPart('my unsent draft')], [])
    renderThreadPane()
    const editor = screen.getByRole('textbox')
    await waitFor(() => expect(editor).toHaveTextContent('my unsent draft'))

    placeCaretAtStart(editor)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })

    await waitFor(() => expect(editor).toHaveTextContent('sent earlier'))
    await waitFor(async () =>
      expect(partsToText((await loadThreadDraft(THREAD))?.parts ?? [])).toBe('my unsent draft'),
    )
  })

  it('opens branch and fork TurnCutDialog from TURN_END, toggles debug view, and inspects child thread', async () => {
    const user = userEvent.setup()
    const treeData: WorkspaceTreeDTO = {
      treeVersion: '1',
      folders: [{
        id: 'f1',
        name: 'Root',
        children: [],
        resources: [{
          kind: 'SESSION',
          id: SESSION,
          title: 'My Session',
          threads: [
            { id: THREAD, title: '分支 1', state: 'IDLE', version: '0', lastActivityAt: 0 },
          ],
        }],
      }],
    }
    queryClient.setQueryData(workspaceKeys.tree('space'), treeData)
    queryClient.setQueryData(queryKeys.threads.snapshot(CHILD_THREAD), {
      ...idleThreadWithSentMessage(),
      thread: {
        ...idleThreadWithSentMessage().thread,
        threadId: CHILD_THREAD,
        name: 'Child worker',
        parentThreadId: THREAD,
        rootThreadId: THREAD,
      },
    })
    const treeNodes = [
      {
        threadId: THREAD,
        kind: 'USER' as const,
        parentThreadId: null,
        name: 'Main',
        agentName: 'agent',
        execution: { driver: 'builtin', settings: {} },
        status: 'RUNNING' as const,
        executionControl: 'RUNNABLE' as const,
        turnCount: 1,
        toolCallCount: 0,
        outcome: null,
        updatedAt: 0,
      },
      {
        threadId: CHILD_THREAD,
        kind: 'SUBAGENT' as const,
        parentThreadId: THREAD,
        name: 'Child worker',
        agentName: 'agent',
        execution: { driver: 'builtin', settings: {} },
        status: 'RUNNING' as const,
        executionControl: 'RUNNABLE' as const,
        turnCount: 1,
        toolCallCount: 0,
        outcome: null,
        updatedAt: 0,
      },
    ]
    queryClient.setQueryData(queryKeys.threads.tree(THREAD), treeNodes)
    vi.spyOn(harnessService, 'getThreadTree').mockResolvedValue(treeNodes)
    const reqSpy = vi.spyOn(apiClient, 'request').mockImplementation((async (endpoint: string) => {
      if (endpoint === 'GET /api/platform/environment-summaries') return []
      if (endpoint === 'GET /api/platform/model-summaries') return [{ name: 'm1', type: 'MODEL', variantIds: ['fast'], defaultVariant: 'fast' }]
      if (endpoint === 'POST /api/chat-spaces/{spaceId}/sessions/{id}/branches') return { sessionId: SESSION, threadId: 'new-branch' }
      return {}
    }) as unknown as typeof apiClient.request)

    const replace = vi.fn()
    renderThreadPane({ replace })

    // Open branch dialog -> suggested title increments to 分支 2
    await user.click(await screen.findByRole('button', { name: '从此处分支' }))
    expect(screen.getByLabelText('分支名称')).toHaveValue('分支 2')
    await user.click(screen.getByRole('button', { name: '创建' }))
    await waitFor(() => expect(replace).toHaveBeenCalledWith({ kind: 'thread', threadId: 'new-branch' }))
    await user.click(screen.getByRole('button', { name: '取消' }))

    // Open fork dialog -> suggested title uses session title
    await user.click(screen.getByRole('button', { name: '从此处新建会话' }))
    expect(screen.getByLabelText('会话名称')).toHaveValue('My Session · 分叉')
    await user.click(screen.getByRole('button', { name: '取消' }))

    // Switch to debug mode via command palette button and close debug mode
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    await user.click(screen.getByRole('option', { name: /调试/ }))
    const closeDebugBtn = await screen.findByRole('button', { name: '关闭 Debug' })
    await user.click(closeDebugBtn)
    await waitFor(() => expect(screen.getByRole('textbox')).toBeVisible())

    // Inspect child thread and navigate back
    const childLink = screen.getByRole('link', { name: /Child worker/ })
    await user.click(childLink)
    const backBtn = await screen.findByRole('button', { name: '回到父 Agent' })
    await user.click(backBtn)
    expect(screen.queryByRole('button', { name: '回到父 Agent' })).toBeNull()
    reqSpy.mockRestore()
  })

  it('renders ReadOnlyPane and clears unsent state for non-root or missing threads', async () => {
    const onUnsentChange = vi.fn<(unsent: boolean) => void>()
    vi.spyOn(harnessService, 'getThreadSnapshot').mockRejectedValue(new ApiError('missing', 404, 'NOT_FOUND', undefined, 'NOT_FOUND'))
    vi.spyOn(apiClient, 'request').mockResolvedValue([])

    renderThreadPane({ threadId: 'missing-thread', onUnsentChange, seedSnapshot: false })
    await waitFor(() => expect(onUnsentChange).toHaveBeenCalledWith(false))
    expect(screen.queryByRole('textbox')).toBeNull()
  })
})
