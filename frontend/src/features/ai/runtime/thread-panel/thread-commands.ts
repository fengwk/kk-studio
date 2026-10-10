import type {
  PaneTarget,
  PaneTargetKind,
} from '@/features/ai/runtime/agent-pane/pane-target'
import { translate } from '@/shared/i18n'

interface ManualCompactionAvailability {
  available: boolean
  disabledReason: string | null
}

export type ThreadCommandId =
  | 'thread'
  | 'agent'
  | 'yolo'
  | 'models'
  | 'history'
  | 'stop'
  | 'new'
  | 'upload'
  | 'debug'
  | 'subagent'
  | 'shortcuts'
  | 'compact'
  | 'rename-session'
  | 'rename-thread'
  | 'goal'
  | 'shell'

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
 * The only user-visible command registry. Chat projects this
 * ordered list from the three durable PaneTarget states.
 */
export const THREAD_COMMANDS: ThreadCommand[] = [
  command('thread', ['branch', 'switch', 'session']),
  command('agent', ['set', 'switch', 'definition']),
  command('yolo', ['auto', 'approve', 'tool']),
  command('models', ['model', 'variant', 'provider', 'switch']),
  command('history', ['history', 'branch', 'entry']),
  command('stop', ['cancel', 'interrupt', 'thread']),
  command('new', ['blank', 'fresh', 'create', 'session']),
  command('upload', ['attach', 'file', 'image', 'video', 'audio', 'paste']),
  command('debug', ['log', 'audit', 'activity', 'entry', 'conversation', 'inspect']),
  command('subagent', ['agent', 'tree', 'execution', 'history']),
  command('shortcuts', ['keys', 'keyboard', 'help', 'hotkeys']),
  command('compact', ['context', 'tokens', 'summary', 'reduce']),
  command('rename-session', ['session', 'name']),
  command('rename-thread', ['thread', 'name']),
  command('goal', ['objective', 'target', 'task', 'goal']),
  command('shell', ['terminal', 'bash', 'console', 'pty', 'sh']),
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
  NEW_SESSION_DRAFT: ['thread', 'agent', 'yolo', 'models', 'upload', 'shortcuts', 'shell'],
  NEW_THREAD_DRAFT: [
    'thread',
    'agent',
    'yolo',
    'models',
    'history',
    'new',
    'upload',
    'debug',
    'shortcuts',
    'rename-session',
    'rename-thread',
    'goal',
    'shell',
  ],
  // 会话 fork 草稿：新 Session/Thread 尚未创建，也没有用户可见名称，因此不提供任何重命名；
  // 其余草稿能力（设置、历史、预览、Goal）与新建 Thread 分支保持一致。
  FORK_SESSION_DRAFT: [
    'thread',
    'agent',
    'yolo',
    'models',
    'history',
    'new',
    'upload',
    'debug',
    'shortcuts',
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
  allowSwitchAgent?: boolean
  allowBranching?: boolean
}

function isThreadCommandOptions(value: unknown): value is ThreadCommandOptions {
  return (
    value != null
    && typeof value === 'object'
    && ('allowNewSession' in value || 'readOnly' in value || 'canBranchFromRoot' in value
      || 'allowSwitchAgent' in value || 'allowBranching' in value)
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
  // 命令可用性只由目标 kind 与显式能力决定；owner 不参与既有 Thread 的能力裁剪。
  const commandList = THREAD_COMMANDS

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
      && item.id !== 'history'
      && item.id !== 'debug'
      && item.id !== 'subagent'
      && item.id !== 'shell'

    const newDisabled =
      item.id === 'new'
      && options.allowNewSession === false
      && options.canBranchFromRoot === false

    const agentDisabled =
      options.allowSwitchAgent === false
      && item.id === 'agent'

    const branchingDisabled =
      options.allowBranching === false
      && (item.id === 'thread' || item.id === 'history' || item.id === 'new')

    const disabled = !targetEnabled || compactDisabled || readOnlyDisabled || newDisabled || agentDisabled || branchingDisabled
    let disabledReason: string | undefined
    if (readOnlyDisabled) {
      disabledReason = translate('ai.runtime.command.disabled.readOnly')
    } else if (compactDisabled) {
      disabledReason = manualCompaction?.disabledReason ?? undefined
    } else if (newDisabled) {
      disabledReason = translate('ai.runtime.command.disabled.singleSession')
    } else if (agentDisabled) {
      disabledReason = translate('ai.runtime.action.agentSwitchDisabled')
    } else if (branchingDisabled) {
      disabledReason = translate('ai.runtime.action.branchingDisabled')
    }

    return {
      ...item,
      disabled,
      disabledReason,
      disabledReasonKey:
        disabled && !compactDisabled && !readOnlyDisabled && !newDisabled && !agentDisabled && !branchingDisabled ? DISABLED_KEY : undefined,
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
