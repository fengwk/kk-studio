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
    rawPayloadJson: entry.payloadJson || '{}',
    subjectEntryId: entry.entryId,
    createdAt: entry.createTime,
    status: 'done',
  }
}
