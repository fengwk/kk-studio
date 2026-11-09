import type { IdeCommand, IdeReply } from '../contracts.js'
import { IdeError, failureReply } from './errors.js'
import type { IdeSnapshot } from './serviceTypes.js'
import { hostHome, listDirectories } from './workspace.js'

type CommandHandler = (command: IdeCommand, signal: AbortSignal) => Promise<IdeReply>

/** Dispatches commands without owning view state or changing close/lease ordering. */
export function createCommandHandler(options: {
  isClosed: () => boolean
  awaitCrashRecovery: (signal: AbortSignal) => Promise<void>
  openView: CommandHandler
  changeDirectory: CommandHandler
  focusTerminal: CommandHandler
  closeView: CommandHandler
  collapseMobile: CommandHandler
  listViews: () => Promise<IdeReply> | IdeReply
  snapshot: () => IdeSnapshot
  boundedText: (value: string | undefined, field: string) => string | undefined
}): CommandHandler {
  return async (command, signal) => {
    if (options.isClosed()) {
      return failureReply(new IdeError('IDE service is closed'))
    }
    try {
      await options.awaitCrashRecovery(signal)
      switch (command.action) {
        case 'OPEN_VIEW':
          return await options.openView(command, signal)
        case 'CHANGE_DIRECTORY':
          return await options.changeDirectory(command, signal)
        case 'FOCUS_TERMINAL':
          return await options.focusTerminal(command, signal)
        case 'CLOSE_VIEW':
          return await options.closeView(command, signal)
        case 'LIST_VIEWS':
          return await options.listViews()
        case 'LIST_DIRECTORIES':
          return {
            ok: true,
            directoryListing: await listDirectories(options.boundedText(command.directory, 'directory') ?? hostHome()),
          }
        case 'COLLAPSE_MOBILE':
          return await options.collapseMobile(command, signal)
        default:
          throw new IdeError('unknown ide action')
      }
    } catch (error) {
      // Any view action may have committed CLOSING/cleanup state before failing; the failed
      // receipt still carries the complete post-failure snapshot for reconciliation.
      return failureReply(
        error,
        command.action === 'LIST_DIRECTORIES' ? undefined : options.snapshot(),
      )
    }
  }
}
