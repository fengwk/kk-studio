import { WireError } from './wire.js'

/**
 * Errors surfaced by the managed IDE service.
 *
 * Messages are safe to send back to the Server: they never include secrets, upstream
 * response bodies or raw paths derived from untrusted input.
 */
export class IdeError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'IdeError'
  }
}

export function safeMessage(error: unknown): string {
  if (error instanceof IdeError) {
    return error.message
  }
  // WireError may wrap a socket error, peer ERROR frame or unknown field containing untrusted text.
  if (error instanceof WireError) {
    return 'ide stream protocol or transport failed'
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return 'ide request was cancelled'
  }
  return 'ide request failed'
}
