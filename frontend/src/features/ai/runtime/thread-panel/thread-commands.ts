export type ThreadCommandScene = 'blank' | 'bound'

export interface ThreadCommand {
  id: string
  label: string
  description: string
  labelKey?: string
  descriptionKey?: string
  keywords?: string[]
  /** Scene-disabled commands stay visible but grayed out. */
  disabled?: boolean
  disabledReason?: string
  disabledReasonKey?: string
}

/**
 * Slash command table (pi-style).
 * Order is stable product order — never reorder by availability.
 */
export const THREAD_COMMANDS: ThreadCommand[] = [
  {
    id: 'session',
    label: 'session',
    description: '',
    labelKey: 'ai.runtime.command.sessionLabel',
    descriptionKey: 'ai.runtime.command.session',
    keywords: ['chat', 'switch', 'rebind', 'head'],
  },
  {
    id: 'thread',
    label: 'thread',
    description: '',
    labelKey: 'ai.runtime.command.threadLabel',
    descriptionKey: 'ai.runtime.command.thread',
    keywords: ['branch', 'switch', 'pane'],
  },
  {
    id: 'agent',
    label: 'agent',
    description: '',
    labelKey: 'ai.runtime.command.agentLabel',
    descriptionKey: 'ai.runtime.command.agent',
    keywords: ['set', 'switch', 'definition'],
  },
  {
    id: 'environment',
    label: 'environment',
    description: '',
    labelKey: 'ai.runtime.command.environmentLabel',
    descriptionKey: 'ai.runtime.command.environment',
    keywords: ['set', 'switch', 'runtime', 'target'],
  },
  {
    id: 'yolo',
    label: 'yolo',
    description: '',
    labelKey: 'ai.runtime.command.yoloLabel',
    descriptionKey: 'ai.runtime.command.yolo',
    keywords: ['auto', 'approve', 'tool'],
  },
  {
    id: 'tree',
    label: 'tree',
    description: '',
    labelKey: 'ai.runtime.command.treeLabel',
    descriptionKey: 'ai.runtime.command.tree',
    keywords: ['history', 'branch', 'rebind', 'head'],
  },
  {
    id: 'stop',
    label: 'stop',
    description: '',
    labelKey: 'ai.runtime.command.stopLabel',
    descriptionKey: 'ai.runtime.command.stop',
    keywords: ['cancel', 'interrupt', 'thread'],
  },
  {
    id: 'new',
    label: 'new',
    description: '',
    labelKey: 'ai.runtime.command.newLabel',
    descriptionKey: 'ai.runtime.command.new',
    keywords: ['blank', 'fresh', 'create', 'session'],
  },
]

/**
 * Commands usable on an empty pane. `/thread` picks an existing Chat-scoped Thread.
 * `/session` (global Session rebind) no longer exists: the tree is current-Session only, so the
 * command stays visible but disabled in every scene.
 */
const BLANK_SCENE_ENABLED = new Set(['thread', 'agent', 'environment', 'yolo'])
const NEVER_ENABLED = new Set(['session'])

/** Project stable command list with scene availability (disabled stays listed). */
export function threadCommandsForScene(scene: ThreadCommandScene): ThreadCommand[] {
  return THREAD_COMMANDS.map((command) => {
    const disabled =
      NEVER_ENABLED.has(command.id)
      || (scene === 'blank' && !BLANK_SCENE_ENABLED.has(command.id))
    return {
      ...command,
      disabled,
      disabledReason: undefined,
      disabledReasonKey: disabled ? 'ai.runtime.command.disabledReason' : undefined,
    }
  })
}

export function filterThreadCommands(
  query: string,
  commands: ThreadCommand[] = THREAD_COMMANDS,
): ThreadCommand[] {
  const q = query.trim().replace(/^\//, '').toLowerCase()
  if (!q) {
    // Empty query: full stable table (including disabled).
    return commands
  }
  // Rank id/label prefix first, then keyword prefix, then fuzzy description text.
  // Preserve relative order within each rank bucket; never drop disabled matches.
  const ranked: ThreadCommand[] = []
  const seen = new Set<string>()
  const pushAll = (candidates: ThreadCommand[]) => {
    for (const command of candidates) {
      if (seen.has(command.id)) {
        continue
      }
      seen.add(command.id)
      ranked.push(command)
    }
  }
  pushAll(
    commands.filter(
      (command) => command.id.toLowerCase().startsWith(q) || command.label.toLowerCase().startsWith(q),
    ),
  )
  pushAll(
    commands.filter((command) => command.keywords?.some((keyword) => keyword.toLowerCase().startsWith(q)) ?? false),
  )
  pushAll(
    commands.filter((command) => {
      const haystack = [command.id, command.label, command.description, command.disabledReason ?? '']
        .join(' ')
        .toLowerCase()
      return haystack.includes(q)
    }),
  )
  return ranked
}
