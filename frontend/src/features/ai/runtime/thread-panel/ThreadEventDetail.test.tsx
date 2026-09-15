import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadEventDetail } from '@/features/ai/runtime/thread-panel/ThreadEventDetail'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'

function createRecord(overrides: Partial<ThreadEventRecord> = {}): ThreadEventRecord {
  return {
    id: 'test-event-1',
    source: 'active-tool',
    entryId: null,
    turnStartEntryId: 'turn-1',
    turnNumber: 1,
    kind: 'ACTIVE_TOOL_INVOCATION',
    status: 'completed',
    title: 'ACTIVE_TOOL_INVOCATION',
    summary: 'bash · 已完成',
    createdAt: '2026-07-28T10:00:00Z',
    details: [
      { label: '状态', value: '已完成' },
      { label: '调用 ID', value: 'inv-123' },
      { label: '参数', value: '{\n  "command": "echo test"\n}' },
      { label: '结果', value: '{\n  "stdout": "test\\n"\n}' },
    ],
    rawJson: '{\n  "id": "inv-123",\n  "status": "COMPLETED"\n}',
    ...overrides,
  }
}

describe('ThreadEventDetail', () => {
  it('renders title, structured details, and pretty raw payload JSON', () => {
    // 意图：验证事件详情面板完整呈现标题、details 列表（包含 dl/dt/dd 结构）与 rawJson。
    const record = createRecord()
    render(<ThreadEventDetail record={record} onClose={vi.fn()} />)

    expect(screen.getByRole('heading', { level: 3, name: 'ACTIVE_TOOL_INVOCATION' })).toBeInTheDocument()
    expect(screen.getByText('状态')).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('调用 ID')).toBeInTheDocument()
    expect(screen.getByText('inv-123')).toBeInTheDocument()
    expect(screen.getByText('参数')).toBeInTheDocument()
    expect(screen.getByText(/echo test/)).toBeInTheDocument()
    expect(screen.getByText('结果')).toBeInTheDocument()
    expect(screen.getByText(/test\\n/)).toBeInTheDocument()

    const payloadPre = document.querySelector('.thread-event-detail-payload')
    expect(payloadPre).not.toBeNull()
    expect(payloadPre?.textContent).toContain('"id": "inv-123"')
    expect(payloadPre?.textContent).toContain('"status": "COMPLETED"')
  })

  it('calls onClose when clicking close button', () => {
    // 意图：验证点击右上角关闭按钮能够触发 onClose 回调以退出事件详情视图。
    const onClose = vi.fn()
    const record = createRecord()
    render(<ThreadEventDetail record={record} onClose={onClose} />)

    const closeButton = screen.getByRole('button', { name: '关闭事件详情' })
    expect(closeButton).toBeInTheDocument()
    fireEvent.click(closeButton)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('falls back to summary when rawJson is null', () => {
    // 意图：验证当事件记录无 rawJson 时，payload 区域兜底展示 summary。
    const record = createRecord({ rawJson: null, summary: 'fallback summary text' })
    render(<ThreadEventDetail record={record} onClose={vi.fn()} />)

    const payloadPre = document.querySelector('.thread-event-detail-payload')
    expect(payloadPre?.textContent).toBe('fallback summary text')
  })

  it('omits details list when details array is empty', () => {
    // 意图：验证当 details 数组为空时，不渲染空的 .thread-event-detail-rows 元素。
    const record = createRecord({ details: [] })
    render(<ThreadEventDetail record={record} onClose={vi.fn()} />)

    expect(document.querySelector('.thread-event-detail-rows')).toBeNull()
    expect(document.querySelector('.thread-event-detail-payload')).not.toBeNull()
  })

  it('renders long (>128 chars) multiline JSON error details and full rawJson payload untruncated in debug view', () => {
    // 意图：验证 /debug 详情面板中，结构化详情行与 rawJson 区域完整展示超长多行原始错误体（含请求 ID、供应商错误码、自定义参数），即使摘要在列表中被截断，详情区仍全量可见
    const rawError = [
      'HTTP 400',
      '{',
      '  "error": {',
      '    "message": "upstream context length exceeded: tokens=145000 max=128000. 详情：超过上下文限制",',
      '    "type": "invalid_request_error",',
      '    "param": "prompt_tokens",',
      '    "code": "context_exceeded",',
      '    "request_id": "req_debug_trace_8877665544_vendor_xyz"',
      '  }',
      '}',
    ].join('\n')
    expect(rawError.length).toBeGreaterThan(128)

    const rawJson = JSON.stringify(
      {
        attempt: 1,
        sequence: '0',
        error: {
          code: 'HTTP_400',
          message: rawError,
        },
      },
      null,
      2,
    )

    const record = createRecord({
      kind: 'MODEL_ATTEMPT_FAILURE',
      title: 'MODEL_ATTEMPT_FAILURE',
      summary: 'attempt 1 · HTTP_400', // 列表预览摘要仅简短文字
      details: [
        { label: '错误代码', value: 'HTTP_400' },
        { label: '错误信息', value: rawError },
      ],
      rawJson,
    })

    const { container } = render(<ThreadEventDetail record={record} onClose={vi.fn()} />)

    // 验证 dl/dd 中结构化呈现未被截断的超长错误信息
    expect(screen.getByText('错误信息')).toBeInTheDocument()
    const ddElements = container.querySelectorAll('dd')
    expect(ddElements[1]?.textContent).toBe(rawError)

    // 验证 pre 区域精确呈现 rawJson
    const payloadPre = container.querySelector('.thread-event-detail-payload')
    expect(payloadPre?.textContent).toBe(record.rawJson)
  })
})
