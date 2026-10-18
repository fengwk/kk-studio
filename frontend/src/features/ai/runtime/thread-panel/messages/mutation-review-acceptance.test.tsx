/* eslint-disable no-restricted-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { ApprovalCard } from '@/features/ai/runtime/interactions/ApprovalCard'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

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
