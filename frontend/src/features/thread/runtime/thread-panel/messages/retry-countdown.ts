export function retryCountdownSeconds(
  retryAt: number | null,
  now = Date.now(),
): number {
  if (retryAt == null) {
    return 0
  }
  return Math.max(0, Math.ceil((retryAt - now) / 1000))
}
