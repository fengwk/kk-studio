import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CompactionBranchView } from '@/features/thread/runtime/panes/CompactionBranchView'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionEntryDTO, HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    stopThread: vi.fn(),
  },
}))

const CHILD_ID = '00000000-0000-4000-8000-000000000010'
const CUT_ID = '00000000-0000-4000-8000-000000000011'
const ROOT_ID = '00000000-0000-4000-8000-000000000012'
const SESSION_ID = '00000000-0000-4000-8000-000000000013'
const ROOT_ENTRY_ID = '00000000-0000-4000-8000-000000000014'
const INSTRUCTION_ID = '00000000-0000-4000-8000-000000000015'
const FIRST_CALL_ID = '00000000-0000-4000-8000-000000000016'
const TOOL_RESULT_ID = '00000000-0000-4000-8000-000000000017'
const END_ID = '00000000-0000-4000-8000-000000000018'

const INSTRUCTION = '[Compaction mode]\nSummarize the conversation.'

function entry(
  entryId: string,
  entryType: HarnessSessionEntryDTO['entryType'],
  payload: unknown,
  usageCost: HarnessSessionEntryDTO['usageCost'] = null,
  parentEntryId: string | null = null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: SESSION_ID,
    parentEntryId,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

function snapshot(status: 'RUNNING' | 'IDLE'): HarnessThreadSnapshotDTO {
  return {
    version: '7',
    baseEntryId: null,
    thread: {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'main',
      sessionId: SESSION_ID,
      headEntryId: END_ID,
      rootThreadId: ROOT_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: '7',
      status,
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'coder',
        execution: { driver: 'builtin', settings: { modelName: 'gpt', variant: 'high' } },
        environmentName: null,
      },
      createdAt: 1767225600000,
      updatedAt: 1767225600000,
    },
    entries: [
      entry(ROOT_ENTRY_ID, 'ROOT', {}, null, null),
      entry(CUT_ID, 'FORK', { mode: 'BRANCH', sourceEntryId: ROOT_ENTRY_ID, sourceThreadId: ROOT_ID }, null, ROOT_ENTRY_ID),
      entry(INSTRUCTION_ID, 'CUSTOM_MESSAGE', {
        message: { role: 'USER', contents: [{ type: 'text', text: INSTRUCTION }] },
      }, null, CUT_ID),
      entry(FIRST_CALL_ID, 'MESSAGE', {
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'draft' }] },
        assistantMetadata: {
          stopReason: 'COMPLETE',
          usage: {
            inputTokens: 100,
            outputTokens: 20,
            cacheReadTokens: 40,
            cacheWriteTokens: 10,
            reasoningTokens: 5,
            providerTotalTokens: 120,
          },
        },
      }, null, INSTRUCTION_ID),
      entry(TOOL_RESULT_ID, 'MESSAGE', {
        message: {
          role: 'TOOL',
          contents: [{
            type: 'tool_result',
            toolCallId: 'call-1',
            toolName: 'read',
            rendererKey: 'read',
            error: true,
            contents: [{ type: 'text', text: 'Tools are disabled in compaction mode.' }],
          }],
        },
      }, null, FIRST_CALL_ID),
      entry(END_ID, 'TURN_END', { outcome: 'FAILED', continueModel: false, reason: 'TURN_FAILED' }, null, TOOL_RESULT_ID),
    ],
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

function renderBranch(onBack?: () => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  return render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <MemoryRouter>
          <CompactionBranchView threadId={CHILD_ID} afterEntryId={CUT_ID} onBack={onBack} />
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
}

describe('CompactionBranchView', () => {
  beforeEach(() => {
    vi.mocked(harnessService.getThreadSnapshot).mockReset()
    vi.mocked(harnessService.stopThread).mockReset()
  })

  it('projects entries after the cut from the shared full Thread snapshot', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot('IDLE'))

    const { container } = renderBranch()

    await screen.findByTestId('compaction-branch-inherited')
    const [, options] = vi.mocked(harnessService.getThreadSnapshot).mock.calls[0] ?? []
    expect(options?.afterEntryId).toBeUndefined()
    // 继承量、指令、每次调用的用量与停用的工具结果都来自分支自己的 Entry。
    expect(screen.getByTestId('compaction-branch-inherited')).toHaveTextContent('继承的上下文：150 tokens')
    expect(container.querySelector('.compaction-branch-instruction')?.textContent).toBe(INSTRUCTION)
    expect(container.querySelector('.compaction-branch-call')).not.toBeNull()
    expect(screen.getByText('read')).toBeInTheDocument()
    expect(screen.getByText('Tools are disabled in compaction mode.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '停止' })).toBeNull()
  })

  it('offers Stop only while the branch is running and sends the snapshot version', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot('RUNNING'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: snapshot('IDLE').thread,
      stoppedThreads: [],
    })

    renderBranch()

    const stop = await screen.findByRole('button', { name: '停止' })
    fireEvent.click(stop)

    await waitFor(() => expect(harnessService.stopThread).toHaveBeenCalledTimes(1))
    expect(harnessService.stopThread).toHaveBeenCalledWith(
      CHILD_ID,
      expect.objectContaining({ expectedVersion: '7' }),
    )
  })

  it('returns to the previous pane layer without loading a parent link', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot('IDLE'))
    const onBack = vi.fn()

    renderBranch(onBack)

    fireEvent.click(await screen.findByRole('button', { name: '返回主对话' }))
    expect(onBack).toHaveBeenCalledTimes(1)
  })
})
