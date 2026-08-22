import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TaskToolRenderer } from '@/features/ai/runtime/thread-panel/messages/TaskToolRenderer'
import {
  parseTaskArguments,
  parseTaskFinalText,
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

const heartbeat =
  '{"kind":"task.status","threadId":"101","subagentType":"explorer","state":"running_tool",'
  + '"depth":2,"turns":3,"toolCalls":5,"lastActivity":"running read","approvals":[]}\n'

describe('parseTaskArguments', () => {
  it('extracts the stable subagent/prompt/session/maxTurns fields', () => {
    expect(
      parseTaskArguments(
        '{"subagent_type":"coder","prompt":"fix it","session_id":"7","maxTurns":4}',
      ),
    ).toEqual({
      subagentType: 'coder',
      prompt: 'fix it',
      sessionId: '7',
      maxTurns: 4,
    })
    // 可选字段缺省时全部为 null。
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"fix it"}')).toEqual({
      subagentType: 'coder',
      prompt: 'fix it',
      sessionId: null,
      maxTurns: null,
    })
  })

  it('drops non-numeric maxTurns and invalid JSON without coercion', () => {
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"p","maxTurns":"4"}').maxTurns).toBeNull()
    expect(parseTaskArguments('{"subagent_type":"coder","prompt":"p","maxTurns":0}').maxTurns).toBeNull()
    expect(parseTaskArguments('not-json')).toEqual({
      subagentType: null,
      prompt: null,
      sessionId: null,
      maxTurns: null,
    })
  })
})

describe('parseTaskFinalText', () => {
  it('parses the completed form and tolerates whitespace/order changes', () => {
    const text =
      '<task id="101" state="completed">\n<task_result>\n'
      + 'report line one\nreport line two\n</task_result>\n</task>'
    expect(parseTaskFinalText(text)).toEqual({
      state: 'completed',
      report: 'report line one\nreport line two',
      error: null,
    })
    const reordered =
      '<task\n  state = "completed"   id = "101"\n>\n'
      + '<task_result>report</task_result></task>'
    expect(parseTaskFinalText(reordered)).toEqual({
      state: 'completed',
      report: 'report',
      error: null,
    })
  })

  it('parses the error and cancelled forms with task_error content', () => {
    expect(
      parseTaskFinalText('<task id="102" state="error">\n<task_error>concurrency limit reached</task_error>\n</task>'),
    ).toEqual({ state: 'error', report: null, error: 'concurrency limit reached' })
    expect(
      parseTaskFinalText('<task id="103" state="cancelled">\n<task_error>Cancelled by user</task_error>\n</task>'),
    ).toEqual({ state: 'cancelled', report: null, error: 'Cancelled by user' })
  })

  it('keeps a report that mentions the task_result closing tag', () => {
    expect(
      parseTaskFinalText(
        '<task state="completed"><task_result>mention </task_result> literally'
        + '</task_result></task>',
      ),
    ).toEqual({
      state: 'completed',
      report: 'mention </task_result> literally',
      error: null,
    })
  })

  it('returns null for anything that is not a task terminal document', () => {
    expect(parseTaskFinalText('plain text')).toBeNull()
    expect(parseTaskFinalText('{"kind":"task.status","state":"running"}')).toBeNull()
    expect(parseTaskFinalText('<task state="running_model">no terminal tags</task>')).toBeNull()
  })
})

describe('TaskToolRenderer call phase', () => {
  it('returns nothing when a non-streaming call is collapsed and has no live status', () => {
    const { container } = render(<TaskToolRenderer message={message({})} />)
    expect(container.querySelector('.task-tool-renderer')).not.toBeInTheDocument()
  })

  it('keeps the collapsed call to live status and defers task arguments until expanded', () => {
    render(
      <TaskToolRenderer
        message={message({
          status: 'streaming',
          partial: heartbeat,
        })}
      />,
    )

    expect(screen.getByText('工具运行中')).toBeInTheDocument()
    expect(screen.getByText('3 轮')).toBeInTheDocument()
    expect(screen.queryByText('inspect the workspace')).not.toBeInTheDocument()
    expect(screen.queryByText('子代理')).not.toBeInTheDocument()
  })

  it('renders stable subagent/prompt/session/maxTurns and the latest live status', () => {
    render(
      <TaskToolRenderer
        expanded
        message={message({
          arguments:
            '{"subagent_type":"explorer","prompt":"inspect the workspace","session_id":"7","maxTurns":4}',
          partial: heartbeat,
        })}
      />,
    )
    expect(screen.getByText('子代理')).toBeInTheDocument()
    expect(screen.getByText('explorer')).toBeInTheDocument()
    expect(screen.getByText('会话')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('最大轮数')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('任务提示')).toBeInTheDocument()
    expect(screen.getByText('inspect the workspace')).toBeInTheDocument()
    // 最新运行状态：标签化展示，绝不是原始 task.status JSON。
    expect(screen.getByText('工具运行中')).toBeInTheDocument()
    expect(screen.getByText('3 轮')).toBeInTheDocument()
    expect(screen.getByText('5 次工具调用')).toBeInTheDocument()
    expect(screen.getByText('running read')).toBeInTheDocument()
    expect(screen.queryByText(/task\.status/)).not.toBeInTheDocument()
  })

  it('renders the live strip without optional fields and with a minimal heartbeat', () => {
    const heartbeat =
      '{"kind":"task.status","threadId":"101","subagentType":"explorer","state":"running_model",'
      + '"depth":null,"turns":null,"toolCalls":null,"lastActivity":null,"approvals":[]}\n'
    render(
      <TaskToolRenderer
        message={message({
          partial: heartbeat,
          status: 'streaming',
        })}
      />,
    )
    // state 有文案即可；turns/toolCalls/lastActivity 缺省时不得渲染对应 span。
    expect(screen.getByText('模型运行中')).toBeInTheDocument()
    expect(screen.queryByText(/轮/u)).not.toBeInTheDocument()
    expect(screen.queryByText(/次工具调用/u)).not.toBeInTheDocument()
  })

  it('renders without the live strip when the partial is not a task.status heartbeat', () => {
    render(
      <TaskToolRenderer
        expanded
        message={message({ partial: '{"kind":"other","text":"delta"}' })}
      />,
    )
    expect(screen.queryByText('工具运行中')).not.toBeInTheDocument()
    expect(screen.getByText('inspect the workspace')).toBeInTheDocument()
  })

  it('falls back to the raw arguments when they are not valid JSON', () => {
    render(
      <TaskToolRenderer
        expanded
        message={message({ arguments: 'not-json', partial: undefined })}
      />,
    )
    expect(screen.getByText('not-json')).toBeInTheDocument()
  })
})

describe('TaskToolRenderer result phase', () => {
  it('shows the last five report lines while collapsed and the full report when expanded', () => {
    const report = Array.from({ length: 7 }, (_, index) => `report-${index + 1}`).join('\n')
    const terminal = message({
      phase: 'result',
      status: 'done',
      text:
        '<task id="101" state="completed">\n<task_result>\n'
        + `${report}\n</task_result>\n</task>`,
    })
    const { container, rerender } = render(<TaskToolRenderer message={terminal} />)
    const output = container.querySelector('.thread-tool-output')

    expect(output).not.toHaveTextContent('report-1')
    expect(output).not.toHaveTextContent('report-2')
    expect(output).toHaveTextContent('report-3')
    expect(output).toHaveTextContent('report-7')

    rerender(<TaskToolRenderer message={terminal} expanded />)
    expect(output).toHaveTextContent('report-1')
    expect(output).toHaveTextContent('report-7')
  })

  it('renders the final report and completed state instead of raw XML', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          text:
            '<task id="101" state="completed">\n<task_result>\n'
            + 'final report\n</task_result>\n</task>',
        })}
      />,
    )
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('报告')).toBeInTheDocument()
    expect(screen.getByText('final report')).toBeInTheDocument()
    expect(screen.queryByText(/<task_result>/)).not.toBeInTheDocument()
    expect(screen.queryByText(/task\.status/)).not.toBeInTheDocument()
  })

  it('renders task final errors (e.g. concurrency limit) from task_error', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          text:
            '<task id="102" state="error">\n'
            + '<task_error>subagent concurrency limit reached (3/3)</task_error>\n</task>',
        })}
      />,
    )
    expect(screen.getByText('失败')).toBeInTheDocument()
    expect(screen.getByText('错误')).toBeInTheDocument()
    expect(screen.getByText('subagent concurrency limit reached (3/3)')).toBeInTheDocument()
  })

  it('renders the cancelled terminal state copy', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'done',
          text:
            '<task id="103" state="cancelled">\n'
            + '<task_error>Cancelled by user</task_error>\n</task>',
        })}
      />,
    )
    expect(screen.getByText('已取消')).toBeInTheDocument()
    expect(screen.getByText('错误')).toBeInTheDocument()
    expect(screen.getByText('Cancelled by user')).toBeInTheDocument()
  })

  it('falls back to raw text and surfaces a distinct errorMessage for non-task documents', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          text: 'plain terminal output',
          errorMessage: 'subagent crashed',
        })}
      />,
    )
    expect(screen.getByText('plain terminal output')).toBeInTheDocument()
    expect(screen.getByText('subagent crashed')).toBeInTheDocument()
  })

  it('omits the error paragraph when errorMessage equals the raw text', () => {
    render(
      <TaskToolRenderer
        message={message({
          phase: 'result',
          status: 'error',
          text: 'same error',
          errorMessage: 'same error',
        })}
      />,
    )
    expect(screen.getByText('same error')).toBeInTheDocument()
    expect(screen.queryAllByText('same error')).toHaveLength(1)
  })

  it('falls back to the raw text when the terminal text is not a task document', () => {
    render(
      <TaskToolRenderer
        message={message({ phase: 'result', text: 'plain terminal output' })}
      />,
    )
    expect(screen.getByText('plain terminal output')).toBeInTheDocument()
  })
})
