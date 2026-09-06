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
   * 测试意图：验证所有命令批提交流程通过统一的 POST /harness/command-batches 端点处理，
   * 确保收敛后的客户端路由与后端新契约完全一致。
   */
  it('submits command batches via POST /harness/command-batches', async () => {
    const http = createClient()
    const service = createHarnessService(http)
    const request = {
      owner: { type: 'CANVAS' as const, id: 'canvas-1' },
      target: {
        type: 'NEW_SESSION' as const,
        sessionId: 's1',
        threadId: 't1',
        rootSettings: {
          workspacePath: null,
          agentName: 'assistant',
          model: { providerName: 'p', modelName: 'm', variant: 'v' },
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
   * 测试意图：验证系统提示词预览、YOLO 策略切换与 Thread 终止的端点，
   * 保持 system-prompt/compact/yolo/stop 子路径不变并在 /harness/threads/{id} 下。
   */
  it('handles thread system-prompt preview, yolo policy update and thread stopping', async () => {
    const http = createClient()
    const service = createHarnessService(http)

    await service.getSystemPromptPreview('thread /1')
    await service.setThreadYolo('thread /1', { expectedVersion: '1', yoloEnabled: true })
    await service.stopThread('thread /1', { stopRequestId: 'req-1', expectedVersion: '2' })

    expect(http.get).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/system-prompt')
    expect(http.put).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/yolo', {
      expectedVersion: '1',
      yoloEnabled: true,
    })
    expect(http.post).toHaveBeenCalledWith('/harness/threads/thread%20%2F1/stop', {
      stopRequestId: 'req-1',
      expectedVersion: '2',
    })
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
      actor: 'user',
      reason: null,
    }

    await service.decideApproval('thread /1', 'invocation /2', body)

    expect(http.put).toHaveBeenCalledWith(
      '/harness/threads/thread%20%2F1/tool-invocations/invocation%20%2F2/approval',
      body,
    )
  })
})
