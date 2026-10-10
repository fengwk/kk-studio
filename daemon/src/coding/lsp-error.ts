export class LspError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'LspError'
  }
}
