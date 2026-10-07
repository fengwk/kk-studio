import { ApiError } from '@/shared/api/client'
import type {
  InteractionDTO,
  InteractionOwnerDTO,
  InteractionPageDTO,
  InteractionType,
} from '@/shared/api/contracts/ai-interaction'
import type { InstantTimestamp } from '@/shared/api/contracts/base'

/**
 * Interaction wire codec：把 `GET /interactions` 的 envelope.data 严格解码为类型化 DTO。
 *
 * 严格 wire 解码：只接受真实服务端输出。`items` 的每个条目都由 `type` 判别——`INPUT` / `APPROVAL`
 * 携带可操作字段（原始调用主键、状态、来源 Thread），`ENVIRONMENT_WAIT` 只有聚合身份且把可操作字段显式编码为
 * null；两者都按 `@JsonInclude(ALWAYS)` 的 required-nullable 约定输出键，因此键缺失、类型不符或与类型矛盾的
 * 取值一律 fail closed（{@link ApiError}），绝不静默降级。HTTP/信封/错误映射由 interaction-service 负责。
 */

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Interaction API returned an invalid payload: ${detail}`)
}

function requireRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidPayload(`${path} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) {
    throw invalidPayload(`${path} must be an array`)
  }
  return value
}

function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string`)
  }
  return value
}

function requireNonEmptyString(value: unknown, path: string): string {
  const text = requireString(value, path)
  if (text.length === 0) {
    throw invalidPayload(`${path} must be a non-empty string`)
  }
  return text
}

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function requireUuid(value: unknown, path: string): string {
  const text = requireString(value, path)
  if (!UUID_SHAPE.test(text)) {
    throw invalidPayload(`${path} must be a canonical UUID string`)
  }
  return text
}

function requireInt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw invalidPayload(`${path} must be a safe integer`)
  }
  return value
}

/**
 * required-nullable：字段带 `@JsonInclude(ALWAYS)`，键必然存在且 null 显式输出；键缺失无法与「未输出」区分，
 * 因此一律 fail closed，而不是当成 null。
 */
function requireRequiredNullableString(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must be explicitly null`)
  }
  return value === null ? null : requireString(value, path)
}

function requireRequiredNullableInt(value: unknown, path: string): number | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must be explicitly null`)
  }
  return value === null ? null : requireInt(value, path)
}

function requireNull(value: string | number | null, path: string): void {
  if (value !== null) {
    throw invalidPayload(`${path} must be null`)
  }
}

const INTERACTION_TYPES = ['INPUT', 'APPROVAL', 'ENVIRONMENT_WAIT'] as const

function requireInteractionType(value: unknown, path: string): InteractionType {
  if (typeof value !== 'string' || !(INTERACTION_TYPES as readonly string[]).includes(value)) {
    throw invalidPayload(`${path} must be one of INPUT, APPROVAL, ENVIRONMENT_WAIT`)
  }
  return value as InteractionType
}

/** HTTP Jackson 将 Instant 编码为 epoch seconds 数字（可带小数），也接受明确的 ISO 时刻。 */
function requireBackendDateTime(value: unknown, path: string): InstantTimestamp {
  if (typeof value === 'string' && Number.isFinite(Date.parse(value))) {
    return value
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  throw invalidPayload(`${path} must be epoch seconds or an ISO date-time string`)
}

function decodeOwner(value: unknown, path: string): InteractionOwnerDTO {
  const record = requireRecord(value, path)
  const type = record.type
  if (type !== 'CHAT' && type !== 'ISSUE_AGENT') {
    throw invalidPayload(`${path}.type must be CHAT or ISSUE_AGENT`)
  }
  const nullable = requireRequiredNullableString
  return {
    type,
    chatId: nullable(record.chatId, `${path}.chatId`),
    chatTitle: nullable(record.chatTitle, `${path}.chatTitle`),
    issueId: nullable(record.issueId, `${path}.issueId`),
    issueTitle: nullable(record.issueTitle, `${path}.issueTitle`),
    agentName: nullable(record.agentName, `${path}.agentName`),
    rootThreadName: nullable(record.rootThreadName, `${path}.rootThreadName`),
  }
}

function decodeItem(value: unknown, path: string): InteractionDTO {
  const record = requireRecord(value, path)
  const type = requireInteractionType(record.type, `${path}.type`)
  const rootThreadId = requireUuid(record.rootThreadId, `${path}.rootThreadId`)
  const createTime = requireBackendDateTime(record.createTime, `${path}.createTime`)
  const owner = decodeOwner(record.owner, `${path}.owner`)
  const interactionId = requireRequiredNullableString(record.interactionId, `${path}.interactionId`)
  const status = requireRequiredNullableString(record.status, `${path}.status`)
  const threadId = requireRequiredNullableString(record.threadId, `${path}.threadId`)
  const sessionId = requireRequiredNullableString(record.sessionId, `${path}.sessionId`)
  const toolCallId = requireRequiredNullableString(record.toolCallId, `${path}.toolCallId`)
  const toolName = requireRequiredNullableString(record.toolName, `${path}.toolName`)
  const argumentsJson = requireRequiredNullableString(record.argumentsJson, `${path}.argumentsJson`)
  const approvalJson = requireRequiredNullableString(record.approvalJson, `${path}.approvalJson`)
  const environmentId = requireRequiredNullableString(record.environmentId, `${path}.environmentId`)
  const environmentName = requireRequiredNullableString(
    record.environmentName,
    `${path}.environmentName`,
  )
  const waitingCount = requireRequiredNullableInt(record.waitingCount, `${path}.waitingCount`)

  if (type === 'ENVIRONMENT_WAIT') {
    requireNull(interactionId, `${path}.interactionId`)
    requireNull(status, `${path}.status`)
    requireNull(threadId, `${path}.threadId`)
    requireNull(sessionId, `${path}.sessionId`)
    requireNull(toolCallId, `${path}.toolCallId`)
    requireNull(toolName, `${path}.toolName`)
    requireNull(argumentsJson, `${path}.argumentsJson`)
    requireNull(approvalJson, `${path}.approvalJson`)
    const environmentIdValue = requireUuid(environmentId, `${path}.environmentId`)
    const environmentNameValue = requireNonEmptyString(
      environmentName,
      `${path}.environmentName`,
    )
    if (waitingCount === null || waitingCount < 1) {
      throw invalidPayload(`${path}.waitingCount must be a positive integer for ENVIRONMENT_WAIT`)
    }
    return {
      type,
      interactionId: null,
      status: null,
      threadId: null,
      sessionId: null,
      rootThreadId,
      owner,
      toolCallId: null,
      toolName: null,
      argumentsJson: null,
      approvalJson: null,
      environmentId: environmentIdValue,
      environmentName: environmentNameValue,
      waitingCount,
      createTime,
    }
  }

  requireNull(environmentId, `${path}.environmentId`)
  requireNull(environmentName, `${path}.environmentName`)
  requireNull(waitingCount, `${path}.waitingCount`)
  const expectedStatus = type === 'APPROVAL' ? 'WAITING_APPROVAL' : 'WAITING_INPUT'
  if (status !== expectedStatus) {
    throw invalidPayload(`${path}.status conflicts with ${type}`)
  }
  const manual = {
    interactionId: requireNonEmptyString(interactionId, `${path}.interactionId`),
    threadId: requireNonEmptyString(threadId, `${path}.threadId`),
    sessionId: requireNonEmptyString(sessionId, `${path}.sessionId`),
    rootThreadId,
    owner,
    toolCallId: requireNonEmptyString(toolCallId, `${path}.toolCallId`),
    toolName: requireNonEmptyString(toolName, `${path}.toolName`),
    argumentsJson: requireNonEmptyString(argumentsJson, `${path}.argumentsJson`),
    environmentId: null,
    environmentName: null,
    waitingCount: null,
    createTime,
  }
  if (type === 'APPROVAL') {
    return { ...manual, type, status: 'WAITING_APPROVAL',
      approvalJson: requireNonEmptyString(approvalJson, `${path}.approvalJson`) }
  }
  requireNull(approvalJson, `${path}.approvalJson`)
  return { ...manual, type, status: 'WAITING_INPUT', approvalJson: null }
}

/**
 * 解码一页待处理 Interaction；`items` 逐条按 `type` 严格校验，`nextCursor` 是 required-nullable，
 * `total` 是同一过滤条件下的真实可见待处理总数。
 */
export function decodeInteractionPage(value: unknown): InteractionPageDTO {
  const record = requireRecord(value, 'page')
  const items = requireArray(record.items, 'page.items').map((item, index) =>
    decodeItem(item, `page.items[${index}]`),
  )
  const nextCursor = requireRequiredNullableString(record.nextCursor, 'page.nextCursor')
  const total = requireInt(record.total, 'page.total')
  if (total < 0) {
    throw invalidPayload('page.total must be non-negative')
  }
  const freshnessAt = record.freshnessAt === null
    ? null : requireBackendDateTime(record.freshnessAt, 'page.freshnessAt')
  return { items, nextCursor, total, freshnessAt }
}
