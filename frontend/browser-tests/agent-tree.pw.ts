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
      status: 'WAITING_CHILDREN',
      processing: true,
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
        status: 'WAITING_CHILDREN',
        processing: true,
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
  await expect(rows.nth(0)).toContainText('等待子 Thread')
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
