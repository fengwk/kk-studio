/* eslint-disable no-restricted-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolMessageBlock } from '@/features/chat/runtime/thread-panel/messages/ToolMessageBlock'
import { ApprovalCard } from '@/features/chat/runtime/interactions/ApprovalCard'
import type { DialogueMessage, ToolDialogueMessage } from '@/features/chat/runtime/thread-timeline-types'
import { buildThreadTimeline } from '@/features/chat/runtime/thread-timeline'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO, ToolInvocationDTO } from '@/shared/api/generated/contracts'

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      {children}
    </QueryClientProvider>
  )
}

const validSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

describe('Slice F 审查与审批组件验收测试', () => {
  it('write 工具 40+ 行全文完整呈现，保留缩进、空白行与尾随换行', () => {
    const lines = Array.from({ length: 45 }, (_, i) => {
      if (i === 10) return '    public void methodWithIndent() {'
      if (i === 11) return '        // indented comment'
      if (i === 12) return ''
      return `    line-${i + 1} with spaces   `
    })
    const fullContent = lines.join('\n') + '\n'

    const callMsg: ToolDialogueMessage = {
      id: 'call-write-1',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-1',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({
        path: '/workspace/Sample.java',
        workdir: '/workspace/root',
        content: fullContent,
      }),
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

    const { container } = render(<ToolMessageBlock message={callMsg} />)

    expect(screen.getByText('/workspace/Sample.java')).toBeInTheDocument()
    expect(screen.getByText('(/workspace/root)')).toBeInTheDocument()

    const viewport = container.querySelector('.thread-tool-preview-body.is-write')
    expect(viewport).toBeInTheDocument()
    expect(viewport).toHaveClass('thread-tool-output')
    expect(viewport?.textContent).toBe(fullContent)

    // 审批理由修复 redundant '： —' 且本地化
    const note = container.querySelector('.thread-tool-approval-note')
    expect(note?.textContent).not.toContain('： —')
    expect(note?.textContent).toContain('权限规则要求审批')
  })

  it('write 空内容正确显示目标路径与空文件标识，不盲批', () => {
    const callMsg: ToolDialogueMessage = {
      id: 'call-write-empty',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-empty',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({
        path: '/workspace/empty.txt',
        content: '',
      }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
      },
    }

    const { container } = render(<ToolMessageBlock message={callMsg} />)
    expect(screen.getAllByText('/workspace/empty.txt').length).toBeGreaterThanOrEqual(1)
    expect(container.querySelector('.thread-tool-empty-content')).toHaveTextContent('（空文件）')
  })

  it('edit 流式参数阶段明确标注为输入，不伪造为真实 diff', () => {
    const streamingCallMsg: ToolDialogueMessage = {
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

    const { container } = render(<ToolMessageBlock message={streamingCallMsg} />)

    expect(screen.getByText(/流式输入内容/)).toBeInTheDocument()
    expect(screen.getByText(/原文本/)).toBeInTheDocument()
    expect(screen.getByText(/新文本/)).toBeInTheDocument()

    const oldBlock = container.querySelector('.thread-tool-preview-body.is-input-old')
    const newBlock = container.querySelector('.thread-tool-preview-body.is-input-new')
    expect(oldBlock?.textContent).toBe('const oldVal = 1;')
    expect(newBlock?.textContent).toBe('const newVal = 2;')

    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    expect(container.querySelector('.is-added')).toBeNull()
    expect(container.querySelector('.is-removed')).toBeNull()
  })

  it('真实 editReview 替换流式输入并展示多 hunk 编号 diff', () => {
    const multiHunkDiff = [
      '@@ -10,3 +10,3 @@',
      ' 10|context 1',
      '-11|const a = 1',
      '+11|const a = 100',
      ' 12|context 1 after',
      '@@ -40,3 +40,3 @@',
      ' 40|context 2',
      '-41|const b = 2',
      '+41|const b = 200',
      ' 42|context 2 after',
    ].join('\n')

    const reviewedCallMsg: ToolDialogueMessage = {
      id: 'call-edit-reviewed',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-reviewed',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: '/src/App.tsx',
        old_string: 'const a = 1;\nconst b = 2;',
        new_string: 'const a = 100;\nconst b = 200;',
      }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: {
          path: '/canonical/src/App.tsx',
          diff: multiHunkDiff,
          sha256: validSha,
        },
      },
    }

    const { container } = render(<ToolMessageBlock message={reviewedCallMsg} />)

    expect(screen.getByText('/canonical/src/App.tsx')).toBeInTheDocument()

    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('-11|const a = 1')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+11|const a = 100')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('-41|const b = 2')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+41|const b = 200')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('@@ -10,3 +10,3 @@')).toHaveClass('is-hunk-header')

    expect(screen.queryByText(/流式输入内容/)).not.toBeInTheDocument()
  })

  it('ApprovalCard 缺失或非法 review 禁用 ALLOW，DENY 始终可用', () => {
    const { rerender } = render(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' })}
        approvalJson={JSON.stringify({ required: true })}
      />,
      { wrapper },
    )

    const allowBtn = screen.getByRole('button', { name: '允许' })
    const denyBtn = screen.getByRole('button', { name: '拒绝' })

    expect(allowBtn).toBeDisabled()
    expect(denyBtn).toBeEnabled()
    expect(screen.getByText('未获取到有效的编辑审查信息，无法允许。')).toBeInTheDocument()

    // 非法 sha256 同样禁用 ALLOW
    rerender(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' })}
        approvalJson={JSON.stringify({
          required: true,
          editReview: { path: '/src/App.tsx', diff: '+b', sha256: 'invalid' },
        })}
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()

    // 合法 review 启用 ALLOW
    rerender(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' })}
        approvalJson={JSON.stringify({
          required: true,
          editReview: { path: '/canonical/src/App.tsx', diff: '-a\n+b', sha256: validSha },
        })}
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()
  })

  it('成功终态短文本去重不重复展示正文或 diff，失败终态完整保留错误', () => {
    const rawDuplicateBody = 'File edit executed successfully:\n-11|const a = 1\n+11|const a = 100'
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
        diff: '-11|const a = 1\n+11|const a = 100',
        sha256: validSha,
      },
    }

    const successResultMsg: ToolDialogueMessage = {
      id: 'result-edit-success',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-success',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({ path: '/src/App.tsx' }),
      contents: [{ type: 'text', text: rawDuplicateBody }],
      createdAt: '2026-10-19T00:00:01Z',
      status: 'done',
    }

    const { container, rerender } = render(<ToolMessageBlock message={callMsg} result={successResultMsg} />)

    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
    expect(within(container).getByText('-11|const a = 1')).toBeInTheDocument()
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    expect(screen.queryByText(rawDuplicateBody)).not.toBeInTheDocument()

    // 失败终态：错误不被遮掩
    const failureError = 'Permission denied'
    const failResultMsg: ToolDialogueMessage = {
      ...successResultMsg,
      status: 'error',
      errorMessage: failureError,
      contents: [{ type: 'text', text: failureError }],
    }
    rerender(<ToolMessageBlock message={{ ...callMsg, status: 'error', invocationStatus: 'FAILED' }} result={failResultMsg} />)
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expect(screen.getByText(failureError)).toBeInTheDocument()
  })

  it('时间线工具卡片与独立 ApprovalCard 渲染同一份审查 payload', () => {
    const reviewData = {
      path: '/src/SharedComponent.tsx',
      diff: '-old\n+new',
      sha256: validSha,
    }
    const rawArgs = JSON.stringify({ path: '/src/SharedComponent.tsx', old_string: 'old', new_string: 'new' })
    const rawApproval = JSON.stringify({ required: true, editReview: reviewData })

    const { container: cardContainer } = render(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={rawArgs}
        approvalJson={rawApproval}
      />,
      { wrapper },
    )

    const { container: timelineContainer } = render(
      <ToolMessageBlock
        message={{
          id: 'call-timeline-same',
          role: 'tool',
          phase: 'call',
          subjectEntryId: 'asst-1',
          toolCallId: 'call-timeline-same',
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
        }}
      />,
    )

    const cardDiff = cardContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent
    const timelineDiff = timelineContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent

    expect(cardDiff).toBeTruthy()
    expect(timelineDiff).toBeTruthy()
    expect(cardDiff).toEqual(timelineDiff)
    expect(cardContainer.querySelector('.interaction-raw-pre')).toBeNull()
  })

  it('审批快照与终态结果冲突时优先展示审批快照', () => {
    const approvalReview = {
      path: '/canonical/Conflict.tsx',
      diff: '-approved\n+approved',
      sha256: validSha,
    }
    const resultReview = {
      path: '/canonical/Conflict.tsx',
      diff: '-result\n+result',
      sha256: validSha,
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

    const { container } = render(<ToolMessageBlock message={callMsg} result={resultMsg} />)
    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(within(preview as HTMLElement).getByText('-approved')).toBeInTheDocument()
    expect(within(preview as HTMLElement).queryByText('-result')).toBeNull()
  })
})

describe('真实 AgentMessageJsonCodec 历史生命周期与真实组件验收测试', () => {
  function createEntry(
    entryId: string,
    entryType: EntryType,
    payload: Record<string, unknown>,
  ): HarnessSessionEntryDTO {
    return {
      entryId,
      sessionId: 'sess-acceptance-1',
      parentEntryId: null,
      entryType,
      payloadJson: JSON.stringify(payload),
      createTime: 1792404000,
      usageCost: null,
    }
  }

  function createInvocation(
    id: string,
    assistantEntryId: string,
    toolCallId: string,
    overrides: Partial<ToolInvocationDTO> = {},
  ): ToolInvocationDTO {
    return {
      id,
      modelInvocationId: `model-${id}`,
      assistantEntryId,
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId,
      toolName: 'edit',
      rendererKey: 'edit',
      environmentId: null,
      requiredEnvironmentId: null,
      waitingForEnvironment: false,
      requiredEnvironmentName: null,
      environmentWaitFreshnessAt: null,
      argumentsJson: JSON.stringify({
        path: '/workspace/src/App.tsx',
        old_string: 'const value = 1;',
        new_string: 'const value = 2;',
      }),
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: 1792404000,
      updateTime: 1792404000,
      ...overrides,
    }
  }

  function renderTimelineTool(messages: DialogueMessage[]) {
    const call = messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'call',
    )
    const result = messages.find(
      (m): m is ToolDialogueMessage => m.role === 'tool' && m.phase === 'result',
    )
    if (!call) {
      throw new Error('Call message expected')
    }
    return render(<ToolMessageBlock message={call} result={result} />)
  }

  const sampleDiff = [
    '@@ -1,3 +1,3 @@',
    ' 1|import React from "react"',
    '-2|const value = 1;',
    '+2|const value = 2;',
    ' 3|export default App',
  ].join('\n')

  const validReview = {
    path: '/workspace/src/App.tsx',
    diff: sampleDiff,
    sha256: validSha,
  }

  it('模拟完整生命周期：invocation overlay 存在 -> durable entry 出现 -> invocation 清理 -> 刷新空 overlay，diff 相同可读且去重展示短成功文案', () => {
    // 阶段 1: invocation overlay 存在（处于 WAITING_APPROVAL，approvalJson 携带真实 editReview）
    const invPhase1 = createInvocation('inv-flow-1', 'asst-entry-1', 'call-edit-1', {
      status: 'WAITING_APPROVAL',
      approvalJson: JSON.stringify({
        required: true,
        decision: null,
        decisionId: null,
        reason: 'Permission rules require approval',
        editReview: validReview,
      }),
    })

    const asstEntry = createEntry('asst-entry-1', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-edit-1',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: invPhase1.argumentsJson,
          },
        ],
      },
    })

    const timelinePhase1 = buildThreadTimeline([asstEntry], [], [invPhase1])
    const { container: container1, unmount: unmount1 } = renderTimelineTool(timelinePhase1.messages)

    const preview1 = container1.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview1).toBeInTheDocument()
    expect(preview1?.textContent).toBe(sampleDiff)
    expect(within(preview1 as HTMLElement).getByText('-2|const value = 1;')).toHaveClass('is-removed')
    expect(within(preview1 as HTMLElement).getByText('+2|const value = 2;')).toHaveClass('is-added')
    unmount1()

    // 阶段 2: durable entry 出现（后端真实 AgentMessageJsonCodec 结构：content.detailsJson 为 JSON 字符串）
    // 此时 invocation 仍留在列表（例如已成为 SUCCEEDED，尚未被 GC/清理）
    const invPhase2 = createInvocation('inv-flow-1', 'asst-entry-1', 'call-edit-1', {
      status: 'SUCCEEDED',
      approvalJson: JSON.stringify({
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-1',
        reason: null,
        editReview: validReview,
      }),
      resultJson: JSON.stringify({
        toolCallId: 'call-edit-1',
        contents: [{ type: 'text', text: 'Edited successfully.' }],
        error: false,
        details: { editReview: validReview },
      }),
    })

    const toolResultEntry = createEntry('result-entry-1', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-edit-1',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited successfully.\nReplacements: 1' }],
            error: false,
            // 真实 AgentMessageJsonCodec 形状：字符串
            detailsJson: JSON.stringify({ editReview: validReview }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-entry-1',
        callIndex: 0,
      },
    })

    const timelinePhase2 = buildThreadTimeline([asstEntry, toolResultEntry], [], [invPhase2])
    const { container: container2, unmount: unmount2 } = renderTimelineTool(timelinePhase2.messages)

    const preview2 = container2.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview2).toBeInTheDocument()
    expect(preview2?.textContent).toBe(sampleDiff)
    // 成功终态展示短提示，不重复输出原正文
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    expect(screen.queryByText('Edited successfully.')).not.toBeInTheDocument()
    unmount2()

    // 阶段 3: invocation 清理（toolInvocations 变为空数组）
    const timelinePhase3 = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container: container3, unmount: unmount3 } = renderTimelineTool(timelinePhase3.messages)

    const preview3 = container3.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview3).toBeInTheDocument()
    // 关键断言：经过 Entry 边界解码 detailsJson 后，清理 invocation 依然能恢复出完全相同 diff！
    expect(preview3?.textContent).toBe(sampleDiff)
    expect(within(preview3 as HTMLElement).getByText('-2|const value = 1;')).toHaveClass('is-removed')
    expect(within(preview3 as HTMLElement).getByText('+2|const value = 2;')).toHaveClass('is-added')
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    unmount3()

    // 阶段 4: 刷新空 overlay（模拟页面从纯 durable entries 重新渲染，没有任何 transient 状态）
    const timelinePhase4 = buildThreadTimeline([asstEntry, toolResultEntry], [], [], null, null, [])
    const { container: container4, unmount: unmount4 } = renderTimelineTool(timelinePhase4.messages)

    const preview4 = container4.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview4).toBeInTheDocument()
    expect(preview4?.textContent).toBe(sampleDiff)
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    // 验证默认展开视口且保留有界结构
    expect(preview4).toHaveClass('thread-tool-output')
    expect(preview4).toHaveClass('thread-tool-preview-body')
    unmount4()
  })

  it('审批冻结快照与终态结果详情冲突时，invocation 存在期间优先展示审批快照，invocation 清理后历史展示结果 review', () => {
    const frozenApprovalReview = {
      path: '/workspace/src/App.tsx',
      diff: '-2|const value = 1;\n+2|const value = 200; // approved snapshot',
      sha256: validSha,
    }
    const daemonResultReview = {
      path: '/workspace/src/App.tsx',
      diff: '-2|const value = 1;\n+2|const value = 999; // daemon actual result',
      sha256: validSha,
    }

    const asstEntry = createEntry('asst-entry-conflict', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-conflict',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-entry-conflict', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-conflict',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited' }],
            error: false,
            detailsJson: JSON.stringify({ editReview: daemonResultReview }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-entry-conflict',
        callIndex: 0,
      },
    })

    const conflictInvocation = createInvocation('inv-conflict', 'asst-entry-conflict', 'call-conflict', {
      status: 'SUCCEEDED',
      approvalJson: JSON.stringify({
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-conflict',
        reason: null,
        editReview: frozenApprovalReview,
      }),
      resultJson: JSON.stringify({
        toolCallId: 'call-conflict',
        contents: [{ type: 'text', text: 'Edited' }],
        error: false,
        details: { editReview: daemonResultReview },
      }),
    })

    // 1. Invocation 存在时：审批冻结快照严格优先！
    const withInvocation = buildThreadTimeline([asstEntry, toolResultEntry], [], [conflictInvocation])
    const { container: c1, unmount: u1 } = renderTimelineTool(withInvocation.messages)
    const p1 = c1.querySelector('.thread-tool-preview-body.is-edit')
    expect(p1?.textContent).toContain('approved snapshot')
    expect(p1?.textContent).not.toContain('daemon actual result')
    u1()

    // 2. Invocation 清理后：成功历史使用结果 review！
    const afterCleanup = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container: c2, unmount: u2 } = renderTimelineTool(afterCleanup.messages)
    const p2 = c2.querySelector('.thread-tool-preview-body.is-edit')
    expect(p2?.textContent).toContain('daemon actual result')
    expect(p2?.textContent).not.toContain('approved snapshot')
    u2()
  })

  it('YOLO 模式：无需审批时从实时 invocation 到 durable 落地再到清理刷新，diff 始终相同可读', () => {
    const yoloReview = {
      path: '/workspace/src/Yolo.tsx',
      diff: '@@ -1,1 +1,1 @@\n-old\n+new',
      sha256: validSha,
    }

    const asstEntry = createEntry('asst-yolo', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-yolo',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/Yolo.tsx"}',
          },
        ],
      },
    })

    const yoloInvocation = createInvocation('inv-yolo', 'asst-yolo', 'call-yolo', {
      status: 'SUCCEEDED',
      approvalJson: null, // 无需审批
      resultJson: JSON.stringify({
        toolCallId: 'call-yolo',
        contents: [{ type: 'text', text: 'Auto applied' }],
        error: false,
        details: { editReview: yoloReview },
      }),
    })

    const toolResultEntry = createEntry('result-yolo', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-yolo',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Auto applied' }],
            error: false,
            detailsJson: JSON.stringify({ editReview: yoloReview }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-yolo',
        callIndex: 0,
      },
    })

    // YOLO 运行期
    const liveTimeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [yoloInvocation])
    const { container: liveContainer, unmount: uLive } = renderTimelineTool(liveTimeline.messages)
    expect(liveContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent).toBe(yoloReview.diff)
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    uLive()

    // YOLO 清理/刷新后
    const durableTimeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container: durableContainer, unmount: uDurable } = renderTimelineTool(durableTimeline.messages)
    expect(durableContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent).toBe(yoloReview.diff)
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
    uDurable()
  })

  it('失败终态：真实错误透传，不展示“编辑成功”，保留错误摘要', () => {
    const errorMsg = 'Pattern old_string not found in file: /workspace/src/App.tsx'

    const asstEntry = createEntry('asst-fail', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-fail',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-fail', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-fail',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: errorMsg }],
            error: true,
            detailsJson: '{}',
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-fail',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    renderTimelineTool(timeline.messages)

    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expect(screen.getByText(errorMsg)).toBeInTheDocument()
  })

  it('旧缺扩展：历史无 editReview（旧数据/detailsJson为空对象），不假造 diff 也不进入转圈状态', () => {
    const asstEntry = createEntry('asst-legacy', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-legacy',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-legacy', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-legacy',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'File updated.' }],
            error: false,
            detailsJson: '{}',
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-legacy',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container } = renderTimelineTool(timeline.messages)

    // 不假造 diff，没有 preview diff 节点
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    // 不进入准备中转圈状态
    expect(container.querySelector('.thread-tool-preview-preparing')).toBeNull()
    // 依然具备成功状态
    expect(screen.getByText('编辑成功')).toBeInTheDocument()
  })

  it('损坏不假造：detailsJson 损坏（非法 JSON / 缺失必须字段 / 非法 sha256）时严格安全降级，不伪造 diff', () => {
    const asstEntry = createEntry('asst-corrupt', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-corrupt',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    // 测试 1: 非法 JSON 字符串
    const brokenJsonEntry = createEntry('result-broken-json', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-corrupt',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'done' }],
            error: false,
            detailsJson: '{ not valid json',
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-corrupt',
        callIndex: 0,
      },
    })

    const timeline1 = buildThreadTimeline([asstEntry, brokenJsonEntry], [], [])
    const { container: c1, unmount: u1 } = renderTimelineTool(timeline1.messages)
    expect(c1.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    u1()

    // 测试 2: sha256 非法（长度不足或非法字符）
    const malformedShaEntry = createEntry('result-bad-sha', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-corrupt',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'done' }],
            error: false,
            detailsJson: JSON.stringify({
              editReview: {
                path: '/workspace/src/App.tsx',
                diff: '+added',
                sha256: 'short-sha',
              },
            }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-corrupt',
        callIndex: 0,
      },
    })

    const timeline2 = buildThreadTimeline([asstEntry, malformedShaEntry], [], [])
    const { container: c2, unmount: u2 } = renderTimelineTool(timeline2.messages)
    expect(c2.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    u2()
  })

  it('Daemon 新增 EOF 缺换行标记作为普通 diff 行自然渲染', () => {
    const eofDiff = [
      '@@ -1,2 +1,2 @@',
      ' 1|first line',
      '-2|second line without newline',
      '+2|second line modified',
      '\\ No newline at end of file',
    ].join('\n')

    const eofReview = {
      path: '/workspace/src/App.tsx',
      diff: eofDiff,
      sha256: validSha,
    }

    const asstEntry = createEntry('asst-eof', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-eof',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-eof', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-eof',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited' }],
            error: false,
            detailsJson: JSON.stringify({ editReview: eofReview }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-eof',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container } = renderTimelineTool(timeline.messages)

    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview).toBeInTheDocument()
    expect(preview?.textContent).toContain('\\ No newline at end of file')
    // 标记被作为 context 行安全渲染，不破坏增删着色
    const markerLine = within(preview as HTMLElement).getByText('\\ No newline at end of file')
    expect(markerLine).toHaveClass('is-context')
  })

  it('durable 边界仅认 detailsJson：携带 details 对象但无 detailsJson 不会恢复 review', () => {
    const asstEntry = createEntry('asst-details-only', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-details-only',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-details-only', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-details-only',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited' }],
            error: false,
            // 违规/旧对象直接放在 content.details，无 detailsJson
            details: { editReview: validReview },
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-details-only',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container } = renderTimelineTool(timeline.messages)
    // 严格禁止多形状兼容：没有 detailsJson 时 details 为 undefined，不恢复 review
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
  })

  it('durable 边界 details 与 detailsJson 冲突时 detailsJson 严格胜出', () => {
    const bypassedReview = {
      path: '/workspace/src/App.tsx',
      diff: '-bypassed\n+bypassed',
      sha256: validSha,
    }
    const authoritativeReview = {
      path: '/workspace/src/App.tsx',
      diff: '-authoritative\n+authoritative',
      sha256: validSha,
    }

    const asstEntry = createEntry('asst-conflict-fields', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-conflict-fields',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-conflict-fields', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-conflict-fields',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited' }],
            error: false,
            details: { editReview: bypassedReview },
            detailsJson: JSON.stringify({ editReview: authoritativeReview }),
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-conflict-fields',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container } = renderTimelineTool(timeline.messages)
    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview).toBeInTheDocument()
    expect(preview?.textContent).toContain('authoritative')
    expect(preview?.textContent).not.toContain('bypassed')
  })

  it('损坏的 detailsJson 严格拒绝回退到未知 details 对象，绝不旁路权威数据', () => {
    const asstEntry = createEntry('asst-corrupt-no-fallback', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-corrupt-no-fallback',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-corrupt-no-fallback', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-corrupt-no-fallback',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'Edited' }],
            error: false,
            details: { editReview: validReview },
            detailsJson: '{ invalid json syntax',
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-corrupt-no-fallback',
        callIndex: 0,
      },
    })

    const timeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container } = renderTimelineTool(timeline.messages)
    // 损坏的 detailsJson 解析失败后 details 为 undefined，绝不能回退透传 content.details
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
  })
})
