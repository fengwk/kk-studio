import { expect, test, type Page, type Route } from './fixture'

/**
 * 新建分支流程真实浏览器回归（独立基座、独立端口 5184）。
 *
 * 挂载真实 `ChatWorkspacePage`，后端全部由 `page.route` 提供。这里验证 jsdom 无法
 * 证明的行为：隐藏 pane 的真实布局显露、真实 `display:none`/`inert` 的 Debug 隐藏与
 * 退出恢复、以及 1..9 目标路由后目标 pane 的真实可见性与焦点。
 */

const CHAT_ID = 'chat-branch-1'
const THREAD_ID = 'a2000000-0000-4000-8000-0000000000a1'
const SESSION_ID = 'b2000000-0000-4000-8000-0000000000b1'
const HARNESS_URL = '/browser-tests/chat-branch-harness.html'

function chat() {
  return {
    id: CHAT_ID,
    title: 'Branch Chat',
    agentName: 'assistant',
    environment: null,
    yoloEnabled: false,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function entries() {
  return [
    { entryId: 'entry-1', sessionId: SESSION_ID, parentEntryId: null, entryType: 'ROOT', payloadJson: '{}', createTime: '2026-10-01T00:00:00Z' },
    { entryId: 'entry-2', sessionId: SESSION_ID, parentEntryId: 'entry-1', entryType: 'TURN_END', payloadJson: JSON.stringify({ outcome: 'COMPLETED' }), createTime: '2026-10-01T00:01:00Z' },
    {
      entryId: 'entry-3',
      sessionId: SESSION_ID,
      parentEntryId: 'entry-2',
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'hello' }] } }),
      createTime: '2026-10-01T00:02:00Z',
    },
  ]
}

function thread() {
  return {
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: SESSION_ID,
    headEntryId: 'entry-3',
    parentThreadId: null,
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
    },
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
  }
}

async function installChatApi(page: Page): Promise<{ commandBatches: unknown[] }> {
  const recorded = { commandBatches: [] as unknown[] }
  await page.routeWebSocket(/\/api\/.*events/, () => {})
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path === `/api/ai/chats/${CHAT_ID}`) {
      await route.fulfill({ json: { status: 200, data: chat() } })
      return
    }
    if (path === `/api/ai/chats/${CHAT_ID}/sessions`) {
      await route.fulfill({
        json: {
          status: 200,
          data: [{
            sessionId: SESSION_ID,
            name: 'Session One',
            createdAt: null,
            lastActivityAt: null,
            firstMessagePreview: null,
            threadCount: 1,
          }],
        },
      })
      return
    }
    if (path === '/api/ai/catalog/agents') {
      await route.fulfill({
        json: {
          status: 200,
          data: {
            pageNumber: 1,
            pageSize: 50,
            totalCount: 1,
            results: [{
              name: 'assistant',
              description: '通用助手',
              systemPrompt: null,
              model: 'minimax/MiniMax',
              variant: 'default',
              config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
              version: '1',
              createTime: null,
              updateTime: null,
            }],
          },
        },
      })
      return
    }
    if (path === '/api/ai/catalog/models') {
      await route.fulfill({
        json: {
          status: 200,
          data: {
            pageNumber: 1,
            pageSize: 50,
            totalCount: 1,
            results: [{
              providerName: 'minimax',
              name: 'MiniMax',
              description: null,
              config: {
                limit: { context: 128000, output: 8192 },
                abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
                defaultVariant: 'default',
                variants: [{ id: 'default' }],
              },
              version: '1',
              createTime: null,
              updateTime: null,
            }],
          },
        },
      })
      return
    }
    if (path === '/api/harness/environments') {
      await route.fulfill({ json: { status: 200, data: [] } })
      return
    }
    if (path === `/api/harness/sessions/${SESSION_ID}/entries`) {
      await route.fulfill({ json: { status: 200, data: entries() } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}`) {
      await route.fulfill({
        json: {
          status: 200,
          data: {
            version: '0',
            thread: thread(),
            entries: entries(),
            queuedCommands: [],
            modelInvocation: null,
            toolInvocations: [],
            modelAttemptFailures: [],
            manualCompaction: { available: false, disabledReason: 'not available' },
            stopReceipts: [],
          },
        },
      })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}/tree`) {
      await route.fulfill({ json: { status: 200, data: [] } })
      return
    }
    if (path.endsWith('/command-batches') && method === 'POST') {
      recorded.commandBatches.push(request.postDataJSON())
      await route.fulfill({ json: { status: 200, data: { accepted: true, thread: thread() } } })
      return
    }
    if (path === '/api/interactions') {
      await route.fulfill({ json: { status: 200, data: { items: [], nextCursor: null } } })
      return
    }
    if (path === '/api/harness/threads/model-request-debug' || path.endsWith('/model-request-debug')) {
      await route.fulfill({
        json: {
          status: 200,
          data: {
            kind: 'NEXT_REQUEST_PREVIEW',
            generatedAt: '2026-10-01T00:00:00Z',
            model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
            environmentName: null,
            systemInstruction: 'system prompt',
            tools: [],
            skills: [],
            subagents: [],
            cacheControl: null,
            planningError: null,
            frozenInvocation: null,
          },
        },
      })
      return
    }
    await route.fulfill({ json: { status: 200, data: {} } })
  })
  return recorded
}

async function bindFirstPaneToThread(page: Page) {
  await page.addInitScript(({ chatId, threadId }) => {
    window.localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${chatId}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId }),
    )
    window.localStorage.setItem(`kk-studio.chat-pane.${chatId}`, JSON.stringify({
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: Array.from({ length: 9 }, (_, index) => ({ id: `pane-${index + 1}` })),
    }))
  }, { chatId: CHAT_ID, threadId: THREAD_ID })
}

test.describe('新建分支命名与目标路由（真实浏览器）', () => {
  test('routes the /tree fork into a hidden pane, reveals it in the real grid and never pre-creates a Thread', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const recorded = await installChatApi(page)
    await bindFirstPaneToThread(page)
    await page.goto(HARNESS_URL)

    const composer = page.getByLabel('给 AI 发送消息').first()
    await composer.click()
    await composer.pressSequentially('/tree')
    await page.keyboard.press('Enter')

    const panel = page.locator('.history-tree-panel')
    await expect(panel).toBeVisible()
    const rows = panel.locator('.history-tree-entry')
    await expect(rows).toHaveCount(3)
    // 线性历史同 lane；只有真实分叉才开新 lane。
    await expect(rows.nth(2)).toHaveAttribute('data-lane', '0')

    await rows.nth(1).click()
    await expect(rows.nth(1)).toHaveAttribute('data-can-fork', 'true')
    await panel.locator('.history-tree-actions .btn-primary').click()

    const dialog = page.locator('.new-branch-dialog')
    await expect(dialog).toBeVisible()
    await dialog.locator('.new-branch-name').fill('browser-branch')
    await dialog.locator('.ui-select-trigger').click()
    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()
    await listbox.getByRole('option').nth(2).click()
    await dialog.locator('.new-branch-actions button[type="submit"]').click()

    // 隐藏的 pane-3 被真实布局显露：三个 Composer 都可见且 pane-3 落在视口内。
    await expect(page.locator('.chat-pane-grid.layout-split-3')).toBeVisible()
    await expect(page.getByLabel('给 AI 发送消息')).toHaveCount(3)
    const thirdBox = (await page.getByLabel('给 AI 发送消息').nth(2).boundingBox())!
    expect(thirdBox.x).toBeGreaterThan(0)
    expect(thirdBox.x + thirdBox.width).toBeLessThanOrEqual(1440)

    // 顶栏面包屑跟随聚焦 pane，并展示规范化后的分支名。
    const breadcrumb = page.locator('.chat-workspace-breadcrumb')
    await expect(breadcrumb).toHaveAttribute('data-focused-pane', 'pane-3')
    await expect(breadcrumb.locator('[data-breadcrumb="branch"]')).toHaveText('browser-branch')

    // 目标 pane 的本地草稿目标已写为 NEW_THREAD_DRAFT，但绝不预创建 Thread。
    const target = await page.evaluate(({ chatId }) =>
      window.localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${chatId}:pane-3`), { chatId: CHAT_ID })
    expect(target).toContain('NEW_THREAD_DRAFT')
    expect(target).toContain('browser-branch')
    expect(recorded.commandBatches).toEqual([])
  })

  test('Debug covers the pane with a real return action and restores the draft on exit', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await installChatApi(page)
    await bindFirstPaneToThread(page)
    await page.goto(HARNESS_URL)

    const composer = page.getByLabel('给 AI 发送消息').first()
    await composer.click()
    await composer.pressSequentially('/debug')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('listbox', { name: '事件' })).toBeVisible()

    const controlArea = page.locator('.thread-control-area').first()
    await expect(controlArea).toBeHidden()
    expect(await controlArea.evaluate((el) => window.getComputedStyle(el).display)).toBe('none')
    await expect(page.locator('.thread-debug-back')).toBeVisible()

    await page.locator('.thread-debug-back').click()
    await expect(page.getByRole('listbox', { name: '事件' })).toHaveCount(0)
    await expect(controlArea).toBeVisible()
    await expect(page.locator('.thread-debug-back')).toHaveCount(0)
  })
})
