import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList } from '@/features/ai/runtime/payload-json'
import type {
  EntryEventDialogueMessage,
  EntryEventKind,
} from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

export function projectRootEntry(entry: HarnessSessionEntryDTO): EntryEventDialogueMessage {
  return event(
    entry,
    'root',
    translate('ai.runtime.entry.rootTitle'),
    translate('ai.runtime.entry.rootText'),
  )
}

export function projectRuntimeConfigEntry(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
): EntryEventDialogueMessage {
  const agent = asRecord(payload.agent)
  const model = asRecord(payload.model)
  const descriptor = asRecord(model.descriptor)
  const variant = asRecord(model.variant)
  const agentName = display(agent.name, translate('ai.runtime.entry.unknownAgent'))
  const agentId = display(agent.definitionId, '')
  const provider = display(descriptor.providerType, 'unknown').toLowerCase()
  const modelId = display(descriptor.modelId, translate('ai.runtime.entry.unknownModel'))
  const variantId = display(variant.id, translate('ai.runtime.entry.defaultVariant'))
  const yolo =
    payload.yoloEnabled === true
      ? translate('ai.runtime.entry.yoloOn')
      : payload.yoloEnabled === false
        ? translate('ai.runtime.entry.yoloOff')
        : translate('ai.runtime.entry.yoloUnknown')
  const tools = names(payload.tools)
  const skills = names(payload.skills)
  const lines = [
    translate('ai.runtime.entry.agentLine', { value: `${agentName}${agentId ? ` (#${agentId})` : ''}` }),
    translate('ai.runtime.entry.modelLine', { value: `${provider}/${modelId} · ${variantId}` }),
    translate('ai.runtime.entry.yoloLine', { value: yolo }),
  ]
  if (tools.length > 0) {
    lines.push(
      translate('ai.runtime.entry.toolsLine', {
        value: tools.join(translate('ai.runtime.entry.listSeparator')),
      }),
    )
  }
  if (skills.length > 0) {
    lines.push(
      translate('ai.runtime.entry.skillsLine', {
        value: skills.join(translate('ai.runtime.entry.listSeparator')),
      }),
    )
  }
  return event(
    entry,
    'runtime_config',
    translate('ai.runtime.entry.runtimeConfigTitle'),
    lines.join('\n'),
  )
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

function names(value: unknown): string[] {
  return getRecordList(value)
    .map((item) => {
      const descriptor = asRecord(item.descriptor)
      return display(descriptor.name, '') || display(item.name, '')
    })
    .filter(Boolean)
}

function display(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) {
    return value.trim()
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value)
  }
  return fallback
}
