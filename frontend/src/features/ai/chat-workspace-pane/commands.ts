import {
  threadCommandsForScene,
  type ThreadCommand,
} from '@/features/ai/thread-panel/thread-commands'

/** Blank pane: full stable command table; unsupported entries stay disabled (grayed). */
export const BLANK_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('blank')

/** Bound pane: every stable command is enabled (the bound Thread can carry any action). */
export const BOUND_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('bound')
