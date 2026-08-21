import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), 'src', relativePath), 'utf8')
}

describe('Thread snapshot architecture', () => {
  it('keeps current Thread state snapshot-only and loads the full Entry Tree on demand', () => {
    const queries = source('features/ai/runtime/useAgentThreadQueries.ts')
    const paneController = source('features/ai/runtime/useAgentPaneController.ts')
    const service = source('shared/api/agent-pane-service.ts')
    const keys = source('shared/lib/query-keys.ts')

    expect(queries).toContain('queryKeys.threads.snapshot(threadId)')
    expect(queries).not.toContain('refetchInterval')
    expect(service).toContain('listSessionEntries:')
    expect(paneController).toContain("interaction === 'tree' || isEntryTarget(target)")
    expect(paneController).toContain("['agent-pane', 'entries'")
    expect(paneController).toContain('treeEntriesQuery.data')
    const threadKeys = keys.slice(keys.indexOf('threads:'), keys.indexOf('comfyui:'))
    expect(threadKeys).toContain('entries:')
    expect(threadKeys).not.toMatch(/detail:|inputs:|events:|toolInvocations:/)
  })

  it('keeps durable and lossy realtime paths causally separate', () => {
    const realtime = source('features/ai/runtime/useHarnessThreadRealtime.ts')

    expect(realtime).toContain('subscription.threadId')
    expect(realtime).toContain("name === 'version'")
    expect(realtime).toContain('invalidateSnapshot()')
    // realtime 事件只走 overlay reducer，绝不触发 snapshot invalidate。
    expect(realtime).toContain('handleRealtime(data)')
    expect(realtime.match(/applicationEvents\.subscribe\(/g)).toHaveLength(1)
    expect(realtime).toContain("{ kind: 'thread', id: subscription.threadId }")
    // subscribed/resync/error 与 version 一样触发 snapshot 对账。
    expect(realtime).toContain('onSubscribed: invalidateSnapshot')
    expect(realtime).toContain('onResync: invalidateSnapshot')
    expect(realtime).toContain('onError: invalidateSnapshot')
    expect(realtime).not.toContain('EventSource')
    // 有界的 single-flight gap recovery 可以使用 timer，但每个 timer 必须可取消，
    // 并在 unmount 或 Thread 切换时清理（避免遗留循环）。
    if (/setTimeout/.test(realtime)) {
      expect(realtime).toContain('clearTimeout')
    } else {
      expect(realtime).not.toContain('setInterval')
    }
  })
})
