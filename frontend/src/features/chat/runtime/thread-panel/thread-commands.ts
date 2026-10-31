import type {
  PaneTarget,
  PaneTargetKind,
} from '@/features/chat/runtime/agent-pane/pane-target'
import { translate, type TranslationKey } from '@/shared/i18n'

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
  | 'files'
  | 'shell'

export interface ThreadCommand {
  id: ThreadCommandId
  label: string
  description: string
  labelKey?: TranslationKey
  descriptionKey?: TranslationKey
  keywords?: string[]
  disabled?: boolean
  disabledReason?: string
  disabledReasonKey?: TranslationKey
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
  command('files', ['explorer', 'browse', 'directory', 'fs']),
  command('shell', ['terminal', 'bash', 'console', 'cli']),
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
  NEW_SESSION_DRAFT: ['thread', 'agent', 'yolo', 'models', 'upload', 'shortcuts', 'files', 'shell'],
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
    'files',
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
    'files',
    'shell',
  ],
  BOUND_THREAD: THREAD_COMMANDS.map((item) => item.id),
}

const DISABLED_KEY = 'ai.runtime.command.disabledReason'

const COMPACTION_DISABLED_REASON_KEYS: Record<string, TranslationKey> = {
  THREAD_STOPPED: 'ai.runtime.command.disabled.compaction.threadStopped',
  THREAD_BUSY: 'ai.runtime.command.disabled.compaction.threadBusy',
  OWNERSHIP_BARRIER: 'ai.runtime.command.disabled.compaction.ownershipBarrier',
  NO_RESOLVED_CONTEXT: 'ai.runtime.command.disabled.compaction.noResolvedContext',
  MODEL_CHANGED: 'ai.runtime.command.disabled.compaction.modelChanged',
  BELOW_MINIMUM: 'ai.runtime.command.disabled.compaction.belowMinimum',
  NOTHING_TO_COMPACT: 'ai.runtime.command.disabled.compaction.nothingToCompact',
}

function manualCompactionDisabledReason(reason: string | null): string {
  if (reason == null) {
    return translate('ai.runtime.action.compactUnavailable')
  }
  const key = COMPACTION_DISABLED_REASON_KEYS[reason]
  if (key) {
    return translate(key)
  }
  return translate('ai.runtime.action.compactUnavailable')
}

export type CommandCompactionState =
  | { status: 'checking' }
  | { status: 'failed' }
  | { status: 'ready'; availability: ManualCompactionAvailability }

export interface ThreadCommandOptions {
  compaction?: CommandCompactionState | null
  allowNewSession?: boolean
  readOnly?: boolean
  canBranchFromRoot?: boolean
  allowSwitchAgent?: boolean
  allowBranching?: boolean
}

export function threadCommandsForTarget(
  target: PaneTarget,
  options: ThreadCommandOptions = {},
): ThreadCommand[] {
  const compaction = options.compaction
  const enabled = new Set(TARGET_COMMANDS[target.kind])
  // 命令可用性只由目标 kind 与显式能力决定；owner 不参与既有 Thread 的能力裁剪。
  const commandList = THREAD_COMMANDS

  return commandList.map((item) => {
    const targetEnabled = enabled.has(item.id)
    let compactDisabled = false
    let compactDisabledReason: string | undefined

    if (item.id === 'compact' && target.kind === 'BOUND_THREAD' && compaction != null) {
      if (compaction.status === 'checking') {
        compactDisabled = true
        compactDisabledReason = translate('ai.runtime.command.disabled.compaction.checking')
      } else if (compaction.status === 'failed') {
        compactDisabled = true
        compactDisabledReason = translate('ai.runtime.command.disabled.compaction.checkFailed')
      } else if (compaction.status === 'ready' && !compaction.availability.available) {
        compactDisabled = true
        compactDisabledReason = manualCompactionDisabledReason(compaction.availability.disabledReason)
      }
    }

    const readOnlyDisabled =
      Boolean(options.readOnly)
      && item.id !== 'shortcuts'
      && item.id !== 'history'
      && item.id !== 'debug'
      && item.id !== 'subagent'

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
      disabledReason = compactDisabledReason
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
