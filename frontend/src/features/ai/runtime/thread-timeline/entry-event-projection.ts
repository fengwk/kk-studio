import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import type {
  EntryEventDialogueMessage,
  EntryEventKind,
} from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

export type SettingsSnapshot = {
  agentName: string
  model: { providerName: string; modelName: string; variant: string }
  environmentName: string | null
}

/** 不完整的快照不能参与差值比较；null 环境必须显式出现。 */
export function parseSettingsSnapshot(value: unknown): SettingsSnapshot | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return null
  const settings = value as Record<string, unknown>
  const model = settings.model
  if (model == null || typeof model !== 'object' || Array.isArray(model)) return null
  const selection = model as Record<string, unknown>
  const valid = (field: unknown): field is string =>
    typeof field === 'string' && field.trim().length > 0
  if (
    !valid(settings.agentName) || !valid(selection.providerName)
    || !valid(selection.modelName) || !valid(selection.variant)
    || !Object.hasOwn(settings, 'environmentName')
    || !(settings.environmentName === null || valid(settings.environmentName))
  ) return null
  return {
    agentName: settings.agentName,
    model: {
      providerName: selection.providerName,
      modelName: selection.modelName,
      variant: selection.variant,
    },
    environmentName: settings.environmentName,
  }
}

function modelLabel(settings: SettingsSnapshot): string {
  const { providerName, modelName, variant } = settings.model
  return `${providerName}/${modelName} (${variant})`
}

export function projectRootSettings(
  entry: HarnessSessionEntryDTO,
  settings: SettingsSnapshot,
): EntryEventDialogueMessage {
  return event(
    entry,
    'root',
    translate('ai.runtime.entry.rootSettings', {
      agent: settings.agentName,
      model: modelLabel(settings),
      environment: settings.environmentName ?? translate('ai.runtime.entry.noEnvironment'),
    }),
    '',
  )
}

export function projectInvalidSettings(entry: HarnessSessionEntryDTO): EntryEventDialogueMessage {
  return event(entry, 'invalid_settings', translate('ai.runtime.entry.invalidSettings'), '')
}

export function projectSettingsChanges(
  entry: HarnessSessionEntryDTO,
  before: SettingsSnapshot,
  after: SettingsSnapshot,
): EntryEventDialogueMessage[] {
  const changes: EntryEventDialogueMessage[] = []
  if (before.agentName !== after.agentName) {
    changes.push({ ...event(entry, 'settings_change', translate('ai.runtime.entry.agentChanged', {
      agent: after.agentName,
    }), ''), id: `entry:${entry.entryId}:agent` })
  }
  if (
    before.model.providerName !== after.model.providerName
    || before.model.modelName !== after.model.modelName
    || before.model.variant !== after.model.variant
  ) {
    changes.push({ ...event(entry, 'settings_change', translate('ai.runtime.entry.modelChanged', {
      model: modelLabel(after),
    }), ''), id: `entry:${entry.entryId}:model` })
  }
  if (before.environmentName !== after.environmentName) {
    changes.push({ ...event(entry, 'settings_change', after.environmentName === null
      ? translate('ai.runtime.entry.environmentDetached')
      : translate('ai.runtime.entry.environmentChanged', {
        environment: after.environmentName,
      }), ''), id: `entry:${entry.entryId}:environment` })
  }
  return changes
}

export function projectEmptyMessageEntry(
  entry: HarnessSessionEntryDTO,
  role: string,
): EntryEventDialogueMessage {
  return event(
    entry,
    'empty_message',
    translate('ai.runtime.entry.emptyTitle', {
      role: role || translate('ai.runtime.entry.unknownRole'),
    }),
    translate('ai.runtime.entry.emptyText'),
  )
}

export function projectUnsupportedMessageEntry(
  entry: HarnessSessionEntryDTO,
  role: string,
): EntryEventDialogueMessage {
  return event(
    entry,
    'unsupported_message',
    translate('ai.runtime.entry.unsupportedTitle'),
    role
      ? translate('ai.runtime.entry.unsupportedRoleText', { role })
      : translate('ai.runtime.entry.unsupportedText'),
  )
}

export function projectUnknownEntry(entry: HarnessSessionEntryDTO): EntryEventDialogueMessage {
  return event(
    entry,
    'unknown_entry',
    translate('ai.runtime.entry.unknownTitle', {
      type: entry.entryType || translate('ai.runtime.entry.unknownType'),
    }),
    translate('ai.runtime.entry.unknownText'),
  )
}

const NOTIFICATION_TITLE_KEYS: Record<string, string> = {
  SUBAGENT_RESULT: 'ai.runtime.notification.entry.SUBAGENT_RESULTTitle',
  TASK_BUDGET: 'ai.runtime.notification.entry.TASK_BUDGETTitle',
}

/**
 * 完整成功压缩的摘要卡片（COMPACTION Entry）：text 是逐字保留的
 * {@code COMPACTION.payload.summaryText}，subjectEntryId 是摘要 Entry 身份。
 * 是否成功由 builder 依据 enclosing TURN_START.phase 与匹配 TURN_END.outcome 判定，
 * 这里只负责最小投影，不引入新的 NOTIFICATION 或 role。
 */
export function projectCompactionEntry(
  entry: HarnessSessionEntryDTO,
  summaryText: string,
): EntryEventDialogueMessage {
  return event(entry, 'compaction', translate('ai.runtime.entry.compactionTitle'), summaryText)
}

/**
 * 系统结果通知（NOTIFICATION）：它是 runtime 的上下文事实，不是人类输入。
 * 使用独立系统样式渲染，既不进入 composer 草稿，也不进入队列与上下键消息历史。
 * kind 与来源以原文显式投影；未知 kind 不假定为子代理结果。
 */
export function projectNotificationEntry(
  entry: HarnessSessionEntryDTO,
  payload: unknown,
): EntryEventDialogueMessage {
  const record = payload != null && typeof payload === 'object' && !Array.isArray(payload)
    ? payload as Record<string, unknown>
    : {}
  const kind = typeof record.kind === 'string' ? record.kind : ''
  const sourceThreadId = typeof record.sourceThreadId === 'string' && record.sourceThreadId
    ? record.sourceThreadId
    : null
  const message = record.message
  const text = message != null && typeof message === 'object' && !Array.isArray(message)
    ? messageContentsText((message as Record<string, unknown>).contents)
    : ''
  const titleKey = NOTIFICATION_TITLE_KEYS[kind]
  return {
    ...event(
      entry,
      'notification',
      titleKey != null
        ? translate(titleKey)
        : translate('ai.runtime.notification.entry.unknownTitle'),
      text || translate('ai.runtime.notification.entry.emptyText'),
    ),
    notification: { kind, sourceThreadId },
  }
}

/** NOTIFICATION 的 message 是 USER AgentMessage；只提取文本内容作为展示事实。 */
function messageContentsText(contents: unknown): string {
  if (!Array.isArray(contents)) {
    return ''
  }
  return contents
    .map((content) => {
      if (content == null || typeof content !== 'object' || Array.isArray(content)) {
        return ''
      }
      const record = content as Record<string, unknown>
      if (record.type !== 'text') {
        return ''
      }
      return typeof record.text === 'string' ? record.text : ''
    })
    .filter(Boolean)
    .join('\n')
    .trim()
}

function event(
  entry: HarnessSessionEntryDTO,
  kind: EntryEventKind,
  title: string,
  text: string,
): EntryEventDialogueMessage {
  return {
    id: `entry:${entry.entryId}`,
    role: 'entry',
    kind,
    title,
    text,
    subjectEntryId: entry.entryId,
    createdAt: entry.createTime,
    status: 'done',
  }
}
