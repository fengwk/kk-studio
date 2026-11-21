import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ModelAttemptFailureMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { retryCountdownSeconds } from '@/features/thread/runtime/thread-panel/messages/retry-countdown'
import {
  FAILURE_EXCERPT_LIMIT,
  summarizeModelFailure,
} from '@/features/thread/runtime/thread-timeline/model-failure-summary'
import type { ModelAttemptFailureDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { setLocale } from '@/shared/i18n'

/** 审查环境 mock 返回的真实 HTTP 400 响应体（原样 JSON 文本）。 */
const REVIEW_RESPONSE_BODY =
  '{"error":{"message":"mock: maximum context length exceeded","type":"invalid_request_error","code":"context_length_exceeded"}}'

function message(
  overrides: Partial<ModelAttemptFailureDialogueMessage> = {},
): ModelAttemptFailureDialogueMessage {
  return {
    id: 'failure-1',
    role: 'model_attempt_failure',
    subjectEntryId: null,
    createdAt: Date.parse('2026-07-28T10:00:00Z'),
    status: 'done',
    attempt: 1,
    sequence: '2',
    text: 'partial output',
    thinking: 'partial thinking',
    errorCode: 'TRANSIENT',
    errorMessage: 'provider is busy',
    failedAt: Date.parse('2026-07-28T10:00:00Z'),
    retryAt: Date.parse('2026-07-28T10:00:05Z'),
    nextAttempt: 2,
    generationId: 'model-1',
    ...overrides,
  }
}

describe('ModelAttemptFailureMessageBlock', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-28T10:00:02Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
    setLocale('zh-CN')
  })

  it('renders partial content normally and error in a separate block with countdown', () => {
    const { container } = render(<ModelAttemptFailureMessageBlock message={message()} />)

    expect(screen.getByText('模型请求失败')).toBeInTheDocument()
    expect(screen.getByText('请求 #1')).toBeInTheDocument()
    expect(screen.getByText('3 秒后重试（请求 #2）')).toBeInTheDocument()
    expect(screen.getByText('partial output')).toBeInTheDocument()
    expect(screen.getByText('partial thinking')).toBeInTheDocument()
    expect(screen.getByText('TRANSIENT')).toBeInTheDocument()
    // 纯文本错误既作为摘要，也作为折叠详情里的未截断原文。
    expect(container.querySelector('.thread-attempt-failure-detail')?.textContent)
      .toBe('provider is busy')
    expect(container.querySelector('.thread-error-raw')?.textContent).toBe('provider is busy')
    expect(container.querySelector('.thread-block-model-attempt-failure-error')).toBeInTheDocument()
  })

  // 意图：审查环境的真实 400 响应不得把 JSON 当主文案——主文案是本地化类别说明 + 供应商 error.message，
  // HTTP 状态只是辅助信息，完整原文默认折叠且可复制。
  it('summarizes a real provider 400 response and folds the raw body', () => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({
          errorCode: 'CONTEXT_LIMIT',
          errorMessage: `HTTP 400\n${REVIEW_RESPONSE_BODY}`,
        })}
      />,
    )

    const block = screen.getByText('上下文超出模型限制')
    expect(block).toHaveClass('thread-attempt-failure-kind')
    expect(screen.getByText('mock: maximum context length exceeded'))
      .toHaveClass('thread-attempt-failure-detail')
    expect(screen.getByText('HTTP 400')).toHaveClass('thread-attempt-failure-http')
    expect(screen.getByText('CONTEXT_LIMIT')).toBeInTheDocument()

    // JSON 正文不再占据卡片主视觉：只有折叠详情里保留 `HTTP 400\n<body>` 原文。
    const details = screen.getByText('原始响应').closest('details')
    expect(details).not.toBeNull()
    expect(details).not.toHaveAttribute('open')
    const raw = details?.querySelector('.thread-error-raw')
    expect(raw?.textContent).toBe(`HTTP 400\n${REVIEW_RESPONSE_BODY}`)
    expect(screen.queryByText(REVIEW_RESPONSE_BODY)).toBeNull()

    const copy = details?.querySelector('.thread-attempt-failure-copy')
    expect(copy).toHaveAttribute('aria-label', '复制原始响应')
  })

  it.each([
    ['CONTEXT_LIMIT', '上下文超出模型限制'],
    ['REQUEST_BODY_LIMIT', '请求体超出模型限制'],
    ['AUTHENTICATION', '模型认证失败'],
    ['BILLING', '账户余额或配额不足'],
    ['TRANSIENT', '模型服务暂时不可用'],
  ])('localizes the %s failure category', (errorCode, label) => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({ errorCode, errorMessage: 'HTTP 503: upstream overloaded' })}
      />,
    )

    expect(screen.getByText(label)).toBeInTheDocument()
  })

  it('keeps the raw text and a localized category in English', () => {
    setLocale('en-US')
    render(
      <ModelAttemptFailureMessageBlock
        message={message({
          errorCode: 'CONTEXT_LIMIT',
          errorMessage: `HTTP 400\n${REVIEW_RESPONSE_BODY}`,
        })}
      />,
    )

    expect(screen.getByText('Context exceeds the model limit')).toBeInTheDocument()
    expect(screen.getByText('mock: maximum context length exceeded')).toBeInTheDocument()
    expect(screen.getByText('Raw response')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy raw response' })).toBeInTheDocument()
  })

  // 意图：无法解析为 JSON 的正文（HTML 网关错误、纯文本）只显示截断原文，完整文本仍留在详情里。
  it('shows a truncated excerpt when the body is not parseable JSON', () => {
    const body = `<html><body><h1>502 Bad Gateway</h1>${'上游网关错误 '.repeat(60)}</body></html>`
    const { container } = render(
      <ModelAttemptFailureMessageBlock
        message={message({ errorCode: 'INVALID_RESPONSE', errorMessage: `HTTP 502\n${body}` })}
      />,
    )

    const detail = container.querySelector('.thread-attempt-failure-detail')
    expect(detail?.textContent?.endsWith('…')).toBe(true)
    expect([...(detail?.textContent ?? '')].length).toBeLessThanOrEqual(FAILURE_EXCERPT_LIMIT + 1)
    expect(detail?.textContent).toContain('502 Bad Gateway')
    // 详情里保留未截断原文（含 HTTP 状态行）。
    expect(container.querySelector('.thread-error-raw')?.textContent).toBe(`HTTP 502\n${body}`)
    expect(screen.getByText('模型返回了无法处理的响应')).toBeInTheDocument()
  })

  it('renders no summary copy and no details for an empty error message', () => {
    const { container } = render(
      <ModelAttemptFailureMessageBlock message={message({ errorCode: '', errorMessage: '   ' })} />,
    )

    expect(container.querySelector('.thread-attempt-failure-detail')).toBeNull()
    expect(container.querySelector('.thread-attempt-failure-kind')).toBeNull()
    expect(container.querySelector('.thread-attempt-failure-raw')).toBeNull()
    expect(screen.getByText('模型请求失败')).toBeInTheDocument()
  })

  it('changes to the stable retrying copy at retryAt and clears its timer on unmount', () => {
    const { unmount } = render(<ModelAttemptFailureMessageBlock message={message()} />)

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(screen.getByText('正在重试（请求 #2）')).toBeInTheDocument()

    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not schedule a timer for terminal failures', () => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({ retryAt: null, nextAttempt: null })}
      />,
    )

    expect(screen.getByText('模型请求失败')).toBeInTheDocument()
    expect(screen.getByText('请求 #1')).toBeInTheDocument()
    expect(screen.queryByText(/重试/u)).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('renders a durable retry as historical state without a countdown timer', () => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({
          subjectEntryId: 'entry-1',
          generationId: undefined,
        })}
      />,
    )

    expect(screen.getByText('已安排重试（请求 #2）')).toBeInTheDocument()
    expect(screen.queryByText(/还剩/u)).not.toBeInTheDocument()
    expect(screen.queryByText(/正在进行/u)).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('calculates remaining seconds without a polling interval', () => {
    expect(
      retryCountdownSeconds(Date.parse('2026-07-28T10:00:05Z'), Date.parse('2026-07-28T10:00:02.001Z')),
    ).toBe(3)
    expect(
      retryCountdownSeconds(Date.parse('2026-07-28T10:00:05Z'), Date.parse('2026-07-28T10:00:05Z')),
    ).toBe(0)
  })

  it('renders long (>128 chars) multiline JSON and HTML error text safely escaped and untruncated', () => {
    // 验证超长多行 JSON、请求 ID 与 HTML 片段在 pre 中原样展示且不被解析为 HTML DOM
    const rawError = [
      'HTTP 400',
      '{',
      '  "error": {',
      '    "message": "context length exceeded by 132450 tokens. 详情：上下文超限",',
      '    "type": "invalid_request_error",',
      '    "param": "messages.[4].content",',
      '    "code": "context_length_exceeded",',
      '    "request_id": "req_vendor_987654321_abc_xyz",',
      '    "html_snippet": "<html><body><script>alert(\\"xss\\")</script>502 Gateway Error</body></html>"',
      '  }',
      '}',
    ].join('\n')
    expect(rawError.length).toBeGreaterThan(128)

    const { container } = render(
      <ModelAttemptFailureMessageBlock
        message={message({
          errorCode: 'INVALID_REQUEST',
          errorMessage: rawError,
        })}
      />,
    )

    const errorPre = container.querySelector('.thread-error-raw')
    expect(errorPre).not.toBeNull()
    expect(errorPre?.textContent).toBe(rawError)
    // HTML 片段是 JSON 字符串内容，不能成为真实 DOM 元素。
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('html')).toBeNull()
    expect(container.querySelector('body')).toBeNull()
    expect(screen.getByText('context length exceeded by 132450 tokens. 详情：上下文超限'))
      .toHaveClass('thread-attempt-failure-detail')
    expect(screen.getAllByText(/req_vendor_987654321_abc_xyz/)).toHaveLength(1)
    expect(screen.getAllByText(/messages\.\[4\]\.content/)).toHaveLength(1)
  })
})

describe('summarizeModelFailure', () => {
  it('splits the HTTP status line from the raw body', () => {
    const rawResponse = `HTTP 400\n${REVIEW_RESPONSE_BODY}\n  `
    expect(summarizeModelFailure('CONTEXT_LIMIT', rawResponse)).toEqual({
      httpStatus: 400,
      kindKey: 'ai.runtime.message.modelFailureContextLimit',
      summary: 'mock: maximum context length exceeded',
      rawResponse,
    })
  })

  it('keeps the fallback body of a status-only message readable', () => {
    expect(summarizeModelFailure('INVALID_REQUEST', 'HTTP 400: OpenAI invalid request')).toEqual({
      httpStatus: 400,
      kindKey: 'ai.runtime.message.modelFailureInvalidRequest',
      summary: 'OpenAI invalid request',
      rawResponse: 'HTTP 400: OpenAI invalid request',
    })
  })

  it('reads a provider message from an SSE error envelope and from a bare error object', () => {
    expect(summarizeModelFailure('INVALID_REQUEST', '{"error":{"message":"bad tool schema"}}'))
      .toMatchObject({ httpStatus: null, summary: 'bad tool schema' })
    expect(summarizeModelFailure('INVALID_REQUEST', '{"error":"quota exhausted"}'))
      .toMatchObject({ httpStatus: null, summary: 'quota exhausted' })
    expect(summarizeModelFailure('TRANSIENT', '{"message":"try again later"}'))
      .toMatchObject({ httpStatus: null, summary: 'try again later' })
  })

  it('falls back to the collapsed raw body when it is not JSON', () => {
    expect(summarizeModelFailure('TRANSIENT', 'provider is busy')).toMatchObject({
      httpStatus: null,
      summary: 'provider is busy',
      rawResponse: 'provider is busy',
    })
    expect(
      summarizeModelFailure('INVALID_RESPONSE', 'HTTP 502\n{"error": {"message": "cut off'),
    ).toMatchObject({ httpStatus: 502, summary: '{"error": {"message": "cut off' })
  })

  it('marks unknown error codes and empty messages without fabricating copy', () => {
    expect(summarizeModelFailure('OFFLINE_FAILURE', '离线失败：no-usage')).toEqual({
      httpStatus: null,
      kindKey: null,
      summary: '离线失败：no-usage',
      rawResponse: '离线失败：no-usage',
    })
    expect(summarizeModelFailure('', '  \n ')).toEqual({
      httpStatus: null,
      kindKey: null,
      summary: '',
      rawResponse: null,
    })
  })
})
