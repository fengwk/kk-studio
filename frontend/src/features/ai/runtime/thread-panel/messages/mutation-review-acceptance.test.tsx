import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
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

describe('Slice F mutation review acceptance criteria', () => {
  it('Criterion 1: write 40+ lines complete and whitespace preserved during approval', () => {
    // Generate 45 lines with leading indentation, trailing spaces, and blank lines
    const lines = Array.from({ length: 45 }, (_, i) => {
      if (i === 10) return '    public void methodWithIndent() {'
      if (i === 11) return '        // indented comment'
      if (i === 12) return ''
      return `    line-${i + 1} with trailing space   `
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
        path: '/absolute/path/to/Sample.java',
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

    const { container } = render(<ToolMessageBlock message={callMsg} />, { wrapper })

    // Target path and workdir displayed
    expect(screen.getByText('/absolute/path/to/Sample.java')).toBeInTheDocument()
    expect(screen.getByText('(/workspace/root)')).toBeInTheDocument()

    // Bounded viewport preserves whitespace and complete text without truncation
    const viewport = container.querySelector('.thread-tool-preview-body.is-write')
    expect(viewport).toBeInTheDocument()
    expect(viewport).toHaveClass('thread-tool-output')
    expect(viewport?.textContent).toBe(fullContent)
    expect(viewport?.textContent).toContain('    public void methodWithIndent() {')
    expect(viewport?.textContent).toContain('line-45 with trailing space   ')

    // Fixed redundant ： — and translated default approval reason
    const note = container.querySelector('.thread-tool-approval-note')
    expect(note?.textContent).not.toContain('： —')
    expect(note?.textContent).toContain('权限规则要求审批')
  })

  it('Criterion 2: streamed edit input not mislabeled real diff', () => {
    const streamingCallMsg: ToolDialogueMessage = {
      id: 'call-edit-1',
      role: 'tool',
      phase: 'call',
      subjectEntryId: null,
      toolCallId: 'call-edit-1',
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

    const { container } = render(<ToolMessageBlock message={streamingCallMsg} />, { wrapper })

    // Explicitly labeled as input, not diff
    expect(screen.getByText(/流式输入内容/)).toBeInTheDocument()
    expect(screen.getByText(/原文本/)).toBeInTheDocument()
    expect(screen.getByText(/新文本/)).toBeInTheDocument()

    const oldBlock = container.querySelector('.thread-tool-preview-body.is-input-old')
    const newBlock = container.querySelector('.thread-tool-preview-body.is-input-new')
    expect(oldBlock?.textContent).toBe('const oldVal = 1;')
    expect(newBlock?.textContent).toBe('const newVal = 2;')

    // Must NOT contain diff markings (+ or -) or diff class
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    expect(container.querySelector('.is-added')).toBeNull()
    expect(container.querySelector('.is-removed')).toBeNull()
  })

  it('Criterion 3: actual multi-hunk numbered file diff from editReview replaces inputs', () => {
    const multiHunkDiff = [
      '@@ -10,3 +10,3 @@',
      ' 10|context before hunk 1',
      '-11|const a = 1',
      '+11|const a = 100',
      ' 12|context after hunk 1',
      '@@ -40,3 +40,3 @@',
      ' 40|context before hunk 2',
      '-41|const b = 2',
      '+41|const b = 200',
      ' 42|context after hunk 2',
    ].join('\n')

    const reviewedCallMsg: ToolDialogueMessage = {
      id: 'call-edit-2',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-2',
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

    const { container } = render(<ToolMessageBlock message={reviewedCallMsg} />, { wrapper })

    // Shows canonical file path
    expect(screen.getByText('/canonical/src/App.tsx')).toBeInTheDocument()

    // Shows real multi-hunk numbered file diff with diff classes
    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('-11|const a = 1')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+11|const a = 100')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('-41|const b = 2')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+41|const b = 200')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('@@ -10,3 +10,3 @@')).toHaveClass('is-hunk-header')

    // Input presentation has been completely replaced
    expect(screen.queryByText(/流式输入内容/)).not.toBeInTheDocument()
  })

  it('Criterion 4: missing review blocks ALLOW while DENY is usable in ApprovalCard', () => {
    // 1. Missing review for edit
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

    // Missing review disables ALLOW, DENY is enabled
    expect(allowBtn).toBeDisabled()
    expect(denyBtn).toBeEnabled()
    expect(screen.getByText('未获取到有效的编辑审查信息，无法允许。')).toBeInTheDocument()

    // 2. Invalid sha256 in review also blocks ALLOW
    rerender(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={JSON.stringify({ path: '/src/App.tsx', old_string: 'a', new_string: 'b' })}
        approvalJson={JSON.stringify({
          required: true,
          editReview: { path: '/src/App.tsx', diff: '+b', sha256: 'invalid-sha' },
        })}
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()

    // 3. Valid edit review enables ALLOW
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

  it('Criterion 5: success body does not duplicate Diff/write while preview remains default open', () => {
    const rawDuplicateBody = 'File edit executed successfully:\n-11|const a = 1\n+11|const a = 100\nfull duplicated details...'
    const callMsg: ToolDialogueMessage = {
      id: 'call-edit-3',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-3',
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

    const resultMsg: ToolDialogueMessage = {
      id: 'result-edit-3',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-3',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({ path: '/src/App.tsx' }),
      contents: [{ type: 'text', text: rawDuplicateBody }],
      createdAt: '2026-10-19T00:00:01Z',
      status: 'done',
    }

    const { container } = render(<ToolMessageBlock message={callMsg} result={resultMsg} />, { wrapper })

    // Preview remains visible and expanded
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
    expect(within(container).getByText('-11|const a = 1')).toBeInTheDocument()

    // Short localized success message is displayed
    expect(screen.getByText('编辑成功')).toBeInTheDocument()

    // Raw duplicated body is NOT displayed
    expect(screen.queryByText(rawDuplicateBody)).not.toBeInTheDocument()
  })

  it('Criterion 6: errors are not masked when execution fails', () => {
    const failureError = 'Permission denied: cannot write to protected path /etc/passwd'
    const callMsg: ToolDialogueMessage = {
      id: 'call-write-fail',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-fail',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({ path: '/etc/passwd', content: 'malicious' }),
      contents: [],
      createdAt: '2026-10-19T00:00:00Z',
      status: 'error',
      invocationStatus: 'FAILED',
    }

    const resultMsg: ToolDialogueMessage = {
      id: 'result-write-fail',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-write-fail',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({ path: '/etc/passwd', content: 'malicious' }),
      contents: [{ type: 'text', text: failureError }],
      errorMessage: failureError,
      createdAt: '2026-10-19T00:00:01Z',
      status: 'error',
    }

    render(<ToolMessageBlock message={callMsg} result={resultMsg} />, { wrapper })

    // Failure message must be visible and not masked by success
    expect(screen.queryByText('写入成功')).not.toBeInTheDocument()
    expect(screen.getByText(failureError)).toBeInTheDocument()
  })

  it('Criterion 7: timeline approval and independent ApprovalCard render same payload', () => {
    const reviewData = {
      path: '/src/SharedComponent.tsx',
      diff: '-old component line\n+new component line',
      sha256: validSha,
    }
    const rawArgs = JSON.stringify({ path: '/src/SharedComponent.tsx', old_string: 'old', new_string: 'new' })
    const rawApproval = JSON.stringify({ required: true, editReview: reviewData })

    // Render ApprovalCard
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

    // Render ToolMessageBlock
    const { container: timelineContainer } = render(
      <ToolMessageBlock
        message={{
          id: 'call-timeline-1',
          role: 'tool',
          phase: 'call',
          subjectEntryId: 'asst-1',
          toolCallId: 'call-timeline-1',
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
      { wrapper },
    )

    // Both containers use ToolCallPreview to render the exact same path and diff
    const cardDiff = cardContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent
    const timelineDiff = timelineContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent

    expect(cardDiff).toBeTruthy()
    expect(timelineDiff).toBeTruthy()
    expect(cardDiff).toEqual(timelineDiff)

    // Neither renders raw arguments JSON
    expect(cardContainer.querySelector('.interaction-raw-pre')).toBeNull()
  })
})
