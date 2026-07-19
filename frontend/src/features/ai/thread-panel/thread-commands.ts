export interface ThreadCommand {
  id: string
  label: string
  description: string
  keywords?: string[]
}

/** Slash command table for the thread composer (pi-style). */
export const THREAD_COMMANDS: ThreadCommand[] = [
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
    id: 'retry',
    label: 'retry',
    description: '重试当前失败的 Thread',
    keywords: ['failed', 'resume', 'thread'],
  },
  {
    id: 'clear-draft',
    label: 'clear',
    description: '清空当前输入',
    keywords: ['reset', 'empty'],
  },
]

export function filterThreadCommands(query: string): ThreadCommand[] {
  const q = query.trim().replace(/^\//, '').toLowerCase()
  if (!q) {
    return THREAD_COMMANDS
  }
  return THREAD_COMMANDS.filter((command) => {
    const haystack = [command.id, command.label, command.description]
      .join(' ')
      .toLowerCase()
    const keywordMatch = command.keywords?.some((keyword) => keyword.toLowerCase().startsWith(q)) ?? false
    return haystack.includes(q) || keywordMatch
  })
}
