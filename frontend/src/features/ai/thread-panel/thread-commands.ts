export interface ThreadCommand {
  id: string
  label: string
  description: string
  keywords?: string[]
}

/** Slash/plus command table for the thread composer (pi-style). */
export const THREAD_COMMANDS: ThreadCommand[] = [
  {
    id: 'yolo',
    label: 'yolo',
    description: '切换 YOLO 自动批准工具调用',
    keywords: ['auto', 'approve', 'tool'],
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
    const haystack = [command.id, command.label, command.description, ...(command.keywords ?? [])]
      .join(' ')
      .toLowerCase()
    return haystack.includes(q)
  })
}
