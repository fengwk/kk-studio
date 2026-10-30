/**
 * 取消错误的唯一判定。
 *
 * <p>同时覆盖 AbortSignal 的 `AbortError`、LSP 固定文案 `Operation cancelled`，以及 Node/undici
 * 的 `The operation was aborted`。非对象与普通错误一律不是取消。
 */
export function isAbortError(error: unknown): boolean {
  if (error === null || error === undefined || typeof error !== 'object') {
    return false
  }
  const candidate = error as { name?: unknown; message?: unknown }
  if (candidate.name === 'AbortError') {
    return true
  }
  return (
    candidate.message === 'Operation cancelled' ||
    (typeof candidate.message === 'string' && candidate.message.includes('The operation was aborted'))
  )
}
