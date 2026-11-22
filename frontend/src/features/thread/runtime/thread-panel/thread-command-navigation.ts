import { useMemo } from 'react'
import {
  filterThreadCommands,
  THREAD_COMMANDS,
  type ThreadCommand,
} from '@/features/thread/runtime/thread-panel/thread-commands'

export function useFilteredThreadCommands(
  query: string,
  commandSource: ThreadCommand[] = THREAD_COMMANDS,
): ThreadCommand[] {
  return useMemo(() => filterThreadCommands(query, commandSource), [commandSource, query])
}

/** 命令表 option 的 DOM id：listbox id 由持有编辑器的 Composer 生成，两边共用同一格式。 */
export function threadCommandOptionId(listboxId: string, commandId: string): string {
  return `${listboxId}-option-${commandId}`
}

/** 在过滤后的命令列表中获取下一个/上一个可用项的索引；支持环绕。 */
export function stepEnabledCommandIndex(
  commands: ThreadCommand[],
  currentIndex: number,
  delta: 1 | -1,
): number {
  if (commands.length === 0) {
    return 0
  }
  const enabled = commands
    .map((command, index) => (command.disabled ? -1 : index))
    .filter((index) => index >= 0)
  if (enabled.length === 0) {
    return Math.max(0, Math.min(currentIndex, commands.length - 1))
  }
  const currentPos = enabled.indexOf(currentIndex)
  if (currentPos < 0) {
    return delta > 0 ? enabled[0]! : enabled[enabled.length - 1]!
  }
  const nextPos = (currentPos + delta + enabled.length) % enabled.length
  return enabled[nextPos]!
}

export function firstEnabledCommandIndex(commands: ThreadCommand[]): number {
  const index = commands.findIndex((command) => !command.disabled)
  return index >= 0 ? index : 0
}
