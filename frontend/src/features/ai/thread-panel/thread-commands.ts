export interface ThreadCommand {
  id: string
  label: string
  description: string
  keywords?: string[]
}

/** Slash command table for the thread composer (pi-style). */
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
    description: '为当前 Thread 设置 Agent（入队 SET_AGENT）',
    keywords: ['set', 'switch', 'definition'],
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
    id: 'clear-draft',
    label: 'clear',
    description: '清空当前输入',
    keywords: ['reset', 'empty'],
  },
]

export function filterThreadCommands(
  query: string,
  commands: ThreadCommand[] = THREAD_COMMANDS,
): ThreadCommand[] {
  const q = query.trim().replace(/^\//, '').toLowerCase()
  if (!q) {
    return commands
  }
  // Rank id/label prefix first, then keyword prefix, then fuzzy description text.
  // This keeps `/thread` on the Thread command while still allowing keyword hits like tree←thread.
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
      const haystack = [command.id, command.label, command.description].join(' ').toLowerCase()
      return haystack.includes(q)
    }),
  )
  return ranked
}
