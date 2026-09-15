import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type {
  HarnessSessionEntryDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { buildThreadEventTimeline } from '@/features/ai/runtime/thread-events'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import type { RealtimeModelStream } from '@/features/ai/runtime/thread-realtime-state'
import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import { ModelAttemptFailureMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { ThreadEventDetail } from '@/features/ai/runtime/thread-panel/ThreadEventDetail'
import { translate } from '@/shared/i18n'

/** 供应商原始多行 JSON 错误（HTTP <status>\n<body> 格式，>128 字符）。 */
const RAW_VENDOR_ERROR_BODY = [
  'HTTP 400',
  '{',
  '  "error": {',
  '    "message": "The requested model `custom-ultra-v3` failed: context length limit exceeded with 132450 tokens. 详情：模型上下文超限，请清理历史记录或开启自动压缩。",',
  '    "type": "invalid_request_error",',
  '    "param": "messages.[4].content",',
  '    "code": "context_length_exceeded",',
  '    "request_id": "req_vendor_9876543210_alpha_beta_gamma",',
  '    "upstream_debug": {',
  '      "cluster": "us-east-zone-1",',
  '      "trace_id": "trace-9988-7766-5544",',
  '      "html_snippet": "<html><body><script>alert(\\"xss\\")</script>502 Gateway Error</body></html>"',
  '    }',
  '  }',
  '}',
].join('\n')

/** 网关原始 HTML 错误（HTTP <status>\n<body> 格式）。 */
const RAW_HTML_GATEWAY_ERROR = [
  'HTTP 502',
  '<!DOCTYPE html>',
  '<html>',
  '<head><title>502 Bad Gateway</title></head>',
  '<body>',
  '<center><h1>502 Bad Gateway</h1></center>',
  '<hr><center>cloudflare-nginx/1.25.1</center>',
  '<!-- request_id: cfd-9923849102830192-SJC -->',
  '<script>window.__injected = true</script>',
  '</body>',
  '</html>',
].join('\n')

function sessionEntry(
  entryId: string,
  entryType: HarnessSessionEntryDTO['entryType'],
  payload: Record<string, unknown>,
  overrides: Partial<HarnessSessionEntryDTO> = {},
): HarnessSessionEntryDTO {
  return {
    entryId,
    parentEntryId: null,
    sessionId: 'session-1',
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-09-15T12:00:00Z',
    ...overrides,
  }
}

describe('raw model error visibility (所见即所得：真实错误不脱敏、不截断、安全转义)', () => {
  describe('Timeline projection (buildThreadTimeline)', () => {
    it('preserves raw multiline error (>128 chars) in durable MODEL_ATTEMPT_FAILURE projection', () => {
      // 验证持久化 MODEL_ATTEMPT_FAILURE 原样保留超长多行原始错误体
      expect(RAW_VENDOR_ERROR_BODY.length).toBeGreaterThan(128)

      const entry = sessionEntry('failure-1', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 0, text: 'partial thoughts', thinking: '' },
        error: { code: 'HTTP_400', message: RAW_VENDOR_ERROR_BODY },
        retryAt: '2026-09-15T12:00:05Z',
      })

      const timeline = buildThreadTimeline([entry], [], [])
      expect(timeline.messages).toHaveLength(1)

      const message = timeline.messages[0]!
      expect(message.role).toBe('model_attempt_failure')
      if (message.role !== 'model_attempt_failure') {
        throw new Error(`expected model_attempt_failure, got ${message.role}`)
      }
      expect(message.errorCode).toBe('HTTP_400')
      expect(message.errorMessage).toBe(RAW_VENDOR_ERROR_BODY)
      expect(message.errorMessage).toContain('req_vendor_9876543210_alpha_beta_gamma')
    })

    it('preserves raw error body in durable ASSISTANT_ERROR with attempt snapshot as model_attempt_failure', () => {
      // 验证带 attempt 快照的持久化 ASSISTANT_ERROR 投影为 model_attempt_failure 时保留原始错误
      const entry = sessionEntry('err-with-attempt', 'ASSISTANT_ERROR', {
        attempt: { attempt: 2, sequence: 1, text: '', thinking: '' },
        error: { code: 'HTTP_502', message: RAW_HTML_GATEWAY_ERROR },
      })

      const timeline = buildThreadTimeline([entry], [], [])
      expect(timeline.messages).toHaveLength(1)

      const message = timeline.messages[0]!
      expect(message.role).toBe('model_attempt_failure')
      if (message.role !== 'model_attempt_failure') {
        throw new Error(`expected model_attempt_failure, got ${message.role}`)
      }
      expect(message.errorCode).toBe('HTTP_502')
      expect(message.errorMessage).toBe(RAW_HTML_GATEWAY_ERROR)
      expect(message.errorMessage).toContain('cfd-9923849102830192-SJC')
    })

    it('preserves raw error body in durable ASSISTANT_ERROR without attempt as assistant error', () => {
      // 验证无 attempt 快照的 ASSISTANT_ERROR 投影为 assistant 错误卡片时保留原始文本
      const entry = sessionEntry('err-no-attempt', 'ASSISTANT_ERROR', {
        attempt: null,
        error: { code: 'HTTP_400', message: RAW_VENDOR_ERROR_BODY },
      })

      const timeline = buildThreadTimeline([entry], [], [])
      expect(timeline.messages).toHaveLength(1)

      const message = timeline.messages[0]!
      expect(message.role).toBe('assistant')
      if (message.role !== 'assistant') {
        throw new Error(`expected assistant, got ${message.role}`)
      }
      expect(message.status).toBe('error')
      expect(message.text).toBe(RAW_VENDOR_ERROR_BODY)
    })

    it('preserves raw multiline error in snapshot modelAttemptFailures', () => {
      // 验证快照阶段 ModelAttemptFailureDTO 投影至 timeline 时保留完整错误
      const failureDto: ModelAttemptFailureDTO = {
        modelInvocationId: 'model-inv-1',
        turnStartEntryId: 'turn-1',
        requestHeadEntryId: 'head-1',
        attempt: 1,
        sequence: '0',
        text: 'streamed partial',
        thinking: 'reasoning',
        errorCode: 'PROVIDER_ERROR',
        errorMessage: RAW_VENDOR_ERROR_BODY,
        failedAt: '2026-09-15T12:00:00Z',
        retryAt: '2026-09-15T12:00:05Z',
      }

      const timeline = buildThreadTimeline([], [], [], null, null, [failureDto])
      expect(timeline.messages).toHaveLength(1)

      const message = timeline.messages[0]!
      expect(message.role).toBe('model_attempt_failure')
      if (message.role !== 'model_attempt_failure') {
        throw new Error(`expected model_attempt_failure, got ${message.role}`)
      }
      expect(message.errorMessage).toBe(RAW_VENDOR_ERROR_BODY)
    })

    it('preserves raw multiline error in live RealtimeModelStream error status', () => {
      // 验证活动流式错误状态透传原始长错误
      const errorStream: RealtimeModelStream = {
        threadId: 'thread-1',
        invocationId: 'model-inv-live',
        attempt: 1,
        sequence: 2,
        text: '',
        thinking: '',
        toolCalls: [],
        createdAt: '2026-09-15T12:00:00Z',
        status: 'error',
        errorCode: 'PROVIDER_ERROR',
        errorText: RAW_VENDOR_ERROR_BODY,
      }

      const timeline = buildThreadTimeline([], [], [], errorStream)
      expect(timeline.messages).toHaveLength(1)

      const message = timeline.messages[0]!
      expect(message.role).toBe('model_attempt_failure')
      if (message.role !== 'model_attempt_failure') {
        throw new Error(`expected model_attempt_failure, got ${message.role}`)
      }
      expect(message.errorMessage).toBe(RAW_VENDOR_ERROR_BODY)
      expect(message.errorCode).toBe('PROVIDER_ERROR')
    })

    it('preserves empty message fallback when error message is absent or empty', () => {
      // 验证错误消息为空或缺失时触发正常兜底，显式断言 role 防止真空通过
      const blankEntry = sessionEntry('failure-blank', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 0, text: '', thinking: '' },
        error: { code: 'HTTP_500', message: '' },
        retryAt: '2026-09-15T12:00:05Z',
      })

      const timeline = buildThreadTimeline([blankEntry], [], [])
      expect(timeline.messages).toHaveLength(1)
      const message = timeline.messages[0]!
      expect(message.role).toBe('model_attempt_failure')
      if (message.role !== 'model_attempt_failure') {
        throw new Error(`expected model_attempt_failure, got ${message.role}`)
      }
      expect(message.errorMessage).toBe(translate('ai.runtime.message.assistantFailed'))

      const missingAssistantEntry = sessionEntry('err-assistant-missing', 'ASSISTANT_ERROR', {
        attempt: null,
        error: { code: 'HTTP_500' },
      })
      const timeline2 = buildThreadTimeline([missingAssistantEntry], [], [])
      expect(timeline2.messages).toHaveLength(1)
      const message2 = timeline2.messages[0]!
      expect(message2.role).toBe('assistant')
      if (message2.role !== 'assistant') {
        throw new Error(`expected assistant, got ${message2.role}`)
      }
      expect(message2.text).toBe(translate('ai.runtime.entry.assistantRequestFailed'))
    })
  })

  describe('Debug event timeline and detail (/debug 所见即所得)', () => {
    it('preserves full untruncated error in event details and rawJson for durable MODEL_ATTEMPT_FAILURE', () => {
      // 验证 /debug 事件记录与 rawJson 全量保留超长多行原始错误体
      const entry = sessionEntry('entry-fail', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 0, text: 'partial', thinking: '' },
        error: { code: 'HTTP_400', message: RAW_VENDOR_ERROR_BODY },
        retryAt: '2026-09-15T12:00:05Z',
      })

      const records = buildThreadEventTimeline({
        entries: [entry],
        modelInvocation: null,
        toolInvocations: [],
      })

      expect(records).toHaveLength(1)
      const record = records[0]!
      expect(record.kind).toBe('MODEL_ATTEMPT_FAILURE')

      const errorDetail = record.details.find((row) => row.label === '错误信息')
      expect(errorDetail).toBeDefined()
      expect(errorDetail?.value).toBe(RAW_VENDOR_ERROR_BODY)

      // 解析 rawJson 并验证错误体精确匹配
      expect(record.rawJson).not.toBeNull()
      const parsedRaw = JSON.parse(record.rawJson!)
      expect(parsedRaw.error.code).toBe('HTTP_400')
      expect(parsedRaw.error.message).toBe(RAW_VENDOR_ERROR_BODY)
    })

    it('preserves full untruncated error in event details and rawJson for durable ASSISTANT_ERROR', () => {
      // 验证 /debug 事件记录与 rawJson 完整保留原始 HTML 错误体
      const entry = sessionEntry('entry-assistant-err', 'ASSISTANT_ERROR', {
        attempt: null,
        error: { code: 'HTTP_502', message: RAW_HTML_GATEWAY_ERROR },
      })

      const records = buildThreadEventTimeline({
        entries: [entry],
        modelInvocation: null,
        toolInvocations: [],
      })

      expect(records).toHaveLength(1)
      const record = records[0]!
      expect(record.kind).toBe('ASSISTANT_ERROR')

      const errorDetail = record.details.find((row) => row.label === '错误信息')
      expect(errorDetail).toBeDefined()
      expect(errorDetail?.value).toBe(RAW_HTML_GATEWAY_ERROR)

      expect(record.rawJson).not.toBeNull()
      const parsedRaw = JSON.parse(record.rawJson!)
      expect(parsedRaw.error.code).toBe('HTTP_502')
      expect(parsedRaw.error.message).toBe(RAW_HTML_GATEWAY_ERROR)
    })

    it('preserves full untruncated error in synthetic attempt-failure event details and rawJson', () => {
      // 验证未物化的活动失败快照在 /debug 中完整保留 rawJson 与 details
      const failureDto: ModelAttemptFailureDTO = {
        modelInvocationId: 'inv-synth-1',
        turnStartEntryId: 'turn-1',
        requestHeadEntryId: 'head-1',
        attempt: 1,
        sequence: '0',
        text: '',
        thinking: '',
        errorCode: 'RATE_LIMIT',
        errorMessage: RAW_VENDOR_ERROR_BODY,
        failedAt: '2026-09-15T12:00:00Z',
        retryAt: '2026-09-15T12:00:05Z',
      }

      const records = buildThreadEventTimeline({
        entries: [],
        modelInvocation: null,
        toolInvocations: [],
        modelAttemptFailures: [failureDto],
      })

      expect(records).toHaveLength(1)
      const record = records[0]!
      const errorDetail = record.details.find((row) => row.label === '错误信息')
      expect(errorDetail?.value).toBe(RAW_VENDOR_ERROR_BODY)

      expect(record.rawJson).not.toBeNull()
      const parsedRaw = JSON.parse(record.rawJson!)
      expect(parsedRaw.errorCode).toBe('RATE_LIMIT')
      expect(parsedRaw.errorMessage).toBe(RAW_VENDOR_ERROR_BODY)
    })

    it('preserves full untruncated errorJson in ACTIVE_MODEL_INVOCATION details and rawJson', () => {
      // 验证活动的 ModelInvocation errorJson 在 /debug 中完整呈现
      const invocation: ModelInvocationDTO = {
        id: 'inv-active-1',
        threadId: 'thread-1',
        turnStartEntryId: 'turn-1',
        requestHeadEntryId: 'head-1',
        status: 'FAILED',
        attempt: 1,
        streamCheckpointJson: null,
        resultJson: null,
        errorJson: JSON.stringify({
          kind: 'PROVIDER_ERROR',
          message: RAW_VENDOR_ERROR_BODY,
        }),
        resultEntryId: null,
        createTime: '2026-09-15T12:00:00Z',
        updateTime: '2026-09-15T12:00:01Z',
      }

      const records = buildThreadEventTimeline({
        entries: [],
        modelInvocation: invocation,
        toolInvocations: [],
      })

      expect(records).toHaveLength(1)
      const record = records[0]!
      expect(record.kind).toBe('ACTIVE_MODEL_INVOCATION')
      const errorDetail = record.details.find((row) => row.label === '错误')
      expect(errorDetail).toBeDefined()
      const parsedDetail = JSON.parse(errorDetail!.value)
      expect(parsedDetail.message).toBe(RAW_VENDOR_ERROR_BODY)

      expect(record.rawJson).not.toBeNull()
      const parsedRaw = JSON.parse(record.rawJson!)
      const parsedError = JSON.parse(parsedRaw.errorJson)
      expect(parsedError.message).toBe(RAW_VENDOR_ERROR_BODY)
    })

    it('renders untruncated error details and exact rawJson payload in ThreadEventDetail widget', () => {
      // 验证 /debug 详情弹层中 <dd> 与 <pre> 完全呈现原始错误与 rawJson
      const entry = sessionEntry('entry-fail', 'MODEL_ATTEMPT_FAILURE', {
        attempt: { attempt: 1, sequence: 0, text: 'partial', thinking: '' },
        error: { code: 'HTTP_400', message: RAW_VENDOR_ERROR_BODY },
        retryAt: '2026-09-15T12:00:05Z',
      })

      const records = buildThreadEventTimeline({
        entries: [entry],
        modelInvocation: null,
        toolInvocations: [],
      })
      const record = records[0]!

      const { container } = render(<ThreadEventDetail record={record} onClose={vi.fn()} />)

      const ddList = container.querySelectorAll('dd')
      const errorDd = Array.from(ddList).find((dd) => dd.textContent === RAW_VENDOR_ERROR_BODY)
      expect(errorDd).toBeDefined()

      const pre = container.querySelector('.thread-event-detail-payload')
      expect(pre?.textContent).toBe(record.rawJson)
    })
  })

  describe('Conversation text rendering and escaping (对话卡片纯文本安全渲染)', () => {
    it('renders ModelAttemptFailureMessageBlock error in preformatted element without markdown or HTML execution', () => {
      // 验证模型重试失败卡片将原始错误体置于 pre.thread-error-raw 中，HTML/脚本纯文本转义
      const { container } = render(
        <ModelAttemptFailureMessageBlock
          message={{
            id: 'fail-1',
            role: 'model_attempt_failure',
            subjectEntryId: 'entry-1',
            createdAt: '2026-09-15T12:00:00Z',
            status: 'done',
            attempt: 1,
            sequence: '0',
            text: '',
            thinking: '',
            errorCode: 'HTTP_400',
            errorMessage: RAW_VENDOR_ERROR_BODY,
            failedAt: '2026-09-15T12:00:00Z',
            retryAt: null,
            nextAttempt: null,
          }}
        />,
      )

      const pre = container.querySelector('.thread-error-raw')
      expect(pre).not.toBeNull()
      expect(pre?.textContent).toBe(RAW_VENDOR_ERROR_BODY)

      expect(container.querySelector('script')).toBeNull()
      expect(container.querySelector('html')).toBeNull()
      expect(container.querySelector('body')).toBeNull()

      expect(screen.getByText(/req_vendor_9876543210_alpha_beta_gamma/)).toBeInTheDocument()
      expect(screen.getByText(/messages\.\[4\]\.content/)).toBeInTheDocument()
      expect(screen.getByText(/trace-9988-7766-5544/)).toBeInTheDocument()
    })

    it('renders AssistantMessageBlock error state in preformatted element without markdown or HTML execution', () => {
      // 验证助手错误卡片使用 pre.thread-error-raw 渲染原始响应，免 Markdown 解析且 HTML 安全转义
      const { container } = render(
        <AssistantMessageBlock
          message={{
            id: 'msg-err-1',
            role: 'assistant',
            subjectEntryId: 'entry-1',
            text: RAW_HTML_GATEWAY_ERROR,
            thinking: '',
            status: 'error',
            createdAt: null,
          }}
        />,
      )

      const pre = container.querySelector('.thread-error-raw')
      expect(pre).not.toBeNull()
      expect(pre?.textContent).toBe(RAW_HTML_GATEWAY_ERROR)

      expect(container.querySelector('h1')).toBeNull()
      expect(container.querySelector('script')).toBeNull()
      expect(container.querySelector('center')).toBeNull()

      expect(screen.getByText(/cfd-9923849102830192-SJC/)).toBeInTheDocument()
      expect(screen.getByText(/cloudflare-nginx\/1\.25\.1/)).toBeInTheDocument()
    })
  })
})
