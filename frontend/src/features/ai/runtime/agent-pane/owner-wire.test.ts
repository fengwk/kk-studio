import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildAcceptanceRequest } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { buildGoalBatchPlan, buildMessageBatchPlan } from '@/features/ai/chat/command-batch-plan'
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
  parentThreadId: null,
  nextCommandSequence: '1',
  name: 'Thread',
  yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
  version: '1',
  status: 'IDLE',
  processing: false,
  executionControl: 'RUNNABLE',
  branchSettings: { agentName: 'coder', model: draft.model, environmentName: null, goal: null },
  createTime: null,
  updateTime: null,
}

/** Reads the serialized request captured by the adapter, failing loudly on a missing entry. */
function wireAt(index: number): { url: string; data: string } {
  const entry = captured[index]
  if (entry == null) {
    throw new Error(`No captured HTTP request at index ${index}`)
  }
  return entry
}

describe('owner-free HTTP wire', () => {
  beforeEach(() => captured.splice(0))

  it('serializes a Chat container creation with owner and a NEW_SESSION target', async () => {
    // 测试意图：创建型批次仍走 owner 契约，且真实序列化后不再携带 legacy id 字段。
    const created = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft,
      base: draft,
      parts: [createTextPart('hello')],
      createId: () => 'cmd-1',
    })
    await harnessService.acceptCommandBatch(created.request)
    const { url, data } = wireAt(0)
    expect(url).toBe('/harness/command-batches')
    const body = JSON.parse(data) as {
      owner: unknown
      target: { type: string }
      commands: Array<{ type: string }>
    }
    expect(body.owner).toEqual({ type: 'CHAT', chatId: 'chat-1' })
    expect(body.target.type).toBe('NEW_SESSION')
    expect(body.commands.some((command) => command.type === 'USER_MESSAGE')).toBe(true)
    expect(data).not.toContain('"id":')
  })

  it('serializes an existing thread message batch without owner or target', async () => {
    // 测试意图：既有 Thread 的发送只提交精确 CAS 游标与命令，wire body 不存在 owner/target。
    const plan = buildMessageBatchPlan({
      thread,
      effectiveBase: draft,
      draft,
      parts: [createTextPart('hello')],
      createCommandId: () => 'cmd-1',
    })
    await harnessService.acceptThreadCommandBatch('thread-1', plan.request)
    const { url, data } = wireAt(0)
    expect(url).toBe('/harness/threads/thread-1/command-batches')
    const body = JSON.parse(data) as Record<string, unknown>
    expect(Object.keys(body).sort()).toEqual([
      'commands',
      'expectedHeadEntryId',
      'expectedNextCommandSequence',
    ])
    expect(body).not.toHaveProperty('owner')
    expect(body).not.toHaveProperty('target')
    expect(body.expectedHeadEntryId).toBe('entry-1')
    expect(body.expectedNextCommandSequence).toBe('1')
    expect(data).not.toContain('"owner"')
    expect(data).not.toContain('"target"')
  })

  it('serializes an existing thread goal batch on the same owner-free endpoint', async () => {
    // 测试意图：Goal 与普通消息共用无 owner 契约，且序列化后确实带有 GOAL 命令。
    const plan = buildGoalBatchPlan({
      thread,
      effectiveBase: draft,
      draft,
      goalText: 'ship it',
      createCommandId: () => 'cmd-1',
    })
    await harnessService.acceptThreadCommandBatch('thread-1', plan.request)
    const { url, data } = wireAt(0)
    expect(url).toBe('/harness/threads/thread-1/command-batches')
    const body = JSON.parse(data) as {
      commands: Array<{ type: string }>
    } & Record<string, unknown>
    expect(body.commands.some((command) => command.type === 'GOAL')).toBe(true)
    expect(body).not.toHaveProperty('owner')
    expect(body).not.toHaveProperty('target')
  })

  it('serializes a provider request preview without owner or target', async () => {
    // 测试意图：预览走 per-thread 端点，wire body 同样不携带 owner/target。
    const plan = buildMessageBatchPlan({
      thread,
      effectiveBase: draft,
      draft,
      parts: [createTextPart('preview')],
      createCommandId: () => 'cmd-2',
    })
    await harnessService.previewProviderRequest('thread-1', plan.request)
    const { url, data } = wireAt(0)
    expect(url).toBe('/harness/threads/thread-1/provider-request-preview')
    const body = JSON.parse(data) as Record<string, unknown>
    expect(body).not.toHaveProperty('owner')
    expect(body).not.toHaveProperty('target')
  })

  it('fails closed for bound-thread acceptance while ISSUE_AGENT stays a container owner', async () => {
    // 测试意图：既有 Thread 不能走创建型构建器（失败前无网络副作用），而 ISSUE_AGENT
    // 仅作为容器 owner 被接受：创建请求携带它，既有 Thread 请求完全不发送它。
    expect(() => buildAcceptanceRequest({
      owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
      target: { kind: 'BOUND_THREAD', threadId: 'thread-1' },
      draft,
      base: draft,
      parts: [createTextPart('hello')],
    })).toThrow('Existing threads submit through the thread command batch contract')
    expect(captured).toHaveLength(0)

    const created = buildAcceptanceRequest({
      owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft,
      base: draft,
      parts: [createTextPart('hello')],
      createId: () => 'cmd-1',
    })
    await harnessService.acceptCommandBatch(created.request)
    const creation = JSON.parse(wireAt(0).data) as { owner: unknown }
    expect(creation.owner).toEqual({ type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' })

    const plan = buildMessageBatchPlan({
      thread,
      effectiveBase: draft,
      draft,
      parts: [createTextPart('hello')],
      createCommandId: () => 'cmd-2',
    })
    await harnessService.acceptThreadCommandBatch('thread-1', plan.request)
    const bound = JSON.parse(wireAt(1).data) as Record<string, unknown>
    expect(bound).not.toHaveProperty('owner')
    expect(bound).not.toHaveProperty('target')
  })

  it('URL-encodes the thread id in the command batch path', async () => {
    // 测试意图：threadId 作为 path segment 必须编码，避免空格/斜杠破坏端点结构。
    const plan = buildMessageBatchPlan({
      thread,
      effectiveBase: draft,
      draft,
      parts: [createTextPart('hello')],
      createCommandId: () => 'cmd-1',
    })
    await harnessService.acceptThreadCommandBatch('thread /1', plan.request)
    expect(wireAt(0).url).toBe('/harness/threads/thread%20%2F1/command-batches')
  })
})
