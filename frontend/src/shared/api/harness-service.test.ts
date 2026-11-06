import { describe, expect, it } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'
import type { HarnessCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'

function createClient() {
  return createMockHttpClient()
}

describe('harnessService', () => {
  /**
   * 测试意图：验证容器创建（NEW_SESSION / NEW_THREAD）仍通过 POST /command-batches 处理，
   * 创建请求必须携带产品 owner 与创建 target。
   */
  it('submits creation command batches via POST /command-batches', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const request: HarnessCommandBatchDTO = {
      owner: { kind: 'CHAT', id: 'chat-1' },
      target: {
        type: 'NEW_SESSION' as const,
        sessionId: 's1',
        threadId: 't1',
        rootSettings: {
          agentName: 'assistant',
          execution: { driver: 'builtin', settings: { modelName: 'm', variant: 'v' } },
          environmentName: null,
          goal: null,
        },
        yoloEnabled: false,
      },
      commands: [{
        type: 'USER_MESSAGE' as const,
        idempotencyKey: 'c1',
        contents: [{ type: 'TEXT' as const, text: 'hello' }],
      }],
    }
    await service.acceptCommandBatch(request)
    expect(http.post).toHaveBeenCalledWith('/command-batches', request)
    // 未知归属也原样传递，授权由服务端决定。
    const opaque = { ...request, owner: { kind: 'PLUGIN', id: 'opaque:id' } }
    await service.acceptCommandBatch(opaque)
    expect(http.post).toHaveBeenLastCalledWith('/command-batches', opaque)
  })

  /**
   * 测试意图：既有 Thread 的通用写入口是 POST /threads/{threadId}/command-batches，
   * threadId 必须被转义；请求体只含 CAS 游标与有序命令，绝不携带 owner 或 target。
   */
  it('submits existing-thread command batches via POST /threads/{threadId}/command-batches', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const request = {
      expectedHeadEntryId: 'head-1',
      expectedNextCommandSequence: '7',
      commands: [{
        type: 'GOAL' as const,
        idempotencyKey: 'goal-1',
        text: 'finish the task',
      }],
    }

    await service.acceptThreadCommandBatch('thread /1', request)

    expect(http.post).toHaveBeenCalledWith(
      '/threads/thread%20%2F1/command-batches',
      request,
    )
    const [, body] = http.post.mock.calls[0]!
    // owner/target 只属于创建批次；既有 Thread 批次体必须恰为 CAS 游标 + commands。
    expect(Object.keys(body as object).sort()).toEqual([
      'commands',
      'expectedHeadEntryId',
      'expectedNextCommandSequence',
    ])
  })

  /**
   * 测试意图：验证 Session 级线程与条目列表查询路由 /sessions/{id}/{threads|entries}，
   * 入口参数被安全转义；Entry 列表按 keyset 游标与 limit 请求，缺省游标不写入查询参数。
   */
  it('queries session threads and paged entries via /sessions/{id}/{threads|entries}', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const signal = new AbortController().signal
    await service.listSessionThreads('session /1')
    await service.listSessionEntries('session /1')
    await service.listSessionEntries('session /2', { cursor: 'cursor-1', limit: 25, signal })

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/sessions/session%20%2F1/threads',
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/sessions/session%20%2F1/entries',
      { params: {}, signal: undefined },
    )
    expect(http.get).toHaveBeenNthCalledWith(
      3,
      '/sessions/session%20%2F2/entries',
      { params: { cursor: 'cursor-1', limit: 25 }, signal },
    )
  })

  /**
   * 测试意图：验证 Thread snapshot 直接 GET /threads/{id}，不包含 /snapshot 后缀；
   * 同时验证 manual compaction 使用 POST /threads/{id}/compact。
   */
  it('queries thread snapshot via GET /threads/{id} and triggers compaction via POST /threads/{id}/compact', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    await service.getThreadSnapshot('thread /1')
    await service.compactThread('thread /1', { expectedVersion: '4' })

    expect(http.get).toHaveBeenCalledWith('/threads/thread%20%2F1', {
      params: undefined,
      signal: undefined,
    })
    expect(http.post).toHaveBeenCalledWith(
      '/threads/thread%20%2F1/compact',
      { expectedVersion: '4' },
    )
  })

  /**
   * 测试意图：验证 Thread snapshot 支持 afterEntryId 游标与 AbortSignal 透传，
   * 并在 GET /threads/{id}/compaction-availability 上支持独立 advisory 读取与 signal。
   */
  it('supports cursor and signal on getThreadSnapshot and compaction-availability endpoint', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const controller = new AbortController()

    await service.getThreadSnapshot('thread /1', {
      afterEntryId: 'entry-cursor-1',
      signal: controller.signal,
    })
    expect(http.get).toHaveBeenCalledWith('/threads/thread%20%2F1', {
      params: { afterEntryId: 'entry-cursor-1' },
      signal: controller.signal,
    })

    await service.getCompactionAvailability('thread /1', {
      signal: controller.signal,
    })
    expect(http.get).toHaveBeenCalledWith(
      '/threads/thread%20%2F1/compaction-availability',
      { signal: controller.signal },
    )
  })

  /**
   * 测试意图：验证结构化模型请求调试、YOLO 策略切换与 Thread 终止的端点，
   * 保持 model-request-debug/compact/yolo/stop 子路径不变并在 /threads/{id} 下。
   */
  it('handles thread model-request-debug, yolo policy update and thread stopping', async () => {
    const http = createClient()
    const service = createHarnessService(http)

    await service.getModelRequestDebug('thread /1', {
      model: { modelName: 'MiniMax-M2.7', variant: 'default' },
      environmentName: 'dev-node',
    })
    await service.setThreadYolo('thread /1', { yoloEnabled: true })
    await service.stopThread('thread /1', { stopRequestId: 'req-1', expectedVersion: '2' })

    expect(http.post).toHaveBeenCalledWith('/threads/thread%20%2F1/model-request-debug', {
      model: { modelName: 'MiniMax-M2.7', variant: 'default' },
      environmentName: 'dev-node',
    })
    expect(http.put).toHaveBeenCalledWith('/threads/thread%20%2F1/yolo', {
      yoloEnabled: true,
    })
    expect(http.post).toHaveBeenCalledWith('/threads/thread%20%2F1/stop', {
      stopRequestId: 'req-1',
      expectedVersion: '2',
    })
  })

  /**
   * 测试意图：关系树从任意节点读取真实 root，路径与 snapshot 分离且参数被编码。
   */
  it('queries the agent relationship tree via GET /threads/{id}/tree', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    await service.getThreadTree('thread /1')

    expect(http.get).toHaveBeenCalledWith('/threads/thread%20%2F1/tree')
  })

  /**
   * 测试意图：验证 Session/Thread 名称修改使用 PUT /sessions/{id}/name 与
   * PUT /threads/{id}/name，且路径参数被安全转义。
   */
  it('renames sessions and threads via PUT .../name endpoints', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const session = await service.renameSession('session /1', { name: '新 Session 名' })
    const thread = await service.renameThread('thread /1', { name: '新 Thread 名' })

    expect(http.put).toHaveBeenNthCalledWith(
      1,
      '/sessions/session%20%2F1/name',
      { name: '新 Session 名' },
    )
    expect(http.put).toHaveBeenNthCalledWith(
      2,
      '/threads/thread%20%2F1/name',
      { name: '新 Thread 名' },
    )
    // Session 重命名返回 HarnessSessionDTO（sessionId/name/createdAt），
    // Thread 重命名返回 HarnessThreadDTO —— 类型区分由编译期契约保证。
    expect(session).toEqual({})
    expect(thread).toEqual({})
  })

  /**
   * 测试意图：验证 Tool approval 提交使用 PUT /threads/{threadId}/tool-invocations/{invocationId}/approval。
   */
  it('submits tool approval decisions via PUT /threads/{threadId}/tool-invocations/{invocationId}/approval', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const body = {
      decision: 'ALLOW' as const,
      decisionId: 'dec-1',
      reason: null,
    }

    await service.decideApproval('thread /1', 'invocation /2', body)

    expect(http.put).toHaveBeenCalledWith(
      '/threads/thread%20%2F1/tool-invocations/invocation%20%2F2/approval',
      body,
    )
  })

  /**
   * 测试意图：放弃等待未确认执行使用 POST /threads/{threadId}/executions/{executionId}/abandon，
   * 两个路径参数都必须转义，body 只含 exact expectedVersion。
   */
  it('abandons one unconfirmed execution via POST .../executions/{executionId}/abandon', async () => {
    const http = createClient()
    const service = createHarnessService(http)

    await service.abandonExecution('thread /1', 'execution /2', { expectedVersion: '9' })

    expect(http.post).toHaveBeenCalledWith(
      '/threads/thread%20%2F1/executions/execution%20%2F2/abandon',
      { expectedVersion: '9' },
    )
  })
})
