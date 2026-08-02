import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
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
