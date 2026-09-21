import { performance } from 'node:perf_hooks'

export function createDurationTimer(now = () => performance.now()) {
  const startedAt = now()
  return () => Math.max(0, Math.round(now() - startedAt))
}
