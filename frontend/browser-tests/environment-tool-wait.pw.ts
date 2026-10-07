import type { WebSocketRoute } from '@playwright/test'
import { expect, test } from './fixture'

const ROOT = 'a1000000-0000-4000-8000-0000000000a1'
const CHILD = 'a1000000-0000-4000-8000-0000000000a2'
const OTHER = 'a1000000-0000-4000-8000-000000000099'

// 真实 AgentPane/readonly 查询、timeline、工具卡与共享事件连接；仅替换 HTTP/WS 服务。
for (const scenario of ['pane-root', 'pane-child']) {
  test(`${scenario}: frozen wait, same-version wire refresh, renewed single deadline and no writes`, async ({ page }) => {
    await page.clock.install()
    const now = await page.evaluate(() => Date.now())
    const id = scenario === 'pane-root' ? ROOT : CHILD
    let freshnessAt = (now + 10000) / 1000
    let waiting = true
    let status = 'READY'
    let reads = 0
    let socket: WebSocketRoute | undefined
    let socketCount = 0
    const subscriptions = new Set<string>()
    const writes: string[] = []
    await page.routeWebSocket(/\/api\/events\/v1$/, (ws) => {
      socket = ws
      socketCount += 1
      ws.onMessage((raw) => {
        const message = JSON.parse(String(raw))
        if (message.type === 'subscribe') subscriptions.add(message.resource.kind)
      })
    })
    await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() !== 'GET') writes.push(path)
      let data: unknown = { results: [], totalCount: 0, pageNumber: 1, pageSize: 50 }
      if (path === `/api/harness/threads/${id}`) {
        reads += 1
        data = {
          version: '1',
          thread: {
            threadId: id, name: '等待工具 Thread', sessionId: 'session-wait', headEntryId: 'assistant-wait',
            parentThreadId: id === ROOT ? null : ROOT,
            yoloPolicy: { mode: id === ROOT ? 'DISABLE' : 'FOLLOW', rootThreadId: id === ROOT ? null : ROOT },
            nextCommandSequence: '1', version: '1', status: 'TOOL_READY', processing: true,
            executionControl: 'RUNNABLE',
            branchSettings: { agentName: 'assistant',
              model: { providerName: 'minimax', modelName: 'VeryLongModelName', variant: 'default' },
              environmentName: null, goal: null },
            createTime: now / 1000, updateTime: now / 1000,
          },
          entries: [{
            entryId: 'assistant-wait', parentEntryId: null, sessionId: 'session-wait',
            entryType: 'MESSAGE', createTime: now / 1000,
            payloadJson: JSON.stringify({ message: { role: 'ASSISTANT', contents: [{
              type: 'tool_call', toolCallId: 'call-wait', toolName: 'bash', rendererKey: 'bash',
              argumentsJson: '{"command":"pwd","workdir":"/srv/frozen-workdir"}',
            }] } }),
          }],
          queuedCommands: [], modelInvocation: null, modelAttemptFailures: [], stopReceipts: [],
          manualCompaction: { available: false, disabledReason: null },
          toolInvocations: [{
            id: 'inv-wait', modelInvocationId: 'model-wait', assistantEntryId: 'assistant-wait', callIndex: 0,
            status, attempt: 1, toolCallId: 'call-wait', toolName: 'bash', rendererKey: 'bash',
            environmentId: null, requiredEnvironmentId: 'frozen-env', requiredEnvironmentName: 'archlinux',
            waitingForEnvironment: waiting, environmentWaitFreshnessAt: freshnessAt,
            argumentsJson: '{"command":"pwd","workdir":"/srv/frozen-workdir"}',
            approvalJson: null, resultJson: null, errorJson: null, createTime: now / 1000, updateTime: now / 1000,
          }],
        }
      } else if (path === '/api/interactions') {
        data = { items: [{
          type: 'APPROVAL', interactionId: 'original-approval', status: 'WAITING_APPROVAL',
          threadId: CHILD, rootThreadId: ROOT, sessionId: 'session-wait',
          owner: { type: 'CHAT', chatId: 'chat-wait', chatTitle: null,
            issueId: null, issueTitle: null, agentName: null, rootThreadName: '等待工具 Thread' },
          toolCallId: 'approval-call', toolName: 'bash',
          argumentsJson: '{"command":"deploy --dry-run","workdir":"/srv/approval-workdir"}',
          approvalJson: '{"required":true,"reason":"Permission rules require approval"}',
          environmentId: null, environmentName: null, waitingCount: null, createTime: now / 1000,
        }], total: 1, nextCursor: null, freshnessAt: null }
      } else if (path.endsWith('/tree')) {
        data = [ROOT, CHILD].map((threadId) => ({
          threadId, parentThreadId: threadId === ROOT ? null : ROOT,
          name: threadId === ROOT ? '等待工具 Thread' : 'worker',
          agentName: 'assistant', model: { providerName: 'minimax', modelName: 'VeryLongModelName', variant: 'default' },
          status: 'IDLE', processing: false, turnCount: 1, toolCallCount: 1,
          outcome: null, updateTime: now / 1000,
        }))
      } else if (path === '/api/harness/environments') {
        data = []
      }
      await route.fulfill({ json: { status: 200, data } })
    })
    await page.goto(`/browser-tests/pane-control-harness.html?scenario=${scenario}`)
    const card = page.locator('.thread-block-tool')
    await expect(card).toHaveAttribute('data-invocation-state', 'environment')
    await expect(card.locator('.thread-tool-summary')).toContainText('bash · 等待环境 archlinux 上线')
    await expect(card.locator('.animate-spin')).toHaveCount(0)
    await expect(page.locator('.thread-working')).toContainText('等待环境 archlinux 上线')
    await expect(page.locator('.thread-status-footer')).not.toContainText('等待环境')
    if (scenario === 'pane-root') {
      const approval = page.locator('.interaction-feed-item')
      await expect(approval.locator('.interaction-source-link')).toContainText('查看 subagent 执行')
      await expect(approval.locator('.interaction-source-link')).toContainText('worker')
      await expect(approval.locator('.interaction-source-link')).toHaveAttribute('href', `/threads/${CHILD}`)
      await expect(approval).not.toContainText(CHILD)
      await expect(approval.locator('.interaction-deny-btn')).toHaveClass(/btn-primary danger/)
      await expect(approval.locator('pre')).toHaveCount(1)
      await expect(approval.locator('pre')).toHaveText('{"command":"deploy --dry-run","workdir":"/srv/approval-workdir"}')
      expect((await approval.textContent())?.split('/srv/approval-workdir')).toHaveLength(2)
      await expect(approval).toContainText('权限规则要求审批')
      await expect(approval.locator('.interaction-status-tag, time')).toHaveCount(0)
    }
    await page.screenshot({ path: `../reports/layout/environment-tool-${scenario}-waiting.png`, fullPage: true })
    await expect.poll(() => subscriptions.has('interactions')).toBe(true)
    expect(socketCount).toBe(1)
    expect(reads).toBe(1)
    if (scenario === 'pane-child') {
      await expect(page.locator('.thread-control-area')).toHaveCount(0)
      await expect(page.locator('.thread-composer')).toHaveCount(0)
    }
    const changed = (rootThreadId: string) => socket!.send(JSON.stringify({
      version: 1, type: 'event', resource: { kind: 'interactions' }, name: 'changed', data: { rootThreadId },
    }))
    // 错根提示不触发 snapshot GET；让浏览器完成事件调度而非任意 sleep。
    changed(OTHER)
    await page.clock.runFor(500)
    expect(reads).toBe(1)
    freshnessAt = (now + 20000) / 1000
    changed(ROOT)
    await expect.poll(() => reads).toBe(2)
    await page.clock.runFor(10250)
    expect(reads).toBe(2)
    await page.clock.runFor(10000)
    await expect.poll(() => reads).toBe(3)
    await page.clock.runFor(60000)
    expect(reads).toBe(3)
    // 同 durable version 的环境恢复也回读；工具本身开始执行才出现 spinner。
    waiting = false
    status = 'RUNNING'
    changed(ROOT)
    await expect(card).toHaveAttribute('data-invocation-state', 'running')
    await expect(card.locator('.animate-spin')).toHaveCount(1)
    await expect(card.locator('.thread-tool-summary')).not.toContainText('等待环境')
    expect(writes).toEqual([])
    await page.screenshot({ path: `../reports/layout/environment-tool-${scenario}.png`, fullPage: true })
  })
}
