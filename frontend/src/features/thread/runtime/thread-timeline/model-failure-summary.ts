import type { TranslationKey } from '@/shared/i18n'

/**
 * 模型失败卡的可读摘要。
 *
 * 后端 `error.message` 是客户端所见的原始错误文本：HTTP 失败是
 * `HTTP <status>\n<body>`（响应体缺失时是 `HTTP <status>: <fallback>`），
 * SSE error envelope 是 envelope 的 JSON 文本，协议内部失败则是纯 fallback 文案。
 * 主文案只呈现用户能读懂的失败类别与供应商错误信息，完整原文仍作为详情保留。
 */
export interface ModelFailureSummary {
  /** 原始错误文本里的 HTTP 状态；没有状态行时为 null。 */
  httpStatus: number | null
  /** 失败类别（ProviderErrorKind）对应的本地化文案 key；未知错误码时为 null。 */
  kindKey: TranslationKey | null
  /** 主文案里的可读摘要：供应商错误信息，无法结构化时是截断的原始正文。 */
  summary: string
  /** 折叠详情里的完整原始错误文本；错误文本为空时为 null。 */
  rawResponse: string | null
}

/** 摘要里保留的原始正文最大长度（Unicode 码点）。 */
export const FAILURE_EXCERPT_LIMIT = 240

/** ProviderErrorKind → 本地化失败类别文案。 */
const FAILURE_KIND_KEYS = new Map<string, TranslationKey>([
  ['TRANSIENT', 'ai.runtime.message.modelFailureTransient'],
  ['CONTEXT_LIMIT', 'ai.runtime.message.modelFailureContextLimit'],
  ['REQUEST_BODY_LIMIT', 'ai.runtime.message.modelFailureRequestBodyLimit'],
  ['MEDIA_LIMIT', 'ai.runtime.message.modelFailureMediaLimit'],
  ['AUTHENTICATION', 'ai.runtime.message.modelFailureAuthentication'],
  ['BILLING', 'ai.runtime.message.modelFailureBilling'],
  ['INVALID_REQUEST', 'ai.runtime.message.modelFailureInvalidRequest'],
  ['INVALID_RESPONSE', 'ai.runtime.message.modelFailureInvalidResponse'],
  ['CANCELLED', 'ai.runtime.message.modelFailureCancelled'],
  ['NO_AVAILABLE_MODEL', 'ai.runtime.message.modelFailureNoAvailableModel'],
])

/** HTTP 状态行：`HTTP <status>\n<body>` 或 `HTTP <status>: <fallback>`。 */
const HTTP_STATUS_LINE = /^HTTP (\d{3})(?:\r?\n|: )/

/** 由错误码与原始错误文本生成摘要；纯函数，不做任何文本脱敏或定价。 */
export function summarizeModelFailure(
  errorCode: string,
  errorMessage: string,
): ModelFailureSummary {
  const code = errorCode.trim()
  const kindKey = FAILURE_KIND_KEYS.get(code) ?? null
  const text = errorMessage.trim()
  if (!text) {
    return { httpStatus: null, kindKey, summary: '', rawResponse: null }
  }
  const { httpStatus, body } = splitHttpStatus(text)
  return {
    httpStatus,
    kindKey,
    summary: providerMessage(body) ?? excerpt(body),
    rawResponse: errorMessage,
  }
}

function splitHttpStatus(raw: string): { httpStatus: number | null; body: string } {
  const match = HTTP_STATUS_LINE.exec(raw)
  if (match == null) {
    return { httpStatus: null, body: raw }
  }
  return { httpStatus: Number(match[1]), body: raw.slice(match[0].length).trim() }
}

/** 从 JSON 错误体里取供应商错误信息；正文不是可解析的 JSON 对象时返回 null。 */
function providerMessage(body: string): string | null {
  const trimmed = body.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return null
  }
  let payload: unknown
  try {
    payload = JSON.parse(trimmed)
  } catch {
    return null
  }
  return messageField(payload)
}

/** 读取常见的供应商错误字段。 */
function messageField(node: unknown): string | null {
  if (node == null || typeof node !== 'object') {
    return null
  }
  const record = node as Record<string, unknown>
  const error = record.error
  const nestedMessage = error != null && typeof error === 'object'
    ? (error as Record<string, unknown>).message
    : null
  return textValue(nestedMessage) ?? textValue(record.message) ?? textValue(error)
}

function textValue(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const text = value.trim()
  return text === '' ? null : text
}

/** 压成单行并按码点截断；详情仍保留完整原文。 */
function excerpt(body: string): string {
  const collapsed = body.replace(/\s+/gu, ' ').trim()
  const characters = [...collapsed]
  return characters.length > FAILURE_EXCERPT_LIMIT
    ? `${characters.slice(0, FAILURE_EXCERPT_LIMIT).join('')}…`
    : collapsed
}
