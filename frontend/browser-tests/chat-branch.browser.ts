import { expect, test, type Page, type Route } from './fixture'
import type { WebSocketRoute } from '@playwright/test'

/**
 * 新建分支流程真实浏览器回归（独立基座、独立端口 5184）。
 *
 * 挂载真实 `ChatWorkspacePage`，后端全部由 `page.route` 提供。这里验证 jsdom 无法
 * 证明的行为：隐藏 pane 的真实布局显露、真实 `display:none`/`inert` 的 Debug 隐藏与
 * 退出恢复、以及 1..9 目标路由后目标 pane 的真实可见性与焦点。
 */

const CHAT_ID = 'chat-branch-1'
const THREAD_ID = 'a2000000-0000-4000-8000-0000000000a1'
const CHILD_ID = 'a2000000-0000-4000-8000-0000000000c1'
const GRANDCHILD_ID = 'a2000000-0000-4000-8000-0000000000d1'
const SESSION_ID = 'b2000000-0000-4000-8000-0000000000b1'
const HARNESS_URL = '/browser-tests/chat-branch-harness.html'
const rootPayload = JSON.stringify({ settings: {
  agentName: 'assistant', model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
  environmentName: null, goal: null,
} })

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

function entries(options: { usage?: boolean } = {}) {
  if (options.usage) {
    // 已关闭回合的真实 usage 事实：ASSISTANT 的 assistantMetadata + 读取投影 usageCost，
    // 后面紧跟 TURN_END，使 conversation 渲染出带 turnUsage 的回合 footer。
    return [
      { entryId: 'entry-1', sessionId: SESSION_ID, parentEntryId: null, entryType: 'ROOT', payloadJson: rootPayload, createTime: '2026-10-01T00:00:00Z' },
      {
        entryId: 'entry-assistant',
        sessionId: SESSION_ID,
        parentEntryId: 'entry-1',
        entryType: 'MESSAGE',
        payloadJson: JSON.stringify({
          message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'done' }] },
          assistantMetadata: {
            usage: {
              inputTokens: 1200,
              outputTokens: 340,
              reasoningTokens: 7,
              cacheReadTokens: 0,
              cacheWriteTokens: 0,
              providerTotalTokens: 1547,
            },
            decodeDurationMillis: 1000,
          },
        }),
        usageCost: { currency: 'USD', amount: '0.0012' },
        createTime: '2026-10-01T00:01:00Z',
      },
      {
        entryId: 'entry-turn',
        sessionId: SESSION_ID,
        parentEntryId: 'entry-assistant',
        entryType: 'TURN_END',
        payloadJson: JSON.stringify({ outcome: 'COMPLETED' }),
        createTime: '2026-10-01T00:02:00Z',
      },
    ]
  }
  return [
    { entryId: 'entry-1', sessionId: SESSION_ID, parentEntryId: null, entryType: 'ROOT', payloadJson: rootPayload, createTime: '2026-10-01T00:00:00Z' },
    {
      entryId: 'entry-ancestor', sessionId: SESSION_ID, parentEntryId: 'entry-1', entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'Real selected ancestor' }] } }),
      createTime: '2026-10-01T00:00:30Z',
    },
    { entryId: 'entry-2', sessionId: SESSION_ID, parentEntryId: 'entry-ancestor', entryType: 'TURN_END', payloadJson: JSON.stringify({ outcome: 'COMPLETED' }), createTime: '2026-10-01T00:01:00Z' },
    {
      entryId: 'entry-3',
      sessionId: SESSION_ID,
      parentEntryId: 'entry-2',
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'hello' }] } }),
      createTime: '2026-10-01T00:02:00Z',
    },
    {
      entryId: 'entry-sibling', sessionId: SESSION_ID, parentEntryId: 'entry-1', entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'Sibling must stay hidden' }] } }),
      createTime: '2026-10-01T00:02:30Z',
    },
  ]
}

function thread(headEntryId = 'entry-3') {
  return {
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: SESSION_ID,
    headEntryId,
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

interface Recorded {
  commandBatches: unknown[]
  branchPreviews: Array<{ startEntryId: string; commands: Array<Record<string, unknown>> }>
  treeReads: number
  completeGrandchild: () => void
}

async function installChatApi(
  page: Page,
  options: { usageEntries?: boolean; conflictFirstSend?: boolean; longHistory?: boolean } = {},
): Promise<Recorded> {
  const entryList = entries({ usage: options.usageEntries })
  if (options.longHistory) {
    entryList.push(...Array.from({ length: 24 }, (_, index) => ({
      entryId: `scroll-${index}`, sessionId: SESSION_ID,
      parentEntryId: index === 0 ? 'entry-3' : `scroll-${index - 1}`, entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{
        type: 'text', text: `Scroll marker ${index}\n${Array(12).fill('Reading position is retained.').join('\n')}`,
      }] } }),
      createTime: `2026-10-01T00:03:${String(index).padStart(2, '0')}Z`,
    })))
  }
  const boundThread = thread(options.longHistory ? 'scroll-23' : options.usageEntries ? 'entry-turn' : 'entry-3')
  let createdThread: ReturnType<typeof thread> | null = null
  let grandCompleted = false
  const sockets: WebSocketRoute[] = []
  const recorded: Recorded = {
    commandBatches: [], branchPreviews: [], treeReads: 0,
    completeGrandchild: () => {
      grandCompleted = true
      for (const socket of sockets) {
        socket.send(JSON.stringify({
          version: 1, type: 'event', resource: { kind: 'thread', id: GRANDCHILD_ID },
          name: 'version', data: { version: '1' }, cursor: '1',
        }))
        socket.send(JSON.stringify({
          version: 1, type: 'event', resource: { kind: 'tree', id: THREAD_ID },
          name: 'changed', data: {},
        }))
      }
    },
  }
  await page.routeWebSocket(/\/api\/.*events/, (socket) => {
    sockets.push(socket)
    socket.onMessage((message) => {
      const frame = JSON.parse(String(message))
      if (frame.type === 'subscribe') {
        socket.send(JSON.stringify({ version: 1, type: 'subscribed', resource: frame.resource, cursor: '0' }))
      }
    })
  })
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
              modelId: 'MiniMax-upstream',
              description: null,
              config: {
                limit: { context: 128000, output: 8192 },
                abilities: { tools: true, reasoning: true, inputModalities: ['TEXT'] },
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'deep', reasoningEffort: 'high' }],
                pricing: {
                  currency: 'USD', pricingTier: 'default', serviceTier: 'default', serviceTierMultiplier: 1,
                  version: '1', inputPerMillionTokens: 1, outputPerMillionTokens: 1,
                  cacheReadPerMillionTokens: 1, cacheWritePerMillionTokens: 1,
                  cacheWriteLongPerMillionTokens: 1, reasoningPerMillionTokens: 1,
                },
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
      await route.fulfill({ json: { status: 200, data: entryList } })
      return
    }
    if (path === `/api/harness/sessions/${SESSION_ID}/threads`) {
      await route.fulfill({ json: { status: 200, data: [{
        threadId: THREAD_ID, parentThreadId: null, name: boundThread.name,
        createdAt: boundThread.createTime, updatedAt: boundThread.updateTime, status: 'IDLE',
        processing: false, model: boundThread.branchSettings.model, headMessagePreview: 'hello',
      }] } })
      return
    }
    if (path === `/api/harness/sessions/${SESSION_ID}/provider-request-preview` && method === 'POST') {
      // 本地分支草稿预检：只读会话前缀 + 命令，回包是最终请求体预览。
      const body = request.postDataJSON() as {
        startEntryId: string
        commands: Array<Record<string, unknown>>
      }
      recorded.branchPreviews.push(body)
      await route.fulfill({
        json: {
          status: 200,
          data: {
            kind: 'DRAFT_REQUEST_PREVIEW',
            providerType: 'openai-compatible',
            modelName: 'MiniMax',
            bodyByteSize: 128,
            bodyJson: JSON.stringify({ model: 'MiniMax', draft: 'branch draft preview' }, null, 2),
            sourceHeadEntryId: body.startEntryId,
            generatedAt: '2026-10-01T00:03:00Z',
          },
        },
      })
      return
    }
    if (path === `/api/harness/threads/${CHILD_ID}` || path === `/api/harness/threads/${GRANDCHILD_ID}`) {
      const grandchild = path.endsWith(GRANDCHILD_ID)
      await route.fulfill({ json: { status: 200, data: {
        version: grandchild && grandCompleted ? '1' : '0',
        thread: { ...boundThread,
          threadId: grandchild ? GRANDCHILD_ID : CHILD_ID,
          name: grandchild ? 'main' : 'direct parent',
          version: grandchild && grandCompleted ? '1' : '0',
          status: grandchild && !grandCompleted ? 'MODEL_STREAM' : 'IDLE',
          processing: grandchild && !grandCompleted,
          headEntryId: grandchild && grandCompleted ? 'entry-background' : boundThread.headEntryId,
          parentThreadId: grandchild ? CHILD_ID : THREAD_ID,
          yoloPolicy: { mode: 'FOLLOW', rootThreadId: THREAD_ID },
          branchSettings: { ...boundThread.branchSettings, agentName: grandchild ? 'researcher' : 'worker',
            model: { providerName: 'minimax', modelName: 'MiniMax', variant: grandchild ? 'deep' : 'default' } },
        },
        entries: [
          ...entryList.filter((entry) => entry.entryId !== 'entry-sibling'),
          ...(grandchild && grandCompleted ? [{
            entryId: 'entry-background', sessionId: SESSION_ID, parentEntryId: boundThread.headEntryId, entryType: 'MESSAGE',
            payloadJson: JSON.stringify({ message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'Background completion persisted' }] } }),
            createTime: '2026-10-01T00:04:00Z',
          }] : []),
        ], queuedCommands: [],
        modelInvocation: null, toolInvocations: [], modelAttemptFailures: [],
        manualCompaction: { available: false, disabledReason: 'readonly' }, stopReceipts: [],
      } } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}` || (createdThread != null && path === `/api/harness/threads/${createdThread.threadId}`)) {
      const isCreated = createdThread != null && path.endsWith(createdThread.threadId)
      await route.fulfill({
        json: {
          status: 200,
          data: {
            version: '0',
            thread: isCreated ? createdThread : boundThread,
            entries: isCreated ? entryList.filter((entry) => ['entry-1', 'entry-ancestor', 'entry-2'].includes(entry.entryId)) : entryList.filter((entry) => entry.entryId !== 'entry-sibling'),
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
      recorded.treeReads += 1
      await route.fulfill({ json: { status: 200, data: [
        { threadId: THREAD_ID, parentThreadId: null, name: boundThread.name, agentName: 'assistant',
          model: boundThread.branchSettings.model, status: 'IDLE', processing: false,
          turnCount: 1, toolCallCount: 0, outcome: 'COMPLETED', updateTime: '2026-10-01T00:00:00Z' },
        { threadId: CHILD_ID, parentThreadId: THREAD_ID, name: 'direct parent', agentName: 'worker',
          model: boundThread.branchSettings.model, status: 'IDLE', processing: false,
          turnCount: 2, toolCallCount: 3, outcome: 'COMPLETED', updateTime: '2026-10-01T00:01:00Z' },
        { threadId: GRANDCHILD_ID, parentThreadId: CHILD_ID, name: 'nested grandchild', agentName: 'researcher',
          model: { ...boundThread.branchSettings.model, variant: 'deep' },
          status: grandCompleted ? 'IDLE' : 'MODEL_STREAM', processing: !grandCompleted,
          turnCount: 4, toolCallCount: 5, outcome: grandCompleted ? 'COMPLETED' : null, updateTime: '2026-10-01T00:02:00Z' },
      ] } })
      return
    }
    if (path.endsWith('/command-batches') && method === 'POST') {
      recorded.commandBatches.push(request.postDataJSON())
      if (options.conflictFirstSend && recorded.commandBatches.length === 1) {
        await route.fulfill({ status: 409, json: { status: 409, code: 'THREAD_NAME_CONFLICT', message: 'name conflict', errors: { reason: 'THREAD_NAME_CONFLICT' } } })
        return
      }
      const body = request.postDataJSON() as { target: { threadId: string; threadName: string; startEntryId: string } }
      createdThread = { ...boundThread, threadId: body.target.threadId, name: body.target.threadName, headEntryId: body.target.startEntryId }
      await route.fulfill({ json: { status: 200, data: {
        session: { sessionId: SESSION_ID, name: 'Session One', createdAt: null },
        rootEntry: entryList[0], thread: createdThread, acceptedCommands: [], replayed: false,
      } } })
      return
    }
    if (path === '/api/interactions') {
      await route.fulfill({ json: { status: 200, data: { items: [], nextCursor: null, total: 0, freshnessAt: null } } })
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

/** 单 pane 的本地分支草稿目标：会话与分叉点已定，Thread 尚未创建。 */
async function bindFirstPaneToBranchDraft(page: Page, startEntryId: string) {
  await page.addInitScript(({ chatId, sessionId, startEntry }) => {
    window.localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${chatId}:pane-1`,
      JSON.stringify({
        kind: 'NEW_THREAD_DRAFT',
        sessionId,
        startEntryId: startEntry,
        threadName: 'browser-draft',
      }),
    )
    window.localStorage.setItem(`kk-studio.chat-pane.${chatId}`, JSON.stringify({
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: Array.from({ length: 9 }, (_, index) => ({ id: `pane-${index + 1}` })),
    }))
  }, { chatId: CHAT_ID, sessionId: SESSION_ID, startEntry: startEntryId })
}

/**
 * 本地分支草稿的 Debug 预检：整 pane 只读覆盖 + 会话级 preview + 可见退出，
 * 且绝不为了预览创建 Thread。这里验证 jsdom 无法证明的真实布局与真实 display:none。
 */
test('the local branch draft Debug previews through the session endpoint without creating a Thread', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 720, height: 820 })
  const recorded = await installChatApi(page)
  await bindFirstPaneToBranchDraft(page, 'entry-2')
  await page.goto(HARNESS_URL)

  // 真实用户路径：斜杠命令只在空草稿生效，所以先写草稿、再从命令表进入 Debug。
  const composer = page.getByLabel('给 AI 发送消息').first()
  await composer.click()
  await composer.pressSequentially('branch draft preview')
  await page.getByRole('button', { name: '打开命令表' }).click()
  const palette = page.locator('.thread-command-palette')
  await expect(palette).toBeVisible()
  await palette.getByRole('option', { name: /debug/ }).click()
  await page.getByRole('tab', { name: '事件', exact: true }).click()
  await expect(page.getByRole('listbox', { name: '事件' })).toBeVisible()

  // 整 pane 只读覆盖：控制区保持挂载但真实 display:none，退出入口可见，草稿原样保留。
  const controlArea = page.locator('.thread-control-area').first()
  await expect(controlArea).toBeHidden()
  expect(await controlArea.evaluate((el) => window.getComputedStyle(el).display)).toBe('none')
  const back = page.getByRole('button', { name: '关闭 Debug', exact: true })
  await expect(back).toBeVisible()
  await expect(composer).toContainText('branch draft preview')

  await page.locator('.thread-debug-preview').click()
  await expect.poll(() => recorded.branchPreviews.length).toBe(1)
  expect(recorded.branchPreviews[0]?.startEntryId).toBe('entry-2')
  expect(recorded.branchPreviews[0]?.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
  // 预览是纯读取：没有创建 Thread、没有提交任何批次。
  expect(recorded.commandBatches).toEqual([])

  // 窄 pane 下详情页签自动接管并渲染最终请求体。
  await expect(page.getByTestId('preview-request-body')).toBeVisible()
  await expect(page.getByText('DRAFT_REQUEST_PREVIEW')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('branch-draft-debug-narrow.png') })

  await back.click()
  await expect(page.getByRole('listbox', { name: '事件' })).toHaveCount(0)
  await expect(controlArea).toBeVisible()
  await expect(page.getByLabel('给 AI 发送消息').first()).toContainText('branch draft preview')
  const target = await page.evaluate(({ chatId }) =>
    window.localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${chatId}:pane-1`), { chatId: CHAT_ID })
  expect(target).toContain('NEW_THREAD_DRAFT')
  expect(recorded.commandBatches).toEqual([])
})

/**
 * per-turn hover 真实读数：由该回合的真实 usage 事实生成完整数字与全称字段，
 * 不再复述可见的紧凑缩写图例（真实浏览器里读取 title 属性）。
 */
test('the turn footer hover readout uses full usage facts instead of the compact legend', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 720, height: 820 })
  await installChatApi(page, { usageEntries: true })
  await bindFirstPaneToThread(page)
  await page.goto(HARNESS_URL)

  const metaText = page.locator('.thread-meta-text').first()
  await expect(metaText).toBeVisible()
  const title = (await metaText.getAttribute('title')) ?? ''
  expect(title).toContain('1200 tokens')
  expect(title).toContain('340 tokens')
  expect(title).toContain('7 tokens')
  expect(title).not.toContain('↑')
  expect(title.split('\n')).toHaveLength(3)
  await page.screenshot({ path: testInfo.outputPath('turn-footer-hover-narrow.png') })
})

test.describe('新建分支命名与目标路由（真实浏览器）', () => {
  test('routes the /history fork into a hidden pane, reveals it in the real grid and never pre-creates a Thread', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const recorded = await installChatApi(page)
    await bindFirstPaneToThread(page)
    await page.goto(HARNESS_URL)

    const composer = page.getByLabel('给 AI 发送消息').first()
    await composer.click()
    await composer.pressSequentially('/history')
    await page.keyboard.press('Enter')

    const panel = page.locator('.history-tree-panel')
    await expect(panel).toBeVisible()
    const rows = panel.locator('.history-tree-entry')
    await expect(rows).toHaveCount(5)
    // Selected ancestry stays in the same lane; the sibling has a separate lane.
    await expect(rows.nth(2)).toHaveAttribute('data-lane', '0')

    await rows.nth(2).click()
    await expect(rows.nth(2)).toHaveAttribute('data-can-fork', 'true')
    await panel.locator('.history-tree-actions .btn-primary').click()

    const dialog = page.locator('.new-branch-dialog')
    await expect(dialog).toBeVisible()
    await dialog.locator('.new-branch-name').fill('browser-branch')
    // B1：正文/操作区使用共享 Dialog 留白，输入与按钮不贴边（真实几何）。
    const dialogBox = (await dialog.boundingBox())!
    const nameBox = (await dialog.locator('.new-branch-name').boundingBox())!
    expect(nameBox.x - dialogBox.x).toBeGreaterThanOrEqual(20)
    const footerBox = (await dialog.locator('.modal-footer').boundingBox())!
    const submitBox = (await dialog.locator('.modal-footer button[type="submit"]').boundingBox())!
    expect(footerBox.x + footerBox.width - (submitBox.x + submitBox.width)).toBeGreaterThanOrEqual(16)
    await dialog.locator('.ui-select-trigger').click()
    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()
    await listbox.getByRole('option').nth(2).click()
    await dialog.locator('.modal-footer button[type="submit"]').click()

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
    const third = page.locator('[data-pane-id="pane-3"]')
    await expect(third.getByLabel('给 AI 发送消息')).toBeFocused()
    await expect(third.getByText('Real selected ancestor', { exact: true })).toBeVisible()
    await expect(third.getByText('hello', { exact: true })).toHaveCount(0)
    await expect(third.getByText('Sibling must stay hidden', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('hidden-pane-history-focus.png') })
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
    await expect(page.getByRole('button', { name: '关闭 Debug', exact: true })).toBeVisible()

    await page.getByRole('button', { name: '关闭 Debug', exact: true }).click()
    await expect(page.getByRole('listbox', { name: '事件' })).toHaveCount(0)
    await expect(controlArea).toBeVisible()
    await expect(page.locator('.thread-debug-back')).toHaveCount(0)
  })
})

test('409 competition keeps history, text and settings; local rename recovers the first atomic send', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  const recorded = await installChatApi(page, { conflictFirstSend: true })
  await bindFirstPaneToBranchDraft(page, 'entry-2')
  await page.goto(HARNESS_URL)
  await expect(page.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  const composer = page.getByLabel('给 AI 发送消息')
  await composer.click()
  await composer.pressSequentially('/yolo')
  await page.keyboard.press('Enter')
  await composer.pressSequentially('Keep browser text')
  expect(recorded.commandBatches).toEqual([])
  await page.getByRole('button', { name: '发送消息' }).click()
  const name = page.getByRole('textbox', { name: '名称' })
  await expect(name).toHaveValue('browser-draft')
  await expect(page.getByRole('alert')).toContainText('此 Session 已有同名')
  await page.screenshot({ path: testInfo.outputPath('shared-name-rename-error.png') })
  await expect(composer).toHaveText('Keep browser text')
  await expect(page.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  await name.fill(' renamed   browser ')
  await page.getByRole('button', { name: '保存' }).click()
  await expect(composer).toBeFocused()
  await expect(composer).toHaveText('Keep browser text')
  // Real Selection points to the end rather than replacing/selecting the existing draft.
  await expect.poll(() => composer.evaluate((el) => {
    const range = document.getSelection()?.getRangeAt(0)
    if (!range?.collapsed || !el.contains(range.endContainer)) return false
    const prefix = document.createRange()
    prefix.selectNodeContents(el)
    prefix.setEnd(range.endContainer, range.endOffset)
    return prefix.toString() === el.textContent
  })).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('conflict-renamed-preserved.png') })
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect.poll(() => recorded.commandBatches.length).toBe(2)
  expect(recorded.commandBatches[1]).toMatchObject({
    target: { type: 'NEW_THREAD', threadName: 'renamed browser', startEntryId: 'entry-2', yoloEnabled: true },
    commands: [{ type: 'USER_MESSAGE', contents: [{ type: 'TEXT', text: 'Keep browser text' }] }],
  })
  await expect(page.locator('[data-breadcrumb="branch"]')).toHaveText('renamed browser')
  await expect(page.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  await expect.poll(() => page.evaluate((chatId) =>
    localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${chatId}:pane-1`), CHAT_ID)).toContain('BOUND_THREAD')
})

test('selecting a Thread waits for readiness, preserves its text and never steals focus after another pane is chosen', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const recorded = await installChatApi(page)
  await page.addInitScript(({ chatId }) => {
    localStorage.setItem(`kk-studio.chat-pane.${chatId}`, JSON.stringify({
      layout: 'split-2', focusedPaneId: 'pane-1',
      panes: Array.from({ length: 9 }, (_, index) => ({ id: `pane-${index + 1}` })),
    }))
  }, { chatId: CHAT_ID })
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  let requested = false
  await page.route(`**/api/harness/threads/${THREAD_ID}`, async (route) => {
    requested = true
    await gate
    await route.fallback()
  })
  await page.goto(HARNESS_URL)
  const first = page.locator('[data-pane-id="pane-1"]')
  const second = page.locator('[data-pane-id="pane-2"]')
  const editor = first.getByLabel('给 AI 发送消息')
  await editor.click()
  await editor.pressSequentially('/thread')
  await page.keyboard.press('Enter')
  await page.getByRole('option', { name: /Session One/ }).click()
  await page.getByRole('option', { name: /thread-name/ }).click()
  await expect.poll(() => requested).toBe(true)
  await second.getByLabel('给 AI 发送消息').click()
  await second.getByLabel('给 AI 发送消息').pressSequentially('other pane text')
  release()
  await expect(first.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  await expect(second.getByLabel('给 AI 发送消息')).toBeFocused()
  await expect(second.getByLabel('给 AI 发送消息')).toHaveText('other pane text')
  await expect(page.locator('.chat-workspace-breadcrumb')).toHaveAttribute('data-focused-pane', 'pane-2')
  expect(recorded.commandBatches).toEqual([])

  // Select the same existing target again as a fresh user intent.
  await first.getByLabel('给 AI 发送消息').click()
  await first.getByLabel('给 AI 发送消息').pressSequentially('/thread')
  await page.keyboard.press('Enter')
  await page.getByRole('option', { name: /Session One/ }).click()
  await page.getByRole('option', { name: /thread-name/ }).click()
  await expect(first.getByLabel('给 AI 发送消息')).toBeFocused()
  await first.getByLabel('给 AI 发送消息').pressSequentially('bound draft text')
  await first.getByRole('button', { name: '打开命令表' }).click()
  await page.locator('.thread-command-palette').getByRole('option', { name: /^thread/ }).click()
  await page.getByRole('option', { name: /Session One/ }).click()
  await page.getByRole('option', { name: /thread-name/ }).click()
  await expect(first.getByLabel('给 AI 发送消息')).toBeFocused()
  await expect(first.getByLabel('给 AI 发送消息')).toHaveText('bound draft text')
  await expect.poll(() => first.getByLabel('给 AI 发送消息').evaluate((el) => {
    const range = document.getSelection()?.getRangeAt(0)
    if (!range?.collapsed || !el.contains(range.endContainer)) return false
    const prefix = document.createRange()
    prefix.selectNodeContents(el)
    prefix.setEnd(range.endContainer, range.endOffset)
    return prefix.toString() === el.textContent
  })).toBe(true)

  // Freeze the existing focus timer after selection but before its callback.
  // A later pane choice must cancel this already-scheduled external intent.
  const frozenTime = new Date('2026-10-08T00:00:00Z')
  await page.clock.install({ time: frozenTime })
  await page.clock.pauseAt(frozenTime)
  await first.getByRole('button', { name: '打开命令表' }).click({ force: true })
  await page.locator('.thread-command-palette').getByRole('option', { name: /^thread/ }).click({ force: true })
  await page.getByRole('option', { name: /Session One/ }).click({ force: true })
  await page.getByRole('option', { name: /thread-name/ }).click({ force: true })
  await expect(first.getByLabel('给 AI 发送消息')).not.toBeFocused()
  await second.getByLabel('给 AI 发送消息').click({ force: true })
  await page.clock.runFor(20)
  await expect(second.getByLabel('给 AI 发送消息')).toBeFocused()
  await expect(page.locator('.chat-workspace-breadcrumb')).toHaveAttribute('data-focused-pane', 'pane-2')
  await expect(first.getByLabel('给 AI 发送消息')).toHaveText('bound draft text')
})

test('Debug preserves an open root subagent panel without queuing hidden-composer focus', async ({ page }) => {
  const recorded = await installChatApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.addInitScript(({ chatId, threadId }) => {
    localStorage.setItem(`kk-studio.agent-pane-target.CHAT:${chatId}:pane-1`, JSON.stringify({ kind: 'BOUND_THREAD', threadId }))
  }, { chatId: CHAT_ID, threadId: THREAD_ID })
  await page.goto(HARNESS_URL)
  const editor = page.getByRole('textbox', { name: '给 AI 发送消息' })
  await expect(editor).toBeEditable()
  await editor.fill('root panel draft')
  const header = page.locator('.chat-workspace-header')
  await header.getByRole('button', { name: '查看 subagent 执行' }).click()
  const tree = page.locator('.subagent-tree-panel')
  await expect(tree).toBeFocused()
  const savedTree = await tree.elementHandle()
  for (const event of [{ key: 'Escape', repeat: true }, { key: 'Escape', keyCode: 229 }]) {
    await tree.dispatchEvent('keydown', event)
    await expect(tree).toBeVisible()
  }
  await header.getByRole('button', { name: 'Debug', exact: true }).click()
  await expect(tree).toBeHidden()
  for (const target of [page.locator('.chat-pane:not([hidden])'), page.locator('.chat-workspace')]) {
    for (const event of [{ key: 'Escape', repeat: true }, { key: 'Escape', keyCode: 229 }]) {
      await target.dispatchEvent('keydown', event)
      await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toBeVisible()
    }
  }
  await header.getByRole('button', { name: '关闭 Debug', exact: true }).click()
  await expect(tree).toBeVisible()
  expect(await savedTree?.evaluate((element) => element.isConnected)).toBe(true)
  await expect(header.getByRole('button', { name: 'Debug', exact: true })).toBeFocused()
  await expect(editor).toBeHidden()
  await tree.focus()
  await page.keyboard.press('Escape')
  await expect(tree).toHaveCount(0)
  await expect(editor).toBeFocused()
  await expect(editor).toContainText('root panel draft')
  expect(recorded.commandBatches).toEqual([])
})

for (const layout of ['split-3', 'grid-4']) {
  test(`readonly grandchild Debug exclusively occupies ${layout} from non-first pane and restores drafts`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const recorded = await installChatApi(page, { longHistory: true })
    const writes: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/') && request.method() !== 'GET') {
        writes.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
    })
    await page.addInitScript(({ chatId, threadId, layout }) => {
      localStorage.setItem(`kk-studio.agent-pane-target.CHAT:${chatId}:pane-2`, JSON.stringify({ kind: 'BOUND_THREAD', threadId }))
      localStorage.setItem(`kk-studio.chat-pane.${chatId}`, JSON.stringify({
        layout, focusedPaneId: 'pane-2', panes: Array.from({ length: 9 }, (_, index) => ({ id: `pane-${index + 1}` })),
      }))
    }, { chatId: CHAT_ID, threadId: THREAD_ID, layout })
    await page.goto(HARNESS_URL)
    const first = page.locator('.chat-pane[data-pane-id="pane-1"]')
    const source = page.locator('.chat-pane[data-pane-id="pane-2"]')
    const firstEditor = first.getByRole('textbox', { name: '给 AI 发送消息' })
    await expect(firstEditor).toBeEditable()
    await firstEditor.fill('background pane draft')
    const savedEditor = await firstEditor.elementHandle()
    const sourceEditor = source.getByRole('textbox', { name: '给 AI 发送消息' })
    await expect(sourceEditor).toBeEditable()
    await sourceEditor.fill('root source draft')
    const rootLog = source.getByRole('log')
    await expect(rootLog).toContainText('Scroll marker 23')
    const rootScroll = await rootLog.evaluate((element) => {
      element.scrollTop = 180
      return element.scrollTop
    })
    expect(rootScroll).toBeGreaterThan(0)
    await source.getByRole('button', { name: '打开命令表' }).click()
    await source.getByRole('option', { name: /subagent/ }).click()
    const tree = source.locator('.subagent-tree-panel')
    await expect(tree.locator('.thread-tree-row')).toHaveCount(2)
    await expect(tree.locator(`[data-thread-id="${GRANDCHILD_ID}"]`)).toContainText('turns: 4 · tools: 5')
    // 从根直接点开孙执行，返回必须先落在实际直属父，而非浏览栈的根。
    await tree.locator(`[data-thread-id="${GRANDCHILD_ID}"] a`).click()
    const header = page.locator('.chat-workspace-header')
    await expect(header.getByText('main', { exact: true })).toHaveCount(0)
    await expect(header.locator('.workspace-view-identity')).toHaveText('researcher · minimax/MiniMax · high')
    await expect(source.locator('.chat-pane-layer:not([hidden]) .thread-composer')).toHaveCount(0)
    const childLog = source.locator('.chat-pane-layer:not([hidden])').getByRole('log')
    await expect(childLog).toContainText('Scroll marker 23')
    const childScroll = await childLog.evaluate((element) => {
      element.scrollTop = 240
      return element.scrollTop
    })
    expect(childScroll).toBeGreaterThan(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
    const debugTrigger = await header.getByRole('button', { name: 'Debug', exact: true }).elementHandle()
    await header.getByRole('button', { name: 'Debug', exact: true }).click()
    await expect(page.locator('.chat-pane-grid')).toHaveClass(/layout-single/)
    await expect(page.locator('.chat-pane:not([hidden])')).toHaveCount(1)
    await expect(source).toBeVisible()
    await expect(first).toBeHidden()
    await expect(first).toHaveAttribute('inert', '')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    await expect(page.locator('.thread-events-shell:visible')).toHaveCount(1)
    const treeReads = recorded.treeReads
    recorded.completeGrandchild()
    await expect(source.getByRole('option', { name: /Background completion persisted/ })).toBeVisible()
    await expect.poll(() => recorded.treeReads).toBeGreaterThan(treeReads)
    await expect(header.locator('.workspace-view-identity')).toHaveText('researcher · minimax/MiniMax · high')
    await expect(page.locator('.thread-composer:visible, .thread-status-footer:visible, .thread-widget-stack:visible')).toHaveCount(0)
    await expect(page.locator('.agent-pane-thread-heading:visible')).toHaveCount(0)
    await expect(page.getByRole('combobox', { name: '布局' })).toHaveCount(0)
    await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toBeVisible()
    await expect(page.locator('.thread-debug-back')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`${layout}-readonly-grandchild-debug.png`) })
    // 第一层 Escape 只关闭已初选的检查详情；下一次才退出 workspace Debug。
    await page.getByRole('listbox', { name: '事件', exact: true }).focus()
    await page.keyboard.press('Escape')
    await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.locator('.chat-pane-grid')).toHaveClass(new RegExp(`layout-${layout}`))
    await expect(firstEditor).toContainText('background pane draft')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    expect(await debugTrigger?.evaluate((element) => element.isConnected)).toBe(true)
    await expect(header.getByRole('button', { name: 'Debug', exact: true })).toBeFocused()
    await expect.poll(() => childLog.evaluate((element) => element.scrollTop)).toBe(childScroll)
    await header.getByRole('button', { name: '返回父 agent' }).click()
    await expect(header).toContainText('direct parent')
    await expect(header.locator('.workspace-view-identity')).toHaveText('worker · minimax/MiniMax')
    await header.getByRole('button', { name: '返回父 agent' }).click()
    await expect(sourceEditor).toBeVisible()
    await expect(sourceEditor).toContainText('root source draft')
    await expect.poll(() => rootLog.evaluate((element) => element.scrollTop)).toBe(rootScroll)
    expect(recorded.commandBatches).toEqual([])
    expect(writes).toEqual([])
    await expect(page.getByRole('alert')).toHaveCount(0)
    await savedEditor?.dispose()
    await debugTrigger?.dispose()
  })
}
