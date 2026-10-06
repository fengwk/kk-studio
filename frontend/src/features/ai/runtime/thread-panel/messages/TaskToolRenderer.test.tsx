import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { TaskToolRenderer } from '@/features/ai/runtime/thread-panel/messages/TaskToolRenderer'
import {
  parseTaskArguments,
  parseTaskReceipt,
} from '@/features/ai/runtime/task-tool-parser'
import type { ToolRendererMessage } from '@/platform/extensions/types'

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
  it('parses valid accepted receipt JSON with canonical UUID thread_id', () => {
    expect(
      parseTaskReceipt('{"thread_id":"00000000-0000-0000-0000-000000000004","status":"accepted"}'),
    ).toEqual({
      status: 'accepted',
      threadId: '00000000-0000-0000-0000-000000000004',
    })
  })

  it('rejects receipt when thread_id is missing, blank, or not a canonical UUID', () => {
    // 缺少 thread_id 或非 UUID shape 绝不能隐瞒为成功收据，必须返回 null
    expect(parseTaskReceipt('{"status":"accepted"}')).toBeNull()
    expect(parseTaskReceipt('{"status":"accepted","thread_id":"   "}')).toBeNull()
    expect(parseTaskReceipt('{"status":"accepted","thread_id":"not-a-uuid"}')).toBeNull()
    expect(parseTaskReceipt('{"status":"accepted","thread_id":123}')).toBeNull()
  })

  it('strictly rejects completed status and avoids accepted vs completed confusion', () => {
    // 终态 completed 绝不是受理收据，必须返回 null
    expect(
      parseTaskReceipt('{"thread_id":"101","status":"completed"}'),
    ).toBeNull()
    expect(
      parseTaskReceipt('{"status":"completed","report":"done"}'),
    ).toBeNull()
    expect(
      parseTaskReceipt('{"thread_id":"101","status":"error"}'),
    ).toBeNull()
    expect(
      parseTaskReceipt('{"thread_id":"101","status":"cancelled"}'),
    ).toBeNull()
  })

  it('rejects legacy XML envelopes and non-JSON text', () => {
    expect(
      parseTaskReceipt('<task thread_id="101" state="accepted">delegation accepted</task>'),
    ).toBeNull()
    expect(
      parseTaskReceipt('<task id="101" state="completed"><task_result>report</task_result></task>'),
    ).toBeNull()
    expect(parseTaskReceipt('plain text receipt')).toBeNull()
  })

  it('rejects malformed JSON and non-object inputs', () => {
    expect(parseTaskReceipt('{bad json')).toBeNull()
    expect(parseTaskReceipt('{"status":"accepted"')).toBeNull()
    expect(parseTaskReceipt('["accepted"]')).toBeNull()
    expect(parseTaskReceipt('123')).toBeNull()
    expect(parseTaskReceipt('')).toBeNull()
    expect(parseTaskReceipt('   ')).toBeNull()
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

  it('renders the prompt and a clickable thread id without repeating header params', () => {
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
    const threadLink = screen.getByRole('link', { name: '00000000-0000-0000-0000-000000000101' })
    expect(threadLink).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000101')
    expect(screen.getByText('inspect the workspace')).toBeInTheDocument()
    // subagent_type / max_turns 已在 Header 展示，正文不重复。
    expect(screen.queryByText('explorer')).not.toBeInTheDocument()
    expect(screen.queryByText('最大轮数')).not.toBeInTheDocument()
    expect(screen.queryByText('5')).not.toBeInTheDocument()
    // 受理不代表完成：不出现任何完成/后台执行暗示。
    expect(screen.queryByText(/已接受|后台执行|已完成/)).not.toBeInTheDocument()
    expect(screen.queryByText(/运行中/)).not.toBeInTheDocument()
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
  it('shows only the thread link for a valid accepted receipt', () => {
    render(
      <MemoryRouter>
        <TaskToolRenderer
          message={message({
            phase: 'result',
            status: 'done',
            contents: [{
              type: 'text',
              text: '{"thread_id":"00000000-0000-0000-0000-000000000202","status":"accepted"}',
            }],
          })}
        />
      </MemoryRouter>,
    )
    const link = screen.getByRole('link', { name: '00000000-0000-0000-0000-000000000202' })
    expect(link).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000202')
    // 受理收据既不重复打印，也不宣称子任务完成。
    expect(screen.queryByText(/"status":"accepted"/)).not.toBeInTheDocument()
    expect(screen.queryByText(/已接受|后台执行|已完成|报告/)).not.toBeInTheDocument()
  })

  it('falls back to the full contents when thread_id is missing or not a canonical UUID', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          contents: [{ type: 'text', text: '{"status":"accepted"}' }],
        })}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('{"status":"accepted"}')).toBeInTheDocument()
  })

  it('never claims completion for a completed-status payload', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          contents: [{
            type: 'text',
            text: '{"thread_id":"child-101","status":"completed","report":"done"}',
          }],
        })}
      />,
    )
    expect(screen.queryByText(/已接受|后台执行|已完成/)).not.toBeInTheDocument()
    expect(
      screen.getByText('{"thread_id":"child-101","status":"completed","report":"done"}'),
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

  it('shows the raw failure contents and JSON body of an error result', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          errorMessage: 'concurrency limit reached',
          contents: [
            { type: 'text', text: 'subagent task rejected' },
            { type: 'json', value: '{"code":"CONCURRENCY_LIMIT"}' },
          ],
        })}
      />,
    )
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
