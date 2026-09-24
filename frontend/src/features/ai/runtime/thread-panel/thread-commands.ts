import type {
  PaneTarget,
  PaneTargetKind,
} from '@/features/ai/runtime/agent-pane/pane-target'

export interface ThreadCommandOwner {
  type: string
  id: string
}

interface ManualCompactionAvailability {
  available: boolean
  disabledReason: string | null
}

export type ThreadCommandId =
  | 'thread'
  | 'agent'
  | 'yolo'
  | 'models'
  | 'tree'
  | 'stop'
  | 'new'
  | 'upload'
  | 'debug'
  | 'shortcuts'
  | 'compact'
  | 'rename-session'
  | 'rename-thread'
  | 'goal'

export interface ThreadCommand {
  id: ThreadCommandId
  label: string
  description: string
  labelKey?: string
  descriptionKey?: string
  keywords?: string[]
  disabled?: boolean
  disabledReason?: string
  disabledReasonKey?: string
}

/**
 * The only user-visible command registry. Chat and Canvas project this same
 * ordered list from the three durable PaneTarget states.
 */
export const THREAD_COMMANDS: ThreadCommand[] = [
  command('thread', ['branch', 'switch', 'session']),
  command('agent', ['set', 'switch', 'definition']),
  command('yolo', ['auto', 'approve', 'tool']),
  command('models', ['model', 'variant', 'provider', 'switch']),
  command('tree', ['history', 'branch', 'entry']),
  command('stop', ['cancel', 'interrupt', 'thread']),
  command('new', ['blank', 'fresh', 'create', 'session']),
  command('upload', ['attach', 'file', 'image', 'video', 'audio', 'paste']),
  command('debug', ['log', 'audit', 'activity', 'entry', 'conversation', 'inspect']),
  command('shortcuts', ['keys', 'keyboard', 'help', 'hotkeys']),
  command('compact', ['context', 'tokens', 'summary', 'reduce']),
  command('rename-session', ['session', 'name']),
  command('rename-thread', ['thread', 'name']),
  command('goal', ['objective', 'target', 'task', 'goal']),
]

function command(id: ThreadCommandId, keywords: string[]): ThreadCommand {
  return {
    id,
    label: id,
    description: '',
    labelKey: `ai.runtime.command.${id}Label`,
    descriptionKey: `ai.runtime.command.${id}`,
    keywords,
  }
}

const TARGET_COMMANDS: Record<PaneTargetKind, ThreadCommandId[]> = {
  NEW_SESSION_DRAFT: ['thread', 'agent', 'yolo', 'models', 'upload', 'shortcuts'],
  NEW_THREAD_DRAFT: [
    'thread',
    'agent',
    'yolo',
    'models',
    'tree',
    'new',
    'upload',
    'shortcuts',
    'rename-session',
    'goal',
  ],
  BOUND_THREAD: THREAD_COMMANDS.map((item) => item.id),
}

const DISABLED_KEY = 'ai.runtime.command.disabledReason'

export interface ThreadCommandOptions {
  manualCompaction?: ManualCompactionAvailability | null
  allowNewSession?: boolean
  readOnly?: boolean
  canBranchFromRoot?: boolean
  owner?: ThreadCommandOwner
}

function isThreadCommandOptions(value: unknown): value is ThreadCommandOptions {
  return (
    value != null
    && typeof value === 'object'
    && ('allowNewSession' in value || 'readOnly' in value || 'canBranchFromRoot' in value || 'owner' in value)
  )
}

export function threadCommandsForTarget(
  target: PaneTarget,
  manualCompactionOrOptions?: ManualCompactionAvailability | ThreadCommandOptions | null,
): ThreadCommand[] {
  const options: ThreadCommandOptions = isThreadCommandOptions(manualCompactionOrOptions)
    ? manualCompactionOrOptions
    : { manualCompaction: manualCompactionOrOptions }
  const manualCompaction = options.manualCompaction
  const enabled = new Set(TARGET_COMMANDS[target.kind])

  // Ordinary Chat/Canvas only: hide Goal for any non-Chat, non-Canvas owner.
  // Default fail-closed: if owner is absent or unknown, goal command is not exposed.
  const isGoalAllowed = options.owner != null && (options.owner.type === 'CHAT' || options.owner.type === 'CANVAS')
  const commandList = isGoalAllowed
    ? THREAD_COMMANDS
    : THREAD_COMMANDS.filter((item) => item.id !== 'goal')

  return commandList.map((item) => {
    const targetEnabled = enabled.has(item.id)
    const compactDisabled =
      item.id === 'compact'
      && target.kind === 'BOUND_THREAD'
      && manualCompaction != null
      && !manualCompaction.available

    const readOnlyDisabled =
      Boolean(options.readOnly)
      && item.id !== 'shortcuts'
      && item.id !== 'tree'
      && item.id !== 'debug'

    const newDisabled =
      item.id === 'new'
      && options.allowNewSession === false
      && options.canBranchFromRoot === false

    const disabled = !targetEnabled || compactDisabled || readOnlyDisabled || newDisabled
    let disabledReason: string | undefined
    if (readOnlyDisabled) {
      disabledReason = '只读模式'
    } else if (compactDisabled) {
      disabledReason = manualCompaction?.disabledReason ?? undefined
    } else if (newDisabled) {
      disabledReason = '当前项目仅支持单会话'
    }

    return {
      ...item,
      disabled,
      disabledReason,
      disabledReasonKey:
        disabled && !compactDisabled && !readOnlyDisabled && !newDisabled ? DISABLED_KEY : undefined,
    }
  })
}

export function commandIdsForTarget(target: PaneTarget): ThreadCommandId[] {
  return TARGET_COMMANDS[target.kind]
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
  const seen = new Set<ThreadCommandId>()
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
