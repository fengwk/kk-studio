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
            { type: 'file', name: 'result.json', mime: 'application/json', data: 'file:///tmp/result.json' },
            { type: 'audio', name: '', mime: 'audio/mpeg', data: '' },
          ],
        })}
      />,
    )

    expect(screen.getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    // file: URI is shown as text (not as an inline media element)
    expect(screen.getByText('file:///tmp/result.json')).toBeInTheDocument()
    expect(screen.getByText('[audio] audio/mpeg')).toBeInTheDocument()
    // Two open-raw links: file: (URI is shown as text + link) and audio (no previewable src, has fallback link)
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
              // ResourceMessageContent.preview is a TEXT excerpt, not a URL.
              preview: '{"version":1,"tools":["web-search"]}',
            },
          ],
        })}
      />,
    )

    expect(screen.getByText('file:///tmp/manifest.json')).toBeInTheDocument()
    // The preview must render as plain text, never as an image with src=<preview text>.
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

    // Re-enabled once the request settles.
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

    // Hidden when approval already decided.
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

    // Hidden when approval not required.
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

    // Hidden when no decision handler.
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

    // Terminal data: resource contents render under the call until the durable result Entry
    // arrives (auto-preview); a remote http(s) resource renders as stable URI + link instead.
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
    // Remote resources are never inlined: stable URI text + explicit link only.
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('https://example.com/remote.png')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开原始内容' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )

    // No transient block when the durable result phase already took over.
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
})
