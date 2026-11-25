import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTextPart, partsToText } from '@/features/thread/composer/composer-parts'
import { loadThreadDraft, saveThreadDraftParts } from '@/features/thread/runtime/state/thread-draft-store'
import { ThreadPaneRenderer } from '@/features/thread/workspace'
import { agentService } from '@/shared/api/agent-service'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'

const THREAD = '11111111-2222-4333-8444-555555555555'
const SESSION = '22222222-2222-4333-8444-555555555555'
const ROOT_ENTRY = '33333333-2222-4333-8444-555555555555'
const MESSAGE_ENTRY = '66666666-2222-4333-8444-555555555555'

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

function renderThreadPane() {
  const noop = () => {}
  queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), idleThreadWithSentMessage())
  return render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <MemoryRouter>
          <ThreadPaneRenderer
            space={{ id: 'space', title: 'Workspace', version: '1', defaultYoloEnabled: false }}
            target={{ kind: 'thread', threadId: THREAD }}
            active
            replace={noop}
            open={noop}
            openBeside={noop}
            onUnsentChange={noop}
            openSpaceSettings={noop}
          />
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
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
})
