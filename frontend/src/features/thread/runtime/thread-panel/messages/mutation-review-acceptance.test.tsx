/* eslint-disable no-restricted-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import { ApprovalCard } from '@/features/thread/runtime/interactions/ApprovalCard'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import {
  ApprovalReviewProvider,
  visibleApprovalInvocationIds,
} from '@/features/thread/runtime/interactions/approval-review-context'
import type { DialogueMessage, ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline/thread-timeline'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO, InteractionApproval, ToolInvocationDTO } from '@/shared/api/generated/contracts'
import { ApplicationEventTestProvider } from '@/test-support/application-event-test-provider'

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <ApplicationEventTestProvider>
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    </ApplicationEventTestProvider>
  )
}

const validSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

/** 终态工具默认收起；展开后才显示 body 中的短成功说明。 */
function expandSettledTool() {
  const expand = screen.queryByRole('button', { name: '展开详情' })
  if (expand != null) {
    fireEvent.click(expand)
  }
}

/** 预览固定为最后 10 个逻辑行，展开详情不改变这个窗口。 */
function tailPreview(lines: string[]): string {
  return lines.slice(-10).join('\n')
}

describe('Slice F 审查与审批组件验收测试', () => {
  it('write 预览固定为最后 10 行，header 展开详情后完整呈现 40+ 行全文', () => {
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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

    expect(container.querySelector('.thread-tool-summary-detail'))
      .toHaveTextContent('/workspace/Sample.java in /workspace/root')

    // 预览是短窗口：只显示最后 10 个逻辑行，省略的前缀在预览上方给出真实行数。
    const viewport = container.querySelector('.thread-tool-preview-body.is-write')
    expect(viewport).toBeInTheDocument()
    expect(viewport).toHaveClass('thread-tool-output')
    expect(viewport?.textContent).toBe(tailPreview(lines))
    expect(screen.getByText('… 上方还有 35 行')).toBeInTheDocument()
    // 收起时预览之外没有别的材料或错误段。
    expect(container.querySelector('.thread-tool-details')).toBeNull()
    expect(container.querySelector('.thread-tool-error')).toBeNull()

    // header chevron 打开有界详情：完整正文（缩进、空白行、尾随换行）原样呈现。
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    const details = container.querySelector('.thread-tool-details')
    expect(details).toBeInTheDocument()
    expect(details?.textContent).toBe(fullContent)
    // 展开详情不改变预览配额。
    expect(container.querySelector('.thread-tool-preview-body.is-write')?.textContent)
      .toBe(tailPreview(lines))
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
    expect(container.querySelector('.thread-tool-empty-content')).toHaveTextContent('空文件')
  })

  it('edit 流式参数阶段只把新文本放进预览，旧文本进详情且不伪造 diff', () => {
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
        edits: [{ old_string: 'const oldVal = 1;', new_string: 'const newVal = 2;' }],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'streaming',
    }

    const { container } = render(<ToolMessageBlock message={streamingCallMsg} />)

    expect(screen.getByText('拟修改内容')).toBeInTheDocument()
    expect(screen.getByText('新文本：')).toBeInTheDocument()

    // 流式预览只显示新文本；旧文本属于详情材料，不占预览配额。
    const newBlock = container.querySelector('.thread-tool-preview-body.is-input-new')
    expect(newBlock?.textContent).toBe('const newVal = 2;')
    expect(container.querySelector('.thread-tool-preview-body.is-input-old')).toBeNull()
    expect(container.querySelector('.thread-tool-details')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(details).not.toBeNull()
    expect(within(details).getByText('原文本：')).toBeInTheDocument()
    expect(within(details).getByText('const oldVal = 1;')).toBeInTheDocument()
    // 详情也不把输入伪装成真实 diff。
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    expect(container.querySelector('.is-added')).toBeNull()
    expect(container.querySelector('.is-removed')).toBeNull()
  })

  it('edit 多条目流式参数按条目展示 change i of n 与 replace all 徽标', () => {
    const streamingCallMsg: ToolDialogueMessage = {
      id: 'call-edit-multi-stream',
      role: 'tool',
      phase: 'call',
      subjectEntryId: null,
      toolCallId: 'call-edit-multi-stream',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: '/src/App.tsx',
        edits: [
          { old_string: 'const a = 1;', new_string: 'const a = 100;', replace_all: true },
          { old_string: 'const b = 2;', new_string: 'const b = 200;' },
          { old_string: 'const c = 3;', new_string: 'const c = 300;' },
        ],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'streaming',
    }

    const { container } = render(<ToolMessageBlock message={streamingCallMsg} />)

    expect(screen.getByText('第 1/3 处修改')).toBeInTheDocument()
    expect(screen.getByText('第 2/3 处修改')).toBeInTheDocument()
    expect(screen.getByText('第 3/3 处修改')).toBeInTheDocument()
    const blocks = container.querySelectorAll('.thread-tool-preview-input-block')
    expect(blocks).toHaveLength(3)
    // 徽标保留：只有第一条要求整体替换。
    expect(blocks[0]?.querySelector('.thread-tool-preview-badge')?.textContent).toBe('替换全部')
    expect(blocks[1]?.querySelector('.thread-tool-preview-badge')).toBeNull()
    expect(blocks[2]?.querySelector('.thread-tool-preview-badge')).toBeNull()
    // 预览只显示各条目的新文本。
    expect(blocks[0]?.querySelector('.is-input-new')?.textContent).toBe('const a = 100;')
    expect(blocks[2]?.querySelector('.is-input-new')?.textContent).toBe('const c = 300;')
    expect(container.querySelector('.is-input-old')).toBeNull()

    // 将被替换的原文本整体进入详情，供核对。
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(details).not.toBeNull()
    expect(within(details).getByText('const a = 1;')).toBeInTheDocument()
    expect(within(details).getByText('const c = 3;')).toBeInTheDocument()
  })

  it('真实 editReview 替换多条目输入，只展示后端权威 diff', () => {
    const reviewedCallMsg: ToolDialogueMessage = {
      id: 'call-edit-multi-reviewed',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-multi-reviewed',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: '/src/App.tsx',
        edits: [
          { old_string: 'const a = 1;', new_string: 'const a = 100;' },
          { old_string: 'const b = 2;', new_string: 'const b = 200;' },
        ],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: {
          path: '/canonical/src/App.tsx',
          diff: '-1|const a = 1;\n+1|const a = 100;\n-2|const b = 2;\n+2|const b = 200;',
          sha256: validSha,
        },
      },
    }

    const { container } = render(<ToolMessageBlock message={reviewedCallMsg} />)

    // 多条目在 Header 上表现为变更数量，不再逐条展开输入。
    expect(container.querySelector('.thread-tool-summary-detail'))
      .toHaveTextContent('/src/App.tsx [2 changes]')
    expect(container.querySelectorAll('.thread-tool-preview-input-block')).toHaveLength(0)
    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
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
        edits: [{
          old_string: 'const a = 1;\nconst b = 2;',
          new_string: 'const a = 100;\nconst b = 200;',
        }],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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

    // Header 参数来自真实调用路径；审查 diff 仍使用 editReview 的权威内容。
    expect(container.querySelector('.thread-tool-summary-detail'))
      .toHaveTextContent('/src/App.tsx')

    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('-11|const a = 1')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+11|const a = 100')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('-41|const b = 2')).toHaveClass('is-removed')
    expect(within(preview as HTMLElement).getByText('+41|const b = 200')).toHaveClass('is-added')
    expect(within(preview as HTMLElement).getByText('@@ -10,3 +10,3 @@')).toHaveClass('is-hunk-header')

    expect(screen.queryByText(/拟修改内容/)).not.toBeInTheDocument()
  })

  it('edit 审查 diff 的预览同样固定为最后 10 行，完整审查材料在详情里', () => {
    const diffLines = [
      '@@ -1,12 +1,12 @@',
      ...Array.from({ length: 12 }, (_, i) => `-${i + 1}|old line ${i + 1}`),
    ]
    const reviewedCallMsg: ToolDialogueMessage = {
      id: 'call-edit-long-diff',
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-1',
      toolCallId: 'call-edit-long-diff',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: '/src/App.tsx',
        edits: [{ old_string: 'const a = 1;', new_string: 'const a = 100;' }],
      }),
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: null,
        editReview: {
          path: '/src/App.tsx',
          diff: diffLines.join('\n'),
          sha256: validSha,
        },
      },
    }

    const { container } = render(<ToolMessageBlock message={reviewedCallMsg} />)

    const preview = container.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview?.textContent).toBe(tailPreview(diffLines))
    expect(screen.getByText('… 上方还有 3 行')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(details).not.toBeNull()
    expect(within(details).getByText('@@ -1,12 +1,12 @@')).toHaveClass('is-hunk-header')
    expect(within(details).getByText('-1|old line 1')).toHaveClass('is-removed')
    expect(within(details).getByText('-12|old line 12')).toHaveClass('is-removed')
    // 详情不影响预览窗口。
    expect(container.querySelector('.thread-tool-preview-body.is-edit')?.textContent)
      .toBe(tailPreview(diffLines))
  })

  it('ApprovalCard 的 write 审批默认给完整冻结正文，审查 Diff 收在原生 summary 里', () => {
    const content = Array.from({ length: 30 }, (_, i) => `line-${i + 1}`).join('\n') + '\n'
    const frozenReview = {
      path: '/canonical/Full.txt',
      diff: '@@ -1,2 +1,2 @@\n-1|previous content\n+1|line-1',
      sha256: validSha,
    }

    const { container } = render(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="write"
        argumentsJson={JSON.stringify({ path: '/workspace/Full.txt', content })}
        approval={{ required: true, editReview: frozenReview }}
      />,
      { wrapper },
    )

    // 审批材料来自冻结参数：完整待写正文，而不是流式前缀或 10 行窗口。
    const contentView = container.querySelector('.thread-tool-preview-body.is-write')
    expect(contentView).toBeInTheDocument()
    expect(contentView?.textContent).toBe(content)
    expect(contentView).toHaveClass('is-bounded')

    // 冻结 diff 不替换正文，只通过原生 summary 按需展开。
    const summary = screen.getByText('审查 Diff').closest('summary')
    expect(summary).not.toBeNull()
    const details = summary?.closest('details') as HTMLElement | null
    expect(details).not.toBeNull()
    expect(container.querySelector('details')).toBe(details)
    expect(within(details as HTMLElement).getByText('-1|previous content')).toHaveClass('is-removed')
    expect(within(details as HTMLElement).getByText('+1|line-1')).toHaveClass('is-added')
  })

  it('ApprovalCard 缺失或非法 review 禁用 ALLOW，DENY 始终可用', () => {
    const { rerender } = render(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        })}
        approval={{ required: true }}
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
        argumentsJson={JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        })}
        approval={{
          required: true,
          editReview: { path: '/src/App.tsx', diff: '+b', sha256: 'invalid' },
        }}
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
        argumentsJson={JSON.stringify({
          path: '/src/App.tsx',
          edits: [{ old_string: 'a', new_string: 'b' }],
        })}
        approval={{
          required: true,
          editReview: { path: '/canonical/src/App.tsx', diff: '-a\n+b', sha256: validSha },
        }}
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
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
    }

    const { container, rerender } = render(<ToolMessageBlock message={callMsg} result={successResultMsg} />)

    expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
    expect(within(container).getByText('-11|const a = 1')).toBeInTheDocument()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
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
    const rawArgs = JSON.stringify({
      path: '/src/SharedComponent.tsx',
      edits: [{ old_string: 'old', new_string: 'new' }],
    })
    const approval = { required: true, editReview: reviewData }

    const { container: cardContainer } = render(
      <ApprovalCard
        threadId="thread-1"
        invocationId="inv-1"
        toolName="edit"
        argumentsJson={rawArgs}
        approval={approval}
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
          createdAt: Date.parse('2026-10-19T00:00:00Z'),
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
      payload,
      createdAt: 1792404000,
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
      threadId: 'thread-1',
      childThreadId: null,
      turnStartEntryId: 'turn-1',
      assistantEntryId,
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId,
      queuedBehindToolCallId: null,
      toolName: 'edit',
      rendererKey: 'edit',
      environmentId: null,
      requiredEnvironmentId: null,
      waitingForEnvironment: false,
      requiredEnvironmentName: null,
      environmentWaitFreshnessAt: null,
      argumentsJson: JSON.stringify({
        path: '/workspace/src/App.tsx',
        edits: [{ old_string: 'const value = 1;', new_string: 'const value = 2;' }],
      }),
      approval: null,
      result: null,
      error: null,
      createdAt: 1792404000,
      updatedAt: 1792404000,
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
    // 阶段 1: invocation overlay 存在（处于 WAITING_APPROVAL，approval 携带真实 editReview）
    const invPhase1 = createInvocation('inv-flow-1', 'asst-entry-1', 'call-edit-1', {
      status: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: 'Permission rules require approval',
        editReview: validReview,
      },
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
      approval: {
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-1',
        reason: null,
        editReview: validReview,
      },
      result: {
        toolCallId: 'call-edit-1',
        contents: [{ type: 'text', text: 'Edited successfully.' }],
        error: false,
        details: { editReview: validReview },
      },
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
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
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
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    unmount3()

    // 阶段 4: 刷新空 overlay（模拟页面从纯 durable entries 重新渲染，没有任何 transient 状态）
    const timelinePhase4 = buildThreadTimeline([asstEntry, toolResultEntry], [], [], null, null, [])
    const { container: container4, unmount: unmount4 } = renderTimelineTool(timelinePhase4.messages)

    const preview4 = container4.querySelector('.thread-tool-preview-body.is-edit')
    expect(preview4).toBeInTheDocument()
    expect(preview4?.textContent).toBe(sampleDiff)
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
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
      approval: {
        required: true,
        decision: 'ALLOWED',
        decisionId: 'dec-conflict',
        reason: null,
        editReview: frozenApprovalReview,
      },
      result: {
        toolCallId: 'call-conflict',
        contents: [{ type: 'text', text: 'Edited' }],
        error: false,
        details: { editReview: daemonResultReview },
      },
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
      approval: null, // 无需审批
      result: {
        toolCallId: 'call-yolo',
        contents: [{ type: 'text', text: 'Auto applied' }],
        error: false,
        details: { editReview: yoloReview },
      },
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
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    uLive()

    // YOLO 清理/刷新后
    const durableTimeline = buildThreadTimeline([asstEntry, toolResultEntry], [], [])
    const { container: durableContainer, unmount: uDurable } = renderTimelineTool(durableTimeline.messages)
    expect(durableContainer.querySelector('.thread-tool-preview-body.is-edit')?.textContent).toBe(yoloReview.diff)
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    uDurable()
  })

  it('失败终态：真实错误透传，不展示“编辑成功”，保留错误摘要', () => {
    const errorMsg = 'Edit 1: Could not find old_string in /workspace/src/App.tsx.'

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

  it('缺少 editReview 扩展时不假造 diff 也不进入转圈状态', () => {
    const asstEntry = createEntry('asst-no-review', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-no-review',
            toolName: 'edit',
            rendererKey: 'edit',
            argumentsJson: '{"path":"/workspace/src/App.tsx"}',
          },
        ],
      },
    })

    const toolResultEntry = createEntry('result-no-review', 'MESSAGE', {
      message: {
        role: 'TOOL',
        contents: [
          {
            type: 'tool_result',
            toolCallId: 'call-no-review',
            toolName: 'edit',
            rendererKey: 'edit',
            contents: [{ type: 'text', text: 'File updated.' }],
            error: false,
            detailsJson: '{}',
          },
        ],
      },
      toolResultMetadata: {
        assistantEntryId: 'asst-no-review',
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
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
    expandSettledTool()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
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
            // 非契约对象直接放在 content.details，无 detailsJson
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
    // 只认 detailsJson：没有 detailsJson 时 details 为 undefined，不恢复 review
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

describe('待审批 mutation 的冻结审查材料只出现一次', () => {
  const editArguments = JSON.stringify({
    path: '/src/Dedup.tsx',
    edits: [{ old_string: 'old', new_string: 'new' }],
  })
  const editReview = {
    path: '/src/Dedup.tsx',
    diff: '-01|old\n+01|new',
    sha256: validSha,
  }
  const writeLines = Array.from({ length: 14 }, (_, index) => `line-${index + 1}`)
  const writeArguments = JSON.stringify({
    path: '/src/DedupWrite.ts',
    workdir: '/workspace',
    content: writeLines.join('\n'),
  })
  const writeReview = {
    path: '/src/DedupWrite.ts',
    diff: writeLines.map((line, index) => `+${index + 1}|${line}`).join('\n'),
    sha256: null as string | null,
  }
  type Review = { path: string; diff: string; sha256: string | null }

  /** 待审批调用：durable invocation 已冻结审查，审批卡与时间线都能读到同一份事实。 */
  function pendingMutation(
    toolName: 'edit' | 'write',
    invocationId: string,
    argumentsJson: string,
    review: Review,
  ): ToolDialogueMessage {
    return {
      id: `call-${invocationId}`,
      role: 'tool',
      phase: 'call',
      subjectEntryId: 'asst-dedup',
      toolCallId: `call-${invocationId}`,
      toolName,
      rendererKey: toolName,
      arguments: argumentsJson,
      contents: [],
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
      status: 'done',
      invocationId,
      invocationStatus: 'WAITING_APPROVAL',
      approval: {
        required: true,
        decision: null,
        decisionId: null,
        reason: 'Permission rules require approval',
        editReview: review,
      },
      editReview: review,
    }
  }

  /** 与根控制区/待处理页相同的审批条目，卡片挂载即声明为该调用的唯一审查位。 */
  function approvalItem(
    invocationId: string,
    toolName: 'edit' | 'write',
    argumentsJson: string,
    review: Review,
  ): InteractionApproval {
    return {
      type: 'APPROVAL',
      toolInvocationId: invocationId,
      threadId: 'thread-1',
      sessionId: 'sess-dedup',
      rootThreadId: 'thread-1',
      owner: { kind: 'CHAT', id: 'chat-1', title: 'Review' },
      toolCallId: `call-${invocationId}`,
      toolName,
      argumentsJson,
      approval: { required: true, editReview: review },
      queuedSameFileCount: 0,
      createdAt: Date.parse('2026-10-19T00:00:00Z'),
    }
  }

  /**
   * 执行根 pane 视图：同一 provider 同时包住时间线与审批卡（与 RootAgentPane 一致），
   * 审批位只由这些真实渲染的条目构成。
   */
  function rootPaneView(tool: ReactNode, cards: InteractionApproval[]) {
    return (
      <ApprovalReviewProvider toolInvocationIds={visibleApprovalInvocationIds(cards)}>
        {tool}
        {cards.map((card) => (
          <InteractionCardBody key={card.toolInvocationId} item={card} />
        ))}
      </ApprovalReviewProvider>
    )
  }

  it('审批卡可见时 edit 工具卡只剩名称、目标与等待审批，diff 只出现一次', () => {
    const message = pendingMutation('edit', 'inv-edit-card', editArguments, editReview)
    const { container } = render(
      rootPaneView(
        <ToolMessageBlock message={message} />,
        [approvalItem('inv-edit-card', 'edit', editArguments, editReview)],
      ),
      { wrapper },
    )

    const card = container.querySelector('.thread-block-tool') as HTMLElement
    expect(card).toHaveAttribute('data-invocation-state', 'approval')
    expect(card.querySelector('.thread-tool-name')).toHaveTextContent('edit')
    expect(card.querySelector('.thread-tool-summary-detail')).toHaveTextContent('/src/Dedup.tsx')
    expect(card.querySelector('.thread-tool-state-label')).toHaveTextContent('等待审批')
    // 等待审批期间不再展开同一 diff，也没有详情入口。
    expect(card.querySelector('.thread-tool-preview-body')).toBeNull()
    expect(card.querySelector('.thread-tool-details')).toBeNull()
    expect(card.querySelector('.thread-disclosure-toggle')).toBeNull()

    const approval = container.querySelector('.interaction-approval-card') as HTMLElement
    expect(within(approval).getByText('-01|old')).toBeInTheDocument()
    // 整页只有审批卡这一份冻结 diff。
    expect(container.querySelectorAll('.thread-tool-preview-body.is-edit')).toHaveLength(1)
  })

  it('审批卡可见时 write 工具卡不再重复冻结正文，无卡的只读记录保留冻结 diff', () => {
    const message = pendingMutation('write', 'inv-write-card', writeArguments, writeReview)
    const { container, rerender } = render(
      rootPaneView(
        <ToolMessageBlock message={message} />,
        [approvalItem('inv-write-card', 'write', writeArguments, writeReview)],
      ),
      { wrapper },
    )

    const card = container.querySelector('.thread-block-tool') as HTMLElement
    expect(card.querySelector('.thread-tool-preview-body')).toBeNull()
    expect(card.querySelector('.thread-disclosure-toggle')).toBeNull()
    // 审批卡自己只展示一份待写正文（冻结 diff 收在原生 summary 里）。
    expect(container.querySelectorAll('.thread-tool-preview-body.is-write')).toHaveLength(1)

    // 只读视图（子 Agent 面板、审批卡未加载/未渲染）没有审查位，工具卡必须继续交出 diff。
    rerender(<ToolMessageBlock message={message} />)
    const readonlyCard = container.querySelector('.thread-block-tool') as HTMLElement
    const readonlyDiff = readonlyCard.querySelector('.thread-tool-preview-body.is-edit')
    expect(readonlyDiff).toBeInTheDocument()
    expect(readonlyDiff?.textContent)
      .toBe(writeLines.slice(-10).map((line, index) => `+${index + 5}|${line}`).join('\n'))
    expect(readonlyCard.querySelector('.thread-tool-preview-body.is-write')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    expect(readonlyCard.querySelector('.thread-tool-details .thread-tool-preview-body.is-edit')?.textContent)
      .toBe(writeReview.diff)
  })

  it('只有同一调用自己的审批卡参与去重，其他调用的卡片不影响本调用的 diff', () => {
    const message = pendingMutation('edit', 'inv-shared-a', editArguments, editReview)
    const { container } = render(
      rootPaneView(
        <ToolMessageBlock message={message} />,
        [approvalItem('inv-shared-b', 'edit', editArguments, editReview)],
      ),
      { wrapper },
    )

    const card = container.querySelector('.thread-block-tool') as HTMLElement
    expect(card.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
    expect(card.querySelector('.thread-disclosure-toggle')).toBeNull()
  })

  it('审批结束后工具卡按既有规则展示结果，不依赖审批卡是否还在', () => {
    const message = pendingMutation('edit', 'inv-terminal', editArguments, editReview)
    const result: ToolDialogueMessage = {
      id: 'result-inv-terminal',
      role: 'tool',
      phase: 'result',
      subjectEntryId: 'asst-dedup',
      toolCallId: 'call-inv-terminal',
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: editArguments,
      contents: [{ type: 'text', text: 'Edited successfully.' }],
      createdAt: Date.parse('2026-10-19T00:00:01Z'),
      status: 'done',
    }
    const card = approvalItem('inv-terminal', 'edit', editArguments, editReview)
    const { container, rerender } = render(
      rootPaneView(
        <ToolMessageBlock message={{ ...message, invocationStatus: 'SUCCEEDED' }} result={result} />,
        [card],
      ),
      { wrapper },
    )

    // 允许并完成：终态恢复既有展示规则（审批快照 diff 优先），不受待审批去重影响。
    const succeededCard = container.querySelector('.thread-block-tool') as HTMLElement
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('data-invocation-state', 'succeeded')
    expect(succeededCard.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()

    // 拒绝：失败终态保留真实错误，不伪造成成功摘要。
    rerender(
      rootPaneView(
        <ToolMessageBlock
          message={{ ...message, status: 'error', invocationStatus: 'FAILED' }}
          result={{ ...result, status: 'error', errorMessage: 'The user denied this change.' }}
        />,
        [card],
      ),
    )
    expect(screen.getByText('The user denied this change.')).toBeInTheDocument()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
  })

  it('审批位消失后 diff 回到时间线，首屏无卡时也不会丢材料', () => {
    const message = pendingMutation('edit', 'inv-reload', editArguments, editReview)
    const card = approvalItem('inv-reload', 'edit', editArguments, editReview)
    const { container, rerender } = render(<ToolMessageBlock message={message} />, { wrapper })
    const toolCard = () => container.querySelector('.thread-block-tool') as HTMLElement

    // 分页未加载 / 首屏仍在读取：没有审查位，材料留在时间线。
    expect(toolCard().querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()

    rerender(rootPaneView(<ToolMessageBlock message={message} />, [card]))
    expect(toolCard().querySelector('.thread-tool-preview-body')).toBeNull()

    // 审批卡随交互列表刷新卸载：材料立即回到时间线。
    rerender(rootPaneView(<ToolMessageBlock message={message} />, []))
    expect(toolCard().querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
  })
})
