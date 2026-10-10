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
    environmentName: null,
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

function thread(headEntryId = 'entry-3', options: { busy?: boolean } = {}) {
  return {
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: SESSION_ID,
    headEntryId,
    parentThreadId: null,
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '0',
    status: options.busy ? 'MODEL_STREAM' : 'IDLE',
    processing: options.busy === true,
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
  options: { usageEntries?: boolean; conflictFirstSend?: boolean; longHistory?: boolean; busyThread?: boolean } = {},
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
  const boundThread = thread(
    options.longHistory ? 'scroll-23' : options.usageEntries ? 'entry-turn' : 'entry-3',
    { busy: options.busyThread },
  )
  let createdThread: ReturnType<typeof thread> | null = null
  // 既有 Thread 的设置提交：接受响应推进 version 并回显 durable 的 SET 命令；后续快照读取
  // 返回这些排队命令，使 chip 从「本地待生效」过渡为服务器已回读的待生效事实。
  let boundVersion = 0
  let queuedCommands: Array<Record<string, unknown>> = []
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
            totalCount: 2,
            results: [
              {
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
              },
              {
                // 单 variant 模型：一次点击即完成选择（无需进入二级 variant 菜单）。
                providerName: 'minimax',
                name: 'Echo',
                modelId: 'Echo-upstream',
                description: null,
                config: {
                  limit: { context: 32000, output: 4096 },
                  abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
                  defaultVariant: 'default',
                  variants: [{ id: 'default' }],
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
              },
            ],
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
      const currentThread = isCreated ? createdThread! : { ...boundThread, version: String(boundVersion) }
      await route.fulfill({
        json: {
          status: 200,
          data: {
            version: currentThread.version,
            thread: currentThread,
            entries: isCreated ? entryList.filter((entry) => ['entry-1', 'entry-ancestor', 'entry-2'].includes(entry.entryId)) : entryList.filter((entry) => entry.entryId !== 'entry-sibling'),
            queuedCommands: isCreated ? [] : queuedCommands,
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
      const body = request.postDataJSON() as {
        expectedHeadEntryId?: string
        commands?: Array<Record<string, unknown>>
        target?: { threadId: string; threadName: string; startEntryId: string }
      }
      recorded.commandBatches.push(body)
      if (options.conflictFirstSend && recorded.commandBatches.length === 1) {
        await route.fulfill({ status: 409, json: { status: 409, code: 'THREAD_NAME_CONFLICT', message: 'name conflict', errors: { reason: 'THREAD_NAME_CONFLICT' } } })
        return
      }
      if (body.target == null) {
        // 既有 Thread 的纯设置批次：回显 durable SET 命令并推进 version，后续快照读回它们。
        boundVersion += 1
        const accepted = (body.commands ?? []).map((command, index) => {
          const payload = command.type === 'SET_AGENT'
            ? { agentName: command.agentName }
            : command.type === 'SET_MODEL'
              ? { model: command.model }
              : { environmentName: command.environmentName ?? null }
          return {
            threadId: THREAD_ID,
            sequence: String(index + 1),
            type: command.type,
            state: 'QUEUED',
            idempotencyKey: command.idempotencyKey,
            payloadJson: JSON.stringify(payload),
            cancelledAt: null,
            createTime: '2026-10-01T00:05:00Z',
          }
        })
        queuedCommands = [...queuedCommands, ...accepted]
        await route.fulfill({ json: { status: 200, data: {
          session: { sessionId: SESSION_ID, name: 'Session One', createdAt: null },
          rootEntry: entryList[0],
          thread: { ...boundThread, version: String(boundVersion) },
          acceptedCommands: accepted,
          replayed: false,
        } } })
        return
      }
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
 * 本地分支草稿的 Debug 视图：整 pane 只读覆盖 + 可见退出，不渲染草稿预览动作，
 * 且绝不向后端发出写请求或会话级草稿预览请求。
 */
test('the local branch draft Debug provides read-only overlay with clean exit without creating a Thread', async ({
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

  // 已移除草稿预览操作按钮，且未触发任何分支预览或命令批次请求。
  await expect(page.locator('.thread-debug-preview')).toHaveCount(0)
  await expect(page.locator('.thread-debug-preview-action')).toHaveCount(0)
  expect(recorded.branchPreviews).toEqual([])
  expect(recorded.commandBatches).toEqual([])
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
  for (const width of [1440, 360, 320]) {
    await page.setViewportSize({ width, height: 820 })
    const centers = await metaText.evaluate((element) => {
      const center = (target: Element) => {
        const box = target.getBoundingClientRect()
        return box.y + box.height / 2
      }
      const row = element.closest('.thread-meta-row')!
      return {
        text: center(element), coins: center(row.querySelector('.thread-meta-icon svg')!),
        branch: center(row.querySelector('.thread-meta-branch-btn')!),
      }
    })
    expect(Math.abs(centers.text - centers.coins)).toBeLessThanOrEqual(1)
    expect(Math.abs(centers.text - centers.branch)).toBeLessThanOrEqual(1)
    await testInfo.attach(`usage-centers-${width}`, { body: JSON.stringify(centers, null, 2), contentType: 'application/json' })
  }
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

test('root slash selectors preserve the draft, close on Escape and keep Debug exclusive', async ({ page }) => {
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
  await expect(header.getByRole('button', { name: '查看 subagent 执行' })).toHaveCount(0)
  await expect(header.getByRole('button', { name: 'Debug', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '打开命令表' }).click()
  await page.getByRole('option', { name: /subagent/ }).click()
  const tree = page.locator('.subagent-tree-panel')
  await expect(tree).toBeFocused()
  for (const event of [{ key: 'Escape', repeat: true }, { key: 'Escape', keyCode: 229 }]) {
    await tree.dispatchEvent('keydown', event)
    await expect(tree).toBeVisible()
  }
  await page.keyboard.press('Escape')
  await expect(tree).toHaveCount(0)
  await expect(editor).toBeFocused()
  await expect(editor).toContainText('root panel draft')
  await page.getByRole('button', { name: '打开命令表' }).click()
  await page.getByRole('option', { name: /debug/ }).click()
  await expect(page.locator('.thread-control-area')).toBeHidden()
  for (const target of [page.locator('.chat-pane:not([hidden])'), page.locator('.chat-workspace')]) {
    for (const event of [{ key: 'Escape', repeat: true }, { key: 'Escape', keyCode: 229 }]) {
      await target.dispatchEvent('keydown', event)
      await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toBeVisible()
    }
  }
  await header.getByRole('button', { name: '关闭 Debug', exact: true }).click()
  await expect(editor).toBeFocused()
  await expect(editor).toContainText('root panel draft')
  expect(recorded.commandBatches).toEqual([])
})

for (const [layout, width] of [['split-3', 1440], ['grid-4', 360], ['grid-4', 320]] as const) {
  test(`readonly grandchild returns to its direct parent in ${layout} at ${width}px and restores drafts`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
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
    await expect(tree.locator('.subagent-card-list li')).toHaveCount(2)
    await expect(tree.locator('[data-depth], .thread-tree-connectors')).toHaveCount(0)
    await expect(tree.locator(`[data-thread-id="${THREAD_ID}"]`)).toHaveCount(0)
    expect(await tree.locator('.subagent-card-list li').evaluateAll((items) => items.map((item) => (item as HTMLElement).dataset.threadId)))
      .toEqual([CHILD_ID, GRANDCHILD_ID])
    await expect(tree.locator(`[data-thread-id="${GRANDCHILD_ID}"]`)).toContainText('turns: 4 · tools: 5')
    // 从根直接点开孙执行，返回必须先落在实际直属父，而非浏览栈的根。
    await tree.focus()
    await page.keyboard.press('ArrowDown')
    await expect(tree.locator(`[data-thread-id="${GRANDCHILD_ID}"] a`)).toBeFocused()
    await page.keyboard.press('Enter')
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
    await expect(header.getByRole('button', { name: 'Debug', exact: true })).toHaveCount(0)
    await expect(header.getByRole('button', { name: '查看 subagent 执行' })).toHaveCount(0)
    await expect(header.getByRole('link', { name: '回到对话' })).toHaveCount(0)
    expect(await header.evaluate((element) => element.querySelector('.chat-workspace-title')?.firstElementChild?.textContent))
      .toBe('回到父 agent')
    await expect(source.locator('.chat-pane-layer[hidden]').first()).toHaveAttribute('inert', '')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    const treeReads = recorded.treeReads
    recorded.completeGrandchild()
    await expect(childLog).toContainText('Background completion persisted')
    await expect.poll(() => recorded.treeReads).toBeGreaterThan(treeReads)
    await expect(header.locator('.workspace-view-identity')).toHaveText('researcher · minimax/MiniMax · high')
    await expect(source.locator('.chat-pane-layer:not([hidden]) .thread-composer')).toHaveCount(0)
    await expect(page.locator('.agent-pane-thread-heading:visible')).toHaveCount(0)
    await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toHaveCount(0)
    await expect(page.locator('.thread-debug-back')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`${layout}-${width}-readonly-grandchild.png`) })
    await expect(page.locator('.chat-pane-grid')).toHaveClass(new RegExp(`layout-${layout}`))
    await expect(firstEditor).toContainText('background pane draft')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    await expect.poll(() => childLog.evaluate((element) => element.scrollTop)).toBe(childScroll)
    await header.getByRole('button', { name: '回到父 agent' }).click()
    await expect(header).toContainText('direct parent')
    await expect(header.locator('.workspace-view-identity')).toHaveText('worker · minimax/MiniMax')
    await header.getByRole('button', { name: '回到父 agent' }).click()
    await expect(sourceEditor).toBeVisible()
    await expect(sourceEditor).toContainText('root source draft')
    await expect.poll(() => rootLog.evaluate((element) => element.scrollTop)).toBe(rootScroll)
    expect(recorded.commandBatches).toEqual([])
    expect(writes).toEqual([])
    await expect(page.getByRole('alert')).toHaveCount(0)
    await savedEditor?.dispose()
  })
}

/**
 * busy Thread 的设置选择走真实浏览器路径：立即提交纯 SET 批次（无 USER_MESSAGE/GOAL），
 * 并以「待生效」chip 展示。
 */
test('busy thread settings selection submits a standalone SET batch and shows the pending chip', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const recorded = await installChatApi(page, { busyThread: true })
  await bindFirstPaneToThread(page)
  await page.goto(HARNESS_URL)

  const modelControl = page.getByRole('button', { name: 'Model 与 Variant' }).first()
  await expect(modelControl).toBeVisible()
  await modelControl.click()
  await page.getByRole('option', { name: 'minimax/Echo' }).click()

  await expect.poll(() => recorded.commandBatches.length).toBe(1)
  const batch = recorded.commandBatches[0] as {
    expectedHeadEntryId: string
    commands: Array<Record<string, unknown>>
  }
  // 纯设置批次：固定顺序的 SET_MODEL，且没有伪造末尾 USER_MESSAGE/GOAL。
  expect(batch.commands.map((command) => command.type)).toEqual(['SET_MODEL'])
  expect(batch.expectedHeadEntryId).toBe('entry-3')

  const chip = page.locator('.thread-composer-settings-status[data-settings-status="pending"]').first()
  await expect(chip).toBeVisible()
  await expect(chip).toHaveText('待生效')
  await page.screenshot({ path: testInfo.outputPath('busy-settings-pending.png') })
})
