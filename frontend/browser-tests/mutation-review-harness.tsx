import { useCallback, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import '@/features/thread/runtime/thread-panel/messages/tool-card.css'
import '@/features/thread/runtime/interactions/interaction-cards.css'
import { ApplicationEventProvider } from '@/shared/app-events/context'
import { setLocale, useI18n } from '@/shared/i18n'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import {
  ApprovalReviewProvider,
  visibleApprovalInvocationIds,
} from '@/features/thread/runtime/interactions/approval-review-context'
import type { InteractionApproval } from '@/shared/api/generated/contracts'
import type { ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline/thread-timeline'
import { LONG_WRITE_CONTENT, MULTI_HUNK_DIFF, VALID_SHA } from './mutation-review-fixtures'

setLocale('zh-CN')

/** 审批卡入口参数：与根控制区/待处理页一致，由交互主体包装成真实 DTO。 */
interface HarnessApproval {
  threadId: string
  invocationId: string
  toolName: string
  argumentsJson?: string
  approval?: unknown
  queuedSameFileCount?: number
}

interface HarnessState {
  scenario: string
  toolBlock?: {
    message: ToolDialogueMessage
    result?: ToolDialogueMessage
  } | null
  approval?: HarnessApproval | null
}

/** 审批条目必须走真实渲染入口：它挂载时才把该调用登记为唯一审查位。 */
function approvalItem(props: HarnessApproval): InteractionApproval {
  return {
    type: 'APPROVAL',
    toolInvocationId: props.invocationId,
    threadId: props.threadId,
    sessionId: 'sess-mutation-harness',
    rootThreadId: props.threadId,
    owner: { kind: 'CHAT', id: 'chat-mutation-harness', title: 'Mutation review harness' },
    toolCallId: props.invocationId,
    toolName: props.toolName,
    argumentsJson: props.argumentsJson ?? '',
    approval: props.approval,
    queuedSameFileCount: props.queuedSameFileCount ?? 0,
    createdAt: Date.parse('2026-10-19T00:00:00Z'),
  }
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
  const { locale } = useI18n()
  // 与执行根 pane 一致：审批条目的调用 ID 就是时间线让出 diff 的依据。
  const approval = useMemo(
    () => (state.approval == null ? null : approvalItem(state.approval)),
    [state.approval],
  )
  const approvalIds = useMemo(
    () => (approval == null ? [] : visibleApprovalInvocationIds([approval])),
    [approval],
  )

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
            <span>)  locale: </span>
            <strong data-testid="active-locale">{locale}</strong>
          </div>

          <ApprovalReviewProvider toolInvocationIds={approvalIds}>
            <div className="mutation-harness-content">
              {state.toolBlock ? (
                <div className="mutation-pane" data-testid="tool-message-pane">
                  <ToolMessageBlock
                    key={state.toolBlock.message.callIdentity ?? state.toolBlock.message.toolCallId}
                    message={state.toolBlock.message}
                    result={state.toolBlock.result}
                  />
                </div>
              ) : null}

              {approval ? (
                <div className="mutation-approval-pane" data-testid="approval-card-pane">
                  <InteractionCardBody item={approval} />
                </div>
              ) : null}
            </div>
          </ApprovalReviewProvider>
        </div>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const mutationReviewHarness = {
  /** 自查截图：切换中英文案，保持当前组件重新渲染。 */
  setLocale(locale: 'zh-CN' | 'en-US') {
    setLocale(locale)
  },
  /** 待审批 write（没有审批卡可见）：只读记录继续展示待写正文短窗口。 */
  renderWritePending() {
    const rawArgs = JSON.stringify({
      path: '/workspace/src/LongService.java',
      workdir: '/workspace/root',
      content: LONG_WRITE_CONTENT,
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationId: 'inv-write-pending',
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
      approval: null,
    })
  },

  /** 待审批 write（审批卡可见）：冻结材料只在审批卡里出现一次。 */
  renderWriteReviewedPending() {
    const rawArgs = JSON.stringify({
      path: '/workspace/src/LongService.java',
      workdir: '/workspace/root',
      content: LONG_WRITE_CONTENT,
    })
    const review = {
      path: '/workspace/src/LongService.java',
      diff: LONG_WRITE_CONTENT.split('\n').slice(0, -1)
        .map((line, index) => `+${index + 1}|${line}`).join('\n'),
      sha256: null,
    }
    const approval = {
      required: true,
      decision: null,
      decisionId: null,
      reason: 'Permission rules require approval',
      editReview: review,
    }

    const callMsg: ToolDialogueMessage = {
      id: 'call-write-reviewed',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-reviewed',
      toolName: 'write',
      rendererKey: 'write',
      arguments: rawArgs,
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationId: 'inv-write-reviewed',
      invocationStatus: 'WAITING_APPROVAL',
      approval,
      editReview: review,
    }

    updateState({
      scenario: 'write-reviewed-pending',
      toolBlock: { message: callMsg },
      approval: {
        threadId: 'thread-1',
        invocationId: 'inv-write-reviewed',
        toolName: 'write',
        argumentsJson: rawArgs,
        approval,
      },
    })
  },

  /** 允许并完成后的 write：终态按既有规则展示待写正文，不拿冻结 diff 顶替。 */
  renderWriteApproved() {
    const rawArgs = JSON.stringify({
      path: '/workspace/src/LongService.java',
      workdir: '/workspace/root',
      content: LONG_WRITE_CONTENT,
    })
    const review = {
      path: '/workspace/src/LongService.java',
      diff: LONG_WRITE_CONTENT.split('\n').slice(0, -1)
        .map((line, index) => `+${index + 1}|${line}`).join('\n'),
      sha256: null,
    }
    const callMsg: ToolDialogueMessage = {
      id: 'call-write-approved',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-approved',
      toolName: 'write',
      rendererKey: 'write',
      arguments: rawArgs,
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationId: 'inv-write-approved',
      invocationStatus: 'SUCCEEDED',
      approval: {
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-write-approved',
        reason: null,
        editReview: review,
      },
      editReview: review,
    }
    const resultMsg: ToolDialogueMessage = {
      id: 'result-write-approved',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-approved',
      toolName: 'write',
      rendererKey: 'write',
      arguments: rawArgs,
      contents: [{ type: 'text', text: 'File written successfully.' }],
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
    }

    updateState({
      scenario: 'write-approved',
      toolBlock: { message: callMsg, result: resultMsg },
      approval: null,
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
    }

    updateState({
      scenario: 'write-success',
      toolBlock: { message: callMsg, result: resultMsg },
      approval: null,
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
        edits: [{ old_string: 'const oldVal = 1;', new_string: 'const newVal = 2;' }],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'streaming',
    }

    updateState({
      scenario: 'edit-streaming',
      toolBlock: { message: callMsg },
      approval: null,
    })
  },

  renderEditStreamingMultiEntry() {
    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-stream-multi',
      role: 'tool',
      phase: 'call',
      subjectEntryId: null,
      toolCallId: 'call-edit-stream-multi',
      toolName: 'edit',
      rendererKey: 'edit',
      // 参数在生成中：第三条尚未给出 new_string，用来验证前缀解析。
      arguments: '{"path":"/src/App.tsx","edits":['
        + '{"old_string":"const a = 1;","new_string":"const a = 100;","replace_all":true},'
        + '{"old_string":"const b = 2;","new_string":"const b = 200;"},'
        + '{"old_string":"const c = 3;","new_',
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'streaming',
    }

    updateState({
      scenario: 'edit-streaming-multi',
      toolBlock: { message: callMsg },
      approval: null,
    })
  },

  renderEditPreparing() {
    const rawArgs = JSON.stringify({
      path: '/src/App.tsx',
      edits: [{ old_string: 'const a = 1;', new_string: 'const a = 100;' }],
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      approval: null,
    })
  },

  renderEditReviewed() {
    const rawArgs = JSON.stringify({
      path: '/src/App.tsx',
      edits: [
        { old_string: 'const a = 1;', new_string: 'const a = 100;' },
        { old_string: 'const b = 2;', new_string: 'const b = 200;' },
      ],
    })
    const reviewData = {
      path: '/canonical/src/App.tsx',
      diff: MULTI_HUNK_DIFF,
      sha256: VALID_SHA,
    }
    const approval = {
      required: true,
      decision: null,
      decisionId: null,
      reason: null,
      editReview: reviewData,
    }

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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationId: 'inv-edit-1',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: reviewData,
      },
      editReview: reviewData,
    }

    updateState({
      scenario: 'edit-reviewed',
      toolBlock: { message: callMsg },
      approval: {
        threadId: 'thread-1',
        invocationId: 'inv-edit-1',
        toolName: 'edit',
        argumentsJson: rawArgs,
        approval,
      },
    })
  },

  /** 待审批 edit（没有审批卡可见）：冻结 diff 仍留在时间线记录里。 */
  renderEditReviewedReadonly() {
    const rawArgs = JSON.stringify({
      path: '/src/App.tsx',
      edits: [
        { old_string: 'const a = 1;', new_string: 'const a = 100;' },
        { old_string: 'const b = 2;', new_string: 'const b = 200;' },
      ],
    })
    const reviewData = {
      path: '/canonical/src/App.tsx',
      diff: MULTI_HUNK_DIFF,
      sha256: VALID_SHA,
    }

    updateState({
      scenario: 'edit-reviewed-readonly',
      toolBlock: {
        message: {
          id: 'call-edit-reviewed-readonly',
          role: 'tool',
          phase: 'call',
          subjectEntryId: 'asst-1',
          toolCallId: 'call-edit-reviewed-readonly',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: rawArgs,
          contents: [],
          createdAt: Date.parse('2026-10-19T00:00:00Z'),
          status: 'done',
          invocationId: 'inv-edit-readonly',
          invocationStatus: 'WAITING_APPROVAL',
          approval: {
            required: true,
            decision: null,
            decisionId: null,
            reason: null,
            editReview: reviewData,
          },
          editReview: reviewData,
        },
      },
      approval: null,
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
      editReview: resultReview,
    }

    updateState({
      scenario: 'edit-conflict',
      toolBlock: { message: callMsg, result: resultMsg },
      approval: null,
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
    }

    updateState({
      scenario: 'edit-success',
      toolBlock: { message: callMsg, result: resultMsg },
      approval: null,
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
        ...(isWrite
          ? { content: 'test content' }
          : { edits: [{ old_string: 'a', new_string: 'b' }] }),
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'error',
    }

    updateState({
      scenario: 'mutation-failure',
      toolBlock: { message: callMsg, result: resultMsg },
      approval: null,
    })
  },

  renderApprovalMissingReview() {
    updateState({
      scenario: 'approval-missing-review',
      toolBlock: null,
      approval: {
        threadId: 'thread-1',
        invocationId: 'inv-missing-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        }),
        approval: { required: true },
      },
    })
  },

  renderApprovalMalformedReview() {
    updateState({
      scenario: 'approval-malformed-review',
      toolBlock: null,
      approval: {
        threadId: 'thread-1',
        invocationId: 'inv-malformed-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        }),
        approval: {
          required: true,
          editReview: {
            path: '/src/App.tsx',
            diff: '+b',
            sha256: 'invalid-sha-characters',
          },
        },
      },
    })
  },

  renderApprovalValid() {
    updateState({
      scenario: 'approval-valid',
      toolBlock: null,
      approval: {
        threadId: 'thread-1',
        invocationId: 'inv-valid-review',
        toolName: 'edit',
        argumentsJson: JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        }),
        approval: {
          required: true,
          editReview: {
            path: '/canonical/src/App.tsx',
            diff: '-a\n+b',
            sha256: VALID_SHA,
          },
        },
      },
    })
  },

  renderEditDurableHistory() {
    const asstEntry = {
      entryId: 'asst-durable-pw',
      sessionId: 'sess-pw',
      parentEntryId: null,
      entryType: 'MESSAGE' as const,
      payload: {
        message: {
          role: 'ASSISTANT',
          contents: [
            {
              type: 'tool_call',
              toolCallId: 'call-durable-pw',
              toolName: 'edit',
              rendererKey: 'edit',
              argumentsJson: JSON.stringify({ path: '/canonical/src/App.tsx' }),
            },
          ],
        },
      },
      createdAt: 1792368000,
      usageCost: null,
    }

    const toolResultEntry = {
      entryId: 'result-durable-pw',
      sessionId: 'sess-pw',
      parentEntryId: null,
      entryType: 'MESSAGE' as const,
      payload: {
        message: {
          role: 'TOOL',
          contents: [
            {
              type: 'tool_result',
              toolCallId: 'call-durable-pw',
              toolName: 'edit',
              rendererKey: 'edit',
              contents: [{ type: 'text', text: 'Edited successfully.\nReplacements: 1' }],
              error: false,
              detailsJson: JSON.stringify({
                editReview: {
                  path: '/canonical/src/App.tsx',
                  diff: MULTI_HUNK_DIFF,
                  sha256: VALID_SHA,
                },
              }),
            },
          ],
        },
        toolResultMetadata: {
          assistantEntryId: 'asst-durable-pw',
          callIndex: 0,
        },
      },
      createdAt: 1792368001,
      usageCost: null,
    }

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const callMsg = timeline.messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'call',
    )
    const resultMsg = timeline.messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'result',
    )

    if (callMsg) {
      updateState({
        scenario: 'edit-durable-history',
        toolBlock: { message: callMsg, result: resultMsg },
        approval: null,
      })
    }
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
  createRoot(rootElement).render(
    <ApplicationEventProvider>
      <MutationReviewHarnessApp />
    </ApplicationEventProvider>,
  )
}
