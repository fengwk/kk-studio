import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { ResourceBlobUrlContext } from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/ai/runtime/transcript-reading'
import type {
  ToolAttachment,
  ToolContent,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

function message(overrides: Partial<ToolDialogueMessage> = {}): ToolDialogueMessage {
  return {
    id: 'tool-1',
    role: 'tool',
    subjectEntryId: null,
    createdAt: null,
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
  return document.querySelector<HTMLElement>('.thread-tool-surface')!
}

function outputTexts(): string[] {
  return Array.from(document.querySelectorAll('.thread-tool-contents > *'))
    .map((element) => element.textContent ?? '')
}

const expandButton = () => screen.queryByRole('button', { name: '展开工具预览' })
const collapseButton = () => screen.queryByRole('button', { name: '收起工具预览' })

describe('ToolMessageBlock shell', () => {
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
            contents: [text('running')],
          })}
        />,
      )

      await userEvent.click(collapseButton()!)

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
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(screen.queryByText('（无参数）')).not.toBeInTheDocument()
    expect(screen.queryByText('等待工具结果…')).not.toBeInTheDocument()
    expect(screen.queryByText('无文本输出')).not.toBeInTheDocument()
  })

  it('uses color state instead of WORKING/DONE/FAILED labels', () => {
    render(<ToolMessageBlock message={message({ phase: 'call', toolName: '', status: 'streaming' })} />)

    expect(screen.getByText('Tool')).toBeInTheDocument()
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'true')
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
          toolName: 'mcp__server__tool',
          rendererKey: 'mcp__server__tool',
          arguments: '{"query":"needle","flag":false}',
          contents: [],
        })}
        result={message({
          phase: 'result',
          toolName: 'mcp__server__tool',
          rendererKey: 'mcp__server__tool',
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
    expect(container.querySelector('.thread-tool-body')?.textContent).not.toContain('"query"')
  })

  it('does not offer a toggle when the card has no body to collapse', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({ phase: 'call', toolName: 'bash', arguments: '{"command":"pwd"}' })}
      />,
    )

    expect(expandButton()).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-toggle')).not.toBeInTheDocument()
    // 没有正文时不渲染空正文容器。
    expect(container.querySelector('.thread-tool-body')).not.toBeInTheDocument()
  })

  it('derives pending/success/error states from the paired result', () => {
    const { rerender } = render(
      <ToolMessageBlock message={message({ phase: 'call', status: 'streaming' })} />,
    )
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'true')

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

describe('ToolMessageBlock failures', () => {
  it('keeps the failure summary visible while collapsed and never repeats the full result', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'mcp__server__tool',
          rendererKey: 'mcp__server__tool',
          arguments: '{"query":"x"}',
        })}
        result={message({
          phase: 'result',
          toolName: 'mcp__server__tool',
          rendererKey: 'mcp__server__tool',
          status: 'error',
          errorMessage: 'connection refused',
          contents: [text('connection refused')],
        })}
      />,
    )

    // 失败结果默认展开事实，摘要不与正文重复。
    expect(screen.getAllByText('connection refused')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-error')).not.toBeInTheDocument()

    // 用户收起后正文消失，但失败摘要仍必须可见（不能被折叠完全隐藏）。
    await user.click(collapseButton()!)
    expect(container.querySelector('.thread-tool-body')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-error'))
      .toHaveTextContent('connection refused')
    expect(screen.getByText('connection refused')).toBeInTheDocument()

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

  it('shows the generic failure label for an empty error result', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({ status: 'error', toolName: 'mcp__server__tool', rendererKey: 'x' })}
      />,
    )

    expect(screen.getByText('工具执行失败。')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-toggle')).not.toBeInTheDocument()
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
            resource({ type: 'file', name: 'out.txt', mime: 'text/plain', data: 'file:///tmp/out.txt' }),
            { type: 'json', value: '{"nested":true,"n":1}' },
            text('last text'),
          ],
        })}
      />,
    )

    expect(container.querySelector('.thread-tool-body')).not.toBeInTheDocument()
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

  it('previews a read image by default from the authoritative MIME', () => {
    render(
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

    // 默认即展开预览：不需要用户先点击展开。
    expect(screen.getByRole('img', { name: 'shot.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    expect(collapseButton()).toHaveAttribute('aria-expanded', 'true')
  })

  it('resolves a blob read image through the storage media type before expanding', async () => {
    const resolveBlobUrls = vi.fn(async () => ({
      original: 'https://s3.test/shot.png',
      preview: 'https://s3.test/shot.png.preview',
      mediaType: 'image/png',
      sizeBytes: 12,
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
    expect(document.querySelector('.thread-tool-body')).not.toBeInTheDocument()
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('shows the write body by default in a bounded viewport and allows collapsing', async () => {
    const user = userEvent.setup()
    const content = Array.from({ length: 40 }, (_, index) => `line-${index + 1}`).join('\n')
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

    expect(screen.getByText(/App\.java/)).toBeInTheDocument()
    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).toHaveTextContent('line-1')
    expect(preview).toHaveTextContent('line-40')
    expect(preview).toHaveClass('thread-tool-output')
    expect(preview).not.toHaveTextContent('more line')
    expect(container.querySelector('.thread-tool-summary-detail')).not.toHaveTextContent('line-1')

    // prompt/正文默认可见不等于不可收起。
    await user.click(collapseButton()!)
    expect(container.querySelector('.thread-tool-preview-body')).not.toBeInTheDocument()
    expect(screen.getByText(/App\.java/)).toBeInTheDocument()
  })

  it('shows the pre-execution edit diff by default without claiming it ran', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            replace_all: true,
            old_string: 'alpha\nbeta',
            new_string: 'alpha\nBETA',
          }),
        })}
      />,
    )

    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).toHaveClass('is-edit')
    expect(within(preview as HTMLElement).getByText('-beta')).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('+BETA')).toBeInTheDocument()
    // replace_all 等影响范围的参数仍可见。
    expect(container.querySelector('.thread-tool-summary-detail')).toHaveTextContent('replace_all')
    // 原始 old/new 只生成一份 diff，不重复铺开两份原文。
    expect(preview?.textContent).not.toContain('old_string')
    expect(preview?.textContent).not.toContain('new_string')
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

  it('shows a complete edit diff once streamed arguments become durable', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: 'assistant-1',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            old_string: 'one\ntwo\nthree\nfour\nfive\nsix',
            new_string: 'ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX',
          }),
        })}
      />,
    )

    const preview = container.querySelector('.thread-tool-preview-body')
    expect(preview).not.toHaveClass('is-streaming')
    expect(within(preview as HTMLElement).getByText('-one')).toBeInTheDocument()
    expect(within(preview as HTMLElement).getByText('-six')).toBeInTheDocument()
    expect(preview).not.toHaveTextContent(/more lines/)
  })

  it('shows bash output by default in the bounded viewport and follows the streaming tail', () => {
    const output = Array.from({ length: 30 }, (_, index) => `build step ${index + 1}`).join('\n')
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          arguments: '{"command":"npm run build"}',
          partialContents: [text(`... [output omitted] ...\n${output}`)],
        })}
      />,
    )

    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    const viewport = container.querySelector('.thread-tool-output')
    // 持续日志默认跟随底部：完整内容都在 DOM 中，不按行截断。
    expect(viewport).toHaveTextContent('build step 1')
    expect(viewport).toHaveTextContent('build step 30')
    expect(viewport?.textContent).not.toContain('earlier lines')
    expect(collapseButton()).toHaveAttribute('aria-expanded', 'true')
  })

  it('follows only the streaming bash tail; settled output is read from the top', () => {
    // 挂载时的贴底写入必须可观测：用原型级尺寸桩，写入才会落在 mock 的 scrollHeight 上。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(400)
      clientHeightSpy.mockReturnValue(100)
      const log = (count: number) =>
        Array.from({ length: count }, (_, index) => `step-${index + 1}`).join('\n')
      const settledCall = message({
        phase: 'call',
        toolName: 'bash',
        rendererKey: 'bash',
        status: 'done',
        subjectEntryId: 'entry-70',
        arguments: '{"command":"npm test"}',
      })
      const settledResult = message({
        id: 'tool-result-1',
        phase: 'result',
        toolName: 'bash',
        rendererKey: 'bash',
        status: 'done',
        subjectEntryId: 'entry-71',
        contents: [text(log(20))],
      })

      // 终态 bash 结果（durable 结果，非流式）：静态正文从顶部读，不自动贴到底部。
      const settled = render(<ToolMessageBlock message={settledCall} result={settledResult} />)
      const settledViewport = settled.container.querySelector<HTMLElement>('.thread-tool-output')
      expect(settledViewport?.scrollTop).toBe(0)
      settled.unmount()

      // 流式 bash 调用（瞬态 partial 日志）：唯一跟随尾部的正文。
      const streamingCall = message({
        phase: 'call',
        toolName: 'bash',
        rendererKey: 'bash',
        status: 'streaming',
        subjectEntryId: null,
        arguments: '{"command":"npm test"}',
        partialContents: [text(log(20))],
      })
      const streaming = render(<ToolMessageBlock message={streamingCall} />)
      const viewport = streaming.container.querySelector<HTMLElement>('.thread-tool-output')
      expect(viewport?.scrollTop).toBe(400)

      // durable 结果到达：同一节点保留阅读位置，不再自动贴底。
      streaming.rerender(<ToolMessageBlock message={settledCall} result={settledResult} />)
      expect(viewport?.scrollTop).toBe(400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('renders the task prompt and a clickable thread id without acceptance copy', () => {
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
    expect(screen.getByText(/renderer:call/)).toBeInTheDocument()
    expect(container.textContent).not.toContain('已受理')
    expect(container.textContent).not.toContain('后台执行')
  })

  it('renders ask_user as a read-only record without a submission path', () => {
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

    // Header 不重复问题文本；正文是冻结问题 + 已物化答案。
    expect(container.querySelector('.thread-tool-summary-detail')).not.toBeInTheDocument()
    expect(screen.getByText('Which environment?')).toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /提交|继续|confirm|submit/i })).not.toBeInTheDocument()
    // 有正文时仍可收起。
    expect(collapseButton()).toBeInTheDocument()
  })

  it('reads canonical answers from json results and marks declined questionnaires', () => {
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

    // json 结果里的规范答案按序物化，且没有任何提交入口。
    const answerTags = Array.from(container.querySelectorAll('.interaction-answer-tag'))
      .map((element) => element.textContent)
    expect(answerTags).toEqual(['a', 'b', 'y'])
    expect(container.querySelector('.interaction-questionnaire-badge')).toBeNull()
    expect(screen.queryByRole('button', { name: /提交|继续|confirm|submit/i })).not.toBeInTheDocument()
  })

  it('marks a declined questionnaire without fabricating answers', () => {
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
              downloadHref: 'https://example.com/remote.png',
            }),
          ],
        })}
      />,
    )

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

  it('renders a resource preview excerpt as text instead of an image source', () => {
    render(
      <ToolMessageBlock
        message={message({
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [resource({
            type: 'file',
            name: 'manifest.json',
            mime: 'application/json',
            data: 'file:///tmp/manifest.json',
            preview: '{"version":1,"tools":["web-search"]}',
          })],
        })}
      />,
    )

    expect(screen.getByText('file:///tmp/manifest.json')).toBeInTheDocument()
    expect(screen.getByText('{"version":1,"tools":["web-search"]}')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('renders audio and video resources without autoplay', () => {
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
      sizeBytes: 12,
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
    expect(screen.getByText(/此工具调用需要审批/)).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: 'looks safe' },
        })}
      />,
    )
    expect(screen.getByText(/已允许/)).toBeInTheDocument()
    expect(screen.getByText(/looks safe/)).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'DENIED', decisionId: 'd-2', reason: null },
        })}
      />,
    )
    expect(screen.getByText(/已拒绝/)).toBeInTheDocument()
    expect(card()).toBeInTheDocument()
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
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
  })

  it('keeps a collapsed choice across the streaming to terminal transition', async () => {
    const user = userEvent.setup()
    const call = message({
      phase: 'call',
      toolName: 'bash',
      rendererKey: 'bash',
      status: 'streaming',
      subjectEntryId: 'assistant-1',
      arguments: '{"command":"ls"}',
      partialContents: [text('partial output')],
    })
    const { rerender } = render(<ToolMessageBlock message={call} />)

    expect(screen.getByText('partial output')).toBeInTheDocument()
    await user.click(collapseButton()!)

    rerender(
      <ToolMessageBlock
        message={call}
        result={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'done',
          contents: [text('final output')],
        })}
      />,
    )
    expect(screen.queryByText('final output')).not.toBeInTheDocument()
    expect(expandButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('prefers the durable result contents over a stale streaming partial', () => {
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

    // result 消息存在时它是唯一展示源，旧 partial 不得再贡献内容。
    expect(document.querySelector('.thread-tool-contents')).toHaveTextContent('durable text')
    expect(document.querySelector('.thread-tool-contents')).not.toHaveTextContent('stale partial')
  })
})
