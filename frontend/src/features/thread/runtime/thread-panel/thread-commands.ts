import type { TranslationKey } from '@/shared/i18n'

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
  command('models', ['model', 'variant', 'provider', 'switch']),
  command('stop', ['cancel', 'interrupt', 'thread']),
  command('upload', ['attach', 'file', 'image', 'video', 'audio', 'paste']),
  command('debug', ['log', 'audit', 'activity', 'entry', 'conversation', 'inspect']),
]

function command(id: 'models' | 'stop' | 'upload' | 'debug', keywords: string[]): ThreadCommand {
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
