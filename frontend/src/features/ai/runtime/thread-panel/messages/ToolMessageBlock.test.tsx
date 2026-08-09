import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'

function message(overrides: Partial<ToolDialogueMessage> = {}): ToolDialogueMessage {
  return {
    id: 'tool-1',
    role: 'tool',
    subjectEntryId: null,
    createdAt: null,
    status: 'done',
    phase: 'result',
    text: '',
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'read',
    arguments: '',
    attachments: [],
    ...overrides,
  }
}

describe('ToolMessageBlock', () => {
  it('renders a call placeholder and a fallback tool name', () => {
    render(<ToolMessageBlock message={message({ phase: 'call', toolName: '', status: 'streaming' })} />)

    expect(screen.getByText(/Tool/)).toBeInTheDocument()
    expect(screen.getByText('running')).toBeInTheDocument()
    expect(screen.getByText('（无参数）')).toBeInTheDocument()
    expect(screen.queryByText('等待工具结果…')).not.toBeInTheDocument()
  })

  it('renders a result and a distinct error message', () => {
    const { container, rerender } = render(
      <ToolMessageBlock
        message={message({
          status: 'error',
          arguments: '{"path":"missing"}',
          text: 'read failed',
          errorMessage: 'file not found',
        })}
      />,
    )

    expect(container.firstElementChild).toHaveClass('error')
    expect(screen.getByText('error')).toBeInTheDocument()
    expect(screen.queryByText('{"path":"missing"}')).not.toBeInTheDocument()
    expect(screen.getByText('read failed')).toBeInTheDocument()
    expect(screen.getByText('file not found')).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({ status: 'error', text: 'same error', errorMessage: 'same error' })}
      />,
    )
    expect(screen.getAllByText('same error')).toHaveLength(1)
  })

  it('renders streaming and completed empty result placeholders without call duplication', () => {
    const { rerender } = render(
      <ToolMessageBlock message={message({ phase: 'result', status: 'streaming' })} />,
    )

    expect(screen.getByText('等待工具结果…')).toBeInTheDocument()
    expect(screen.queryByText('tool call ·')).not.toBeInTheDocument()

    rerender(<ToolMessageBlock message={message({ phase: 'result', status: 'done' })} />)
    expect(screen.getByText('无文本输出')).toBeInTheDocument()
  })

  it('renders image, linked file, and attachment fallbacks', () => {
    render(
      <ToolMessageBlock
        message={message({
          attachments: [
            { type: 'image', name: 'preview.png', mime: 'image/png', data: 'data:image/png;base64,aGVsbG8=' },
            {
              type: 'file',
              name: 'result.json',
              mime: 'application/json',
              data: 'file:///tmp/result.json',
              size: 2,
              sha256: 'a'.repeat(64),
              downloadHref:
                `/api/ai/runtime/resources/${'a'.repeat(64)}`
                + '?mediaType=application%2Fjson&size=2&name=result.json',
            },
            { type: 'audio', name: '', mime: 'audio/mpeg', data: '' },
          ],
        })}
      />,
    )

    expect(screen.getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    // file: URI 仅以文本形式展示（不作为内联媒体元素）
    expect(screen.getByText('file:///tmp/result.json')).toBeInTheDocument()
    expect(screen.getByText('[audio] audio/mpeg')).toBeInTheDocument()
    // 两个"打开原始内容"链接：file:（URI 以文本+链接形式展示）和 audio（无可预览 src，提供后备链接）
    expect(screen.getAllByRole('link', { name: '打开原始内容' })).toHaveLength(2)
  })

  it('renders a non-previewable resource preview as TEXT (never as an img src)', () => {
    render(
      <ToolMessageBlock
        message={message({
          attachments: [
            {
              type: 'file',
              name: 'manifest.json',
              mime: 'application/json',
              data: 'file:///tmp/manifest.json',
              // ResourceMessageContent.preview 是文本摘录，而非 URL。
              preview: '{"version":1,"tools":["web-search"]}',
            },
          ],
        })}
      />,
    )

    expect(screen.getByText('file:///tmp/manifest.json')).toBeInTheDocument()
    // preview 必须以纯文本形式渲染，绝不能作为 src=<preview text> 的图片。
    expect(screen.getByText('{"version":1,"tools":["web-search"]}')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('disables approval buttons while an approval request is in flight', () => {
    const onDecideApproval = vi.fn()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
        approvalPending
      />,
    )

    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()

    // 请求结束后再次启用。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
        approvalPending={false}
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
  })

  it('renders the approval bar only when required + undecided + onDecideApproval present', async () => {
    const onDecideApproval = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )

    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '允许' }))
    expect(onDecideApproval).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'tool-1' }), 'ALLOW')

    await user.click(screen.getByRole('button', { name: '拒绝' }))
    expect(onDecideApproval).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'tool-1' }), 'DENY')

    // 审批已决定时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()

    // 无需审批时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: false, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()

    // 没有决策处理器时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })

  it('shows the persisted ALLOWED/DENIED decision with optional reason instead of buttons', () => {
    const onDecideApproval = vi.fn()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: 'looks safe' },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()
    expect(screen.getByText(/已允许/)).toBeInTheDocument()
    expect(screen.getByText(/looks safe/)).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'DENIED', decisionId: 'd-2', reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.getByText(/已拒绝/)).toBeInTheDocument()
  })

  it('renders a transient result block under the active call for partial/terminal/resource/error', () => {
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          invocationId: 'inv-1',
          partial: 'streaming answer',
        })}
      />,
    )
    expect(screen.getByText('streaming answer')).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'error',
          invocationId: 'inv-1',
          partialErrorText: 'boom',
        })}
      />,
    )
    expect(screen.getByText('boom')).toBeInTheDocument()

    // 终止态数据：资源内容会渲染在调用下，直到持久的 result Entry 到达（自动预览）；
    // 远程 http(s) 资源则只渲染为稳定 URI + 链接。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          invocationId: 'inv-1',
          partialAttachments: [
            {
              type: 'image',
              name: 'a.png',
              mime: 'image/png',
              data: 'data:image/png;base64,aW1n',
            },
          ],
        })}
      />,
    )
    expect(screen.getByRole('img', { name: 'a.png' })).toBeInTheDocument()
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          invocationId: 'inv-1',
          partialAttachments: [
            {
              type: 'image',
              name: 'remote.png',
              mime: 'image/png',
              data: 'https://example.com/remote.png',
            },
          ],
        })}
      />,
    )
    // 远程资源绝不内联：只展示稳定 URI 文本 + 显式链接。
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('https://example.com/remote.png')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开原始内容' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )

    // 持久 result 阶段已接管时，不显示瞬态块。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          invocationId: 'inv-1',
        })}
      />,
    )
    expect(screen.queryByText('streaming answer')).not.toBeInTheDocument()
  })

  it('overlays partial text over durable result text', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          status: 'streaming',
          text: 'stable text',
          partial: 'streaming partial',
        })}
      />,
    )
    expect(container.textContent).toContain('streaming partial')
    expect(container.textContent).not.toContain('stable text')
  })

  it('keeps complete input visible and constrains only output to the tail viewport', () => {
    const { container, rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: '{"line1":1,\n"line2":2,\n"line3":3,\n"line4":4,\n"line5":5,\n"line6":6}',
        })}
      />,
    )
    const input = container.querySelector('.thread-tool-input')
    expect(input).toHaveTextContent('"line1":1')
    expect(input).toHaveTextContent('"line6":6')
    expect(input).not.toHaveClass('thread-tool-output')

    rerender(
      <ToolMessageBlock
        message={message({ phase: 'result', text: 'one\ntwo\nthree\nfour\nfive\nsix' })}
      />,
    )
    const output = container.querySelector('.thread-tool-output')
    expect(output).toHaveTextContent('one')
    expect(output).toHaveTextContent('six')
    expect(output).toHaveAttribute('tabindex', '0')
  })

  it('delegates the body to a compile-time renderer without duplicating the default output', () => {
    render(
      <ToolMessageBlock
        message={message({ text: 'default output' })}
        renderer={({ message: rendered }) => (
          <div>custom renderer: {rendered.text}</div>
        )}
      />,
    )
    expect(screen.getByText('custom renderer: default output')).toBeInTheDocument()
    expect(screen.queryByText('default output')).not.toBeInTheDocument()
  })
})
