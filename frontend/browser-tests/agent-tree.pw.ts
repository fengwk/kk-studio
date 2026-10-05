import { resolve } from 'node:path'
import { expect, test, type Page, type Route } from './fixture'

const reportsDir = resolve(new URL('.', import.meta.url).pathname, '../../reports/layout')
const THREAD_ID = 'f0000000-0000-0000-0000-00000000f001'
const CHILD_ID = 'f0000000-0000-0000-0000-00000000f002'
const GRAND_ID = 'f0000000-0000-0000-0000-00000000f003'
const SIBLING_ID = 'f0000000-0000-0000-0000-00000000f004'
const LONG_MODEL = 'claude-opus-very-long-model-name-that-must-stay-inside-the-pane'

function treeNodes(turnCount = 3) {
  return [
    {
      threadId: SIBLING_ID,
      parentThreadId: THREAD_ID,
      name: 'Stopped Sibling',
      agentName: 'reviewer',
      model: { providerName: 'anthropic', modelName: LONG_MODEL, variant: 'fast' },
      status: 'IDLE',
      processing: false,
      turnCount: 1,
      toolCallCount: 0,
      outcome: 'STOPPED',
    },
    {
      threadId: GRAND_ID,
      parentThreadId: CHILD_ID,
      name: 'Grand Worker',
      agentName: 'coder',
      model: { providerName: 'minimax', modelName: 'MiniMax-M2', variant: 'default' },
      status: 'MODEL_RUNNING',
      processing: true,
      turnCount: 4,
      toolCallCount: 5,
      outcome: null,
    },
    {
      threadId: CHILD_ID,
      parentThreadId: THREAD_ID,
      name: 'Child Planner',
      agentName: 'planner',
      model: { providerName: 'minimax', modelName: 'MiniMax-M2', variant: 'default' },
      status: 'TOOL_WAITING_APPROVAL',
      processing: true,
      turnCount,
      toolCallCount: 2,
      outcome: null,
    },
    {
      threadId: THREAD_ID,
      parentThreadId: null,
      name: 'Preview Thread',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'minimax-m2.7', variant: 'default' },
      // 本地状态：根自己空闲，不递归投影子树忙碌。
      status: 'IDLE',
      processing: false,
      turnCount: 6,
      toolCallCount: 3,
      outcome: null,
    },
  ]
}

function snapshot() {
  return {
    status: 200,
    data: {
      version: '1',
      thread: {
        threadId: THREAD_ID,
        name: 'Preview Thread',
        sessionId: '50000000-0000-0000-0000-000000000001',
        headEntryId: 'e0000000-0000-0000-0000-00000000e001',
        parentThreadId: null,
        yoloEnabled: false,
        nextCommandSequence: '1',
        version: '1',
        status: 'IDLE',
        processing: false,
        executionControl: 'RUNNABLE',
        branchSettings: {
          agentName: 'assistant',
          model: { providerName: 'minimax', modelName: 'minimax-m2.7', variant: 'default' },
          environmentName: 'dev-node',
          goal: null,
        },
        createTime: '2026-10-01T00:00:00Z',
        updateTime: '2026-10-01T00:00:00Z',
      },
      entries: [],
      queuedCommands: [],
      modelInvocation: null,
      toolInvocations: [],
      modelAttemptFailures: [],
      manualCompaction: { available: false, disabledReason: null },
      stopReceipts: [],
    },
  }
}

async function installAgentTreeMock(page: Page) {
  const treeCalls: string[] = []
  let nodes = treeNodes()
  await page.routeWebSocket(/\/api\/events\/v1$/, () => {})
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/harness/threads/${THREAD_ID}/tree`) {
      treeCalls.push(path)
      await route.fulfill({ json: { status: 200, data: nodes } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}`) {
      await route.fulfill({ json: snapshot() })
      return
    }
    if (path === '/api/ai/catalog/agents' || path === '/api/ai/catalog/models') {
      await route.fulfill({
        json: { status: 200, data: { pageNumber: 1, pageSize: 50, totalCount: 0, results: [] } },
      })
      return
    }
    await route.fulfill({ json: { status: 200, data: {} } })
  })
  return {
    treeCalls,
    updateTurns(turnCount: number) {
      nodes = treeNodes(turnCount)
    },
  }
}

async function expectNoHorizontalOverflow(panel: ReturnType<Page['getByRole']>) {
  const metrics = await panel.evaluate((element) => {
    const list = element.querySelector('.thread-agent-tree-list')
    return {
      panel: element.scrollWidth <= element.clientWidth + 1,
      list: !(list instanceof HTMLElement) || list.scrollWidth <= list.clientWidth + 1,
    }
  })
  expect(metrics).toEqual({ panel: true, list: true })
}

/**
 * 本地 IDLE 的根仍有活跃后代：一个 Stop 必须停止整棵子树，并把未消费的人类输入
 * 退回各自的持久草稿（根的输入回到可见 composer），且请求体不带任何产品 owner/target。
 */
test('an idle root can still stop its running subtree in one owner-free request', async ({ page }) => {
  const stopRequests: Array<{ stopRequestId?: string; expectedVersion?: string }> = []
  const commandBatches: Array<Record<string, unknown>> = []
  const cancelledText = '先澄清一下接口契约'
  let stopped = false

  await page.routeWebSocket(/\/api\/events\/v1$/, () => {})
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path === `/api/harness/threads/${THREAD_ID}/stop` && method === 'POST') {
      stopRequests.push(route.request().postDataJSON())
      stopped = true
      await route.fulfill({
        json: {
          status: 200,
          data: {
            status: 'STOPPED',
            // 请求目标自己的权威投影：本地阶段变 STOPPED，执行控制已被停止。
            thread: { ...snapshot().data.thread, status: 'STOPPED', executionControl: 'STOPPED', version: '2' },
            stoppedThreads: [
              {
                threadId: THREAD_ID,
                stopRequestId: route.request().postDataJSON().stopRequestId,
                stoppedTurnEndEntryId: null,
                cancelledCommandCount: 1,
                cancelledInputs: [
                  {
                    sequence: '1',
                    idempotencyKey: 'cmd-root-1',
                    type: 'USER_MESSAGE',
                    payloadJson: JSON.stringify({
                      message: { role: 'USER', contents: [{ type: 'TEXT', text: cancelledText }] },
                    }),
                  },
                ],
              },
              {
                threadId: CHILD_ID,
                stopRequestId: route.request().postDataJSON().stopRequestId,
                stoppedTurnEndEntryId: null,
                cancelledCommandCount: 1,
                cancelledInputs: [
                  {
                    sequence: '2',
                    idempotencyKey: 'cmd-child-1',
                    type: 'USER_MESSAGE',
                    payloadJson: JSON.stringify({
                      message: { role: 'USER', contents: [{ type: 'TEXT', text: '子 Thread 自己的草稿' }] },
                    }),
                  },
                ],
              },
            ],
          },
        },
      })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}/command-batches` && method === 'POST') {
      commandBatches.push(route.request().postDataJSON())
      await route.fulfill({ json: { status: 200, data: { accepted: true, thread: snapshot().data.thread } } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}/tree`) {
      await route.fulfill({ json: { status: 200, data: treeNodes() } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}`) {
      await route.fulfill({ json: snapshot() })
      return
    }
    if (path === '/api/ai/catalog/agents' || path === '/api/ai/catalog/models') {
      await route.fulfill({ json: { status: 200, data: { pageNumber: 1, pageSize: 50, totalCount: 0, results: [] } } })
      return
    }
    await route.fulfill({ json: { status: 200, data: {} } })
  })

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/browser-tests/debug-preview-harness.html')
  await expect(page.getByRole('heading', { name: 'Preview Thread' })).toBeVisible()

  // 1. 根是本地的空闲（子树在跑），关系树里子 Thread 仍是运行中。
  const toggle = page.getByRole('button', { name: 'Agent 关系' })
  await toggle.click()
  const panel = page.getByRole('region', { name: 'Agent 关系' })
  const rows = panel.locator('.thread-agent-tree-row')
  await expect(rows.nth(0)).toContainText('空闲')
  await expect(rows.nth(1)).toContainText('Child Planner')
  await expect(rows.nth(1)).toContainText('等待审批')

  // 2. 本地 IDLE 不隐藏 Stop：composer 的 /stop 命令可用并提交 owner-free 请求。
  const composer = page.locator('.thread-composer')
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('/stop')
  const palette = composer.locator('.thread-command-palette')
  await expect(palette).toBeVisible()
  const stopItem = palette.locator('button', { hasText: 'stop' })
  await expect(stopItem).not.toHaveAttribute('disabled', '')
  await stopItem.click()

  // 3. 请求体只有精确重放所需的 Stop 身份，没有 owner/target。
  await expect.poll(() => stopRequests.length).toBe(1)
  expect(Object.keys(stopRequests[0] ?? {}).sort()).toEqual(['expectedVersion', 'stopRequestId'])
  expect(stopRequests[0]?.stopRequestId).toBeTruthy()
  expect(stopRequests[0]?.expectedVersion).toBe('1')
  await expect.poll(() => stopped).toBe(true)

  // 4. 根自己的未消费输入被回退到可见草稿；子 Thread 的输入只写它自己的记录。
  await expect(editor).toContainText(cancelledText)
  expect(commandBatches).toEqual([])
  await page.screenshot({ path: resolve(reportsDir, 'idle-root-stop-subtree.png') })
})

test('agent relationship tree stays inside the bound pane and opens exact threads', async ({ page }) => {
  // 真实 AgentPane 默认不读关系树；打开后展示主、子、孙和终态，跳转不改变当前绑定。
  const mock = await installAgentTreeMock(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/browser-tests/debug-preview-harness.html')
  const toggle = page.getByRole('button', { name: 'Agent 关系' })
  await expect(toggle).toBeVisible()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(mock.treeCalls).toEqual([])

  await toggle.click()
  const panel = page.getByRole('region', { name: 'Agent 关系' })
  await expect(panel).toBeVisible()
  await expect.poll(() => mock.treeCalls.length).toBe(1)
  const rows = panel.locator('.thread-agent-tree-row')
  await expect(rows).toHaveCount(4)
  await expect(rows.nth(0)).toContainText('Preview Thread')
  // 父 Thread 只展示自己的本地阶段：子树忙碌不再把根写成「等待子 Thread」。
  await expect(rows.nth(0)).toContainText('空闲')
  await expect(rows.nth(0)).toContainText('6 回合')
  await expect(rows.nth(1)).toContainText('Child Planner')
  await expect(rows.nth(1)).toContainText('等待审批')
  await expect(rows.nth(1)).toContainText('2 次工具调用')
  await expect(rows.nth(2)).toContainText('Grand Worker')
  await expect(rows.nth(2)).toContainText('模型运行中')
  await expect(rows.nth(2)).toContainText('5 次工具调用')
  await expect(rows.nth(3)).toContainText('Stopped Sibling')
  await expect(rows.nth(3)).toContainText('已停止')
  await expect(rows.nth(3)).toContainText(`anthropic/${LONG_MODEL}/fast`)
  await expect(panel).toHaveAttribute('data-thread-id', THREAD_ID)
  await expectNoHorizontalOverflow(panel)
  await page.screenshot({ path: resolve(reportsDir, 'agent-tree-wide.png') })

  mock.updateTurns(9)
  await panel.getByRole('button', { name: '刷新' }).click()
  await expect.poll(() => mock.treeCalls.length).toBe(2)
  await expect(rows.nth(1)).toContainText('9 回合')

  const popupPromise = page.waitForEvent('popup')
  await panel.getByRole('link', { name: 'Grand Worker' }).click()
  const popup = await popupPromise
  await expect(popup).toHaveURL(new RegExp(`/threads/${GRAND_ID}$`))
  await popup.close()
  await expect(page).toHaveURL(/debug-preview-harness/)
  await expect(page.getByRole('heading', { name: 'Preview Thread' })).toBeVisible()
  await expect(panel).toHaveAttribute('data-thread-id', THREAD_ID)

  await page.setViewportSize({ width: 954, height: 934 })
  await expectNoHorizontalOverflow(panel)
  await page.screenshot({ path: resolve(reportsDir, 'agent-tree-narrow.png') })

  const callsBeforeClose = mock.treeCalls.length
  await toggle.click()
  await expect(panel).toHaveCount(0)
  await page.clock.install()
  await page.clock.fastForward(12_000)
  expect(mock.treeCalls.length).toBe(callsBeforeClose)
})
