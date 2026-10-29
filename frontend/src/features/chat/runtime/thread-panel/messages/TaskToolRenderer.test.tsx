import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { TaskToolRenderer } from '@/features/chat/runtime/thread-panel/messages/TaskToolRenderer'
import {
  parseTaskArguments,
  parseTaskReceipt,
} from '@/features/chat/runtime/task-tool-parser'
import type { ToolRendererMessage } from '@/platform/extensions/types'
import { setLocale } from '@/shared/i18n'

function message(overrides: Partial<ToolRendererMessage> = {}): ToolRendererMessage {
  return {
    rendererKey: 'task',
    phase: 'call',
    toolCallId: 'call-task',
    toolName: 'task',
    arguments: '{"subagent_type":"explorer","prompt":"inspect the workspace"}',
    contents: [],
    ...overrides,
  }
}

describe('parseTaskArguments', () => {
  it('extracts canonical subagent_type, prompt, thread_id, max_turns', () => {
    expect(
      parseTaskArguments(
        '{"subagent_type":"coder","prompt":"fix it","thread_id":"00000000-0000-0000-0000-000000000007","max_turns":4}',
      ),
    ).toEqual({
      subagentType: 'coder',
      prompt: 'fix it',
      threadId: '00000000-0000-0000-0000-000000000007',
      maxTurns: 4,
    })
    // 可选字段缺省时为 null
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"fix it"}')).toEqual({
      subagentType: 'coder',
      prompt: 'fix it',
      threadId: null,
      maxTurns: null,
    })
  })

  it('strictly does not recognize legacy session_id or camelCase maxTurns aliases', () => {
    // 传入旧别名 session_id / maxTurns，解析结果中对应字段必须为 null
    expect(
      parseTaskArguments(
        '{"subagent_type":"coder","prompt":"fix it","session_id":"old-session","maxTurns":4}',
      ),
    ).toEqual({
      subagentType: 'coder',
      prompt: 'fix it',
      threadId: null,
      maxTurns: null,
    })
  })

  it('drops non-numeric max_turns and invalid JSON without coercion', () => {
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"p","max_turns":"4"}').maxTurns).toBeNull()
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"p","max_turns":0}').maxTurns).toBeNull()
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"p","max_turns":-1}').maxTurns).toBeNull()
    expect(parseTaskArguments('not-json')).toEqual({
      subagentType: null,
      prompt: null,
      threadId: null,
      maxTurns: null,
    })
    expect(parseTaskArguments('["array"]')).toEqual({
      subagentType: null,
      prompt: null,
      threadId: null,
      maxTurns: null,
    })
  })
})

describe('parseTaskReceipt', () => {
  it('parses canonical task.accepted details object with canonical UUID thread_id', () => {
    expect(
      parseTaskReceipt({
        kind: 'task.accepted',
        thread_id: '00000000-0000-0000-0000-000000000004',
      }),
    ).toEqual({
      status: 'accepted',
      threadId: '00000000-0000-0000-0000-000000000004',
    })
    // 允许两端有空白字符的 UUID，解析后 trim
    expect(
      parseTaskReceipt({
        kind: 'task.accepted',
        thread_id: '  00000000-0000-0000-0000-000000000004  ',
      }),
    ).toEqual({
      status: 'accepted',
      threadId: '00000000-0000-0000-0000-000000000004',
    })
  })

  it('strictly rejects strings and never parses receipt from body JSON text', () => {
    // 严禁从正文 JSON 字符串反序列化收据，哪怕包含正确字段
    expect(
      parseTaskReceipt('{"kind":"task.accepted","thread_id":"00000000-0000-0000-0000-000000000004"}'),
    ).toBeNull()
    expect(
      parseTaskReceipt('{"thread_id":"00000000-0000-0000-0000-000000000004","status":"accepted"}'),
    ).toBeNull()
    expect(
      parseTaskReceipt('<task thread_id="00000000-0000-0000-0000-000000000004" state="accepted">delegation accepted</task>'),
    ).toBeNull()
    expect(parseTaskReceipt('plain text receipt')).toBeNull()
  })

  it('rejects receipt when thread_id is missing, blank, or not a canonical UUID', () => {
    // 缺少 thread_id 或非 UUID shape 绝不能隐瞒为成功收据，必须返回 null
    expect(parseTaskReceipt({ kind: 'task.accepted' })).toBeNull()
    expect(parseTaskReceipt({ kind: 'task.accepted', thread_id: '   ' })).toBeNull()
    expect(parseTaskReceipt({ kind: 'task.accepted', thread_id: 'not-a-uuid' })).toBeNull()
    expect(parseTaskReceipt({ kind: 'task.accepted', thread_id: 123 })).toBeNull()
  })

  it('strictly rejects non-task.accepted kind and avoids accepted vs completed confusion', () => {
    // 终态 completed 或其他 kind 绝不是受理收据，必须返回 null
    expect(
      parseTaskReceipt({ kind: 'task.completed', thread_id: '00000000-0000-0000-0000-000000000004' }),
    ).toBeNull()
    expect(
      parseTaskReceipt({ kind: 'task.error', thread_id: '00000000-0000-0000-0000-000000000004' }),
    ).toBeNull()
    expect(
      parseTaskReceipt({ kind: 'task.cancelled', thread_id: '00000000-0000-0000-0000-000000000004' }),
    ).toBeNull()
    // 遗留 status 字段不被识别
    expect(
      parseTaskReceipt({ status: 'accepted', thread_id: '00000000-0000-0000-0000-000000000004' }),
    ).toBeNull()
  })

  it('rejects null, undefined, arrays, and primitive inputs', () => {
    expect(parseTaskReceipt(null)).toBeNull()
    expect(parseTaskReceipt(undefined)).toBeNull()
    expect(parseTaskReceipt(['task.accepted'])).toBeNull()
    expect(parseTaskReceipt(123)).toBeNull()
    expect(parseTaskReceipt(true)).toBeNull()
    expect(parseTaskReceipt('')).toBeNull()
  })
})

describe('TaskToolRenderer call phase', () => {
  it('renders the call body regardless of expanded (collapse is owned by the shell)', () => {
    // 展开/收起由宿主外壳统一负责；renderer 只负责正文部位。
    const { container, rerender } = render(<TaskToolRenderer message={message({})} />)
    expect(container.querySelector('.task-tool-renderer')).toBeInTheDocument()
    rerender(<TaskToolRenderer message={message({})} expanded={false} />)
    expect(container.querySelector('.task-tool-renderer')).toBeInTheDocument()
  })

  it('renders the prompt without a call-side thread link or repeated header params', () => {
    render(
      <MemoryRouter>
        <TaskToolRenderer
          expanded
          message={message({
            arguments:
              '{"subagent_type":"explorer","prompt":"inspect the workspace","thread_id":"00000000-0000-0000-0000-000000000101","max_turns":5}',
          })}
        />
      </MemoryRouter>,
    )
    // 导航只能由结果中的受理收据提供，调用阶段不能提前生成链接。
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('inspect the workspace')).toBeInTheDocument()
    // subagent_type / max_turns 已在 Header 展示，正文不重复。
    expect(screen.queryByText('explorer')).not.toBeInTheDocument()
    expect(screen.queryByText('最大轮数')).not.toBeInTheDocument()
    expect(screen.queryByText('5')).not.toBeInTheDocument()
    // 受理不代表完成：不出现任何完成/后台执行暗示。
    expect(screen.queryByText(/已接受|后台执行|已完成/)).not.toBeInTheDocument()
    expect(screen.queryByText(/运行中/)).not.toBeInTheDocument()
  })

  it('does not repeat a thread-only call as raw arguments', () => {
    const args = '{"thread_id":"00000000-0000-0000-0000-000000000101"}'
    const { container } = render(
      <MemoryRouter><TaskToolRenderer message={message({ arguments: args })} /></MemoryRouter>,
    )
    // thread_id 已在 Header 显示，缺少 prompt 不应触发原始参数回退。
    expect(container.querySelector('.thread-tool-output')).toBeNull()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('does not render fields for legacy session_id or maxTurns aliases', () => {
    render(
      <TaskToolRenderer
        expanded
        message={message({
          arguments:
            '{"subagent_type":"explorer","prompt":"inspect","session_id":"legacy-7","maxTurns":10}',
        })}
      />,
    )
    expect(screen.getByText('inspect')).toBeInTheDocument()
    // session_id 与 maxTurns 不识别，不应出现 Thread 链接或遗留会话指纹。
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('legacy-7')).not.toBeInTheDocument()
  })

  it('falls back to raw arguments viewport when arguments is not valid JSON', () => {
    render(
      <TaskToolRenderer
        expanded
        message={message({ arguments: 'not-json' })}
      />,
    )
    expect(screen.getByText('not-json')).toBeInTheDocument()
  })
})

describe('TaskToolRenderer result phase', () => {
  it('renders a canonical details receipt as a readable link and handles an empty result without inventing one', () => {
    const { rerender } = render(
      <MemoryRouter>
        <TaskToolRenderer
          message={message({
            phase: 'result',
            status: 'done',
            details: {
              kind: 'task.accepted',
              thread_id: '00000000-0000-4000-8000-000000000004',
            },
            contents: [{ type: 'text', text: 'Subagent execution started.' }],
          })}
        />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '查看 subagent (00000000-0000-4000-8000-000000000004) 执行' }))
      .toHaveAttribute('href', '/threads/00000000-0000-4000-8000-000000000004')
    rerender(
      <MemoryRouter>
        <TaskToolRenderer
          message={message({
            phase: 'result',
            status: 'done',
            contents: [{ type: 'text', text: '   ' }],
          })}
        />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('preserves user-owned Thread ID text including a UUID in the prompt', () => {
    const { container } = render(<MemoryRouter><TaskToolRenderer message={message({
      arguments: JSON.stringify({
        subagent_type: 'Explorer', thread_id: '00000000-0000-4000-8000-000000000004',
        prompt: 'Thread ID 00000000-0000-4000-8000-000000000004 user-owned-content must remain verbatim',
      }),
    })} /></MemoryRouter>)
    expect(container.textContent)
      .toContain('Thread ID 00000000-0000-4000-8000-000000000004 user-owned-content must remain verbatim')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(container.querySelector('dt')).toBeNull()
  })

  it('shows only the thread link for a canonical accepted receipt and suppresses natural language contents', () => {
    render(
      <MemoryRouter>
        <TaskToolRenderer
          message={message({
            phase: 'result',
            status: 'done',
            details: {
              kind: 'task.accepted',
              thread_id: '00000000-0000-0000-0000-000000000202',
            },
            contents: [{
              type: 'text',
              text: 'Subagent execution started.',
            }],
          })}
        />
      </MemoryRouter>,
    )
    const link = screen.getByRole('link', { name: '查看 subagent (00000000-0000-0000-0000-000000000202) 执行' })
    expect(link).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000202')
    // 受理收据既不重复打印正文，也不宣称子任务完成。
    expect(screen.queryByText('Subagent execution started.')).not.toBeInTheDocument()
    expect(screen.queryByText(/已接受|后台执行|已完成|报告/)).not.toBeInTheDocument()
  })

  it('shows the full receipt thread ID in the English link label', () => {
    // 英文插值必须与实际导航目标一致，不能显示未替换的占位符。
    setLocale('en-US')
    render(
      <MemoryRouter>
        <TaskToolRenderer message={message({
          phase: 'result',
          details: { kind: 'task.accepted', thread_id: '00000000-0000-4000-8000-000000000004' },
        })} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'View subagent (00000000-0000-4000-8000-000000000004) execution' }))
      .toHaveAttribute('href', '/threads/00000000-0000-4000-8000-000000000004')
  })

  it('falls back to the full contents when details is missing or invalid', () => {
    const { rerender } = render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          contents: [{ type: 'text', text: 'raw result without details' }],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('raw result without details')).toBeInTheDocument()

    // details 损坏（缺少 UUID 或格式错误）：无链接，保留原始文本
    rerender(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          details: { kind: 'task.accepted', thread_id: 'not-a-uuid' },
          contents: [{ type: 'text', text: 'raw result with malformed details' }],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('raw result with malformed details')).toBeInTheDocument()
  })

  it('treats legacy body JSON text as fallback content without synthesizing a link', () => {
    // 遗留正文 JSON 不再被识别为 receipt，直接按原文展示
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          contents: [{ type: 'text', text: '{"status":"accepted","thread_id":"00000000-0000-0000-0000-000000000202"}' }],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('{"status":"accepted","thread_id":"00000000-0000-0000-0000-000000000202"}')).toBeInTheDocument()
  })

  it('never claims completion for a non-accepted kind payload', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          details: {
            kind: 'task.completed',
            thread_id: '00000000-0000-0000-0000-000000000101',
          },
          contents: [{
            type: 'text',
            text: 'report: execution finished',
          }],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText(/已接受|后台执行|已完成/)).not.toBeInTheDocument()
    expect(
      screen.getByText('report: execution finished'),
    ).toBeInTheDocument()
  })

  it('shows malformed payloads verbatim without swallowing them', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          contents: [{ type: 'text', text: '{malformed json content' }],
        })}
      />,
    )
    expect(screen.getByText('{malformed json content')).toBeInTheDocument()
  })

  it('shows the raw failure contents and JSON body of an error result without link even if details is present', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          errorMessage: 'concurrency limit reached',
          details: {
            kind: 'task.accepted',
            thread_id: '00000000-0000-0000-0000-000000000999',
          },
          contents: [
            { type: 'text', text: 'subagent task rejected' },
            { type: 'json', value: '{"code":"CONCURRENCY_LIMIT"}' },
          ],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('subagent task rejected')).toBeInTheDocument()
    expect(screen.getByText(/"code": "CONCURRENCY_LIMIT"/)).toBeInTheDocument()
    expect(screen.queryByText(/已接受|后台执行/)).not.toBeInTheDocument()
  })

  it('renders the complete ordered fallback text in the bounded viewport', () => {
    const lines = Array.from({ length: 7 }, (_, i) => `fallback-line-${i + 1}`).join('\n')
    const terminal = message({
      phase: 'result',
      status: 'done',
      contents: [{ type: 'text', text: lines }],
    })
    const { container } = render(<TaskToolRenderer message={terminal} expanded />)
    const output = container.querySelector('.thread-tool-output')

    // 正文完整可见（折叠由卡片外壳处理），不再按行截断。
    expect(output).toHaveTextContent('fallback-line-1')
    expect(output).toHaveTextContent('fallback-line-7')
    expect(output?.textContent).not.toMatch(/more line|earlier line/)
  })
})
