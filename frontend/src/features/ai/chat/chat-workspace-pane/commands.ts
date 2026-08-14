import {
  threadCommandsForScene,
  type ThreadCommand,
} from '@/features/ai/runtime'

/** 空面板：完整的稳定 command 表；不支持的条目保持禁用（灰显）。 */
export const BLANK_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('chat-blank')

/** 绑定面板：每个稳定 command 都已启用（绑定的 Thread 可以执行任意操作）。 */
export const BOUND_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('chat-bound')
