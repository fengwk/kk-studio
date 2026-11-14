import { asRecord, getRecordList } from '@/features/thread/runtime/thread-timeline/payload-json'
import { getString } from '@/shared/lib/value'
import { translate } from '@/shared/i18n'
import { contentText } from '@/features/thread/runtime/thread-timeline/content-utils'
import { formatUsageCost, formatTurnUsageText } from '@/features/thread/runtime/thread-timeline/turn-usage-format'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'
import type {
  EntryTurnContext,
  RecordBase,
  ThreadEventDetailRow,
  ThreadEventRecord,
} from '@/features/thread/runtime/thread-timeline/thread-events-types'
import {
  hasAssistantMetadata,
  prettyJson,
  scalarText,
  summarizeField,
  withTime,
} from '@/features/thread/runtime/thread-timeline/thread-events-json'

function messageContentsText(contents: Array<Record<string, unknown>>): string {
  return contents.map(contentText).filter(Boolean).join(' ').trim()
}

/** tool_result content 的正文（text/json 内层 contents）。 */
function toolResultText(contents: Array<Record<string, unknown>>): string {
  for (const content of contents) {
    if (getString(content.type) === 'tool_result') {
      const text = messageContentsText(getRecordList(content.contents))
      if (text) {
        return text
      }
    }
  }
  return ''
}

export function projectEntryRecord(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  turn: EntryTurnContext,
): ThreadEventRecord {
  const baseDetails: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.entryId'), value: entry.entryId },
    {
      label: translate('ai.runtime.event.detail.entryType'),
      value: entry.entryType || translate('ai.runtime.entry.unknownType'),
    },
    ...(turn.turnNumber > 0
      ? [{ label: translate('ai.runtime.event.detail.turn'), value: String(turn.turnNumber) }]
      : []),
  ]
  const base: RecordBase = {
    id: `entry:${entry.entryId}`,
    source: 'entry',
    entryId: entry.entryId,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    createdAt: entry.createdAt,
    rawJson: prettyJson(entry.payload),
    details: baseDetails,
  }

  switch (entry.entryType) {
    case 'ROOT':
      return {
        ...base,
        kind: 'ROOT',
        status: 'completed',
        title: 'ROOT',
        summary: translate('ai.runtime.entry.rootText'),
        details: withTime(base.details, entry.createdAt),
      }
    case 'TURN_START':
      return {
        ...base,
        kind: 'TURN_START',
        status: 'completed',
        title: 'TURN_START',
        summary: summarizeField(getString(payload.reason), translate('ai.runtime.event.emptyText')),
        details: withTime(base.details, entry.createdAt),
      }
    case 'SETTINGS':
      return {
        ...base,
        kind: 'SETTINGS',
        status: 'completed',
        title: 'SETTINGS',
        summary: '',
        details: withTime(base.details, entry.createdAt),
      }
    case 'COMPACTION':
      return {
        ...base,
        kind: 'COMPACTION',
        status: 'completed',
        title: 'COMPACTION',
        summary: summarizeField(getString(payload.reason), translate('ai.runtime.event.emptyText')),
        // 只有真实模型输出（携带 canonical metadata）才可读取历史请求预览；纯摘要无请求可重建。
        historicalPreviewEligible: hasAssistantMetadata(payload),
        details: withTime(base.details, entry.createdAt),
      }
    case 'TURN_END': {
      const outcome = getString(payload.outcome)
      const usage = turn.pendingUsage
      const details = [...base.details]
      if (usage != null) {
        details.push(
          {
            label: translate('ai.runtime.event.detail.input'),
            value:
              usage.input != null
                ? String(usage.input)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.output'),
            value:
              usage.output != null
                ? String(usage.output)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.cacheRead'),
            value:
              usage.cacheRead != null
                ? String(usage.cacheRead)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.cacheWrite'),
            value:
              usage.cacheWrite != null
                ? String(usage.cacheWrite)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.reasoning'),
            value:
              usage.reasoning != null
                ? String(usage.reasoning)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.providerTotal'),
            value:
              usage.providerTotal != null
                ? String(usage.providerTotal)
                : translate('ai.runtime.status.noData'),
          },
          {
            label: translate('ai.runtime.event.detail.cost'),
            value: formatUsageCost(usage.cost) ?? translate('ai.runtime.status.noData'),
          },
        )
      }
      return {
        ...base,
        kind: 'TURN_END',
        status: 'completed',
        title: 'TURN_END',
        summary: [
          outcome,
          ...(usage != null ? [formatTurnUsageText(usage)] : []),
        ].filter(Boolean).join(' · '),
        details: withTime([
          ...details,
          { label: translate('ai.runtime.event.detail.outcome'), value: outcome },
        ], entry.createdAt),
      }
    }
    case 'MODEL_ATTEMPT_FAILURE': {
      const attempt = asRecord(payload.attempt)
      const error = asRecord(payload.error)
      // attempt.attempt 在 durable payload 中是数字（JSON number）。
      const attemptText = scalarText(attempt.attempt)
      const code = getString(error.code)
      return {
        ...base,
        kind: 'MODEL_ATTEMPT_FAILURE',
        status: 'failed',
        title: 'MODEL_ATTEMPT_FAILURE',
        summary: [`attempt ${attemptText}`, code].filter(Boolean).join(' · '),
        details: withTime([
          ...base.details,
          { label: translate('ai.runtime.event.detail.attempt'), value: attemptText },
          { label: translate('ai.runtime.event.detail.sequence'), value: scalarText(attempt.sequence) },
          { label: translate('ai.runtime.event.detail.errorCode'), value: code },
          {
            label: translate('ai.runtime.event.detail.errorMessage'),
            value: getString(error.message),
          },
        ], entry.createdAt),
      }
    }
    case 'ASSISTANT_ERROR': {
      const error = asRecord(payload.error)
      return {
        ...base,
        kind: 'ASSISTANT_ERROR',
        status: 'failed',
        title: 'ASSISTANT_ERROR',
        summary: summarizeField(
          getString(error.message),
          translate('ai.runtime.entry.assistantRequestFailed'),
        ),
        details: withTime([
          ...base.details,
          {
            label: translate('ai.runtime.event.detail.errorMessage'),
            value: getString(error.message),
          },
        ], entry.createdAt),
      }
    }
    case 'ASSISTANT_ABORTED':
      return {
        ...base,
        kind: 'ASSISTANT_ABORTED',
        status: 'stopped',
        title: 'ASSISTANT_ABORTED',
        summary: translate('ai.runtime.event.abortedText'),
        details: withTime(base.details, entry.createdAt),
      }
    case 'CUSTOM_MESSAGE':
      return projectMessageRecord(base, payload, 'CUSTOM_MESSAGE')
    case 'NOTIFICATION': {
      // 系统结果通知：它是 runtime 的上下文事实，不是人类输入。按真实 kind 呈现，绝不伪装成人类消息。
      const notificationKind = getString(payload.kind)
      return {
        ...base,
        kind: 'NOTIFICATION',
        status: 'completed',
        title: notificationKind || translate('ai.runtime.event.unknownType', { type: entry.entryType }),
        summary: notificationKind,
        details: withTime([
          ...base.details,
          ...(getString(payload.sourceThreadId)
            ? [{
              label: translate('ai.runtime.notification.entry.source'),
              value: getString(payload.sourceThreadId),
            }]
            : []),
        ], entry.createdAt),
      }
    }
    case 'FORK': {
      const mode = getString(payload.mode)
      const sourceEntryId = getString(payload.sourceEntryId)
      const sourceThreadId = getString(payload.sourceThreadId)
      return {
        ...base,
        kind: 'FORK',
        status: 'completed',
        title: 'FORK',
        summary: [mode, sourceEntryId].filter(Boolean).join(' · '),
        details: withTime([
          ...base.details,
          ...(mode
            ? [{ label: translate('ai.runtime.event.detail.forkMode'), value: mode }]
            : []),
          ...(sourceEntryId
            ? [{ label: translate('ai.runtime.event.detail.sourceEntryId'), value: sourceEntryId }]
            : []),
          ...(sourceThreadId
            ? [{ label: translate('ai.runtime.notification.entry.source'), value: sourceThreadId }]
            : []),
        ], entry.createdAt),
      }
    }
    case 'MESSAGE': {
      const message = asRecord(payload.message)
      const role = getString(message.role)
      if (role === 'USER') {
        return projectMessageRecord(base, payload, 'USER_MESSAGE')
      }
      if (role === 'ASSISTANT') {
        const contents = getRecordList(message.contents)
        const record = contents.some((content) => getString(content.type) === 'tool_call')
          ? projectToolCallRecord(base, payload)
          : projectMessageRecord(base, payload, 'ASSISTANT_MESSAGE')
        // 模型输出来源以 assistantMetadata 判定：普通输出与带 tool_call 的输出同样可读取历史预览。
        return hasAssistantMetadata(payload)
          ? { ...record, historicalPreviewEligible: true }
          : record
      }
      if (role === 'TOOL') {
        return projectToolResultRecord(base, payload)
      }
      // 其他未知角色按 CUSTOM_MESSAGE 处理。
      return projectMessageRecord(base, payload, 'CUSTOM_MESSAGE')
    }
    default:
      // 未知 Entry 类型：审计视图不丢信息，并以原类型标签展示。
      return {
        ...base,
        kind: 'UNKNOWN_ENTRY',
        status: 'completed',
        title: translate('ai.runtime.event.unknownType', { type: entry.entryType }),
        summary: translate('ai.runtime.event.emptyText'),
        details: withTime(base.details, entry.createdAt),
      }
  }
}

function projectMessageRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
  kind: 'USER_MESSAGE' | 'ASSISTANT_MESSAGE' | 'CUSTOM_MESSAGE',
): ThreadEventRecord {
  const message = asRecord(payload.message)
  const role = getString(message.role) || translate('ai.runtime.entry.unknownRole')
  const text = messageContentsText(getRecordList(message.contents))
  return {
    ...base,
    kind,
    status: 'completed',
    // 标题用消息真实角色（USER/ASSISTANT/TOOL/SYSTEM），不再被 entryType 覆盖成 "MESSAGE"。
    title: role,
    summary: summarizeField(text, role),
    details: withTime([
      ...base.details,
      { label: translate('ai.runtime.event.detail.role'), value: role },
    ], base.createdAt),
  }
}

function projectToolCallRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
): ThreadEventRecord {
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const toolCall = contents.find((content) => getString(content.type) === 'tool_call')
  const toolName = getString(toolCall?.toolName) || translate('ai.runtime.event.unknownTool')
  const text = messageContentsText(contents)
  return {
    ...base,
    kind: 'TOOL_CALL',
    status: 'completed',
    // 带 tool_call 的输出仍是 ASSISTANT：kind 保留 TOOL_CALL，标题仍是真实角色。
    title: getString(message.role) || translate('ai.runtime.entry.unknownRole'),
    summary: summarizeField([text, toolName].filter(Boolean).join(' · '), toolName),
    details: withTime([
      ...base.details,
      { label: translate('ai.runtime.event.detail.role'), value: getString(message.role) },
      { label: translate('ai.runtime.event.detail.toolName'), value: toolName },
      { label: translate('ai.runtime.event.detail.toolCallId'), value: getString(toolCall?.toolCallId) },
    ], base.createdAt),
  }
}

function projectToolResultRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
): ThreadEventRecord {
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const toolResult = contents.find((content) => getString(content.type) === 'tool_result')
  const text = toolResultText(contents)
  return {
    ...base,
    kind: 'TOOL_RESULT',
    status: 'completed',
    title: getString(message.role) || 'TOOL',
    summary: summarizeField(text, translate('ai.runtime.event.emptyText')),
    details: withTime([
      ...base.details,
      {
        label: translate('ai.runtime.event.detail.toolCallId'),
        value: getString(toolResult?.toolCallId),
      },
    ], base.createdAt),
  }
}
