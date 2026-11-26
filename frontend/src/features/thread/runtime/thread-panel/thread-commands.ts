import type { TranslationKey } from '@/shared/i18n'

interface ManualCompactionAvailability {
  available: boolean
  disabledReason: string | null
}

export type CommandCompactionState =
  | { status: 'checking' }
  | { status: 'failed' }
  | { status: 'ready'; availability: ManualCompactionAvailability }

const COMPACTION_DISABLED_REASON_KEYS: Record<string, TranslationKey> = {
  THREAD_STOPPED: 'ai.runtime.command.disabled.compaction.threadStopped',
  THREAD_BUSY: 'ai.runtime.command.disabled.compaction.threadBusy',
  OWNERSHIP_BARRIER: 'ai.runtime.command.disabled.compaction.ownershipBarrier',
  NO_RESOLVED_CONTEXT: 'ai.runtime.command.disabled.compaction.noResolvedContext',
  EXECUTION_UNSUPPORTED: 'ai.runtime.command.disabled.compaction.executionUnsupported',
  NOTHING_TO_COMPACT: 'ai.runtime.command.disabled.compaction.nothingToCompact',
}

export function manualCompactionDisabledReasonKey(reason: string | null): TranslationKey {
  if (reason == null) {
    return 'ai.runtime.action.compactUnavailable'
  }
  return COMPACTION_DISABLED_REASON_KEYS[reason] ?? 'ai.runtime.action.compactUnavailable'
}

export interface ThreadCommand {
  id: string
  label: string
  description: string
  labelKey?: TranslationKey
  descriptionKey?: TranslationKey
  keywords?: string[]
  disabled?: boolean
  disabledReason?: string
  disabledReasonKey?: TranslationKey
}

/** The user-visible thread composer command registry. */
export const THREAD_COMMANDS: ThreadCommand[] = [
  command('yolo', ['auto', 'approve', 'tool']),
  command('models', ['model', 'variant', 'provider', 'switch']),
  command('stop', ['cancel', 'interrupt', 'thread']),
  command('upload', ['attach', 'file', 'image', 'video', 'audio', 'paste']),
  command('debug', ['log', 'audit', 'activity', 'entry', 'conversation', 'inspect']),
]

function command(id: 'yolo' | 'models' | 'stop' | 'upload' | 'debug', keywords: string[]): ThreadCommand {
  return {
    id,
    label: id,
    description: '',
    labelKey: `ai.runtime.command.${id}Label`,
    descriptionKey: `ai.runtime.command.${id}`,
    keywords,
  }
}

export function filterThreadCommands(
  query: string,
  commands: ThreadCommand[] = THREAD_COMMANDS,
): ThreadCommand[] {
  const normalized = query.trim().replace(/^\//, '').toLowerCase()
  if (!normalized) {
    return commands
  }
  const result: ThreadCommand[] = []
  const seen = new Set<string>()
  const add = (items: ThreadCommand[]) => {
    for (const item of items) {
      if (!seen.has(item.id)) {
        seen.add(item.id)
        result.push(item)
      }
    }
  }
  add(commands.filter((item) => item.id.startsWith(normalized)))
  add(commands.filter((item) => item.keywords?.some((keyword) => keyword.startsWith(normalized)) ?? false))
  add(commands.filter((item) => `${item.id} ${item.description}`.toLowerCase().includes(normalized)))
  return result
}
