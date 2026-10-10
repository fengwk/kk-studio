import { describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient & {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
  put: ReturnType<typeof vi.fn>
  delete: ReturnType<typeof vi.fn>
} {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('harnessService', () => {
  /**
   * 测试意图：验证容器创建（NEW_SESSION / NEW_THREAD）仍通过 POST /harness/command-batches 处理，
   * 创建请求必须携带产品 owner 与创建 target。
   */
  it('submits creation command batches via POST /harness/command-batches', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const request = {
      owner: { type: 'CHAT' as const, chatId: 'chat-1' },
      target: {
        type: 'NEW_SESSION' as const,
        sessionId: 's1',
        threadId: 't1',
        rootSettings: {
          agentName: 'assistant',
          model: { providerName: 'p', modelName: 'm', variant: 'v' },
          environmentName: null,
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
    expect(http.post).toHaveBeenCalledWith('/harness/command-batches', request)
  })

  /**
   * 测试意图：既有 Thread 的通用写入口是 POST /harness/threads/{threadId}/command-batches，
   * threadId 必须被转义；请求体只含 CAS 游标与有序命令，绝不携带 owner 或 target。
   */
  it('submits existing-thread command batches via POST /harness/threads/{threadId}/command-batches', async () => {
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
      '/harness/threads/thread%20%2F1/command-batches',
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
   * 测试意图：既有 Thread 的 Provider 请求预览走 per-thread 端点，threadId 被转义，
   * 请求体同样不带 owner/target。
   */
  it('previews existing-thread provider requests via POST /harness/threads/{threadId}/provider-request-preview', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const request = {
      expectedHeadEntryId: 'head-1',
      expectedNextCommandSequence: '3',
      commands: [{
        type: 'USER_MESSAGE' as const,
        idempotencyKey: 'msg-1',
        contents: [{ type: 'TEXT' as const, text: 'hello' }],
      }],
    }

    await service.previewProviderRequest('thread /1', request)

    expect(http.post).toHaveBeenCalledWith(
      '/harness/threads/thread%20%2F1/provider-request-preview',
      request,
    )
    const [, body] = http.post.mock.calls[0]!
    expect(Object.keys(body as object).sort()).toEqual([
      'commands',
      'expectedHeadEntryId',
      'expectedNextCommandSequence',
    ])
  })

  /**
   * 测试意图：验证 Session 级线程与条目列表查询路由迁移至 /harness/sessions/{id}/{threads|entries}，
   * 确保参数被安全转义且路径符合新契约。
   */
  it('queries session threads and entries via /harness/sessions/{id}/{threads|entries}', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    await service.listSessionThreads('session /1')
    await service.listSessionEntries('session /1')

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/harness/sessions/session%20%2F1/threads',
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/harness/sessions/session%20%2F1/entries',
    )
  })

  /**
   * 测试意图：验证 Thread snapshot 改为直接 GET /harness/threads/{id}，不再包含 /snapshot 后缀；
   * 同时验证 manual compaction 使用 POST /harness/threads/{id}/compact。
   */
  it('queries thread snapshot via GET /harness/threads/{id} and triggers compaction via POST /harness/threads/{id}/compact', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    await service.getThreadSnapshot('thread /1')
    await service.compactThread('thread /1', { expectedVersion: '4' })

    expect(http.get).toHaveBeenCalledWith('/harness/threads/thread%20%2F1')
    expect(http.post).toHaveBeenCalledWith(
      '/harness/threads/thread%20%2F1/compact',
      { expectedVersion: '4' },
    )
  })

  /**
   * 测试意图：验证结构化模型请求调试、YOLO 策略切换与 Thread 终止的端点，
   * 保持 model-request-debug/compact/yolo/stop 子路径不变并在 /harness/threads/{id} 下。
   */
  it('handles thread model-request-debug, yolo policy update and thread stopping', async () => {
    const http = createClient()
    const service = createHarnessService(http)

    await service.getModelRequestDebug('thread /1', {
      model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
      environmentName: 'dev-node',
    })
    await service.setThreadYolo('thread /1', { yoloEnabled: true })
    await service.stopThread('thread /1', { stopRequestId: 'req-1', expectedVersion: '2' })

    expect(http.post).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/model-request-debug', {
      model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
      environmentName: 'dev-node',
    })
    expect(http.put).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/yolo', {
      yoloEnabled: true,
    })
    expect(http.post).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/stop', {
      stopRequestId: 'req-1',
      expectedVersion: '2',
    })
  })

  /**
   * 测试意图：关系树从任意节点读取真实 root，路径与 snapshot 分离且参数被编码。
   */
  it('queries the agent relationship tree via GET /harness/threads/{id}/tree', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    await service.getThreadTree('thread /1')

    expect(http.get).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/tree')
  })

  /**
   * 测试意图：验证 Session/Thread 名称修改使用 PUT /harness/sessions/{id}/name 与
   * PUT /harness/threads/{id}/name，且路径参数被安全转义。
   */
  it('renames sessions and threads via PUT .../name endpoints', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const session = await service.renameSession('session /1', { name: '新 Session 名' })
    const thread = await service.renameThread('thread /1', { name: '新 Thread 名' })

    expect(http.put).toHaveBeenNthCalledWith(
      1,
      '/harness/sessions/session%20%2F1/name',
      { name: '新 Session 名' },
    )
    expect(http.put).toHaveBeenNthCalledWith(
      2,
      '/harness/threads/thread%20%2F1/name',
      { name: '新 Thread 名' },
    )
    // Session 重命名返回 HarnessSessionDTO（sessionId/name/createdAt），
    // Thread 重命名返回 HarnessThreadDTO —— 类型区分由编译期契约保证。
    expect(session).toEqual({})
    expect(thread).toEqual({})
  })

  /**
   * 测试意图：验证 Tool approval 提交由 POST 迁移为 PUT /harness/threads/{threadId}/tool-invocations/{invocationId}/approval，
   * 确保 HTTP 方法和路径严格符合最新后端契约。
   */
  it('submits tool approval decisions via PUT /harness/threads/{threadId}/tool-invocations/{invocationId}/approval', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const body = {
      decision: 'ALLOW' as const,
      decisionId: 'dec-1',
      reason: null,
    }

    await service.decideApproval('thread /1', 'invocation /2', body)

    expect(http.put).toHaveBeenCalledWith(
      '/harness/threads/thread%20%2F1/tool-invocations/invocation%20%2F2/approval',
      body,
    )
  })
})
