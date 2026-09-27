import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildAcceptanceRequest } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { createTextPart } from '@/features/ai/composer/composer-parts'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

const captured = vi.hoisted(() => [] as Array<{ url: string; data: string }>)

// Keep the real axios instance, request serialization and apiClient interceptors;
// replace only the network adapter so assertions inspect the actual HTTP body.
vi.mock('axios', async (importOriginal) => {
  const { default: axios } = await importOriginal<typeof import('axios')>()
  return {
    default: {
      create: (config: Parameters<typeof axios.create>[0]) => {
        const client = axios.create(config)
        client.defaults.adapter = async (request) => {
          captured.push({ url: request.url ?? '', data: String(request.data) })
          return { data: { status: 200, data: {} }, status: 200, statusText: 'OK', headers: {}, config: request }
        }
        return client
      },
    },
  }
})

const draft = {
  agentName: 'coder',
  model: { providerName: 'p', modelName: 'm', variant: 'default' },
  environmentName: null,
  yoloEnabled: false,
}
const thread: HarnessThreadDTO = {
  threadId: 'thread-1',
  sessionId: 'session-1',
  headEntryId: 'entry-1',
  nextCommandSequence: '1',
  name: 'Thread',
  yoloEnabled: false,
  version: '1',
  status: 'IDLE',
  processing: false,
  branchSettings: { agentName: 'coder', model: draft.model, environmentName: null, goal: null },
  createTime: null,
  updateTime: null,
}

describe('owner HTTP wire', () => {
  beforeEach(() => captured.splice(0))

  it('serializes Chat accept and preview with chatId and no legacy id', async () => {
    // 测试意图：校验真实 HttpClient 序列化后的两条 wire 路径，而非仅验证构建器或 mock service。
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
    const accepted = buildAcceptanceRequest({
      owner, target: { kind: 'NEW_SESSION_DRAFT' }, draft, base: draft,
      parts: [createTextPart('hello')], createId: () => 'cmd-1',
    })
    const preview = buildAcceptanceRequest({
      owner, target: { kind: 'BOUND_THREAD', threadId: 'thread-1' }, draft, base: draft,
      thread,
      parts: [createTextPart('preview')], createId: () => 'cmd-2',
    })
    await harnessService.acceptCommandBatch(accepted.request)
    await harnessService.previewProviderRequest('thread-1', preview.request)
    expect(captured.map(({ url }) => url)).toEqual([
      '/harness/command-batches',
      '/harness/threads/thread-1/provider-request-preview',
    ])
    for (const { data } of captured) {
      const body = JSON.parse(data) as { owner: unknown }
      expect(body.owner).toEqual({ type: 'CHAT', chatId: 'chat-1' })
      expect(data).not.toContain('"id":')
    }
  })

  it('refuses to build a generic Issue acceptance or preview request', () => {
    // 测试意图：受控 Issue 不能通过共用批次构建器绕过受控指令入口。
    expect(() => buildAcceptanceRequest({
      owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
      target: { kind: 'BOUND_THREAD', threadId: 'thread-1' },
      draft, base: draft, parts: [createTextPart('hello')],
    })).toThrow('Controlled Issue')
    expect(captured).toHaveLength(0)
  })
})
