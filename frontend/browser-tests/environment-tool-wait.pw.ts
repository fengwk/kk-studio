import type { WebSocketRoute } from '@playwright/test'
import { expect, test } from './fixture'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadTreeNodeDTO,
  InteractionApproval,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

const ROOT = 'a1000000-0000-4000-8000-0000000000a1'
const CHILD = 'a1000000-0000-4000-8000-0000000000a2'
const SESSION = 'a1000000-0000-4000-8000-0000000000a3'
const ROOT_ENTRY = 'a1000000-0000-4000-8000-0000000000a4'
const TURN_START_ENTRY = 'a1000000-0000-4000-8000-0000000000a5'
const ASSISTANT_ENTRY = 'a1000000-0000-4000-8000-0000000000a6'
const INVOCATION = 'a1000000-0000-4000-8000-0000000000a8'
const EVENT_NODE = 'a1000000-0000-4000-8000-0000000000e1'
const CONNECTION = 'a1000000-0000-4000-8000-0000000000e2'
const ENVIRONMENT = 'a1000000-0000-4000-8000-0000000000e3'
const CHAT = 'a1000000-0000-4000-8000-0000000000e4'

const branchSettings = {
  agentName: 'assistant',
  execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
  environmentName: null,
}

function entries(): HarnessSessionEntryDTO[] {
  return [
    {
      entryId: ROOT_ENTRY,
      sessionId: SESSION,
      parentEntryId: null,
      entryType: 'ROOT',
      payload: { settings: branchSettings },
      createdAt: 1_790_812_740,
      usageCost: null,
    },
    {
      entryId: TURN_START_ENTRY,
      sessionId: SESSION,
      parentEntryId: ROOT_ENTRY,
      entryType: 'TURN_START',
      payload: { settings: branchSettings },
      createdAt: 1_790_812_770,
      usageCost: null,
    },
    {
      entryId: ASSISTANT_ENTRY,
      sessionId: SESSION,
      parentEntryId: TURN_START_ENTRY,
      entryType: 'MESSAGE',
      payload: { message: { role: 'ASSISTANT', contents: [{
        type: 'tool_call', toolCallId: 'call-wait', toolName: 'bash', rendererKey: 'bash',
        argumentsJson: '{"command":"pwd","workdir":"/srv/frozen-workdir"}',
      }] } },
      createdAt: 1_790_812_800,
      usageCost: null,
    },
  ]
}

function invocation(
  threadId: string,
  waiting: boolean,
  freshnessAt: number | null,
  status: ToolInvocationDTO['status'],
): ToolInvocationDTO {
  return {
    id: INVOCATION,
    threadId,
    childThreadId: null,
    turnStartEntryId: TURN_START_ENTRY,
    assistantEntryId: ASSISTANT_ENTRY,
    callIndex: 0,
    status,
    attempt: 1,
    toolCallId: 'call-wait',
    queuedBehindToolCallId: null,
    toolName: 'bash',
    rendererKey: 'bash',
    environmentId: status === 'RUNNING' ? ENVIRONMENT : null,
    requiredEnvironmentId: ENVIRONMENT,
    requiredEnvironmentName: 'archlinux',
    waitingForEnvironment: waiting,
    environmentWaitFreshnessAt: freshnessAt,
    argumentsJson: '{"command":"pwd","workdir":"/srv/frozen-workdir"}',
    approval: null,
    result: null,
    error: null,
    createdAt: 1_790_812_800,
    updatedAt: 1_790_812_800,
  }
}

function snapshot(threadId: string, waiting: boolean, freshnessAt: number | null, status: ToolInvocationDTO['status']): HarnessThreadSnapshotDTO {
  return {
    version: '1',
    thread: {
      threadId,
      parentThreadId: threadId === ROOT ? null : ROOT,
      name: '等待工具 Thread',
      sessionId: SESSION,
      headEntryId: ASSISTANT_ENTRY,
      rootThreadId: ROOT,
      yoloEnabled: false,
      nextCommandSequence: '1',
      version: '1',
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings,
      createdAt: 1_790_812_800,
      updatedAt: 1_790_812_800,
    },
    baseEntryId: null,
    entries: entries(),
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [invocation(threadId, waiting, freshnessAt, status)],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

function threadTree(now: number): HarnessThreadTreeNodeDTO[] {
  return [
    {
      threadId: ROOT,
      kind: 'USER',
      parentThreadId: null,
      name: '等待工具 Thread',
      agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      turnCount: 1,
      toolCallCount: 1,
      outcome: null,
      updatedAt: now,
    },
    {
      threadId: CHILD,
      kind: 'SUBAGENT',
      parentThreadId: ROOT,
      name: 'worker',
      agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
      status: 'IDLE',
      executionControl: 'RUNNABLE',
      turnCount: 1,
      toolCallCount: 0,
      outcome: null,
      updatedAt: now,
    },
  ]
}

const approval: InteractionApproval = {
  type: 'APPROVAL',
  toolInvocationId: 'a1000000-0000-4000-8000-0000000000a9',
  threadId: CHILD,
  rootThreadId: ROOT,
  sessionId: SESSION,
  owner: { kind: 'CHAT', id: CHAT, title: '等待工具聊天', link: `/chats/${CHAT}` },
  toolCallId: 'approval-call',
  toolName: 'bash',
  argumentsJson: '{"command":"deploy --dry-run","workdir":"/srv/approval-workdir"}',
  approval: { required: true, reason: '高危操作需要人工确认' },
  queuedSameFileCount: 0,
  createdAt: 1_790_812_800,
}

// 用严格 DTO 作为 HTTP bootstrap，后续权威状态仅由 ACK 后的 WebSocket snapshot 推进。
for (const scenario of ['pane-root', 'pane-child']) {
  test(`${scenario}: frozen wait, same-version wire renewal/expiry/resume and no writes`, async ({ page }) => {
    const now = Date.UTC(2026, 9, 24)
    await page.clock.install({ time: now })
    const isRoot = scenario === 'pane-root'
    const id = isRoot ? ROOT : CHILD
    let waiting = true
    let freshnessAt: number | null = now + 10_000
    let status: ToolInvocationDTO['status'] = 'READY'
    let socket: WebSocketRoute | undefined
    let socketCount = 0
    let threadReads = 0
    let treeReads = 0
    const writes: string[] = []
    const subscriptions = new Set<string>()
    const pushThreadSnapshot = (afterEntryId?: string) => {
      const data = snapshot(id, waiting, freshnessAt, status)
      if (afterEntryId === ASSISTANT_ENTRY) {
        data.baseEntryId = afterEntryId
        data.entries = []
      }
      socket!.send(JSON.stringify({ type: 'event', resource: { kind: 'thread', id }, name: 'snapshot', data }))
    }
    // 续期替换服务端截止时间；到期重投影后，不再挂同一个截止时间。
    const advanceServerClock = async (ms: number) => {
      await page.clock.runFor(ms)
      if (freshnessAt !== null && await page.evaluate(() => Date.now()) >= freshnessAt) {
        freshnessAt = null
        waiting = false
        pushThreadSnapshot(ASSISTANT_ENTRY)
      }
    }
    const pushTreeSnapshot = () => socket?.send(JSON.stringify({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT },
      name: 'snapshot',
      data: { nodes: threadTree(now) },
    }))

    await page.routeWebSocket(/\/ws\/events$/, (ws) => {
      socket = ws
      socketCount += 1
      ws.send(JSON.stringify({
        type: 'connected',
        eventNodeId: EVENT_NODE,
        connectionId: CONNECTION,
        heartbeatIntervalMillis: '60000',
      }))
      ws.onMessage((raw) => {
        const message = JSON.parse(String(raw))
        if (message.type === 'subscribe') {
          const resource = message.resource as { kind: string; id?: string }
          subscriptions.add(resource.kind)
          // 确认 wire 订阅后才发该资源快照，不模拟任何 HTTP 回读。
          ws.send(JSON.stringify({ type: 'subscribed', resource, cursor: '0' }))
          if (resource.kind === 'thread' && resource.id === id) pushThreadSnapshot(message.afterEntryId)
          if (resource.kind === 'thread-tree' && resource.id === ROOT) pushTreeSnapshot()
        } else if (message.type === 'refresh' && message.resource?.kind === 'thread' && message.resource.id === id) {
          pushThreadSnapshot()
        } else if (message.type === 'refresh' && message.resource?.kind === 'thread-tree' && message.resource.id === ROOT) {
          pushTreeSnapshot()
        }
      })
    })

    await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (request.method() !== 'GET') {
        writes.push(`${request.method()} ${path}`)
        throw new Error(`Unexpected write in environment wait harness: ${request.method()} ${path}`)
      }
      if (path === `/api/harness/threads/${id}`) {
        threadReads += 1
        // 初始 HTTP 数据不含等待状态，等待必须来自订阅后的快照。
        await route.fulfill({ json: snapshot(id, false, null, 'RUNNING') })
      } else if (path === `/api/harness/threads/${id}/tree`) {
        treeReads += 1
        await route.fulfill({ json: threadTree(now) })
      } else if (path === '/api/harness/interactions') {
        await route.fulfill({ json: { items: [approval], total: 1, nextCursor: null, freshnessAt: null } })
      } else if (path === '/api/platform/agents' || path === '/api/platform/models'
        || path === '/api/platform/providers' || path === '/api/platform/environments') {
        await route.fulfill({ json: [] })
      } else {
        throw new Error(`Unexpected API request in environment wait harness: ${request.method()} ${path}`)
      }
    })

    await page.goto(`/browser-tests/pane-control-harness.html?scenario=${scenario}`)
    const card = page.locator('.thread-block-tool')
    await expect(card).toHaveAttribute('data-invocation-state', 'environment')
    await expect(card.locator('.thread-tool-summary')).toContainText('bash · 等待环境 archlinux 上线')
    await expect(card.locator('.animate-spin')).toHaveCount(0)
    await expect(page.locator('.thread-working')).toContainText('等待环境 archlinux 上线')
    await expect(page.locator('.thread-status-footer')).not.toContainText('等待环境')

    if (isRoot) {
      const approvalCard = page.locator('.interaction-feed-item')
      await expect(approvalCard.getByRole('link', { name: '打开 subagent', exact: true })).toHaveAttribute('href', `/threads/${CHILD}`)
      await expect(approvalCard.getByRole('button', { name: '拒绝', exact: true })).toBeEnabled()
      await expect(approvalCard).not.toContainText('Chat · 等待工具聊天')
      await expect(approvalCard.locator('pre')).toHaveCount(2)
      await expect(approvalCard.locator('pre.is-command')).toHaveText('deploy --dry-run')
      await expect(approvalCard.getByText('/srv/approval-workdir', { exact: true })).toHaveCount(1)
      expect((await approvalCard.textContent())?.split('/srv/approval-workdir')).toHaveLength(2)
      await expect(approvalCard.getByRole('note')).toHaveText('高危操作需要人工确认')
      await expect(approvalCard.locator('.interaction-status-tag, time')).toHaveCount(0)
    }

    await expect.poll(() => subscriptions.has('thread')).toBe(true)
    if (isRoot) {
      await expect.poll(() => subscriptions.has('thread-tree')).toBe(true)
      await expect.poll(() => subscriptions.has('interactions')).toBe(true)
    } else {
      expect(subscriptions.has('thread-tree')).toBe(false)
      expect(subscriptions.has('interactions')).toBe(false)
      await expect(page.locator('.thread-control-area')).toHaveCount(0)
      await expect(page.locator('.thread-composer')).toHaveCount(0)
    }

    const bootstrapTreeReads = treeReads
    await page.screenshot({ path: `../reports/layout/environment-tool-${scenario}-waiting.png`, fullPage: true })
    const renewAt = await page.evaluate(() => Date.now())
    freshnessAt = renewAt + 20_000
    pushThreadSnapshot(ASSISTANT_ENTRY)
    await advanceServerClock(10_000)
    await expect(card).toHaveAttribute('data-invocation-state', 'environment')
    await advanceServerClock(10_000)
    await expect(card).toHaveAttribute('data-invocation-state', 'queued')
    await expect(card.locator('.thread-tool-summary')).not.toContainText('等待环境')
    await advanceServerClock(60_000)
    await expect(card).toHaveAttribute('data-invocation-state', 'queued')

    status = 'RUNNING'
    pushThreadSnapshot(ASSISTANT_ENTRY)
    await expect(card).toHaveAttribute('data-invocation-state', 'running')
    await expect(card).toHaveAttribute('aria-busy', 'true')
    await expect(card.locator('.animate-spin, .thread-tool-tail > svg')).toHaveCount(0)
    await expect(card.locator('.thread-tool-summary')).not.toContainText('等待环境')

    await page.screenshot({ path: `../reports/layout/environment-tool-${scenario}.png`, fullPage: true })
    expect(socketCount).toBe(1)
    expect(threadReads).toBe(1)
    expect(treeReads).toBe(bootstrapTreeReads)
    expect(writes).toEqual([])
  })
}
