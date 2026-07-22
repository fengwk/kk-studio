export type ThreadCommandScene = 'blank' | 'bound'

export interface ThreadCommand {
  id: string
  label: string
  description: string
  keywords?: string[]
  /** Scene-disabled commands stay visible but grayed out. */
  disabled?: boolean
  disabledReason?: string
}

/**
 * Slash command table (pi-style).
 * Order is stable product order — never reorder by availability.
 */
export const THREAD_COMMANDS: ThreadCommand[] = [
  {
    id: 'session',
    label: 'session',
    description: '切换当前 Pane 到本 Chat 下的 Session Main Thread',
    keywords: ['chat', 'switch', 'member'],
  },
  {
    id: 'thread',
    label: 'thread',
    description: '切换当前 Pane 到当前 Session 的 Thread',
    keywords: ['branch', 'switch'],
  },
  {
    id: 'agent',
    label: 'agent',
    description: '切换 Agent（空白页改默认 Agent；对话中入队 SET_AGENT）',
    keywords: ['set', 'switch', 'definition'],
  },
  {
    id: 'model',
    label: 'model',
    description: '切换 Model（入队 SET_MODEL）',
    keywords: ['set', 'switch', 'provider'],
  },
  {
    id: 'variant',
    label: 'variant',
    description: '切换 Variant（入队 SET_MODEL）',
    keywords: ['set', 'switch', 'reasoning'],
  },
  {
    id: 'yolo',
    label: 'yolo',
    description: '切换 YOLO 自动批准工具调用',
    keywords: ['auto', 'approve', 'tool'],
  },
  {
    id: 'tree',
    label: 'tree',
    description: '打开历史分支面板',
    keywords: ['history', 'branch', 'fork', 'thread'],
  },
  {
    id: 'stop',
    label: 'stop',
    description: '停止当前 Thread 并恢复尚未处理的消息',
    keywords: ['cancel', 'interrupt', 'thread'],
  },
  {
    id: 'new',
    label: 'new',
    description: '回到空面板；发送后创建新的 Session / Thread',
    keywords: ['blank', 'fresh', 'create', 'session'],
  },
]

/** Commands usable before any Session/Thread exists. `/new` only applies inside a bound Thread. */
const BLANK_SCENE_ENABLED = new Set(['session', 'agent'])

/** Project stable command list with scene availability (disabled stays listed). */
export function threadCommandsForScene(scene: ThreadCommandScene): ThreadCommand[] {
  return THREAD_COMMANDS.map((command) => {
    if (scene === 'bound' || BLANK_SCENE_ENABLED.has(command.id)) {
      return { ...command, disabled: false, disabledReason: undefined }
    }
    return {
      ...command,
      disabled: true,
      disabledReason: '创建对话后可用',
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

/** First enabled command in the filtered list (for Enter to confirm). */
export function firstEnabledThreadCommand(
  query: string,
  commands: ThreadCommand[] = THREAD_COMMANDS,
): ThreadCommand | undefined {
  return filterThreadCommands(query, commands).find((command) => !command.disabled)
}
