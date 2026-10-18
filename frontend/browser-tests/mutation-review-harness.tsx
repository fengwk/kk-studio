import { useCallback, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import '@/features/ai/runtime/thread-panel/messages/tool-card.css'
import '@/features/ai/runtime/interactions/interaction-cards.css'
import { setLocale } from '@/shared/i18n'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { ApprovalCard, type ApprovalCardProps } from '@/features/ai/runtime/interactions/ApprovalCard'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { LONG_WRITE_CONTENT, MULTI_HUNK_DIFF, VALID_SHA } from './mutation-review-fixtures'

setLocale('zh-CN')

interface HarnessState {
  scenario: string
  toolBlock?: {
    message: ToolDialogueMessage
    result?: ToolDialogueMessage
  } | null
  approvalCard?: ApprovalCardProps | null
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false },
  },
})

let updateState: (state: HarnessState) => void = () => {}

export function MutationReviewHarnessApp() {
  const [state, setState] = useState<HarnessState>({
    scenario: 'init',
  })

  const release = useCallback((next: HarnessState) => {
    setState(next)
  }, [])

  useEffect(() => {
    updateState = release
    return () => {
      updateState = () => {}
    }
  }, [release])

  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <div className="mutation-harness-root" data-testid="mutation-harness-root">
          <div className="mutation-harness-bar">
            <span>Mutation Review Harness (Active Scenario: </span>
            <strong data-testid="active-scenario">{state.scenario}</strong>
            <span>)</span>
          </div>

          <div className="mutation-harness-content">
            {state.toolBlock ? (
              <div className="mutation-pane" data-testid="tool-message-pane">
                <ToolMessageBlock
                  message={state.toolBlock.message}
                  result={state.toolBlock.result}
                />
              </div>
            ) : null}

            {state.approvalCard ? (
              <div className="mutation-approval-pane" data-testid="approval-card-pane">
                <ApprovalCard {...state.approvalCard} />
              </div>
            ) : null}
          </div>
        </div>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const mutationReviewHarness = {
  renderWritePending() {
    const rawArgs = JSON.stringify({
      path: '/workspace/src/LongService.java',
      workdir: '/workspace/root',
      content: LONG_WRITE_CONTENT,
    })
    const rawApproval = JSON.stringify({
      required: true,
      decision: null,
      decisionId: null,
      reason: 'Permission rules require approval',
    })

    const callMsg: ToolDialogueMessage = {
      id: 'call-write-pending',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-pending',
      toolName: 'write',
      rendererKey: 'write',
      arguments: rawArgs,
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: 'Permission rules require approval',
      },
    }

    updateState({
      scenario: 'write-pending',
      toolBlock: { message: callMsg },
      approvalCard: {
        threadId: 'thread-1',
        invocationId: 'inv-write-1',
        toolName: 'write',
        argumentsJson: rawArgs,
        approvalJson: rawApproval,
      },
    })
  },

  renderWriteSuccess() {
    const rawDuplicateBody = `File /workspace/src/LongService.java written successfully:\n${LONG_WRITE_CONTENT}`
    const callMsg: ToolDialogueMessage = {
      id: 'call-write-success',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-success',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({
        path: '/workspace/src/LongService.java',
        workdir: '/workspace/root',
        content: LONG_WRITE_CONTENT,
      }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'SUCCEEDED',
    }
    const resultMsg: ToolDialogueMessage = {
      id: 'result-write-success',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-success',
      toolName: 'write',
      rendererKey: 'write',
      arguments: callMsg.arguments,
      contents: [{ type: 'text', text: rawDuplicateBody }],
      createdAt: '2026-10-19T00:00:01Z',
      status: 'done',
    }

    updateState({
      scenario: 'write-success',
      toolBlock: { message: callMsg, result: resultMsg },
      approvalCard: null,
    })
  },

  renderEditStreaming() {
    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-stream',
      role: 'tool',
      phase: 'call',
      subjectEntryId: null,
      toolCallId: 'call-edit-stream',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: '/src/App.tsx',
        old_string: 'const oldVal = 1;',
        new_string: 'const newVal = 2;',
      }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'streaming',
    }

    updateState({
      scenario: 'edit-streaming',
      toolBlock: { message: callMsg },
      approvalCard: null,
    })
  },

  renderEditPreparing() {
    const rawArgs = JSON.stringify({
      path: '/src/App.tsx',
      old_string: 'const a = 1;',
      new_string: 'const a = 100;',
    })
    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-preparing',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-preparing',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: rawArgs,
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: null,
      },
    }

    updateState({
      scenario: 'edit-preparing',
      toolBlock: { message: callMsg },
      approvalCard: null,
    })
  },

  renderEditReviewed() {
    const rawArgs = JSON.stringify({
      path: '/src/App.tsx',
      old_string: 'const a = 1;\nconst b = 2;',
      new_string: 'const a = 100;\nconst b = 200;',
    })
    const reviewData = {
      path: '/canonical/src/App.tsx',
      diff: MULTI_HUNK_DIFF,
      sha256: VALID_SHA,
    }
    const rawApproval = JSON.stringify({
      required: true,
      decision: null,
      decisionId: null,
      reason: null,
      editReview: reviewData,
    })

    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-reviewed',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-reviewed',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: rawArgs,
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: reviewData,
      },
    }

    updateState({
      scenario: 'edit-reviewed',
      toolBlock: { message: callMsg },
      approvalCard: {
        threadId: 'thread-1',
        invocationId: 'inv-edit-1',
        toolName: 'edit',
        argumentsJson: rawArgs,
        approvalJson: rawApproval,
      },
    })
  },

  renderEditConflict() {
    const approvalReview = {
      path: '/canonical/Conflict.tsx',
      diff: '-approved line\n+approved line',
      sha256: VALID_SHA,
    }
    const resultReview = {
      path: '/canonical/Conflict.tsx',
      diff: '-conflicting result line\n+conflicting result line',
      sha256: VALID_SHA,
    }

    const callMsg: ToolDialogueMessage = {
      id: 'call-conflict',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-conflict',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({ path: '/canonical/Conflict.tsx' }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'SUCCEEDED',
      approval: {
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-1',
        reason: null,
        editReview: approvalReview,
      },
      editReview: approvalReview,
    }

    const resultMsg: ToolDialogueMessage = {
      id: 'result-conflict',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-conflict',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({ path: '/canonical/Conflict.tsx' }),
      contents: [],
      createdAt: '2026-10-19T00:00:01Z',
      status: 'done',
      editReview: resultReview,
    }

    updateState({
      scenario: 'edit-conflict',
      toolBlock: { message: callMsg, result: resultMsg },
      approvalCard: null,
    })
  },

  renderEditSuccess() {
    const rawDuplicateBody = `File edit executed successfully:\n${MULTI_HUNK_DIFF}`
    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-success',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-success',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({ path: '/src/App.tsx' }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'SUCCEEDED',
      editReview: {
        path: '/canonical/src/App.tsx',
        diff: MULTI_HUNK_DIFF,
        sha256: VALID_SHA,
      },
    }
    const resultMsg: ToolDialogueMessage = {
      id: 'result-edit-success',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-success',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: callMsg.arguments,
      contents: [{ type: 'text', text: rawDuplicateBody }],
      createdAt: '2026-10-19T00:00:01Z',
      status: 'done',
    }

    updateState({
      scenario: 'edit-success',
      toolBlock: { message: callMsg, result: resultMsg },
      approvalCard: null,
    })
  },

  renderMutationFailure(tool: 'write' | 'edit' = 'edit') {
    const failureError = 'Permission denied: /protected/config.ts'
    const isWrite = tool === 'write'
    const callMsg: ToolDialogueMessage = {
      id: 'call-mutation-failure',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-mutation-failure',
      toolName: tool,
      rendererKey: tool,
      arguments: JSON.stringify({
        path: '/protected/config.ts',
        ...(isWrite ? { content: 'test content' } : { old_string: 'a', new_string: 'b' }),
      }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'error',
      invocationStatus: 'FAILED',
    }
    const resultMsg: ToolDialogueMessage = {
      id: 'result-mutation-failure',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-mutation-failure',
      toolName: tool,
      rendererKey: tool,
      arguments: callMsg.arguments,
      contents: [{ type: 'text', text: failureError }],
      errorMessage: failureError,
      createdAt: '2026-10-19T00:00:01Z',
      status: 'error',
    }

    updateState({
      scenario: 'mutation-failure',
      toolBlock: { message: callMsg, result: resultMsg },
      approvalCard: null,
    })
  },

  renderApprovalMissingReview() {
    updateState({
      scenario: 'approval-missing-review',
      toolBlock: null,
      approvalCard: {
        threadId: 'thread-1',
        invocationId: 'inv-missing-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' }),
        approvalJson: JSON.stringify({ required: true }),
      },
    })
  },

  renderApprovalMalformedReview() {
    updateState({
      scenario: 'approval-malformed-review',
      toolBlock: null,
      approvalCard: {
        threadId: 'thread-1',
        invocationId: 'inv-malformed-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' }),
        approvalJson: JSON.stringify({
          required: true,
          editReview: {
            path: '/src/App.tsx',
            diff: '+b',
            sha256: 'invalid-sha-characters',
          },
        }),
      },
    })
  },

  renderApprovalValid() {
    updateState({
      scenario: 'approval-valid',
      toolBlock: null,
      approvalCard: {
        threadId: 'thread-1',
        invocationId: 'inv-valid-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' }),
        approvalJson: JSON.stringify({
          required: true,
          editReview: {
            path: '/canonical/src/App.tsx',
            diff: '-a\n+b',
            sha256: VALID_SHA,
          },
        }),
      },
    })
  },
}

declare global {
  interface Window {
    mutationReviewHarness: typeof mutationReviewHarness
  }
}

window.mutationReviewHarness = mutationReviewHarness

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<MutationReviewHarnessApp />)
}
