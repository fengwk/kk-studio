import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), 'src', relativePath), 'utf8')
}

describe('Thread snapshot architecture', () => {
  // 验证当前 Thread 状态保持 snapshot 仅拉取，Entry Tree 按需读取，所有 Harness 运行时调用收敛于 harness-service。
  it('keeps current Thread state snapshot-only and loads the full Entry Tree on demand', () => {
    const queries = source('features/ai/runtime/useAgentThreadQueries.ts')
    const paneController = [
      'features/ai/runtime/useRootThreadControl.ts',
      'features/ai/runtime/root-thread/usePaneHistory.ts',
    ].map(source).join('\n')
    const service = source('shared/api/harness-service.ts')
    const keys = source('shared/lib/query-keys.ts')

    expect(queries).toContain('queryKeys.threads.snapshot(threadId)')
    expect(queries).not.toContain('refetchInterval')
    expect(service).toContain('listSessionEntries:')
    expect(paneController).toContain("interaction === 'history' || isDraftHistoryTarget(target)")
    expect(paneController).toContain("['agent-pane', 'entries'")
    expect(paneController).toContain('treeEntriesQuery.data')
    const threadKeys = keys.slice(keys.indexOf('threads:'), keys.indexOf('systemSettings:'))
    expect(threadKeys).toContain('tree:')
    expect(threadKeys).toContain('entries:')
    expect(threadKeys).not.toMatch(/detail:|inputs:|events:|toolInvocations:/)
  })

  it('keeps durable and lossy realtime paths causally separate', () => {
    const realtime = [
      'features/ai/runtime/useHarnessThreadRealtime.ts',
      'features/ai/runtime/thread-realtime/realtime-streams.ts',
    ].map(source).join('\n')

    expect(realtime).toContain('subscription.threadId')
    expect(realtime).toContain("name === 'version'")
    expect(realtime).toContain('invalidateSnapshot()')
    // delta 事件只走 overlay reducer，不触发 snapshot invalidate；
    // 只有覆盖缺口的 MODEL_CHECKPOINT 才触发回读（行为由 hook 测试覆盖）。
    expect(realtime).toContain('parseRealtimeModelCheckpoint(raw)')
    expect(realtime).toContain('parseRealtimeModelDelta(raw)')
    expect(realtime).toContain('handleRealtime(data)')
    expect(realtime.match(/applicationEvents\.subscribe\(/g)).toHaveLength(1)
    expect(realtime).toContain("{ kind: 'thread', id: subscription.threadId }")
    // version/resync/error 与订阅建立一样触发 snapshot 对账；onSubscribed 的
    // invalidate 顺序与单次 catchup 由 hook 行为测试验证，不做源码字符串匹配。
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
