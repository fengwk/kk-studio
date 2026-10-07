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
      updateTime: '2026-03-31T12:00:00.000Z',
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
      updateTime: '2026-03-31T12:00:00.000Z',
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
      updateTime: '2026-03-31T12:00:00.000Z',
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
      updateTime: '2026-03-31T12:00:00.000Z',
    },
  ]
}

function threadDto(
  threadId: string,
  parentThreadId: string | null,
  name: string,
  status: string,
) {
  return {
    name,
    threadId,
    sessionId: '50000000-0000-0000-0000-000000000001',
    headEntryId: 'e0000000-0000-0000-0000-00000000e001',
    parentThreadId,
    // 子代理的 YOLO 跟随执行根；根自己的策略仍是显式开关。
    yoloPolicy: parentThreadId == null
      ? { mode: 'DISABLE', rootThreadId: null }
      : { mode: 'FOLLOW', rootThreadId: THREAD_ID },
    nextCommandSequence: '1',
    version: '1',
    status,
    processing: status !== 'IDLE' && status !== 'STOPPED',
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'minimax-m2.7', variant: 'default' },
      environmentName: 'dev-node',
      goal: null,
    },
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
  }
}

function snapshot(threadId: string, parentThreadId: string | null, name: string, status: string) {
  const thread = threadDto(threadId, parentThreadId, name, status)
  return {
    version: thread.version,
    thread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    manualCompaction: { available: false, disabledReason: null },
    stopReceipts: [],
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
      await route.fulfill({
        json: { status: 200, data: snapshot(THREAD_ID, null, 'Preview Thread', 'IDLE') },
      })
      return
    }
    if (path === `/api/harness/threads/${CHILD_ID}`) {
      await route.fulfill({
        json: {
          status: 200,
          data: snapshot(CHILD_ID, THREAD_ID, 'Child Planner', 'TOOL_WAITING_APPROVAL'),
        },
      })
      return
    }
    if (path === `/api/harness/threads/${GRAND_ID}`) {
      await route.fulfill({
        json: { status: 200, data: snapshot(GRAND_ID, CHILD_ID, 'Grand Worker', 'MODEL_RUNNING') },
      })
      return
    }
    if (path === '/api/interactions') {
      await route.fulfill({ json: { status: 200, data: { items: [], nextCursor: null } } })
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

async function expectNoHorizontalOverflow(widget: ReturnType<Page['getByRole']>) {
  const metrics = await widget.evaluate((element) => ({
    widget: element.scrollWidth <= element.clientWidth + 1,
    list: Array.from(element.querySelectorAll('.active-thread-tree-list'))
      .every((list) => list.scrollWidth <= list.clientWidth + 1),
  }))
  expect(metrics).toEqual({ widget: true, list: true })
}

/**
 * 会话名由顶栏面包屑承载；这里校验 composer 设置来自绑定 Thread 的 branchSettings。
 */
async function expectBoundRootIdentity(page: Page) {
  await expect(page.getByRole('button', { name: 'Model 与 Variant' }))
    .toHaveText('minimax/minimax-m2.7 · default')
  await expect(page.getByRole('button', { name: '环境' })).toHaveText('dev-node')
}

/**
 * 活跃子代理树不需要任何开关：执行根面板自动展示正在处理的后代（含其祖先层级），
 * 根自身不出一行，点进后代是同一 pane 内观察而不是新开窗口。
 */
test('the active subagent tree renders automatically inside the bound pane and observes in place', async ({
  page,
}) => {
  const mock = await installAgentTreeMock(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/browser-tests/debug-preview-harness.html')
  await expectBoundRootIdentity(page)

  // 1. 自动出现：没有任何「Agent 关系」开关，也没有手动刷新按钮。
  await expect(page.getByRole('button', { name: 'Agent 关系' })).toHaveCount(0)
  await expect.poll(() => mock.treeCalls.length).toBeGreaterThan(0)
  const widget = page.locator('.active-thread-tree')
  await expect(widget).toBeVisible()

  // 2. 只有处理中的后代 + 其祖先层级；根自己与已停止的空闲兄弟都不出现。
  const rows = widget.locator('.active-thread-tree-row')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0)).toContainText('Child Planner')
  await expect(rows.nth(0)).toContainText('等待审批')
  await expect(rows.nth(1)).toContainText('Grand Worker')
  await expect(rows.nth(1)).toContainText('模型运行中')
  await expect(widget).not.toContainText('Preview Thread')
  await expect(widget).not.toContainText('Stopped Sibling')
  await expect(widget).toContainText('2 个活跃')
  await expectNoHorizontalOverflow(widget)
  await page.screenshot({ path: resolve(reportsDir, 'active-thread-tree-wide.png') })

  // 3. 窄宽度保持单行不横向溢出。
  await page.setViewportSize({ width: 954, height: 934 })
  await expectNoHorizontalOverflow(widget)
  await page.screenshot({ path: resolve(reportsDir, 'active-thread-tree-narrow.png') })

  // 4. 点进后代：同一 pane 内观察（没有新窗口），URL 不变，可逐层返回。
  await page.setViewportSize({ width: 1440, height: 900 })
  const popupOpened = { value: false }
  page.on('popup', () => {
    popupOpened.value = true
  })
  await widget.getByRole('link', { name: /Child Planner/ }).click()
  await expect(page.getByText('只读查看')).toBeVisible()
  await expect(page.getByRole('button', { name: '返回父 agent' })).toBeVisible()
  await expect(page).toHaveURL(/debug-preview-harness/)
  expect(popupOpened.value).toBe(false)

  await page.getByRole('button', { name: '返回父 agent' }).click()
  await expectBoundRootIdentity(page)
  await expect(widget).toBeVisible()
})
