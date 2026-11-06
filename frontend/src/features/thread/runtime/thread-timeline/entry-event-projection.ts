import type {
  EntryEventDialogueMessage,
  EntryEventKind,
} from '@/features/thread/runtime/thread-timeline-types'
import { formatModelLabel } from '@/features/catalog'
import {
  getBuiltinModelSelection,
  isSameExecution,
} from '@/shared/api/contracts/ai-runtime'
import { translate, type TranslationKey } from '@/shared/i18n'
import type {
  HarnessExecutionSelectionDTO,
  HarnessSessionEntryDTO,
} from '@/shared/api/generated/contracts'

export type SettingsSnapshot = {
  agentName: string
  execution: HarnessExecutionSelectionDTO
  environmentName: string | null
}

/** 不完整的快照不能参与差值比较；null 环境必须显式出现。 */
export function parseSettingsSnapshot(value: unknown): SettingsSnapshot | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return null
  const settings = value as Record<string, unknown>
  const execution = settings.execution
  if (execution == null || typeof execution !== 'object' || Array.isArray(execution)) return null
  const exec = execution as Record<string, unknown>
  const valid = (field: unknown): field is string =>
    typeof field === 'string' && field.trim().length > 0
  if (
    !valid(settings.agentName) || !valid(exec.driver)
    || exec.settings == null || typeof exec.settings !== 'object' || Array.isArray(exec.settings)
    || !Object.hasOwn(settings, 'environmentName')
    || !(settings.environmentName === null || valid(settings.environmentName))
  ) return null
  const execSettings = exec.settings as Record<string, unknown>
  if (exec.driver === 'builtin') {
    if (!valid(execSettings.modelName)) return null
    if (!(execSettings.variant === undefined || execSettings.variant === null || valid(execSettings.variant))) return null
  }
  return {
    agentName: settings.agentName,
    execution: {
      driver: exec.driver,
      settings: execSettings,
    },
    environmentName: settings.environmentName,
  }
}

export function projectRootSettings(
  entry: HarnessSessionEntryDTO,
  settings: SettingsSnapshot,
): EntryEventDialogueMessage {
  const builtin = getBuiltinModelSelection(settings.execution)
  const modelText = builtin
    ? formatModelLabel(builtin.modelName, builtin.variant)
    : settings.execution.driver
  return event(
    entry,
    'root',
    translate('ai.runtime.entry.rootSettings', {
      agent: settings.agentName,
      model: modelText,
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
  if (!isSameExecution(before.execution, after.execution)) {
    const afterBuiltin = getBuiltinModelSelection(after.execution)
    if (afterBuiltin) {
      changes.push({ ...event(entry, 'settings_change', translate('ai.runtime.entry.modelChanged', {
        model: formatModelLabel(afterBuiltin.modelName, afterBuiltin.variant),
      }), ''), id: `entry:${entry.entryId}:model` })
    }
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

/**
 * 本轮因 Thread 设置的模型不可用而改用 Agent 默认模型时的提示；Thread 设置本身不变。
 * payload.executedSelection 缺失或不完整时不产生提示。
 */
export function projectModelFallback(
  entry: HarnessSessionEntryDTO,
  settings: SettingsSnapshot,
  executedSelection: unknown,
): EntryEventDialogueMessage | null {
  const currentModel = getBuiltinModelSelection(settings.execution)
  const executedModel = getBuiltinModelSelection(executedSelection)
  if (currentModel == null || executedModel == null) {
    return null
  }
  if (
    currentModel.modelName === executedModel.modelName
    && (currentModel.variant ?? null) === (executedModel.variant ?? null)
  ) {
    return null
  }
  return {
    ...event(entry, 'settings_change', translate('ai.runtime.entry.modelFallback', {
      model: formatModelLabel(currentModel.modelName, currentModel.variant),
      fallback: formatModelLabel(executedModel.modelName, executedModel.variant),
    }), ''),
    id: `entry:${entry.entryId}:model-fallback`,
  }
}

export function projectEmptyMessageEntry(
  entry: HarnessSessionEntryDTO,
  role: string,
): EntryEventDialogueMessage {
  return event(
    entry,
    'empty_message',
    translate(role === 'ASSISTANT'
      ? 'ai.runtime.entry.emptyAssistant'
      : 'ai.runtime.entry.emptyMessage'),
    '',
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

const NOTIFICATION_TITLE_KEYS: Record<string, TranslationKey> = {
  SUBAGENT_RESULT: 'ai.runtime.notification.entry.SUBAGENT_RESULTTitle',
  TASK_BUDGET: 'ai.runtime.notification.entry.TASK_BUDGETTitle',
}

/**
 * FORK Entry 的模型可见固定英文通知正文（与后端 ForkPayload.NOTICE 保持逐字一致）。
 * 只陈述事实，不包含行为引导，也不是更高权限的 system 指令。
 */
export const FORK_NOTICE_TEXT =
  'This thread was forked. Earlier tool calls and background tasks will not resume or report here. Subagent thread IDs inherited from history are no longer valid.'

const FORK_TITLE_KEYS: Record<string, TranslationKey> = {
  BRANCH: 'ai.runtime.entry.forkBranchTitle',
  SESSION: 'ai.runtime.entry.forkSessionTitle',
}

/**
 * fork 事实节点（FORK Entry）：呈现固定英文通知正文与真实 mode/sourceEntryId/sourceThreadId，
 * 与平台系统通知一样使用独立系统外观，绝不伪装成人类输入，也不进入可编辑草稿。
 */
export function projectForkEntry(
  entry: HarnessSessionEntryDTO,
  payload: unknown,
): EntryEventDialogueMessage {
  const record = payload != null && typeof payload === 'object' && !Array.isArray(payload)
    ? payload as Record<string, unknown>
    : {}
  const mode = typeof record.mode === 'string' ? record.mode : ''
  const sourceEntryId = typeof record.sourceEntryId === 'string' && record.sourceEntryId
    ? record.sourceEntryId
    : null
  const sourceThreadId = typeof record.sourceThreadId === 'string' && record.sourceThreadId
    ? record.sourceThreadId
    : null
  const titleKey = FORK_TITLE_KEYS[mode] ?? 'ai.runtime.entry.forkTitle'
  return {
    ...event(
      entry,
      'fork',
      translate(titleKey),
      FORK_NOTICE_TEXT,
    ),
    fork: { mode, sourceEntryId, sourceThreadId },
  }
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
    createdAt: entry.createdAt,
    status: 'done',
  }
}
