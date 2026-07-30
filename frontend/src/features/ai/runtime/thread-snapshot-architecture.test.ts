import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), 'src', relativePath), 'utf8')
}

describe('Thread snapshot architecture', () => {
  it('has one snapshot query and no Thread business polling or fragmented API consumption', () => {
    const queries = source('features/ai/runtime/useAgentThreadQueries.ts')
    const observability = source('features/ai/runtime/useHarnessThreadObservability.ts')
    const service = source('shared/api/harness-service.ts')
    const keys = source('shared/lib/query-keys.ts')

    expect(queries).toContain('queryKeys.threads.snapshot(threadId)')
    expect(queries).not.toContain('refetchInterval')
    expect(observability).not.toMatch(/useQuery\(/)
    expect(observability).not.toContain('refetchInterval')
    expect(service).not.toMatch(
      /getThread:|listThreadEntries:|listThreadInputs:|listThreadToolInvocations:|getThreadUsage:/,
    )
    const threadKeys = keys.slice(keys.indexOf('threads:'), keys.indexOf('sessions:'))
    expect(threadKeys).not.toMatch(/detail:|entries:|inputs:|events:|toolInvocations:/)
  })

  it('keeps durable and lossy SSE paths causally separate', () => {
    const realtime = source('features/ai/runtime/useHarnessThreadRealtime.ts')

    expect(realtime).toContain('subscription.revision')
    expect(realtime).toContain("addEventListener('revision', invalidateSnapshot")
    expect(realtime).toContain("addEventListener('resync', invalidateSnapshot")
    expect(realtime).not.toContain("addEventListener('realtime', invalidateSnapshot")
    expect(realtime.match(/createThreadRealtimeStream\(/g)).toHaveLength(1)
    expect(realtime).not.toMatch(/setTimeout|setInterval/)
  })
})
