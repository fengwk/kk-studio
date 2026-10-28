import type { IdeReply } from '../contracts.js'

/**
 * Errors surfaced by the managed IDE service.
 *
 * Messages are safe to send back to the Server: they never include secrets, upstream
 * response bodies or raw paths derived from untrusted input. The code is always a bounded
 * uppercase token from the frozen IDE contract.
 */
export class IdeError extends Error {
  readonly code: string

  constructor(message: string, code = 'UNAVAILABLE') {
    super(message)
    this.name = 'IdeError'
    this.code = code
  }
}

export function safeMessage(error: unknown): string {
  if (error instanceof IdeError) {
    return error.message
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return 'ide request was cancelled'
  }
  return 'ide request failed'
}

export function failureReply(error: unknown): IdeReply {
  const code = error instanceof IdeError ? error.code : error instanceof Error && error.name === 'AbortError' ? 'BUSY' : 'UNAVAILABLE'
  return { ok: false, code, message: safeMessage(error) }
}
