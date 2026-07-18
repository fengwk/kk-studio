export interface SessionCommand {
  id: string
  label: string
  description: string
  keywords?: string[]
}

/** Slash/plus command table for the session composer (pi-style). */
export const SESSION_COMMANDS: SessionCommand[] = [
  {
    id: 'yolo',
    label: 'yolo',
    description: '切换 YOLO 自动批准工具调用',
    keywords: ['auto', 'approve', 'tool'],
  },
  {
    id: 'steer',
    label: 'steer',
    description: '向当前运行插入指令（运行中可用）',
    keywords: ['interrupt', 'guide'],
  },
  {
    id: 'follow-up',
    label: 'follow-up',
    description: '排队下一条用户消息',
    keywords: ['queue', 'next'],
  },
  {
    id: 'abort',
    label: 'abort',
    description: '终止当前运行',
    keywords: ['stop', 'cancel'],
  },
  {
    id: 'clear-draft',
    label: 'clear',
    description: '清空当前输入',
    keywords: ['reset', 'empty'],
  },
]

export function filterSessionCommands(query: string): SessionCommand[] {
  const q = query.trim().replace(/^\//, '').toLowerCase()
  if (!q) {
    return SESSION_COMMANDS
  }
  return SESSION_COMMANDS.filter((command) => {
    const haystack = [command.id, command.label, command.description, ...(command.keywords ?? [])]
      .join(' ')
      .toLowerCase()
    return haystack.includes(q)
  })
}
