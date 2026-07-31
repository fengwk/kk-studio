import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
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
            { type: 'image', name: 'preview.png', mime: 'image/png', data: 'aGVsbG8=' },
            { type: 'file', name: 'result.json', mime: 'application/json', data: '/api/ai/runtime/artifacts/1' },
            { type: 'audio', name: '', mime: 'audio/mpeg', data: '' },
          ],
        })}
      />,
    )

    expect(screen.getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    expect(screen.getByText('[file] result.json')).toBeInTheDocument()
    expect(screen.getByText('[audio] audio/mpeg')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: '打开原始内容' })).toHaveLength(2)
  })

})
