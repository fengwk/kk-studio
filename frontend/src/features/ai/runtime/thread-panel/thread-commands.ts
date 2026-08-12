export type ThreadCommandScene = 'blank' | 'bound'

export interface ThreadCommand {
  id: string
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
 * Slash 命令表（pi 风格）。
 * 顺序是稳定的产品顺序，切勿按可用性重新排序。
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
  {
    id: 'upload',
    label: 'upload',
    description: '',
    labelKey: 'ai.runtime.command.uploadLabel',
    descriptionKey: 'ai.runtime.command.upload',
    keywords: ['attach', 'file', 'image', 'video', 'audio', 'paste'],
  },
]

/**
 * 空面板下可用的命令。`/thread` 用于选择一个已存在的 Chat-scoped Thread。
 * `/session`（全局 Session 重绑定）已不再存在：树只属于当前 Session，因此该
 * 命令在所有场景下都保持可见但禁用状态。
 */
const BLANK_SCENE_ENABLED = new Set(['upload', 'thread', 'agent', 'environment', 'yolo'])
const NEVER_ENABLED = new Set(['session'])

/** 投影稳定的 command 列表并附带场景可用性（disabled 仍保留在列表中）。 */
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
