import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { TaskToolRenderer } from '@/features/thread/runtime/thread-panel/messages/TaskToolRenderer'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import { ResourceBlobUrlContext } from '@/features/thread/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/thread-timeline/transcript-reading'
import { setLocale } from '@/shared/i18n'
import type {
  ToolAttachment,
  ToolContent,
  ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

function message(overrides: Partial<ToolDialogueMessage> = {}): ToolDialogueMessage {
  return {
    id: 'tool-1',
    role: 'tool',
    subjectEntryId: null,
    createdAt: 1785232800000,
    status: 'done',
    phase: 'result',
    contents: [],
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'read',
    arguments: '',
    ...overrides,
  }
}

function text(value: string): ToolContent {
  return { type: 'text', text: value }
}

function json(value: unknown): ToolContent {
  return { type: 'json', value }
}

function resource(attachment: Partial<ToolAttachment> & { name: string }): ToolContent {
  return {
    type: 'resource',
    attachment: {
      type: 'file',
      mime: '',
      data: '',
      ...attachment,
    },
  }
}

function card() {
  return document.querySelector<HTMLElement>('.thread-block-tool')!
}

function outputTexts(): string[] {
  return Array.from(document.querySelectorAll('.thread-tool-contents > *'))
    .map((element) => element.textContent ?? '')
}

const expandButton = () => screen.queryByRole('button', { name: '展开详情' })
const collapseButton = () => screen.queryByRole('button', { name: '收起详情' })

describe('ToolMessageBlock shell', () => {
  it('shows the localized waiting suffix only while a READY predecessor is unfinished', () => {
    const { container, rerender } = render(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'edit', invocationStatus: 'READY',
      queuedBehindToolCallId: 'earlier-call', queuedBehindToolCallUnfinished: true,
    })} />)
    expect(container.querySelector('.thread-tool-summary'))
      .toHaveTextContent('edit · 等待前一个对此文件的修改')

    setLocale('en-US')
    rerender(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'edit', invocationStatus: 'READY',
      queuedBehindToolCallId: 'earlier-call', queuedBehindToolCallUnfinished: true,
    })} />)
    expect(container.querySelector('.thread-tool-summary'))
      .toHaveTextContent('edit · Waiting for the earlier change to this file')

    rerender(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'edit', invocationStatus: 'READY',
      queuedBehindToolCallId: 'earlier-call', queuedBehindToolCallUnfinished: false,
    })} />)
    expect(container.querySelector('.thread-tool-summary'))
      .not.toHaveTextContent('Waiting for the earlier change to this file')
    setLocale('zh-CN')
  })

  it('localizes the real environment wait label in English', () => {
    setLocale('en-US')
    render(<ToolMessageBlock message={message({
      phase: 'call', invocationStatus: 'READY', waitingForEnvironment: true,
      requiredEnvironmentName: 'archlinux',
    })} />)
    expect(screen.getByText('· Waiting for environment archlinux to come online')).toBeInTheDocument()
    expect(document.querySelector('.animate-spin')).toBeNull()
  })
  it.each([
    ['READY', 'queued', '排队中', false],
    ['WAITING_APPROVAL', 'approval', '等待审批', true],
    ['WAITING_INPUT', 'input', '等待输入', true],
    ['RUNNING', 'running', '执行中', false],
    ['SUCCEEDED', 'succeeded', '成功', false],
    ['FAILED', 'failed', '失败', true],
    ['CANCELLED', 'cancelled', '已停止', true],
    ['UNKNOWN', 'unknown', '执行结果未知', true],
  ])('durable %s has a distinct label and only RUNNING spins', (status, state, label, labeled) => {
    const { container } = render(
      <ToolMessageBlock message={message({ phase: 'call', invocationStatus: status })} />,
    )
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('data-invocation-state', state)
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('title', `read: ${label}`)
    // 只有需要用户注意的状态把标签保留在 Header；排队、执行中和成功不加文字噪音。
    if (labeled) {
      expect(container.querySelector('.thread-tool-state-label')).toHaveTextContent(label)
    } else {
      expect(container.querySelector('.thread-tool-state-label')).toBeNull()
    }
    expect(container.querySelector('.thread-disclosure-toggle')).toBeNull()
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', String(status === 'RUNNING'))
  })

  it('waits statically for the frozen environment and uses invocation state without a task receipt', () => {
    const { container, rerender } = render(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'bash', invocationStatus: 'READY',
      requiredEnvironmentId: 'environment-frozen', requiredEnvironmentName: 'archlinux',
      waitingForEnvironment: true, arguments: '{"command":"pwd"}',
    })} />)
    expect(container.querySelector('.thread-tool-summary')).toHaveTextContent('bash · 等待环境 archlinux 上线')
    expect(container.querySelector('.animate-spin')).toBeNull()
    rerender(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'task', invocationStatus: 'SUCCEEDED',
    })} />)
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('title', 'task: 成功')
    expect(screen.queryByText('· 成功')).not.toBeInTheDocument()
  })

  it('shows WAITING_CHILD as a static localized subagent-result wait', () => {
    const { container, rerender } = render(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'task', invocationStatus: 'WAITING_CHILD',
    })} />)
    const disclosure = container.querySelector('.thread-block-tool')
    expect(disclosure).toHaveAttribute('data-invocation-state', 'waiting-child')
    expect(disclosure).toHaveAttribute('title', 'task: 等待子代理结果')
    expect(container.querySelector('.thread-tool-state-label')).toHaveTextContent('等待子代理结果')
    expect(disclosure).toHaveAttribute('aria-busy', 'false')
    expect(container.querySelector('.animate-spin')).toBeNull()

    setLocale('en-US')
    rerender(<ToolMessageBlock message={message({
      phase: 'call', toolName: 'task', invocationStatus: 'WAITING_CHILD',
    })} />)
    expect(container.querySelector('.thread-tool-state-label'))
      .toHaveTextContent('Waiting for subagent result')
    expect(container.querySelector('.animate-spin')).toBeNull()
    setLocale('zh-CN')
  })

  it('does not label a SUCCEEDED invocation with an error result as a successful tool operation', () => {
    const { container } = render(<ToolMessageBlock
      message={message({ phase: 'call', invocationStatus: 'SUCCEEDED' })}
      result={message({ phase: 'result', status: 'error', errorMessage: 'permission failure' })}
    />)
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('data-invocation-state', 'failed')
    expect(screen.getByText('permission failure')).toBeInTheDocument()
    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('title', 'read: 失败')
  })

  it('announces reading intent when the user toggles a card (streaming growth must not steal it)', async () => {
    const onReadingIntent = vi.fn()
    document.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    try {
      render(
        <ToolMessageBlock
          message={message({
            phase: 'result',
            status: 'streaming',
            toolName: 'bash',
            rendererKey: 'bash',
            arguments: '{"command":"npm test"}',
            contents: [text(Array.from({ length: 20 }, (_, index) => `line-${index + 1}`).join('\n'))],
          })}
        />,
      )

      await userEvent.click(expandButton()!)

      // 展开/收起是用户对只读卡片的交互意图：外层据此暂停贴底。
      expect(onReadingIntent).toHaveBeenCalledTimes(1)
      expect(onReadingIntent.mock.calls[0]?.[0]).toMatchObject({ bubbles: true })
    } finally {
      document.removeEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    }
  })

  it('keeps the whole header parameter text in one addressable single-line region', () => {
    const longValue = 'x'.repeat(4000)
    render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          status: 'done',
          toolName: 'mcp__filesystem__search',
          rendererKey: undefined,
          arguments: `{"query":"${longValue}"}`,
          contents: [text('no matches')],
        })}
      />,
    )

    const detail = card().querySelector<HTMLElement>('.thread-tool-summary-detail')
    expect(detail).not.toBeNull()
    // 单行 compact：完整原文留在 DOM 中（可选中复制/横向滚动），不做省略或截断。
    expect(detail!.textContent).toBe(`{"query":"${longValue}"}`)
    expect(detail).toHaveAttribute('tabindex', '0')
    expect(detail!.getAttribute('title')).toBeNull()
  })

  it('renders one full-width card with no copy action and no nested surface', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: '{"path":"/app"}',
          status: 'error',
          errorMessage: 'Environment tool read has no environment binding.',
        })}
      />,
    )

    expect(screen.queryByRole('button', { name: '复制工具预览' })).not.toBeInTheDocument()
    expect(container.querySelector('.thread-turn-tool > .thread-block-tool')).toBeInTheDocument()
    expect(container.querySelectorAll('.thread-block-tool')).toHaveLength(1)
    expect(screen.queryByText('（无参数）')).not.toBeInTheDocument()
    expect(screen.queryByText('等待工具结果…')).not.toBeInTheDocument()
    expect(screen.queryByText('无文本输出')).not.toBeInTheDocument()
  })

  it('does not mistake a streamed call for a running invocation', () => {
    render(<ToolMessageBlock message={message({ phase: 'call', toolName: '', status: 'streaming' })} />)

    expect(screen.getByText('Tool')).toBeInTheDocument()
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'false')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('title', 'Tool: 生成调用中')
    expect(document.querySelector('.animate-spin')).toBeNull()
    expect(screen.queryByText(/WORKING|DONE|FAILED/)).not.toBeInTheDocument()
  })

  it('keeps the complete header parameter text in the DOM for viewing and copying', () => {
    // 超长值完整渲染为可选中的文本；不能只靠被截断的 title 提示承载。
    const longValue = 'y'.repeat(4_000)
    const values = {
      flag: false,
      count: 0,
      empty: '',
      script: 'line1\n  line2 "quoted"',
      long: longValue,
    }
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'mcp__server__tool',
          rendererKey: 'mcp__server__tool',
          arguments: JSON.stringify(values),
        })}
      />,
    )

    const detail = container.querySelector<HTMLElement>('.thread-tool-summary-detail')
    expect(detail?.textContent).toBe(JSON.stringify(values))
    expect(detail?.textContent).not.toContain('…')
    expect(detail?.textContent).toContain('"flag":false')
    expect(detail?.textContent).toContain('"count":0')
    expect(detail?.textContent).toContain('"empty":""')
    expect(detail?.textContent).not.toContain('timeout_seconds')
    expect(detail?.textContent).not.toContain('workdir')
  })

  it('collapses an unknown tool result by default and expands to the result only', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'custom',
          rendererKey: 'custom',
          arguments: '{"query":"needle","flag":false}',
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'custom',
          rendererKey: 'custom',
          arguments: '{"query":"needle","flag":false}',
          contents: [text('search result body')],
        })}
      />,
    )

    // Header 呈现紧凑参数 JSON，结果默认折叠；无「参数/结果」标题。
    expect(container.querySelector('.thread-tool-summary-detail')).toHaveTextContent(
      '{"query":"needle","flag":false}',
    )
    expect(screen.queryByText('search result body')).not.toBeInTheDocument()
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')

    await user.click(expandButton()!)
    expect(screen.getByText('search result body')).toBeInTheDocument()
    expect(container.textContent).not.toContain('参数')
    expect(container.textContent).not.toContain('结果')
    // 参数已在 Header 展示过，展开区不重复。
    expect(container.querySelectorAll('.thread-tool-summary-detail')).toHaveLength(1)
    expect(container.querySelector('.thread-disclosure-body')?.textContent).not.toContain('"query"')
  })

  it('does not offer a toggle when the card has no body to collapse', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({ phase: 'call', toolName: 'bash', arguments: '{"command":"pwd"}' })}
      />,
    )

    expect(expandButton()).not.toBeInTheDocument()
    expect(container.querySelector('.thread-disclosure-toggle')).not.toBeInTheDocument()
    // 没有正文时不渲染空正文容器。
    expect(container.querySelector('.thread-disclosure-body')).not.toBeInTheDocument()
  })

  it('derives pending/success/error states from the paired result', () => {
    const { rerender } = render(
      <ToolMessageBlock message={message({ phase: 'call', status: 'streaming' })} />,
    )
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'false')

    rerender(
      <ToolMessageBlock
        message={message({ phase: 'call', status: 'streaming' })}
        result={message({ phase: 'result', status: 'done', contents: [text('result text')] })}
      />,
    )
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-success')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'false')

    rerender(
      <ToolMessageBlock
        message={message({ phase: 'call', status: 'streaming' })}
        result={message({
          phase: 'result',
          status: 'error',
          errorMessage: 'execution failed',
          contents: [text('execution failed')],
        })}
      />,
    )
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-error')
  })

  it('treats a standalone result with missing status as success', () => {
    render(<ToolMessageBlock message={message({ phase: 'result', status: undefined, contents: [text('ok')] })} />)
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-success')
  })
})

describe.each(['bash', 'mcp__server__tool'])('ToolMessageBlock empty regions: %s', (toolName) => {
  it.each([
    ['preparing', { phase: 'call', status: 'streaming' }],
    ['queued', { phase: 'call', invocationStatus: 'READY' }],
    ['running', { phase: 'call', status: 'streaming', invocationStatus: 'RUNNING' }],
    ['succeeded', { phase: 'result', invocationStatus: 'SUCCEEDED' }],
  ] as const)('omits empty preview and body while %s', (_state, overrides) => {
    const { container } = render(<ToolMessageBlock message={message({
      toolName, rendererKey: toolName, ...overrides,
    })} />)

    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
    expect(container.querySelector('.thread-tool-pre')).toBeNull()
  })

  it('adds the preview only when partial output arrives and removes it for an empty final result', () => {
    const call = message({
      phase: 'call', status: 'streaming', invocationStatus: 'RUNNING',
      toolName, rendererKey: toolName,
    })
    const { container, rerender } = render(<ToolMessageBlock message={call} />)
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()

    const output = 'first\n\nlast\n'
    rerender(<ToolMessageBlock message={{ ...call, partialContents: [text(output)] }} />)
    expect(container.querySelector('.thread-disclosure-preview')).not.toBeNull()
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe('first\n\nlast')

    rerender(<ToolMessageBlock
      message={{ ...call, partialContents: [text(output)] }}
      result={message({ toolName, rendererKey: toolName, contents: [text('')] })}
    />)
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
    expect(container.querySelector('.thread-tool-pre')).toBeNull()
  })

  it('does not keep an empty preview after the user has expanded the output', async () => {
    const result = message({
      toolName, rendererKey: toolName,
      contents: [text(Array.from({ length: 12 }, (_, index) => `line-${index}`).join('\n'))],
    })
    const { container, rerender } = render(<ToolMessageBlock message={result} />)
    await userEvent.click(expandButton()!)
    expect(collapseButton()).toBeInTheDocument()

    rerender(<ToolMessageBlock message={{ ...result, contents: [text('')] }} />)
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
    expect(container.querySelector('.thread-tool-pre')).toBeNull()
  })
})

describe('ToolMessageBlock nonempty content', () => {
  it('does not keep an empty body after expanded read output is cleared', async () => {
    const result = message({ contents: [text('result text')] })
    const { container, rerender } = render(<ToolMessageBlock message={result} />)
    await userEvent.click(expandButton()!)
    expect(container.querySelector('.thread-disclosure-body')).toHaveTextContent('result text')

    rerender(<ToolMessageBlock message={{ ...result, contents: [text('')] }} />)
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
    expect(container.querySelector('.thread-tool-pre')).toBeNull()
  })

  it.each([false, 0, null])('keeps JSON %s visible', (value) => {
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'bash', rendererKey: 'bash', contents: [json(value)],
    })} />)
    expect(container.querySelector('.thread-disclosure-preview')).not.toBeNull()
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe(String(value))
  })

  it('omits empty text items without changing resources or real output whitespace', async () => {
    const output = '  first\n\nlast\n'
    const { container } = render(<ToolMessageBlock message={message({
      contents: [text(''), resource({ name: 'output.bin' }), text(output), text('')],
    })} />)
    await userEvent.click(expandButton()!)

    expect(container.querySelectorAll('.thread-tool-output')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe(output)
    expect(container.querySelector('.thread-tool-resource')).toHaveTextContent('output.bin')
  })
})

describe('ToolMessageBlock failures', () => {
  it('keeps the failure block visible in its own slot, marks the truncated tail, and shows the rest only on demand', async () => {
    const user = userEvent.setup()
    const errorText = 'connection refused\nsecond line\nthird line\nfourth line'
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'custom',
          rendererKey: 'custom',
          arguments: '{"query":"x"}',
        })}
        result={message({
          phase: 'result',
          toolName: 'custom',
          rendererKey: 'custom',
          status: 'error',
          errorMessage: errorText,
          contents: [text(errorText)],
        })}
      />,
    )

    // 失败原文独占错误槽位，不混进 preview 或 body。
    const slot = container.querySelector('.thread-disclosure-error-slot')
    expect(slot).not.toBeNull()
    expect(slot?.querySelector('.thread-tool-error')).not.toBeNull()
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
    expect(screen.getAllByText(/connection refused/)).toHaveLength(1)

    // 收起时最多三行，省略号标记后面还有失败原文。
    expect(container.querySelector('.thread-tool-error')).not.toHaveTextContent('fourth line')
    expect(container.querySelector('.thread-tool-error')?.textContent).toMatch(/…$/)

    // 完整失败原文只能通过 Header 的详情开关查看。
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-details')).toHaveTextContent('fourth line')
  })

  it('keeps a mutation failure explanation in the body instead of dropping it', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: '{"path":"/src/App.tsx"}',
          status: 'done',
        })}
        result={message({
          phase: 'result',
          toolName: 'edit',
          rendererKey: 'edit',
          status: 'error',
          errorMessage: 'permission denied',
          contents: [text('permission denied'), text('extra failure detail')],
        })}
      />,
    )

    expect(container.querySelector('.thread-tool-error')).toHaveTextContent('permission denied')
    expect(screen.queryByText('extra failure detail')).not.toBeInTheDocument()
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()

    await user.click(expandButton()!)
    expect(screen.getByText('extra failure detail')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-error')).toHaveTextContent('permission denied')
    expect(screen.queryByText('编辑成功')).not.toBeInTheDocument()
  })

  it('renders the failure summary next to unrelated result text', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'error',
          errorMessage: 'exit code 1',
          contents: [text('npm ERR! missing script: test')],
        })}
      />,
    )

    // 正文不是失败文本本身时必须保留摘要，不能吞掉失败原因。
    expect(screen.getByText('npm ERR! missing script: test')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-error')).toHaveTextContent('exit code 1')
  })

  it('shows only the error state for a bodiless error result without raw text', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({ status: 'error', toolName: 'mcp__server__tool', rendererKey: 'x' })}
      />,
    )

    // 无原文错误不补通用「工具执行失败」文案，也不展开空框，只保留错误态。
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-error')
    expect(container.querySelector('.thread-tool-error')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-disclosure-toggle')).not.toBeInTheDocument()
    expect(screen.queryByText('工具执行失败。')).not.toBeInTheDocument()
  })

  it('keeps a read error visible while collapsed and never repeats its raw text', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          toolName: 'read',
          rendererKey: 'read',
          arguments: '{"path":"/tmp/bin.dat"}',
          status: 'error',
          errorMessage: 'file appears to be binary',
          contents: [text('file appears to be binary')],
        })}
      />,
    )

    // read 默认收起，但失败事实优先：错误块始终可见，原文只出现一次。
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-error')
    expect(container.querySelector('.thread-tool-error')).toHaveTextContent('file appears to be binary')
    expect(screen.getAllByText('file appears to be binary')).toHaveLength(1)
    expect(container.querySelector('.thread-disclosure-body')).not.toBeInTheDocument()
    // 错误块已完整承载同一文本，没有额外的隐藏正文可展开。
    expect(container.querySelector('.thread-disclosure-toggle')).not.toBeInTheDocument()
  })

  it('keeps a streaming partial error visible before the durable result arrives', () => {
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'error',
          partialErrorText: 'boom',
        })}
      />,
    )

    expect(screen.getByText('boom')).toBeInTheDocument()
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-error')
  })
})

describe('ToolMessageBlock per-tool bodies', () => {
  it('keeps read text collapsed, keeps ordered contents, and formats JSON bodies', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: '{"path":"/app/file.ts"}',
        })}
        result={message({
          phase: 'result',
          arguments: '{"path":"/app/file.ts"}',
          contents: [
            text('first text'),
            resource({ type: 'file', name: 'out.txt', mime: 'text/plain', data: 'https://example.test/out.txt' }),
            { type: 'json', value: { nested: true, n: 1 } },
            text('last text'),
          ],
        })}
      />,
    )

    expect(container.querySelector('.thread-disclosure-body')).not.toBeInTheDocument()
    await user.click(expandButton()!)

    // 有序保留：text → resource → json → text，不被压平成「全部文本 + 全部附件」。
    const order = outputTexts()
    expect(order).toHaveLength(4)
    expect(order[0]).toContain('first text')
    expect(order[1]).toContain('out.txt')
    expect(order[2]).toContain('"nested": true')
    expect(order[2]).toContain('"n": 1')
    expect(order[3]).toContain('last text')
    // JSON 内容不使用 Markdown/guess 渲染。
    expect(document.querySelectorAll('.thread-tool-output.is-json')).toHaveLength(1)
  })

  it('previews a read image after expanding from the authoritative MIME', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({ phase: 'call', arguments: '{"path":"/tmp/shot.png"}' })}
        result={message({
          phase: 'result',
          arguments: '{"path":"/tmp/shot.png"}',
          contents: [
            resource({
              type: 'image',
              name: 'shot.png',
              mime: 'image/png',
              data: 'data:image/png;base64,aGVsbG8=',
            }),
          ],
        })}
      />,
    )

    // 只读记录默认收起；用户展开后才解析并预览图片。
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByRole('img', { name: 'shot.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    expect(collapseButton()).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelector('.thread-tool-output.is-bounded')).toBeNull()
  })

  it('resolves a blob read image through the storage media type after expanding', async () => {
    const user = userEvent.setup()
    const resolveBlobUrls = vi.fn(async () => ({
      original: 'https://s3.test/shot.png',
      preview: 'https://s3.test/shot.png.preview',
      mediaType: 'image/png',
      sizeBytes: '12',
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ToolMessageBlock
          message={message({ phase: 'call', arguments: '{"path":"/tmp/shot.png"}' })}
          result={message({
            phase: 'result',
            arguments: '{"path":"/tmp/shot.png"}',
            contents: [resource({ name: 'shot.png', blobId: 'blob-1' })],
          })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    await user.click(expandButton()!)
    expect(await screen.findByRole('img', { name: 'shot.png' })).toHaveAttribute(
      'src',
      'https://s3.test/shot.png.preview',
    )
    expect(resolveBlobUrls).toHaveBeenCalledWith('blob-1')
  })

  it('keeps an unknown-MIME read attachment collapsed instead of guessing', () => {
    render(
      <ToolMessageBlock
        message={message({ phase: 'call', arguments: '{"path":"/tmp/result.txt"}' })}
        result={message({
          phase: 'result',
          arguments: '{"path":"/tmp/result.txt"}',
          contents: [resource({ name: 'result.txt', blobId: 'blob-2' })],
        })}
      />,
    )

    // 权威 MIME 未知：不提前展开，也不按扩展名猜成图片。
    expect(document.querySelector('.thread-disclosure-body')).not.toBeInTheDocument()
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('does not auto-expand a write review and keeps the preview at the last ten lines', async () => {
    const user = userEvent.setup()
    const content = Array.from({ length: 40 }, (_, index) => `line-${index + 1}`).join('\n')
    const tail = Array.from({ length: 10 }, (_, index) => `line-${index + 31}`).join('\n')
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({ path: 'App.java', content }),
        })}
      />,
    )

    expect(screen.getAllByText(/App\.java/).length).toBeGreaterThanOrEqual(1)
    const preview = container.querySelector('.thread-tool-preview-body')
    // 审查期间不自动展开：预览只有最后 10 行，省略的行以静态计数说明。
    expect(preview?.textContent).toBe(tail)
    expect(preview).toHaveClass('thread-tool-output')
    expect(preview).not.toHaveClass('is-bounded')
    expect(screen.getByText('… 上方还有 30 行')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-details')).toBeNull()
    expect(container.querySelector('.thread-tool-summary-detail')).not.toHaveTextContent('line-1')

    // 完整审查材料由 Header 的 chevron 打开，且不隐藏在任何内部滚动容器之外。
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-details')?.textContent).toBe(content)
    expect(container.querySelector('.thread-tool-preview-body')).toHaveTextContent('line-40')
  })

  it('keeps the frozen write review diff in the read-only record instead of only the body', async () => {
    const user = userEvent.setup()
    const diff = Array.from({ length: 14 }, (_, index) => `+${index + 1}|line-${index + 1}`).join('\n')
    const tail = Array.from({ length: 10 }, (_, index) => `+${index + 5}|line-${index + 5}`).join('\n')
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({ path: 'App.java', content: 'body-one\nbody-two' }),
          editReview: {
            path: 'App.java',
            diff,
            sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          },
        })}
      />,
    )

    // 冻结审查是权威材料：只读记录直接展示 diff，不退化成只有正文。
    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).toHaveClass('is-edit')
    expect(preview?.textContent).toBe(tail)
    expect(container.querySelector('.thread-tool-preview-body.is-write')).toBeNull()
    expect(screen.getByText('… 上方还有 4 行')).toBeInTheDocument()

    // 完整冻结 diff 由 Header 的 chevron 打开，不再叠一份正文。
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-details .thread-tool-preview-body')?.textContent)
      .toBe(diff)
    expect(container.querySelector('.thread-tool-details .thread-tool-preview-body.is-write')).toBeNull()
  })

  it('falls back to the write body once the reviewed call is terminal, including failures', () => {
    const contentLines = Array.from({ length: 14 }, (_, index) => `line-${index + 1}`)
    const content = contentLines.join('\n')
    const tail = contentLines.slice(-10).join('\n')
    const call = message({
      phase: 'call',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({ path: 'App.java', content }),
      editReview: {
        path: 'App.java',
        diff: contentLines.map((line, index) => `+${index + 1}|${line}`).join('\n'),
        sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
      },
    })
    const { container, rerender } = render(<ToolMessageBlock message={call} />)

    // 等待审批期间冻结 diff 就是时间线材料。
    expect(container.querySelector('.thread-tool-preview-body')).toHaveClass('is-edit')

    // 终态（含失败）沿用既有待写正文展示，不让 diff 顶替正文。
    for (const invocationStatus of ['SUCCEEDED', 'FAILED'] as const) {
      rerender(
        <ToolMessageBlock
          message={{
            ...call,
            invocationStatus,
            status: invocationStatus === 'FAILED' ? 'error' : 'done',
          }}
        />,
      )
      const preview = container.querySelector('.thread-tool-preview-body')
      expect(preview).toHaveClass('is-write')
      expect(preview?.textContent).toBe(tail)
      expect(container.querySelector('.thread-tool-preview-body.is-edit')).toBeNull()
    }
  })

  it('shows the pre-execution edit diff when editReview is present', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            edits: [{
              old_string: 'alpha\nbeta',
              new_string: 'alpha\nBETA',
              replace_all: true,
            }],
          }),
          editReview: {
            path: 'App.java',
            diff: '-beta\n+BETA',
            sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          },
        })}
      />,
    )

    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).toHaveClass('is-edit')
    expect(within(preview as HTMLElement).getByText('-beta')).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('+BETA')).toBeInTheDocument()
    // replace_all 等影响范围的参数仍可见。
    expect(container.querySelector('.thread-tool-summary-detail')).toHaveTextContent('replace_all')
    // 尚未执行：不显示成功说明。
    expect(screen.queryByText(/已执行|修改成功|applied/)).not.toBeInTheDocument()
  })

  it('marks streamed arguments as not yet final review material', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: null,
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({ path: 'App.java', content: 'one\ntwo' }),
        })}
      />,
    )

    expect(container.querySelector('.thread-tool-preview-body')).toHaveClass('is-streaming')
  })

  it('shows streamed edit drafts as proposed changes with new text only', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: null,
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            edits: [{ old_string: 'one\ntwo', new_string: 'ONE\nTWO' }],
          }),
        })}
      />,
    )

    expect(screen.getByText('拟修改内容')).toBeInTheDocument()
    expect(screen.getByText('新文本：')).toBeInTheDocument()
    // 预览只放新文本；被替换的原文本属于详情材料。
    expect(screen.queryByText('原文本：')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-preview-body.is-input-old')).toBeNull()
    expect(container.querySelector('.thread-tool-preview-body.is-input-new')?.textContent).toBe('ONE\nTWO')

    await user.click(expandButton()!)
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(within(details).getByText('原文本：')).toBeInTheDocument()
    expect(details.querySelector('.thread-tool-preview-body.is-input-old')?.textContent).toBe('one\ntwo')
  })

  it('shares the ten-line preview budget across every edit entry', async () => {
    const user = userEvent.setup()
    const lines = (prefix: string) => Array.from({ length: 8 }, (_, index) => `${prefix}-${index + 1}`)
    const first = lines('one')
    const second = lines('two')
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: null,
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            edits: [
              { old_string: 'old-one', new_string: first.join('\n') },
              { old_string: 'old-two', new_string: second.join('\n') },
            ],
          }),
        })}
      />,
    )

    const blocks = container.querySelectorAll('.thread-tool-preview-input-block')
    expect(blocks).toHaveLength(2)
    // 配额从最后一条往前分配：后一条占满 8 行，前一条只剩 2 行。
    expect(blocks[0]?.querySelector('.is-input-new')?.textContent).toBe(first.slice(-2).join('\n'))
    expect(blocks[1]?.querySelector('.is-input-new')?.textContent).toBe(second.join('\n'))
    expect(screen.getByText('… 上方还有 6 行')).toBeInTheDocument()

    // 被窗口截掉的条目文本与全部原文本只能在详情里查看。
    await user.click(expandButton()!)
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    const detailBlocks = details.querySelectorAll('.thread-tool-preview-input-block')
    expect(detailBlocks).toHaveLength(2)
    expect(detailBlocks[0]?.querySelector('.is-input-new')?.textContent).toBe(first.join('\n'))
    expect(detailBlocks[1]?.querySelector('.is-input-new')?.textContent).toBe(second.join('\n'))
    expect(detailBlocks[0]?.querySelector('.is-input-old')?.textContent).toBe('old-one')
  })

  it('numbers each streamed edit entry and marks the replace-all one', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: null,
          toolName: 'edit',
          rendererKey: 'edit',
          // 参数在中途截止：最后一个条目尚未给出 new_string。
          arguments: '{"path":"App.java","edits":['
            + '{"old_string":"one","new_string":"ONE","replace_all":true},'
            + '{"old_string":"two","new_string":"TW',
        })}
      />,
    )

    expect(screen.getByText(/第 1\/2 处修改/)).toBeInTheDocument()
    expect(screen.getByText(/第 2\/2 处修改/)).toBeInTheDocument()
    const blocks = container.querySelectorAll('.thread-tool-preview-input-block')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]?.querySelector('.thread-tool-preview-badge')).toHaveTextContent('替换全部')
    expect(blocks[1]?.querySelector('.thread-tool-preview-badge')).toBeNull()
    expect(blocks[1]?.querySelector('.is-input-new')?.textContent).toBe('TW')
    // 摘要同样按条目数计数，且带出 replace_all。
    expect(container.querySelector('.thread-tool-summary-detail'))
      .toHaveTextContent('App.java [2 changes, replace_all]')
  })

  it('shows a complete edit diff once editReview is provided', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            edits: [{
              old_string: 'one\ntwo\nthree\nfour\nfive\nsix',
              new_string: 'ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX',
            }],
          }),
          editReview: {
            path: 'App.java',
            diff: '-one\n-six\n+ONE\n+SIX',
            sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          },
        })}
      />,
    )

    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).not.toHaveClass('is-streaming')
    expect(within(preview as HTMLElement).getByText('-one')).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('-six')).toBeInTheDocument()
  })

  it('shows the localized lossy-decode warning above the edit diff', () => {
    setLocale('zh-CN')
    const reviewFor = (invalidSequences: number) => ({
      phase: 'call' as const,
      toolName: 'edit',
      rendererKey: 'edit',
      arguments: JSON.stringify({
        path: 'App.java',
        edits: [{ old_string: 'one', new_string: 'ONE' }],
      }),
      editReview: {
        path: 'App.java',
        diff: '-one\n+ONE',
        sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        lossyDecode: { encoding: 'GB18030', invalidSequences },
      },
    })
    const { container, rerender } = render(
      <ToolMessageBlock message={message(reviewFor(1))} />,
    )

    const warning = container.querySelector('.thread-tool-lossy-warning')
    expect(warning).toHaveTextContent(
      '原文件有 1 处字节序列不是有效的 GB18030，显示为 �；保存后若仍保留，该位置会写为 U+FFFD，原始字节无法恢复。',
    )
    expect(warning?.nextElementSibling).toHaveClass('thread-tool-preview-body', 'is-edit')

    setLocale('en-US')
    rerender(<ToolMessageBlock message={message(reviewFor(3))} />)
    expect(container.querySelector('.thread-tool-lossy-warning')).toHaveTextContent(
      'The original file contains 3 byte sequences that are not valid GB18030, shown as �.'
        + ' Those still present are saved as U+FFFD, so their original bytes cannot be recovered.',
    )
  })

  it('fixes the bash preview at the last ten logical lines and puts the full record in details', async () => {
    const user = userEvent.setup()
    const output = Array.from({ length: 30 }, (_, index) => `build step ${index + 1}`).join('\n')
    const tail = Array.from({ length: 10 }, (_, index) => `build step ${index + 21}`).join('\n')
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          arguments: '{"command":"npm run build"}',
          partialContents: [text(output)],
        })}
      />,
    )

    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    const viewport = container.querySelector('.thread-tool-output')
    // 预览固定为最后 10 个逻辑行；前面的行以静态计数说明。
    expect(viewport?.textContent).toBe(tail)
    expect(viewport).not.toHaveClass('is-bounded')
    const hint = screen.getByText('… 上方还有 20 行')
    expect(hint).toHaveClass('thread-disclosure-hint')
    expect(screen.queryByRole('button', { name: '… 上方还有 20 行' })).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-details')).toBeNull()

    // 完整记录只在 Header 打开的详情里，展开不改变预览窗口。
    await user.click(expandButton()!)
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(details).not.toBeNull()
    expect(details.querySelector('.thread-tool-output')?.textContent).toBe(output)
    expect(container.querySelector('.thread-tool-text-preview .thread-tool-output')?.textContent).toBe(tail)
  })

  it('creates no preview region for bash output that has no visible text', () => {
    const marker = '... [output omitted] ...'
    const omitted = render(<ToolMessageBlock message={message({
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"seq 1 200000"}',
      contents: [text(marker)],
    })} />)

    // 只剩上游省略标记时没有可展示的输出：不建空 preview，也不把标记当正文。
    expect(omitted.container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(omitted.container.querySelector('.thread-tool-output')).toBeNull()
    expect(omitted.container.textContent).not.toContain(marker)
    omitted.unmount()

    const blank = render(<ToolMessageBlock message={message({
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: JSON.stringify({ command: 'printf " \\n "' }),
      contents: [text('   \n  ')],
    })} />)

    // 纯空白输出同样没有可见内容，不建 preview。
    expect(blank.container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(blank.container.querySelector('.thread-tool-output')).toBeNull()
  })

  it('recomputes the collapsed bash tail as streaming output grows', () => {
    const log = (count: number) =>
      Array.from({ length: count }, (_, index) => `step-${index + 1}`).join('\n')
    const messageFor = (count: number) => message({
      phase: 'call',
      toolName: 'bash',
      rendererKey: 'bash',
      status: 'streaming',
      subjectEntryId: null,
      arguments: '{"command":"npm test"}',
      partialContents: [text(log(count))],
    })
    const { container, rerender } = render(<ToolMessageBlock message={messageFor(20)} />)
    expect(container.querySelector('.thread-tool-output')?.textContent)
      .toBe(Array.from({ length: 10 }, (_, index) => `step-${index + 11}`).join('\n'))
    expect(screen.getByText('… 上方还有 10 行')).toBeInTheDocument()

    rerender(<ToolMessageBlock message={messageFor(30)} />)
    expect(container.querySelector('.thread-tool-output')?.textContent)
      .toBe(Array.from({ length: 10 }, (_, index) => `step-${index + 21}`).join('\n'))
    expect(screen.getByText('… 上方还有 20 行')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-output')).not.toHaveClass('is-bounded')
    // 静态计数提示不提供点击展开，展开入口只有 Header 的 chevron。
    expect(screen.queryByRole('button', { name: /上方还有/ })).not.toBeInTheDocument()
  })

  it('collapses MCP results to five lines and applies the 1500-character cap', async () => {
    const user = userEvent.setup()
    const lines = Array.from({ length: 8 }, (_, index) => `issue-${index + 1}`).join('\n')
    const { container, unmount } = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          toolName: 'mcp_github_list_issues',
          rendererKey: 'mcp_github_list_issues',
          arguments: '{"state":"open"}',
          contents: [text(lines)],
        })}
      />,
    )
    expect(container.querySelector('.thread-tool-output')?.textContent)
      .toBe(Array.from({ length: 5 }, (_, index) => `issue-${index + 1}`).join('\n'))
    expect(screen.getByRole('button', { name: '… 3 more lines' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '… 3 more lines' }))
    expect(container.querySelector('.thread-tool-output')).toHaveTextContent('issue-8')

    unmount()

    // 单行超长内容由 1500 code point 上限截断。
    const longLine = `start-${'x'.repeat(1_800)}`
    const long = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          toolName: 'mcp_github_get_issue',
          rendererKey: 'mcp_github_get_issue',
          arguments: '{"number":118}',
          contents: [text(longLine)],
        })}
      />,
    )
    const truncated = long.container.querySelector('.thread-tool-output')?.textContent ?? ''
    expect(Array.from(truncated)).toHaveLength(1_500)
    expect(truncated.startsWith('start-xxx')).toBe(true)
    expect(screen.getByRole('button', { name: '… truncated' })).toBeInTheDocument()
  })

  it('keeps a write preview at the last ten lines when the review settles', async () => {
    const user = userEvent.setup()
    const content = Array.from({ length: 40 }, (_, index) => `line-${index + 1}`).join('\n')
    const tail = Array.from({ length: 10 }, (_, index) => `line-${index + 31}`).join('\n')
    const call = message({
      phase: 'call',
      toolName: 'write',
      rendererKey: 'write',
      arguments: JSON.stringify({ path: 'App.java', content }),
    })
    const { container, rerender } = render(<ToolMessageBlock message={call} />)

    // 审查期间也是尾部短窗口，不自动展开详情。
    expect(container.querySelector('.thread-tool-preview-body')?.textContent).toBe(tail)
    expect(container.querySelector('.thread-tool-preview-body')).not.toHaveClass('is-bounded')
    expect(container.querySelector('.thread-tool-details')).toBeNull()

    // 终态也不切换窗口或自动展开，且不补写成功说明。
    rerender(<ToolMessageBlock message={call} result={message({
      phase: 'result',
      toolName: 'write',
      rendererKey: 'write',
      contents: [text('File written successfully.')],
    })} />)
    expect(container.querySelector('.thread-tool-preview-body')?.textContent).toBe(tail)
    expect(container.querySelector('.thread-tool-details')).toBeNull()
    expect(screen.queryByText('写入成功')).not.toBeInTheDocument()

    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-details .thread-tool-preview-body.is-write')?.textContent)
      .toBe(content)
    expect(screen.queryByText('写入成功')).not.toBeInTheDocument()
  })

  it('hides the task prompt while collapsed and renders it without acceptance copy', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'task',
          rendererKey: 'task',
          arguments: JSON.stringify({ subagent_type: 'explorer', prompt: 'inspect the repository' }),
        })}
        renderer={({ message: rendered }) => (
          <div className="task-tool-renderer">
            <span>{`renderer:${rendered.phase}:${rendered.arguments}`}</span>
          </div>
        )}
      />,
    )

    expect(container.querySelector('.thread-tool-summary-detail')).toHaveTextContent('explorer')
    expect(container.querySelector('.thread-tool-summary-detail')).not.toHaveTextContent('prompt')
    expect(screen.queryByText(/renderer:call/)).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText(/renderer:call/)).toBeInTheDocument()
    expect(container.textContent).not.toContain('已受理')
    expect(container.textContent).not.toContain('后台执行')
  })

  it('puts the canonical result link in the header without a UUID and falls back cleanly', async () => {
    const user = userEvent.setup()
    const resumeArguments = JSON.stringify({
      subagent_type: 'coder',
      prompt: 'continue working on subtask',
      thread_id: '00000000-0000-0000-0000-000000000001',
    })
    const callMessage = message({
      phase: 'call',
      toolName: 'task',
      rendererKey: 'task',
      arguments: resumeArguments,
      invocationStatus: 'SUCCEEDED',
      childThreadId: '00000000-0000-4000-8000-000000000004',
    })
    const resultWithCanonicalDetails = message({
      phase: 'result',
      toolName: 'task',
      rendererKey: 'task',
      arguments: resumeArguments,
      details: {
        kind: 'task.result',
        status: 'completed',
        thread_id: '00000000-0000-0000-0000-000000000002',
      },
      contents: [text('Subagent report.')],
    })

    const { container, unmount } = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={callMessage}
          result={resultWithCanonicalDetails}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )

    // 导航只使用 task.result 指定的 threadId，Header 收起时不展示完整 UUID。
    const link = screen.getByRole('link', { name: '查看 subagent 执行' })
    expect(link).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000002')
    expect(container.querySelector('.thread-disclosure-actions')).toContainElement(link)
    expect(container.querySelector('.thread-tool-summary-detail')).toHaveTextContent('coder')
    expect(container.querySelector('.thread-tool-summary-detail')).not.toHaveTextContent('thread_id')
    expect(screen.queryByText('continue working on subtask')).not.toBeInTheDocument()
    // 终态报告随正文收起，不重复展示在 Header 或 preview。
    expect(screen.queryByText('Subagent report.')).not.toBeInTheDocument()

    // 展开后完整参数仍保留原始 thread_id，同步报告作为正文展示。
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-summary-detail'))
      .toHaveTextContent('coder [thread_id=00000000-0000-0000-0000-000000000001]')
    expect(screen.getByText('continue working on subtask')).toBeInTheDocument()
    expect(screen.getByText('Subagent report.')).toBeInTheDocument()
    unmount()

    // 有结果但缺少 details 时不回退到 invocation 子线程 ID，展开后保留原始正文。
    const resultWithoutDetails = message({
      phase: 'result',
      toolName: 'task',
      rendererKey: 'task',
      arguments: resumeArguments,
      contents: [text('Raw output without details receipt.')],
    })
    const { unmount: unmount2 } = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={callMessage}
          result={resultWithoutDetails}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('Raw output without details receipt.')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('Raw output without details receipt.')).toBeInTheDocument()
    unmount2()

    // 有结果但回执 UUID 非法时，也不得回退到调用参数或 invocation 子线程 ID。
    const { unmount: unmount3 } = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={callMessage}
          result={{ ...resultWithCanonicalDetails, details: { kind: 'task.result', status: 'completed', thread_id: 'invalid' },
            contents: [text('Invalid receipt output')] }}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('Invalid receipt output')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('Invalid receipt output')).toBeInTheDocument()
    unmount3()

    // 失败终态仍以 task.result 为导航依据：子 Thread 链接与错误原文同时保留。
    const failedResult = message({
      phase: 'result',
      status: 'error',
      errorMessage: 'Subagent dispatch rejected: budget exceeded',
      toolName: 'task',
      rendererKey: 'task',
      arguments: resumeArguments,
      details: {
        kind: 'task.result',
        status: 'failed',
        thread_id: '00000000-0000-0000-0000-000000000003',
      },
      contents: [text('Failure explanation detail')],
    })
    const failed = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={callMessage}
          result={failedResult}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000003')
    expect(failed.container.querySelector('.thread-tool-error')).toHaveTextContent(
      'Subagent dispatch rejected: budget exceeded',
    )
    await user.click(expandButton()!)
    expect(screen.getByText('Failure explanation detail')).toBeInTheDocument()
    failed.unmount()

    // 通用错误 details 没有回执时不使用 invocation 子线程 ID，错误原文仍保留。
    const genericError = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={callMessage}
          result={{ ...failedResult, details: {}, contents: [text('Generic failure detail')] }}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(genericError.container.querySelector('.thread-tool-error')).toHaveTextContent(
      'Subagent dispatch rejected: budget exceeded',
    )
  })

  it('links to the durable child before a result and prefers the completed receipt', async () => {
    const user = userEvent.setup()
    const call = message({
      phase: 'call', toolName: 'task', rendererKey: 'task', invocationStatus: 'RUNNING',
      childThreadId: '00000000-0000-0000-0000-000000000001',
      arguments: JSON.stringify({
        subagent_type: 'coder', prompt: 'resume pending',
        thread_id: '00000000-0000-0000-0000-000000000099',
      }),
    })
    const { rerender } = render(
      <MemoryRouter><ToolMessageBlock message={call} renderer={TaskToolRenderer} /></MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000001')
    rerender(
      <MemoryRouter>
        <ToolMessageBlock
          message={{ ...call, invocationStatus: 'CANCELLED' }}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000001')
    expect(screen.queryByText('resume pending')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('resume pending')).toBeInTheDocument()
    rerender(
      <MemoryRouter>
        <ToolMessageBlock message={call} renderer={TaskToolRenderer} result={message({
          phase: 'result', toolName: 'task', rendererKey: 'task',
          details: {
            kind: 'task.result', status: 'completed',
            thread_id: '00000000-0000-0000-0000-000000000002',
          },
          contents: [text('subagent report')],
        })} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000002')
    expect(screen.queryByText('00000000-0000-0000-0000-000000000002')).not.toBeInTheDocument()
  })

  it('does not create an empty task result body', () => {
    const result = message({
      phase: 'result', toolName: 'task', rendererKey: 'task',
      details: {
        kind: 'task.result', status: 'completed',
        thread_id: '00000000-0000-0000-0000-000000000021',
      },
      contents: [],
    })
    const { container } = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={message({
            phase: 'call', toolName: 'task', rendererKey: 'task',
            arguments: '{"subagent_type":"coder","prompt":"done"}',
          })}
          result={result}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000021')
    expect(container.querySelector('.thread-disclosure-body')).toBeNull()
  })

  it('keeps the task result action in the header and the prompt in the details', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <MemoryRouter>
        <ToolMessageBlock message={message({
          phase: 'call', toolName: 'task', rendererKey: 'task',
          arguments: JSON.stringify({ subagent_type: 'explorer', prompt: 'first task prompt' }),
        })} renderer={TaskToolRenderer} result={message({
          phase: 'result', toolName: 'task', rendererKey: 'task',
          details: {
            kind: 'task.result', status: 'completed',
            thread_id: '00000000-0000-0000-0000-000000000002',
          },
          contents: [text('subagent report')],
        })} />
      </MemoryRouter>,
    )
    const link = screen.getByRole('link', { name: '查看 subagent 执行' })
    expect(link).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000002')
    expect(container.querySelector('.thread-disclosure-actions')).toContainElement(link)
    // 调用正文与操作入口不争同一个区域：preview 为空，prompt 进详情。
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(screen.queryByText('first task prompt')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(within(details).getByText('first task prompt')).toBeInTheDocument()
    expect(within(details).queryByRole('link')).toBeNull()
  })

  it('shows synchronous task completion, report, and child navigation', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <MemoryRouter>
        <ToolMessageBlock
          message={message({
            phase: 'call', toolName: 'task', rendererKey: 'task', invocationStatus: 'SUCCEEDED',
            arguments: JSON.stringify({ subagent_type: 'coder', prompt: 'finish the work' }),
          })}
          result={message({
            phase: 'result', toolName: 'task', rendererKey: 'task',
            details: {
              kind: 'task.result', status: 'completed',
              thread_id: '00000000-0000-0000-0000-000000000002',
            },
            contents: [text('subagent report')],
          })}
          renderer={TaskToolRenderer}
        />
      </MemoryRouter>,
    )

    expect(container.querySelector('.thread-block-tool')).toHaveAttribute('title', 'task: 已完成')
    expect(screen.getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000002')
    await user.click(expandButton()!)
    expect(screen.getByText('subagent report')).toBeInTheDocument()
  })

  it('shows streaming task arguments once and retains collapse across completed arguments', async () => {
    const user = userEvent.setup()
    const partial = String.raw`{"max_turns":15,"prompt":"Objective\n...未闭合片段`
    const call = message({
      phase: 'call', toolName: 'task', rendererKey: 'task',
      status: 'streaming', subjectEntryId: null, arguments: partial,
    })
    const { container, rerender } = render(<ToolMessageBlock message={call} renderer={TaskToolRenderer} />)
    const header = container.querySelector('.thread-tool-summary')!
    // 未闭合参数默认收起；已解析的短参数仍留在标题。
    expect(header).toHaveTextContent('task [max_turns=15]')
    expect(header.textContent).not.toContain('Objective')
    expect(container.querySelector('.thread-tool-output')).toBeNull()

    await user.click(expandButton()!)
    // prompt 属于调用正文，进入有界详情而不是 preview。
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(container.querySelector('.thread-disclosure-preview')).toBeNull()
    expect(details.querySelectorAll('.thread-tool-output')).toHaveLength(1)
    expect(details.querySelector('.thread-tool-output')?.textContent).toBe(partial)
    expect(container.textContent?.split(partial)).toHaveLength(2)

    // 流式时主动收起，完整参数到达后仍保留用户选择。
    await user.click(collapseButton()!)
    const complete = {
      ...call, status: 'done' as const, subjectEntryId: 'assistant-1',
      arguments: JSON.stringify({ max_turns: 15, prompt: 'Objective\ncompleted prompt', subagent_type: 'Searcher' }),
    }
    rerender(<ToolMessageBlock message={complete} renderer={TaskToolRenderer} />)
    expect(header).toHaveTextContent('task Searcher [max_turns=15]')
    expect(header.textContent).not.toContain('Objective')
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
    expect(container.querySelector('.thread-tool-output')).toBeNull()
    await user.click(expandButton()!)
    expect(screen.getAllByText('Objective completed prompt')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe('Objective\ncompleted prompt')
  })

  it('renders ask_user as a read-only record after expanding, with no submission path', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          arguments: JSON.stringify({
            questions: [{
              question: 'Which environment?',
              options: ['dev', 'prod'],
              multiple: false,
            }],
          }),
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          contents: [text(JSON.stringify({ answers: [['dev']], declined: false }))],
        })}
      />,
    )

    // Header 不重复问题文本；默认收起，展开后是冻结问题 + 已物化答案。
    expect(container.querySelector('.thread-tool-summary-detail')).not.toBeInTheDocument()
    expect(screen.queryByText('Which environment?')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('Which environment?')).toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /提交|继续|confirm|submit/i })).not.toBeInTheDocument()
    // 有正文时仍可收起。
    expect(collapseButton()).toBeInTheDocument()
  })

  it('reads canonical answers from json results and marks declined questionnaires', async () => {
    const user = userEvent.setup()
    const questions = JSON.stringify({
      questions: [
        { question: 'Pick two', options: ['a', 'b', 'c'], multiple: true },
        { question: 'Pick one', options: ['x', 'y'], multiple: false },
      ],
    })
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          arguments: questions,
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          contents: [json({ answers: [['a', 'b'], ['y']], declined: false })],
        })}
      />,
    )

    await user.click(expandButton()!)
    // json 结果里的规范答案按序物化，且没有任何提交入口。
    const answerTags = Array.from(container.querySelectorAll('.interaction-answer-tag'))
      .map((element) => element.textContent)
    expect(answerTags).toEqual(['a', 'b', 'y'])
    expect(container.querySelector('.interaction-questionnaire-badge')).toBeNull()
    expect(screen.queryByRole('button', { name: /提交|继续|confirm|submit/i })).not.toBeInTheDocument()
  })

  it('marks a declined questionnaire without fabricating answers', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          arguments: JSON.stringify({
            questions: [{ question: 'Which environment?', options: ['dev', 'prod'] }],
          }),
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          contents: [text(JSON.stringify({ declined: true }))],
        })}
      />,
    )

    await user.click(expandButton()!)
    expect(container.querySelector('.interaction-questionnaire-badge.declined')).not.toBeNull()
    expect(container.querySelectorAll('.interaction-answer-tag')).toHaveLength(0)
  })

  it('skips ask_user records whose frozen arguments are not a questionnaire', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          arguments: 'not-json',
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'ask_user',
          rendererKey: 'ask_user',
          contents: [text('no canonical payload')],
        })}
      />,
    )

    expect(container.querySelector('.ask-user-record')).toBeNull()
  })
})

describe('ToolMessageBlock resources and approvals', () => {
  it('keeps bash text in the tail window and every resource in the details', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [
            text('before resource'),
            resource({
              type: 'file',
              name: 'a.txt',
              mime: 'text/plain',
              data: 'https://example.test/a.txt',
            }),
            text('after resource'),
          ],
        })}
      />,
    )

    // 预览只拼接文本（保持文本块内部顺序），附件不占短窗口。
    expect(container.querySelector('.thread-tool-output')?.textContent)
      .toBe('before resource\nafter resource')
    expect(screen.queryByText('https://example.test/a.txt')).not.toBeInTheDocument()
    expect(expandButton()).toBeInTheDocument()

    await user.click(expandButton()!)
    // 附件不被文本窗口吞掉：展开后在详情里逐条渲染。
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(within(details).getByText('https://example.test/a.txt')).toBeInTheDocument()
    expect(details.querySelector('.thread-tool-output')).toBeNull()
  })

  it('preserves interleaved text and resource order in the expanded MCP preview', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          toolName: 'mcp_server_fetch',
          rendererKey: 'mcp_server_fetch',
          contents: [
            text('before resource'),
            resource({
              type: 'file',
              name: 'a.txt',
              mime: 'text/plain',
              data: 'https://example.test/a.txt',
            }),
            text('after resource'),
          ],
        })}
      />,
    )

    // MCP 收起时只有前 5 行的文本流，附件不提前展示。
    expect(container.querySelector('.thread-tool-output')?.textContent)
      .toBe('before resource\nafter resource')
    expect(screen.queryByText('https://example.test/a.txt')).not.toBeInTheDocument()

    await user.click(expandButton()!)
    const sequence = Array.from(
      container.querySelectorAll('.thread-disclosure-preview > .thread-tool-contents > *'),
    ).map((element) => element.textContent ?? '')
    expect(sequence).toHaveLength(3)
    expect(sequence[0]).toContain('before resource')
    expect(sequence[1]).toContain('a.txt')
    expect(sequence[1]).toContain('https://example.test/a.txt')
    expect(sequence[2]).toContain('after resource')
  })

  it('renders inline data media but never auto-requests remote resources', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [
            resource({
              type: 'image',
              name: 'preview.png',
              mime: 'image/png',
              data: 'data:image/png;base64,aGVsbG8=',
            }),
            resource({
              type: 'image',
              name: 'remote.png',
              mime: 'image/png',
              data: 'https://example.com/remote.png',
            }),
          ],
        })}
      />,
    )

    await user.click(expandButton()!)
    expect(screen.getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    // 远程 http(s) 资源只保留稳定 URI 文本 + 显式链接，绝不自动发起 img 请求。
    expect(screen.getByText('https://example.com/remote.png')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '下载 remote.png' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )
    expect(screen.queryByRole('img', { name: 'remote.png' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '预览 preview.png' }))
    const dialog = screen.getByRole('dialog', { name: '预览 preview.png' })
    expect(within(dialog).getByRole('img', { name: 'preview.png' })).toBeInTheDocument()
  })

  it('renders a resource preview excerpt as text instead of an image source', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [resource({
            type: 'file',
            name: 'manifest.json',
            mime: 'application/json',
            data: 'https://example.test/manifest.json',
            preview: '{"version":1,"tools":["web-search"]}',
          })],
        })}
      />,
    )

    await user.click(expandButton()!)
    expect(screen.getByText('https://example.test/manifest.json')).toBeInTheDocument()
    expect(screen.getByText('{"version":1,"tools":["web-search"]}')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('renders audio and video resources without autoplay', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [
            resource({
              type: 'audio',
              name: 'clip.mp3',
              mime: 'audio/mpeg',
              data: 'data:audio/mpeg;base64,QUFBQQ==',
            }),
            resource({
              type: 'video',
              name: 'clip.mp4',
              mime: 'video/mp4',
              data: 'data:video/mp4;base64,QUFBQQ==',
            }),
          ],
        })}
      />,
    )

    await user.click(expandButton()!)
    expect(container.querySelector('audio')).toHaveAttribute(
      'src',
      'data:audio/mpeg;base64,QUFBQQ==',
    )
    expect(container.querySelector('video')).toHaveAttribute(
      'src',
      'data:video/mp4;base64,QUFBQQ==',
    )
    expect(container.querySelector('audio')).not.toHaveAttribute('autoplay')
    expect(container.querySelector('video')).not.toHaveAttribute('autoplay')
  })

  it('resolves a durable blob resource through the storage service once expanded', async () => {
    const user = userEvent.setup()
    const resolveBlobUrls = vi.fn(async () => ({
      original: 'https://s3.test/result',
      preview: null,
      mediaType: 'text/plain',
      sizeBytes: '12',
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ToolMessageBlock
          message={message({
            contents: [resource({
              name: 'result.txt',
              blobId: 'blob-3',
              preview: 'durable excerpt',
            })],
          })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    // 权威 MIME 未解析前保持收起，用户展开后才解析并渲染资源。
    await user.click(expandButton()!)
    expect(resolveBlobUrls).toHaveBeenCalledWith('blob-3')
    expect(await screen.findByRole('link', { name: /下载 result\.txt/ })).toHaveAttribute(
      'href',
      'https://s3.test/result',
    )
    expect(screen.getByText('durable excerpt')).toBeInTheDocument()
  })

  it('shows approval as a read-only record with no write path', () => {
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
      />,
    )

    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()
    expect(screen.queryByText(/此工具调用需要审批/)).not.toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: 'looks safe' },
        })}
      />,
    )
    expect(screen.queryByText(/已允许/)).not.toBeInTheDocument()
    expect(screen.queryByText(/looks safe/)).not.toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'DENIED', decisionId: 'd-2', reason: null },
        })}
      />,
    )
    expect(screen.queryByText(/已拒绝/)).not.toBeInTheDocument()
    expect(card()).toBeInTheDocument()
  })
})

describe('ToolMessageBlock integrated disclosure boundaries', () => {
  it('shows an early bash failure outside the tail window in its own slot', async () => {
    const user = userEvent.setup()
    const log = [...Array.from({ length: 30 }, (_, i) => `step-${i}`), '[Command exited with code 2.]'].join('\n')
    const tail = log.split('\n').slice(-10).join('\n')
    // 真实投影把失败的所有文本合成 errorMessage：开头不在尾部窗口里，必须另外给出。
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'bash', status: 'error', errorMessage: log, contents: [text(log)],
    })} />)

    const error = container.querySelector('.thread-disclosure-error-slot .thread-tool-error')
    expect(error).toHaveTextContent('step-0')
    expect(error?.textContent).toMatch(/…$/)
    expect(error).not.toHaveTextContent('[Command exited with code 2.]')
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe(tail)

    await user.click(expandButton()!)
    // 展开详情后的完整记录包含退出说明。
    const details = container.querySelector('.thread-tool-details') as HTMLElement
    expect(details.querySelector('.thread-tool-output')?.textContent).toBe(log)
    expect(details).toHaveTextContent('[Command exited with code 2.]')
    expect(container.querySelector('.thread-disclosure-error-slot .thread-tool-error')).toHaveTextContent('step-0')
  })

  it('does not count daemon spool metadata or the timeout note as bash output', async () => {
    const user = userEvent.setup()
    const path = '/workspace/logs/captured.log'
    const note = '[Command timed out after 10 ms and was terminated.]'
    const lines = Array.from({ length: 12 }, (_, i) => `step-${i}`)
    const output = [
      'head',
      '[... 128 bytes omitted here; the middle of the output is not shown ...]',
      ...lines,
      '',
      `[Full output: 512 bytes, 30 lines. Saved to: ${path}`,
      'Use read with offset/limit to page through the file, or grep to search it.]',
      note,
    ].join('\n')
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'bash', status: 'error', errorMessage: output, contents: [text(output)],
      details: { process: { outcome: 'TIMED_OUT' }, textOutput: { totalBytes: 512, path } },
    })} />)

    expect(card()).toHaveAccessibleName('bash: 执行超时')
    expect(container.querySelector('.thread-disclosure-error-text')).toHaveTextContent(note)
    const preview = container.querySelector('.thread-disclosure-preview')!
    expect(preview.querySelector('pre')?.textContent).toBe(lines.slice(-10).join('\n'))
    expect(preview).toHaveTextContent('更早的输出已省略')
    expect(preview).not.toHaveTextContent('上方还有')
    await user.click(expandButton()!)
    expect(screen.getByRole('region', { name: '完整详情' })).toHaveTextContent(`已保存的日志：${path}`)
  })

  it('deduplicates a bash failure that is already inside the visible tail window', () => {
    const output = ['step-1', 'step-2', '[Command exited with code 2.]'].join('\n')
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'bash', status: 'error', errorMessage: '[Command exited with code 2.]',
      contents: [text(output)],
    })} />)

    // 失败说明就在可见尾部：不额外占一个错误槽位，输出只呈现一次。
    expect(container.querySelector('.thread-disclosure-error-slot')).toBeNull()
    expect(container.querySelector('.thread-tool-output')?.textContent).toBe(output)
    expect(screen.getAllByText(/Command exited with code 2/)).toHaveLength(1)
  })

  it('shares the collapsed 1500-character budget between the error and normal preview', async () => {
    const user = userEvent.setup()
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'mcp_server_tool', status: 'error', errorMessage: 'E'.repeat(1000),
      contents: [text('x'.repeat(2000))],
    })} />)
    const error = container.querySelector('.thread-disclosure-error-text')!.textContent!
    const preview = container.querySelector('.thread-tool-output')!.textContent!
    expect(Array.from(error + preview)).toHaveLength(1500)
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-output')!.textContent).toHaveLength(2000)
  })

  it('does not call a truncated write preview an empty file when the error used its budget', () => {
    // 错误已占满预算时隐藏写入内容并保留展开提示，不能伪称写入的是空文件。
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'write', status: 'error', errorMessage: 'E'.repeat(2000),
      arguments: JSON.stringify({ path: 'a.txt', content: 'real content' }),
    })} />)
    expect(container.querySelector('.thread-tool-empty-content')).toBeNull()
    expect(container.querySelector('.thread-tool-preview-body')).toBeNull()
    expect(expandButton()).toBeInTheDocument()
  })

  it('deduplicates multi-part failure text without discarding its attachment', async () => {
    const user = userEvent.setup()
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'mcp_server_tool', status: 'error', errorMessage: 'first\nsecond',
      contents: [text('first'), resource({ name: 'trace.txt', data: 'https://example.test/trace.txt' }), text('second')],
    })} />)
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-disclosure-error-text')).toHaveTextContent('first second')
    expect(container.querySelectorAll('.thread-tool-output')).toHaveLength(0)
    expect(screen.getByRole('link', { name: '下载 trace.txt' })).toBeInTheDocument()
  })

  it('keeps resource results when deduplicating a successful mutation body', async () => {
    const user = userEvent.setup()
    const { container } = render(<ToolMessageBlock message={message({
      toolName: 'write', arguments: JSON.stringify({ path: 'a.txt', content: 'hello' }),
      contents: [text('written hello'), resource({ name: 'artifact.txt', data: 'https://example.test/artifact.txt' })],
    })} />)
    expect(screen.queryByRole('link')).toBeNull()
    // 成功不用勾选图标宣称；卡片壳始终中性，状态只由 Header 标签与数据属性表达。
    expect(container.querySelector('.thread-tool-success-icon')).toBeNull()
    expect(card()).toHaveClass('is-neutral')
    await user.click(expandButton()!)
    expect(container.querySelector('.thread-tool-mutation-success')).toBeNull()
    expect(screen.getByRole('link', { name: '下载 artifact.txt' })).toBeInTheDocument()
    expect(container).not.toHaveTextContent('written hello')
  })
})

describe('ToolMessageBlock identity', () => {
  it('keeps the user expand choice across the streaming to terminal transition', async () => {
    const user = userEvent.setup()
    const call = message({
      phase: 'call',
      toolName: 'grep',
      rendererKey: 'grep',
      status: 'streaming',
      subjectEntryId: 'assistant-1',
      arguments: '{"pattern":"needle"}',
      partialContents: [text('searching…')],
    })
    const { container, rerender } = render(<ToolMessageBlock message={call} />)

    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
    await user.click(expandButton()!)
    expect(collapseButton()).toHaveAttribute('aria-expanded', 'true')

    // 终态持久结果到达：同一身份，展开选择不被默认值重置。
    rerender(
      <ToolMessageBlock
        message={call}
        result={message({
          phase: 'result',
          toolName: 'grep',
          rendererKey: 'grep',
          status: 'done',
          contents: [text('match: needle')],
        })}
      />,
    )
    expect(screen.getByText('match: needle')).toBeInTheDocument()
    expect(collapseButton()).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelectorAll('.thread-block-tool')).toHaveLength(1)
  })

  it('keeps a collapsed choice across the streaming to terminal transition', async () => {
    const user = userEvent.setup()
    const call = message({
      phase: 'call',
      toolName: 'grep',
      rendererKey: 'grep',
      status: 'streaming',
      subjectEntryId: 'assistant-1',
      arguments: '{"pattern":"x"}',
      partialContents: [text('partial output')],
    })
    const { rerender } = render(<ToolMessageBlock message={call} />)

    await user.click(expandButton()!)
    expect(screen.getByText('partial output')).toBeInTheDocument()
    await user.click(collapseButton()!)

    rerender(
      <ToolMessageBlock
        message={call}
        result={message({
          phase: 'result',
          toolName: 'grep',
          rendererKey: 'grep',
          status: 'done',
          contents: [text('final output')],
        })}
      />,
    )
    expect(screen.queryByText('final output')).not.toBeInTheDocument()
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('prefers the durable result contents over a stale streaming partial', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          partialContents: [text('stale partial')],
        })}
        result={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          partialContents: [text('stable text')],
          contents: [text('durable text')],
        })}
      />,
    )

    await user.click(expandButton()!)
    // result 消息存在时它是唯一展示源，旧 partial 不得再贡献内容。
    expect(document.querySelector('.thread-tool-output')).toHaveTextContent('durable text')
    expect(document.querySelector('.thread-tool-output')).not.toHaveTextContent('stale partial')
  })
})
