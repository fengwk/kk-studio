export class LspError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'LspError'
  }
}

export class LspTimeoutError extends LspError {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'LspTimeoutError'
  }
}
