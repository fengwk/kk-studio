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
    text: '',
    toolCallId: 'call-task',
    toolName: 'task',
    arguments: '{"subagent_type":"explorer","prompt":"inspect the workspace"}',
    attachments: [],
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
  it('returns nothing when call is collapsed', () => {
    const { container } = render(<TaskToolRenderer message={message({})} />)
    expect(container.querySelector('.task-tool-renderer')).not.toBeInTheDocument()
  })

  it('renders subagent, threadId, maxTurns, and prompt when expanded', () => {
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
    expect(screen.getByText('子代理')).toBeInTheDocument()
    expect(screen.getByText('explorer')).toBeInTheDocument()
    expect(screen.getByText('Thread ID')).toBeInTheDocument()
    const threadLink = screen.getByRole('link', { name: '00000000-0000-0000-0000-000000000101' })
    expect(threadLink).toBeInTheDocument()
    expect(threadLink).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000101')
    expect(screen.getByText('最大轮数')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('任务提示')).toBeInTheDocument()
    expect(screen.getByText('inspect the workspace')).toBeInTheDocument()
    // 绝不包含已废弃的 TaskLiveStatus 心跳内容
    expect(screen.queryByText(/运行中/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\d+\s*轮/)).not.toBeInTheDocument()
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
    expect(screen.getByText('子代理')).toBeInTheDocument()
    expect(screen.getByText('explorer')).toBeInTheDocument()
    expect(screen.getByText('任务提示')).toBeInTheDocument()
    expect(screen.getByText('inspect')).toBeInTheDocument()
    // session_id 与 maxTurns 不识别，不应出现 Thread ID 或 最大轮数
    expect(screen.queryByText('Thread ID')).not.toBeInTheDocument()
    expect(screen.queryByText('legacy-7')).not.toBeInTheDocument()
    expect(screen.queryByText('最大轮数')).not.toBeInTheDocument()
    expect(screen.queryByText('10')).not.toBeInTheDocument()
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
  it('renders accepted state and Thread ID for valid accepted receipt JSON', () => {
    render(
      <MemoryRouter>
        <TaskToolRenderer
          message={message({
            phase: 'result',
            status: 'done',
            text: '{"thread_id":"00000000-0000-0000-0000-000000000202","status":"accepted"}',
          })}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('已接受 / 后台执行')).toBeInTheDocument()
    expect(screen.getByText('Thread ID')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: '00000000-0000-0000-0000-000000000202' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/threads/00000000-0000-0000-0000-000000000202')
    // 绝不显示已完成、报告或伪造的终态
    expect(screen.queryByText('已完成')).not.toBeInTheDocument()
    expect(screen.queryByText('报告')).not.toBeInTheDocument()
  })

  it('falls back to raw text when thread_id is missing or invalid UUID', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          text: '{"status":"accepted"}',
        })}
      />,
    )
    expect(screen.queryByText('已接受 / 后台执行')).not.toBeInTheDocument()
    expect(screen.getByText('{"status":"accepted"}')).toBeInTheDocument()
  })

  it('falls back to raw text and does not claim completed when given completed status', () => {
    // 即使 tool result 给出了 completed 文本（或旧 XML/JSON），也绝不当收据，原样降级展示且绝不伪造已完成
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          text: '{"thread_id":"child-101","status":"completed","report":"done"}',
        })}
      />,
    )
    expect(screen.queryByText('已接受 / 后台执行')).not.toBeInTheDocument()
    expect(screen.queryByText('已完成')).not.toBeInTheDocument()
    expect(
      screen.getByText('{"thread_id":"child-101","status":"completed","report":"done"}'),
    ).toBeInTheDocument()
  })

  it('falls back to raw text for malformed JSON or unknown status without swallowing', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          text: '{malformed json content',
        })}
      />,
    )
    expect(screen.getByText('{malformed json content')).toBeInTheDocument()
    expect(screen.queryByText('已接受 / 后台执行')).not.toBeInTheDocument()
  })

  it('renders error message and raw text cleanly on error result', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          text: 'subagent task rejected',
          errorMessage: 'concurrency limit reached',
        })}
      />,
    )
    expect(screen.getByText('subagent task rejected')).toBeInTheDocument()
    expect(screen.getByText('concurrency limit reached')).toBeInTheDocument()
    expect(screen.queryByText('已接受 / 后台执行')).not.toBeInTheDocument()
  })

  it('omits separate errorMessage when it equals raw text', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          text: 'task failed',
          errorMessage: 'task failed',
        })}
      />,
    )
    expect(screen.getByText('task failed')).toBeInTheDocument()
    expect(screen.queryAllByText('task failed')).toHaveLength(1)
  })

  it('respects collapsed vs expanded preview for fallback multiline text', () => {
    const lines = Array.from({ length: 7 }, (_, i) => `fallback-line-${i + 1}`).join('\n')
    const terminal = message({
      phase: 'result',
      status: 'done',
      text: lines,
    })
    const { container, rerender } = render(<TaskToolRenderer message={terminal} />)
    const output = container.querySelector('.thread-tool-output')

    // 收起时截断前两行，只展示后五行
    expect(output).not.toHaveTextContent('fallback-line-1')
    expect(output).toHaveTextContent('fallback-line-7')

    // 展开时完整呈现
    rerender(<TaskToolRenderer message={terminal} expanded />)
    expect(output).toHaveTextContent('fallback-line-1')
    expect(output).toHaveTextContent('fallback-line-7')
  })
})
