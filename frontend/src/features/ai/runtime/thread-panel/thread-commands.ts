export type ThreadCommandScene =
  | 'chat-blank'
  | 'chat-bound'
  | 'canvas-blank'
  | 'canvas-bound'

export type ThreadCommandId =
  | 'thread'
  | 'agent'
  | 'environment'
  | 'yolo'
  | 'models'
  | 'tree'
  | 'stop'
  | 'new'
  | 'upload'
  | 'events'
  | 'shortcuts'

export interface ThreadCommand {
  id: ThreadCommandId
  label: string
  description: string
  labelKey?: string
  descriptionKey?: string
  keywords?: string[]
  /** 场景禁用的命令保持可见但呈灰态。 */
  disabled?: boolean
  disabledReason?: string
  disabledReasonKey?: string
}

/**
 * 命令菜单（pi 风格）。`+` 是主入口，composer 开头的 `/` 是同一菜单的文本快捷入口。
 * 顺序是稳定的产品顺序，切勿按可用性重新排序。
 */
export const THREAD_COMMANDS: ThreadCommand[] = [
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
    id: 'models',
    label: 'models',
    description: '',
    labelKey: 'ai.runtime.command.modelsLabel',
    descriptionKey: 'ai.runtime.command.models',
    keywords: ['model', 'variant', 'provider', 'switch'],
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
  {
    id: 'upload',
    label: 'upload',
    description: '',
    labelKey: 'ai.runtime.command.uploadLabel',
    descriptionKey: 'ai.runtime.command.upload',
    keywords: ['attach', 'file', 'image', 'video', 'audio', 'paste'],
  },
  {
    id: 'events',
    label: 'events',
    description: '',
    labelKey: 'ai.runtime.command.eventsLabel',
    descriptionKey: 'ai.runtime.command.events',
    keywords: ['log', 'audit', 'activity', 'entry', 'conversation', 'toggle'],
  },
  {
    id: 'shortcuts',
    label: 'shortcuts',
    description: '',
    labelKey: 'ai.runtime.command.shortcutsLabel',
    descriptionKey: 'ai.runtime.command.shortcuts',
    keywords: ['keys', 'keyboard', 'help', 'hotkeys'],
  },
]

/**
 * 每个命令的可用场景。场景区分 Chat 与 Canvas、Blank 与 Bound：
 * - Canvas Bound 与 Chat Bound 都允许编辑下一条消息的 agent/environment/yolo 草稿；
 *   tree/new/thread 仍是 Chat 专属。
 * `/session` 已彻底移除：树只属于当前 Session，不存在可用的全局 Session 重绑定命令。
 */
const SCENE_AVAILABILITY: Record<ThreadCommandId, ThreadCommandScene[]> = {
  thread: ['chat-blank', 'chat-bound'],
  agent: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
  environment: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
  yolo: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
  models: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
  tree: ['chat-bound'],
  stop: ['chat-bound', 'canvas-bound'],
  new: ['chat-bound'],
  upload: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
  events: ['chat-bound', 'canvas-bound'],
  shortcuts: ['chat-blank', 'canvas-blank', 'chat-bound', 'canvas-bound'],
}

/** 投影稳定的 command 列表并附带场景可用性（disabled 仍保留在列表中）。 */
export function threadCommandsForScene(scene: ThreadCommandScene): ThreadCommand[] {
  return THREAD_COMMANDS.map((command) => {
    const disabled = !(SCENE_AVAILABILITY[command.id]?.includes(scene) ?? false)
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
    // 空查询：返回完整稳定表（包含 disabled）。
    return commands
  }
  // 先按 id/label 前缀排序，再按 keyword 前缀，最后按模糊的 description 文本。
  // 每个排序桶内保持相对顺序；绝不丢弃 disabled 匹配项。
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
