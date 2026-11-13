import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CompactionBranchView } from '@/features/thread/runtime/CompactionBranchView'
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

const INSTRUCTION = '[Compaction mode]\nSummarize the conversation.'

function entry(
  entryId: string,
  entryType: HarnessSessionEntryDTO['entryType'],
  payload: unknown,
  usageCost: HarnessSessionEntryDTO['usageCost'] = null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

function snapshot(status: 'RUNNING' | 'IDLE'): HarnessThreadSnapshotDTO {
  return {
    version: '7',
    baseEntryId: CUT_ID,
    thread: {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'main',
      sessionId: 'session-1',
      headEntryId: 'end',
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
      entry('fork', 'FORK', { mode: 'BRANCH', sourceEntryId: CUT_ID, sourceThreadId: null }),
      entry('instruction', 'CUSTOM_MESSAGE', {
        message: { role: 'USER', contents: [{ type: 'text', text: INSTRUCTION }] },
      }),
      entry('first-call', 'MESSAGE', {
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
      }),
      entry('tool-result', 'MESSAGE', {
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
      }),
      entry('end', 'TURN_END', { outcome: 'FAILED', continueModel: false, reason: 'TURN_FAILED' }),
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
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <CompactionBranchView threadId={CHILD_ID} afterEntryId={CUT_ID} onBack={onBack} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CompactionBranchView', () => {
  beforeEach(() => {
    vi.mocked(harnessService.getThreadSnapshot).mockReset()
    vi.mocked(harnessService.stopThread).mockReset()
  })

  it('loads only the branch entries after the cut and renders the execution facts', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot('IDLE'))

    const { container } = renderBranch()

    await screen.findByTestId('compaction-branch-inherited')
    expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(
      CHILD_ID,
      expect.objectContaining({ afterEntryId: CUT_ID }),
    )
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
