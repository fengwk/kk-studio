import fs from 'node:fs'
import path from 'node:path'
import { expect, test, tokenColor, type Page } from './fixture'
import {
  expectFirstMarkVisible,
  expectNoLeadingClipping,
  readSearchHitText,
  recordSearchHitMetrics,
} from './search-hit-geometry'
import { isInside } from './visual-shots'
import type { Locator, Route, TestInfo, WebSocketRoute } from '@playwright/test'
import { createDefaultChatPaneState, type ChatLayout, type ChatTabSnapshot } from '@/features/chat/chat-pane-state'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane/pane-target'
import { messageCatalog } from '@/shared/i18n/catalogs'
import type { EntryType, HarnessSessionEntryDTO, HarnessThreadTreeNodeDTO } from '@/shared/api/generated/contracts'

/**
 * 新建分支流程真实浏览器回归（独立基座）。
 *
 * 挂载真实 `ChatWorkspacePage`，后端全部由 `page.route` 提供。这里验证 jsdom 无法
 * 证明的行为：隐藏 pane 的真实布局显露、真实 `display:none`/`inert` 的 Debug 隐藏与
 * 退出恢复、以及 1..8 目标路由后目标 pane 的真实可见性与焦点。
 */

const CHAT_ID = 'chat-branch-1'
const THREAD_ID = 'a2000000-0000-4000-8000-0000000000a1'
const CHILD_ID = 'a2000000-0000-4000-8000-0000000000c1'
const GRANDCHILD_ID = 'a2000000-0000-4000-8000-0000000000d1'
const SESSION_ID = 'b2000000-0000-4000-8000-0000000000b1'
const HARNESS_URL = '/browser-tests/chat-branch-harness.html'
const ATTACHMENT_NAME = 'layout-draft.txt'
const DRAFT_TEXT = 'pane two draft survives'
const UPLOAD_ID = 'c3000000-0000-4000-8000-0000000000c1'
/**
 * Session 首条消息与 Thread 头部消息的 Markdown 源：标题井号、强调星号、行内代码反引号、
 * 链接语法、列表与引用前缀都在其中，用于验收选择列表的预览只显示纯文本。
 */
const MARKDOWN_PREVIEW = [
  '## 发布前必读',
  '**必须**先跑 `verify`，细节见 [运行手册](https://example.test/runbook)',
  '- 列表项不要跳过',
  '| 检查 | 状态 |',
  '| --- | --- |',
  '| 构建 | 通过 |',
  '> 引用结论照旧',
].join('\n')
/** 折成单行后用于展示和搜索的预览文字。 */
const PREVIEW_TEXT = '发布前必读 必须先跑 verify，细节见 运行手册 列表项不要跳过 检查 状态 构建 通过 引用结论照旧'

/** 可选截图输出目录：给出 `KK_BROWSER_SHOTS_DIR` 时把验收截图同时复制出去。 */
const SHOTS_DIR = process.env.KK_BROWSER_SHOTS_DIR

type HarnessLocale = 'zh-CN' | 'en-US'

/** 界面文案直接取 catalog：文案调整时本文件的断言跟着变化，不复制字符串。 */
const COPY = {
  'zh-CN': {
    layout: messageCatalog['ai.chat.layout']['zh-CN'],
    send: messageCatalog['ai.runtime.composer.send']['zh-CN'],
    continueAction: messageCatalog['ai.chat.continueConversation']['zh-CN'],
    selectSession: messageCatalog['ai.chat.selectSession']['zh-CN'],
    selectThread: messageCatalog['ai.chat.selectThread']['zh-CN'],
    emptyThread: messageCatalog['ai.runtime.thread.empty']['zh-CN'],
    singleThread: messageCatalog['ai.chat.threadCount.one']['zh-CN'],
    search: messageCatalog['ai.chat.selection.search']['zh-CN'],
    composer: messageCatalog['ai.runtime.composer.ariaLabel']['zh-CN'],
    closeDebug: messageCatalog['ai.runtime.debug.close']['zh-CN'],
  },
  'en-US': {
    layout: messageCatalog['ai.chat.layout']['en-US'],
    send: messageCatalog['ai.runtime.composer.send']['en-US'],
    continueAction: messageCatalog['ai.chat.continueConversation']['en-US'],
    selectSession: messageCatalog['ai.chat.selectSession']['en-US'],
    selectThread: messageCatalog['ai.chat.selectThread']['en-US'],
    emptyThread: messageCatalog['ai.runtime.thread.empty']['en-US'],
    singleThread: messageCatalog['ai.chat.threadCount.one']['en-US'],
    search: messageCatalog['ai.chat.selection.search']['en-US'],
    composer: messageCatalog['ai.runtime.composer.ariaLabel']['en-US'],
    closeDebug: messageCatalog['ai.runtime.debug.close']['en-US'],
  },
} as const

/** 验收要求的两个语言与两个视口：每个状态都在 2 × 2 组合下留证。 */
const LOCALES: HarnessLocale[] = ['zh-CN', 'en-US']
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
] as const

/** 1440 宽下副标题整行放得下：此时要求开头不被前导省略裁掉。 */
const WIDE = 1440

function viewportKey(viewport: { width: number; height: number }): string {
  return `${viewport.width}x${viewport.height}`
}

/** 验收截图文件名：语言 + 视口 + 状态，与 catalog 文案、视口矩阵一一对应。 */
function shotName(
  locale: HarnessLocale,
  viewport: { width: number; height: number },
  state: string,
): string {
  return `${locale}-${viewportKey(viewport)}-${state}`
}

async function readAt(page: Page, transcript: Locator, position: number): Promise<number> {
  await transcript.hover()
  await expect(transcript).toHaveAttribute('data-following', 'true')
  const current = await transcript.evaluate((element) => element.scrollTop)
  await page.mouse.wheel(0, position - current)
  await expect(transcript).toHaveAttribute('data-following', 'false')
  await expect.poll(() => transcript.evaluate((element, top) => Math.abs(element.scrollTop - top), position))
    .toBeLessThanOrEqual(1)
  return transcript.evaluate((element) => element.scrollTop)
}
const rootPayload = { settings: {
  agentName: 'assistant', model: { modelName: 'MiniMax', variant: 'default' },
  environmentName: null,
} }

function chat(title = 'Branch Chat') {
  return {
    id: CHAT_ID,
    title,
    agentName: 'assistant',
    environmentName: null,
    yoloEnabled: false,
    version: '1',
    createdAt: null,
    updatedAt: null,
  }
}

function entries(options: { usage?: boolean } = {}): HarnessSessionEntryDTO[] {
  if (options.usage) {
    // 已关闭回合的真实 usage 事实：ASSISTANT 的 assistantMetadata + 读取投影 usageCost，
    // 后面紧跟 TURN_END，使 conversation 渲染出带 turnUsage 的回合 footer。
    return [
      { entryId: 'entry-1', sessionId: SESSION_ID, parentEntryId: null, entryType: 'ROOT', payload: rootPayload, createdAt: 1790812800, usageCost: null },
      {
        entryId: 'entry-assistant',
        sessionId: SESSION_ID,
        parentEntryId: 'entry-1',
        entryType: 'MESSAGE',
        payload: {
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
        },
        usageCost: { currency: 'USD', amount: '0.0012' },
        createdAt: 1790812860,
      },
      {
        entryId: 'entry-turn',
        sessionId: SESSION_ID,
        parentEntryId: 'entry-assistant',
        entryType: 'TURN_END',
        payload: { outcome: 'COMPLETED' },
        createdAt: 1790812920,
        usageCost: null,
      },
    ]
  }
  return [
    { entryId: 'entry-1', sessionId: SESSION_ID, parentEntryId: null, entryType: 'ROOT', payload: rootPayload, createdAt: 1790812800, usageCost: null },
    {
      entryId: 'entry-ancestor', sessionId: SESSION_ID, parentEntryId: 'entry-1', entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'text', text: 'Real selected ancestor' }] } },
      createdAt: 1790812830,
      usageCost: null,
    },
    { entryId: 'entry-2', sessionId: SESSION_ID, parentEntryId: 'entry-ancestor', entryType: 'TURN_END', payload: { outcome: 'COMPLETED' }, createdAt: 1790812860, usageCost: null },
    {
      entryId: 'entry-3',
      sessionId: SESSION_ID,
      parentEntryId: 'entry-2',
      entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'text', text: 'hello' }] } },
      createdAt: 1790812920,
      usageCost: null,
    },
    {
      entryId: 'entry-sibling', sessionId: SESSION_ID, parentEntryId: 'entry-1', entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'text', text: 'Sibling must stay hidden' }] } },
      createdAt: 1790812950,
      usageCost: null,
    },
  ]
}

function thread(headEntryId = 'entry-3', options: { busy?: boolean; name?: string } = {}) {
  return {
    name: options.name ?? 'thread-name',
    threadId: THREAD_ID,
    sessionId: SESSION_ID,
    headEntryId,
    parentThreadId: null,
    rootThreadId: THREAD_ID,
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: options.busy ? 'RUNNING' : 'IDLE',
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
      environmentName: null,
    },
    createdAt: 1790812800,
    updatedAt: 1790812800,
  }
}

interface Recorded {
  commandBatches: unknown[]
  branchPreviews: Array<{ startEntryId: string; commands: Array<Record<string, unknown>> }>
  modelRequestDebug: Array<{ model: unknown; environmentName: string | null }>
  uploadCalls: string[]
  deletedUploads: string[]
  treeReads: number
  snapshotReads: number
  completeGrandchild: () => void
}

async function installChatApi(
  page: Page,
  options: {
    usageEntries?: boolean
    conflictFirstSend?: boolean
    longHistory?: boolean
    busyThread?: boolean
    /** 附件句柄：reserve → 直传 → complete，并记录释放（DELETE）。 */
    uploads?: boolean
    /** `empty` 表示该 Chat 没有任何 Session，用于空白 pane 入口的隐藏条件。 */
    sessions?: 'one' | 'empty'
    /** 预览用 Markdown 源：Session 首条消息与 Thread 头部消息都带标记，用于验收只显示纯文本。 */
    markdownPreviews?: boolean
    /** 顶栏面包屑夹具：Chat 标题、Session 名（取自已发送的第一条消息）与绑定的 Thread 名。 */
    names?: { chatTitle?: string; sessionName?: string; threadName?: string }
  } = {},
): Promise<Recorded> {
  const messagePreview = options.markdownPreviews ? MARKDOWN_PREVIEW : 'hello'
  const sessionName = options.names?.sessionName ?? 'Session One'
  const entryList = entries({ usage: options.usageEntries })
  if (options.longHistory) {
    entryList.push(...Array.from({ length: 24 }, (_, index) => ({
      entryId: `scroll-${index}`, sessionId: SESSION_ID,
      parentEntryId: index === 0 ? 'entry-3' : `scroll-${index - 1}`, entryType: 'MESSAGE' as EntryType,
      payload: { message: { role: 'USER', contents: [{
        type: 'text', text: `Scroll marker ${index}\n${Array(12).fill('Reading position is retained.').join('\n')}`,
      }] } },
      createdAt: Date.parse('2026-10-01T00:03:00Z') / 1000 + index,
      usageCost: null,
    })))
  }
  const boundThread = thread(
    options.longHistory ? 'scroll-23' : options.usageEntries ? 'entry-turn' : 'entry-3',
    { busy: options.busyThread, name: options.names?.threadName },
  )
  let createdThread: ReturnType<typeof thread> | null = null
  // 既有 Thread 的设置提交：接受响应推进 version 并回显 durable 的 SET 命令；后续快照读取
  // 返回这些排队命令，使 chip 从「本地待生效」过渡为服务器已回读的待生效事实。
  let boundVersion = 0
  let queuedCommands: Array<Record<string, unknown>> = []
  let grandCompleted = false
  const currentTree = (): HarnessThreadTreeNodeDTO[] => [
    {
      threadId: THREAD_ID, kind: 'USER', parentThreadId: null, name: boundThread.name, agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
      status: 'IDLE', executionControl: 'RUNNABLE', turnCount: 1, toolCallCount: 0,
      outcome: 'COMPLETED', updatedAt: 1790812800,
    },
    {
      threadId: CHILD_ID, kind: 'SUBAGENT', parentThreadId: THREAD_ID, name: 'direct parent', agentName: 'worker',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
      status: 'IDLE', executionControl: 'RUNNABLE', turnCount: 2, toolCallCount: 3,
      outcome: 'COMPLETED', updatedAt: 1790812860,
    },
    {
      threadId: GRANDCHILD_ID, kind: 'SUBAGENT', parentThreadId: CHILD_ID, name: 'nested grandchild', agentName: 'researcher',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'deep' } },
      status: grandCompleted ? 'IDLE' : 'RUNNING', executionControl: 'RUNNABLE', turnCount: 4, toolCallCount: 5,
      outcome: grandCompleted ? 'COMPLETED' : null, updatedAt: 1790812920,
    },
  ]
  const sendTreeSnapshot = (socket: WebSocketRoute) => socket.send(JSON.stringify({
    type: 'event', resource: { kind: 'thread-tree', id: THREAD_ID },
    name: 'snapshot', data: { nodes: currentTree() },
  }))
  const sockets: WebSocketRoute[] = []
  const recorded: Recorded = {
    commandBatches: [], branchPreviews: [], modelRequestDebug: [], uploadCalls: [], deletedUploads: [],
    treeReads: 0, snapshotReads: 0,
    completeGrandchild: () => {
      grandCompleted = true
      const entryIdByOriginal = new Map(entryList.map((entry, index) => [
        entry.entryId,
        `dddddddd-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      ]))
      const backgroundEntryId = 'eeeeeeee-0000-4000-8000-000000000001'
      const currentThread = {
        ...boundThread,
        threadId: GRANDCHILD_ID,
        name: 'main',
        parentThreadId: CHILD_ID,
        rootThreadId: THREAD_ID,
        headEntryId: backgroundEntryId,
        version: '1',
        status: 'IDLE' as const,
        branchSettings: {
          ...boundThread.branchSettings,
          agentName: 'researcher',
          execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'deep' } },
        },
      }
      const snapshotEntries = entryList
        .filter((entry) => entry.entryId !== 'entry-sibling')
        .map((entry) => ({
          ...entry,
          entryId: entryIdByOriginal.get(entry.entryId)!,
          parentEntryId: entry.parentEntryId == null ? null : entryIdByOriginal.get(entry.parentEntryId)!,
        }))
      snapshotEntries.push({
        entryId: backgroundEntryId,
        sessionId: SESSION_ID,
        parentEntryId: entryIdByOriginal.get(boundThread.headEntryId)!,
        entryType: 'MESSAGE',
        payload: { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'Background completion persisted' }] } },
        createdAt: 1790813040,
        usageCost: null,
      })
      const snapshot = {
        version: '1',
        thread: currentThread,
        baseEntryId: null,
        entries: snapshotEntries,
        queuedCommands: [],
        activeAssistantOutput: null,
        toolInvocations: [],
        generationFailures: [],
        stopReceipts: [],
        unconfirmedExecutions: [],
      }
      for (const socket of sockets) {
        socket.send(JSON.stringify({
          type: 'event', resource: { kind: 'thread', id: GRANDCHILD_ID },
          name: 'snapshot', data: snapshot,
        }))
        sendTreeSnapshot(socket)
      }
    },
  }
  await page.routeWebSocket(/\/ws\/events$/, (socket) => {
    sockets.push(socket)
    socket.onMessage((message) => {
      const frame = JSON.parse(String(message))
      if (frame.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: frame.resource, cursor: '0' }))
        if (frame.resource.kind === 'thread-tree' && frame.resource.id === THREAD_ID) {
          sendTreeSnapshot(socket)
        }
      } else if (frame.type === 'refresh' && frame.resource.kind === 'thread-tree'
        && frame.resource.id === THREAD_ID) {
        sendTreeSnapshot(socket)
      }
    })
  })
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path === `/api/chat/chats/${CHAT_ID}`) {
      await route.fulfill({ json: chat(options.names?.chatTitle) })
      return
    }
    if (path === `/api/chat/chats/${CHAT_ID}/sessions/${SESSION_ID}`) {
      // 顶栏面包屑按 id 单读会话名：默认名称取自已发送的第一条消息。
      await route.fulfill({
        json: {
            sessionId: SESSION_ID,
            name: sessionName,
            createdAt: 1790812800000,
            lastActivityAt: 1790812860000,
            firstMessagePreview: messagePreview,
            threadCount: 1,
        },
      })
      return
    }
    if (path === `/api/chat/chats/${CHAT_ID}/sessions`) {
      // 归属 Session 摘要按 keyset 分页返回（ChatSessionPageDTO），首屏无下一页。
      // 空白 pane 的“继续已有会话”入口只看 owner 是否已有会话，所以这里也用它表达空列表。
      await route.fulfill({
        json: {
            items: options.sessions === 'empty' ? [] : [{
              sessionId: SESSION_ID,
              name: sessionName,
              createdAt: 1790812800000,
              lastActivityAt: 1790812860000,
              firstMessagePreview: messagePreview,
              threadCount: 1,
            }],
            nextCursor: null,
        },
      })
      return
    }
    if (path === '/api/platform/agents') {
      await route.fulfill({
        json: [{
              name: 'assistant',
              description: '通用助手',
              systemPrompt: null,
              execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
              config: { tools: [], skills: [], subagents: [] },
              version: '1',
              createdAt: null,
              updatedAt: null,
            }],
      })
      return
    }
    if (path === '/api/platform/models') {
      await route.fulfill({
        json: [
              {
                providerName: 'minimax',
                type: 'MODEL',
                name: 'MiniMax',
                modelId: 'MiniMax-upstream',
                description: null,
                config: {
                  limit: { context: 128000, output: 8192 },
                  abilities: { tools: true, reasoning: true, inputModalities: ['TEXT'] },
                  defaultVariant: 'default',
                  variants: [{ id: 'default' }, { id: 'deep', reasoningEffort: 'high' }],
                  pricing: {
      base: { input: '1', output: '1', cacheRead: '1', cacheWrite: '1', cacheWriteByTtl: {}, reasoning: null },
      rules: [],
      changes: []
    },
                },
                version: '1',
                createdAt: 0,
                updatedAt: 0,
              },
              {
                // 单 variant 模型：一次点击即完成选择（无需进入二级 variant 菜单）。
                providerName: 'minimax',
                type: 'MODEL',
                name: 'Echo',
                modelId: 'Echo-upstream',
                description: null,
                config: {
                  limit: { context: 32000, output: 4096 },
                  abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
                  defaultVariant: 'default',
                  variants: [{ id: 'default' }],
                  pricing: {
      base: { input: '1', output: '1', cacheRead: '1', cacheWrite: '1', cacheWriteByTtl: {}, reasoning: null },
      rules: [],
      changes: []
    },
                },
                version: '1',
                createdAt: 0,
                updatedAt: 0,
              },
        ],
      })
      return
    }
    if (path === '/api/platform/environments') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === `/api/harness/sessions/${SESSION_ID}/entries`) {
      // 每页 2 条模拟真实 keyset 分页：历史树必须逐页累积后才能得到完整行数。
      const cursor = new URL(request.url()).searchParams.get('cursor')
      const cursorIndex = cursor == null
        ? -1
        : entryList.findIndex((entry) => entry.entryId === cursor)
      if (cursor != null && cursorIndex < 0) {
        await route.fulfill({ status: 400, contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 400, code: 'validation', title: `unknown cursor ${cursor}` } })
        return
      }
      const start = cursorIndex + 1
      const items = entryList.slice(start, start + 2)
      const nextIndex = start + items.length
      await route.fulfill({
        json: {
          items,
          nextCursor: nextIndex < entryList.length ? items.at(-1)!.entryId : null,
        },
      })
      return
    }
    if (path === `/api/harness/sessions/${SESSION_ID}/threads`) {
      // Thread 摘要的时间是 epoch 毫秒，而 Thread DTO 用 epoch 秒：换算后再交给面板格式化。
      await route.fulfill({ json: [{
        threadId: THREAD_ID, parentThreadId: null, name: boundThread.name,
        createdAt: boundThread.createdAt * 1000, updatedAt: boundThread.updatedAt * 1000, status: 'IDLE',
        executionControl: 'RUNNABLE', model: { modelName: 'MiniMax', variant: 'default' }, headMessagePreview: messagePreview,
      }] })
      return
    }
    if (path === `/api/harness/threads/${CHILD_ID}` || path === `/api/harness/threads/${GRANDCHILD_ID}`) {
      recorded.snapshotReads += 1
      const grandchild = path.endsWith(GRANDCHILD_ID)
      await route.fulfill({ json: {
        version: grandchild && grandCompleted ? '1' : '0',
        thread: { ...boundThread,
          threadId: grandchild ? GRANDCHILD_ID : CHILD_ID,
          name: grandchild ? 'main' : 'direct parent',
          version: grandchild && grandCompleted ? '1' : '0',
          status: grandchild && !grandCompleted ? 'RUNNING' : 'IDLE',
          headEntryId: grandchild && grandCompleted ? 'entry-background' : boundThread.headEntryId,
          parentThreadId: grandchild ? CHILD_ID : THREAD_ID,
          branchSettings: { ...boundThread.branchSettings, agentName: grandchild ? 'researcher' : 'worker',
            execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: grandchild ? 'deep' : 'default' } } },
        },
        entries: [
          ...entryList.filter((entry) => entry.entryId !== 'entry-sibling'),
          ...(grandchild && grandCompleted ? [{
            entryId: 'entry-background', sessionId: SESSION_ID, parentEntryId: boundThread.headEntryId, entryType: 'MESSAGE',
            payload: { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'Background completion persisted' }] } },
            createdAt: 1790813040,
            usageCost: null,
          }] : []),
        ], baseEntryId: null, queuedCommands: [],
        activeAssistantOutput: null, toolInvocations: [], generationFailures: [],
        stopReceipts: [], unconfirmedExecutions: [],
      } })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}` || (createdThread != null && path === `/api/harness/threads/${createdThread.threadId}`)) {
      const isCreated = createdThread != null && path.endsWith(createdThread.threadId)
      const currentThread = isCreated ? createdThread! : { ...boundThread, version: String(boundVersion) }
      await route.fulfill({
        json: {
            version: currentThread.version,
            thread: currentThread,
            baseEntryId: null,
            entries: isCreated ? entryList.filter((entry) => ['entry-1', 'entry-ancestor', 'entry-2'].includes(entry.entryId)) : entryList.filter((entry) => entry.entryId !== 'entry-sibling'),
            queuedCommands: isCreated ? [] : queuedCommands,
            activeAssistantOutput: null,
            toolInvocations: [],
            generationFailures: [],
            stopReceipts: [],
            unconfirmedExecutions: [],
        },
      })
      return
    }
    if (path === `/api/harness/threads/${THREAD_ID}/tree`) {
      recorded.treeReads += 1
      await route.fulfill({ json: currentTree() })
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
        await route.fulfill({ status: 409, contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 409, code: 'conflict', reason: 'THREAD_NAME_CONFLICT', title: 'name conflict' } })
        return
      }
      if (body.target == null) {
        // 既有 Thread 的纯设置批次：回显 durable SET 命令并推进 version，后续快照读回它们。
        boundVersion += 1
        const accepted = (body.commands ?? []).map((command, index) => {
          const payload = command.type === 'SET_EXECUTION'
            ? { execution: command.execution }
            : { environmentName: command.environmentName ?? null }
          return {
            threadId: THREAD_ID,
            sequence: String(index + 1),
            type: command.type,
            state: 'QUEUED',
            idempotencyKey: command.idempotencyKey,
            payload,
            cancelledAt: null,
            createdAt: 1790813100,
          }
        })
        queuedCommands = [...queuedCommands, ...accepted]
        await route.fulfill({ json: {
          session: { sessionId: SESSION_ID, name: 'Session One', createdAt: null },
          rootEntry: entryList[0],
          thread: { ...boundThread, version: String(boundVersion) },
          acceptedCommands: accepted,
          replayed: false,
        } })
        return
      }
      createdThread = { ...boundThread, threadId: body.target.threadId, name: body.target.threadName, headEntryId: body.target.startEntryId }
      await route.fulfill({ json: {
        session: { sessionId: SESSION_ID, name: 'Session One', createdAt: null },
        rootEntry: entryList[0], thread: createdThread, acceptedCommands: [], replayed: false,
      } })
      return
    }
    if (path === '/api/harness/interactions') {
      await route.fulfill({ json: { items: [], nextCursor: null, total: 0, freshnessAt: null } })
      return
    }
    if (path.endsWith('/model-request-debug')) {
      // 只读调试预览是 POST-only：非 POST 一律 405，POST 记录草稿选择（model/environmentName）。
      if (method !== 'POST') {
        await route.fulfill({ status: 405, contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 405, code: 'validation', title: 'Method Not Allowed' } })
        return
      }
      const body = request.postDataJSON() as { model: unknown; environmentName: string | null }
      recorded.modelRequestDebug.push(body)
      await route.fulfill({
        json: {
            kind: 'NEXT_REQUEST_PREVIEW',
            generatedAt: '2026-10-01T00:00:00Z',
            model: body.model,
            environmentName: body.environmentName ?? null,
            systemInstruction: 'system prompt',
            tools: [],
            skills: [],
            subagents: [],
            cacheControl: null,
            planningError: null,
            frozenInvocation: null,
        },
      })
      return
    }
    if (options.uploads && path === '/api/platform/storage/uploads' && method === 'POST') {
      // 附件句柄预留：直传地址由浏览器直接 PUT（下方 storage.test 路由）。
      recorded.uploadCalls.push('reserve')
      await route.fulfill({
        json: {
          type: 'PENDING',
          id: UPLOAD_ID,
          presignedPut: {
            method: 'PUT',
            url: 'https://storage.test/branch-put',
            headers: {},
            expiresAt: 1786492800000,
            mediaType: null,
            sizeBytes: null,
          },
          expiresAt: 1786492800000,
        },
      })
      return
    }
    if (options.uploads && path === `/api/platform/storage/uploads/${UPLOAD_ID}/complete`) {
      recorded.uploadCalls.push('complete')
      await route.fulfill({
        json: { type: 'READY', id: UPLOAD_ID, blobId: 'blob-branch-1', expiresAt: 1786492800000 },
      })
      return
    }
    if (options.uploads && path.startsWith('/api/platform/storage/uploads/') && method === 'DELETE') {
      recorded.deletedUploads.push(path)
      await route.fulfill({ status: 204 })
      return
    }
    await route.fulfill({ json: {} })
  })
  if (options.uploads) {
    await page.route('https://storage.test/**', async (route) => {
      await route.fulfill({ status: 200, body: '' })
    })
  }
  return recorded
}

/** 空白草稿 pane：未绑定任何 Session/Thread，等待用户开始或继续对话。 */
async function seedBlankPane(page: Page) {
  await seedChatTab(page, 'single', 'pane-1')
}

/** 两个 pane 绑定同一 Thread：用于证明缩小布局时被隐藏 pane 的草稿与附件原地保留。 */
async function seedSplitDraftPanes(page: Page) {
  await seedChatTab(page, 'split-2', 'pane-2', {
    'pane-1': { kind: 'BOUND_THREAD', threadId: THREAD_ID },
    'pane-2': { kind: 'BOUND_THREAD', threadId: THREAD_ID },
  })
}

/** 以指定语言与视口打开基座，并用真实 `lang` 属性确认语言已生效。 */
async function openHarness(
  page: Page,
  locale: HarnessLocale,
  viewport: { width: number; height: number },
) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height })
  await page.goto(`${HARNESS_URL}?locale=${locale}`)
  await expect(page.locator('.chat-workspace')).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
}

/**
 * 布局选项的无障碍名带量词：中文「2 个窗格」，英文按数量区分单复数（1 pane / 2 panes）。
 * 可见文本与可访问名同源，因此按可访问名定位选项只依赖 catalog 文案。
 */
function paneCountLabel(locale: HarnessLocale, panes: number): string {
  const messages = panes === 1
    ? messageCatalog['ai.chat.paneCount.one']
    : messageCatalog['ai.chat.paneCount.other']
  return messages[locale].replace('{{count}}', String(panes))
}

/** 通过真实的布局选择器切换可见 pane 数：选项按带量词的无障碍名定位。 */
async function chooseLayout(page: Page, locale: HarnessLocale, panes: 1 | 2) {
  await page.locator('.chat-layout-selector .ui-select-trigger').click()
  const listbox = page.getByRole('listbox', { name: COPY[locale].layout })
  await expect(listbox).toBeVisible()
  await listbox.getByRole('option', { name: paneCountLabel(locale, panes), exact: true }).click()
  await expect(page.locator('.chat-pane-grid'))
    .toHaveClass(new RegExp(`layout-${panes === 1 ? 'single' : 'split-2'}`))
}

/** 上传一个已就绪附件：reserve → 直传 → complete 走完，返回可继续断言的句柄定位器。 */
async function uploadReadyAttachment(pane: Locator) {
  await pane.locator('input[type="file"].composer-file-input-hidden').setInputFiles({
    name: ATTACHMENT_NAME,
    mimeType: 'text/plain',
    buffer: Buffer.from('layout draft attachment'),
  })
  const pill = pane.locator(`.composer-pill[data-part-type="attachment"][data-filename="${ATTACHMENT_NAME}"]`)
  const reference = pane.locator(`.attachment-reference[data-filename="${ATTACHMENT_NAME}"]`)
  await expect(pill).toHaveCount(1)
  await expect(reference).toHaveClass(/is-ready/)
  return { pill, reference }
}

/** 截图留在 Playwright 报告目录；给出 `KK_BROWSER_SHOTS_DIR` 时同时复制到验收截图目录。 */
async function captureShot(page: Page, testInfo: TestInfo, name: string) {
  const file = testInfo.outputPath(`${name}.png`)
  await page.screenshot({ path: file })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
  if (SHOTS_DIR == null) {
    return
  }
  fs.mkdirSync(SHOTS_DIR, { recursive: true })
  fs.copyFileSync(file, path.join(SHOTS_DIR, `${name}.png`))
}

async function seedChatTab(
  page: Page,
  layout: ChatLayout,
  focusedPaneId: string,
  targets: Record<string, PaneTarget> = {},
) {
  const presentation = createDefaultChatPaneState(layout)
  presentation.focusedPaneId = focusedPaneId
  presentation.panes = presentation.panes.map((pane) => ({ ...pane, target: targets[pane.id] ?? pane.target }))
  const snapshot: ChatTabSnapshot = { presentation }
  const key = `kk-studio.tab-state:chat:${CHAT_ID}`
  await page.addInitScript(({ key, snapshot }) => {
    // 仅初始化空 tab；刷新必须恢复用户操作后写入的快照。
    if (window.top === window && sessionStorage.getItem(key) == null) {
      sessionStorage.setItem(key, JSON.stringify(snapshot))
    }
  }, { key, snapshot })
}

async function readPaneTarget(
  page: Page,
  paneId: string,
  store: 'session' | 'local' = 'session',
): Promise<PaneTarget | null> {
  return page.evaluate(({ key, paneId, store }) => {
    const storage = store === 'session' ? sessionStorage : localStorage
    const raw = storage.getItem(key)
    if (raw == null) return null
    const snapshot = JSON.parse(raw) as ChatTabSnapshot
    return snapshot.presentation.panes.find((pane) => pane.id === paneId)?.target ?? null
  }, {
    key: `kk-studio.${store === 'session' ? 'tab' : 'last'}-state:chat:${CHAT_ID}`,
    paneId,
    store,
  })
}

async function bindFirstPaneToThread(page: Page) {
  await seedChatTab(page, 'single', 'pane-1', {
    'pane-1': { kind: 'BOUND_THREAD', threadId: THREAD_ID },
  })
}

/** 单 pane 的本地分支草稿目标：会话与分叉点已定，Thread 尚未创建。 */
async function bindFirstPaneToBranchDraft(page: Page, startEntryId: string) {
  await seedChatTab(page, 'single', 'pane-1', {
    'pane-1': { kind: 'NEW_THREAD_DRAFT', sessionId: SESSION_ID, startEntryId, threadName: 'browser-draft' },
  })
}

test('E2E split fixture restores both pane targets and clears both storage sources', async ({ page }) => {
  // 加载实际 E2E JS 辅助函数，而不是在测试中重新实现其存储格式。
  const helperPath = '../../scripts/dev/verify/e2e/ui/chat-pane-storage.mjs'
  const { seedSplitChatPanes, clearChatPaneState } = await import(helperPath)
  await page.setViewportSize({ width: 1440, height: 900 })
  await installChatApi(page)
  await page.goto(HARNESS_URL)
  await seedSplitChatPanes(page, CHAT_ID, THREAD_ID, CHILD_ID)
  await page.reload()

  const first = page.locator('[data-pane-id="pane-1"]')
  const second = page.locator('[data-pane-id="pane-2"]')
  await expect(page.locator('.chat-pane-grid.layout-split-2')).toBeVisible()
  await expect(first.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  await expect(second.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  expect(await readPaneTarget(page, 'pane-1')).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
  expect(await readPaneTarget(page, 'pane-2')).toEqual({ kind: 'BOUND_THREAD', threadId: CHILD_ID })
  expect(await readPaneTarget(page, 'pane-2', 'local')).toEqual({ kind: 'BOUND_THREAD', threadId: CHILD_ID })

  // 本 tab 索引缺失时从 last-state 恢复；清理必须使两个来源均失效。
  await page.evaluate((key) => sessionStorage.removeItem(key), `kk-studio.tab-state:chat:${CHAT_ID}`)
  await page.reload()
  await expect(page.locator('.chat-pane-grid.layout-split-2')).toBeVisible()
  await expect(second.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  await clearChatPaneState(page, CHAT_ID)
  expect(await readPaneTarget(page, 'pane-1')).toBeNull()
  expect(await readPaneTarget(page, 'pane-1', 'local')).toBeNull()
  await page.reload()
  await expect(page.locator('.chat-pane-grid.layout-single')).toBeVisible()
  await expect(second).toBeHidden()
  expect(await readPaneTarget(page, 'pane-1')).toEqual({ kind: 'NEW_SESSION_DRAFT' })
})

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

  // 只读 Debug 不触发分支预览或命令批次请求。
  expect(recorded.branchPreviews).toEqual([])
  expect(recorded.commandBatches).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('branch-draft-debug-narrow.png') })

  await back.click()
  await expect(page.getByRole('listbox', { name: '事件' })).toHaveCount(0)
  await expect(controlArea).toBeVisible()
  await expect(page.getByLabel('给 AI 发送消息').first()).toContainText('branch draft preview')
  expect(await readPaneTarget(page, 'pane-1')).toEqual({
    kind: 'NEW_THREAD_DRAFT', sessionId: SESSION_ID, startEntryId: 'entry-2', threadName: 'browser-draft',
  })
  expect(recorded.commandBatches).toEqual([])
})

/**
 * per-turn hover 真实读数：由该回合的真实 usage 事实生成完整数字与全称字段，
 * 不复述可见的紧凑缩写图例（真实浏览器里读取 title 属性）。
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
    await expect(listbox.getByRole('option')).toHaveCount(8)
    await listbox.getByRole('option').nth(7).click()
    await dialog.locator('.modal-footer button[type="submit"]').click()

    // 最大隐藏位置也要在真实网格中显露并落在视口内。
    await expect(page.locator('.chat-pane-grid.layout-grid-8')).toBeVisible()
    await expect(page.getByLabel('给 AI 发送消息')).toHaveCount(8)
    const destination = page.locator('[data-pane-id="pane-8"]')
    await expect(destination.getByLabel('给 AI 发送消息')).toBeInViewport()

    // 顶栏面包屑跟随聚焦 pane，并展示规范化后的分支名。
    const breadcrumb = page.locator('.chat-workspace-breadcrumb')
    await expect(breadcrumb).toHaveAttribute('data-focused-pane', 'pane-8')
    await expect(breadcrumb.locator('[data-breadcrumb="branch"]')).toHaveText('browser-branch')

    // 目标 pane 的本地草稿目标已写为 NEW_THREAD_DRAFT，但绝不预创建 Thread。
    const target = { kind: 'NEW_THREAD_DRAFT', sessionId: SESSION_ID, startEntryId: 'entry-2', threadName: 'browser-branch' }
    await expect.poll(() => readPaneTarget(page, 'pane-8')).toEqual(target)
    await expect.poll(() => readPaneTarget(page, 'pane-8', 'local')).toEqual(target)
    expect(recorded.commandBatches).toEqual([])
    await expect(destination.getByLabel('给 AI 发送消息')).toBeFocused()
    await expect(destination.getByText('Real selected ancestor', { exact: true })).toBeVisible()
    await expect(destination.getByText('hello', { exact: true })).toHaveCount(0)
    await expect(destination.getByText('Sibling must stay hidden', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('hidden-pane-history-focus.png') })

    // 重载必须恢复刚刚路由的目标，而不是重放初始 pane fixture。
    const lastKey = `kk-studio.last-state:chat:${CHAT_ID}`
    const lastBeforeReload = await page.evaluate((key) => localStorage.getItem(key), lastKey)
    await page.reload()
    await expect(page.locator('.chat-pane-grid.layout-grid-8')).toBeVisible()
    await expect.poll(() => readPaneTarget(page, 'pane-8')).toEqual(target)
    expect(await page.evaluate((key) => localStorage.getItem(key), lastKey)).toBe(lastBeforeReload)
    await expect(destination.getByText('Real selected ancestor', { exact: true })).toBeVisible()
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

/** 历史树 footer 的提示、原因与三个动作文案同样取自 catalog，不复制字符串。 */
function historyFooterCopy(locale: HarnessLocale) {
  const hintTemplate = messageCatalog['ai.chat.history.hint'][locale]
  return {
    /** 提示里的当前位置由选中行在列表中的序号决定，总数是真实行数。 */
    hint: (current: number, total: number) => hintTemplate
      .replace('{{current}}', String(current))
      .replace('{{total}}', String(total)),
    cancel: messageCatalog['ai.chat.history.cancel'][locale],
    forkSession: messageCatalog['ai.chat.branch.forkSession'][locale],
    fromHere: messageCatalog['ai.chat.branch.fromHere'][locale],
    notBoundary: messageCatalog['ai.chat.branch.notBoundary'][locale],
  }
}

/** 判定底栏内容未被压成零宽、截断或推出面板。 */
async function expectFooterFits(panel: Locator, parts: Locator[]) {
  for (const part of [panel.locator('.history-tree-footer'), ...parts]) {
    await expect(part).toBeVisible()
    expect(await isInside(part, panel), `${part} fits inside the panel`).toBe(true)
    expect(await part.evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      `${part} keeps all its content visible`).toBe(true)
  }
}

/**
 * FTC-5：历史树底部操作区在 390 宽英文长文案下不得溢出。
 *
 * 中英 × 1440/390 覆盖两种真实状态：普通消息行给出禁用原因，三个动作仍全部可见；
 * 选中可分支点（已关闭 TURN_END）时三个动作可点，并真的把切点前历史复制进本 pane 的
 * 会话草稿。截图记录可分支点状态，即用户真正操作的那一帧。
 */
for (const locale of LOCALES) {
  for (const viewport of VIEWPORTS) {
    test(`history tree footer keeps hint, reason and three actions whole (${locale}, ${viewportKey(viewport)})`, async ({
      page,
    }, testInfo) => {
      const copy = historyFooterCopy(locale)
      await installChatApi(page)
      await bindFirstPaneToThread(page)
      await openHarness(page, locale, viewport)

      const composer = page.getByLabel(COPY[locale].composer).first()
      await composer.click()
      await composer.pressSequentially('/history')
      await page.keyboard.press('Enter')

      const panel = page.locator('.history-tree-panel')
      await expect(panel).toBeVisible()
      const rows = panel.locator('.history-tree-entry')
      await expect(rows).toHaveCount(5)

      const hint = panel.locator('.history-tree-hint')
      const reason = panel.locator('.history-tree-disabled-reason')
      const actions = panel.locator('.history-tree-actions')
      const cancel = actions.getByRole('button', { name: copy.cancel, exact: true })
      const forkSession = actions.getByRole('button', { name: copy.forkSession, exact: true })
      const fromHere = actions.getByRole('button', { name: copy.fromHere, exact: true })
      await expect(actions.getByRole('button')).toHaveCount(3)

      // 非分支点（普通消息行）：三个动作仍全部可见，禁用原因占据整行完整可读。
      await rows.nth(1).click()
      await expect(rows.nth(1)).toHaveAttribute('data-can-fork', 'false')
      await expect(reason).toBeVisible()
      await expect(reason).toHaveText(copy.notBoundary)
      await expect(cancel).toBeEnabled()
      await expect(forkSession).toBeDisabled()
      await expect(forkSession).toHaveAttribute('title', copy.notBoundary)
      await expect(fromHere).toBeDisabled()
      await expect(fromHere).toHaveAttribute('title', copy.notBoundary)
      await expect(hint).toHaveText(copy.hint(2, 5))
      await expectFooterFits(panel, [hint, reason, cancel, forkSession, fromHere])

      // 可分支点：三个动作全部可见可点，提示完整可读。
      await rows.nth(2).click()
      await expect(rows.nth(2)).toHaveAttribute('data-can-fork', 'true')
      await expect(reason).toHaveCount(0)
      await expect(hint).toHaveText(copy.hint(3, 5))
      await expectFooterFits(panel, [hint, cancel, forkSession, fromHere])
      for (const action of [cancel, forkSession, fromHere]) {
        await action.click({ trial: true })
      }
      await captureShot(page, testInfo, `history-footer-${locale}-${viewport.width}`)

      // 窄屏下动作真实生效：把切点前的历史复制进本 pane 的会话 fork 草稿并收面板。
      await forkSession.click()
      await expect(panel).toHaveCount(0)
      await expect.poll(() => readPaneTarget(page, 'pane-1')).toEqual({
        kind: 'FORK_SESSION_DRAFT',
        sessionId: SESSION_ID,
        sourceThreadId: THREAD_ID,
        startEntryId: 'entry-2',
      })
    })
  }
}

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
  await expect(page.getByRole('alert')).toContainText('此会话已有同名')
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
  const accepted = recorded.commandBatches[1] as { target: { threadId: string } }
  await expect.poll(() => readPaneTarget(page, 'pane-1')).toEqual({
    kind: 'BOUND_THREAD', threadId: accepted.target.threadId,
  })
  await expect.poll(() => readPaneTarget(page, 'pane-1', 'local')).toEqual({
    kind: 'BOUND_THREAD', threadId: accepted.target.threadId,
  })
})

test('selecting a Thread waits for readiness, preserves its text and never steals focus after another pane is chosen', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const recorded = await installChatApi(page)
  await seedChatTab(page, 'split-2', 'pane-1')
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

test('legacy nine-slot snapshots reset and saved conversations reopen through /thread', async ({ page }) => {
  const recorded = await installChatApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  const key = `kk-studio.tab-state:chat:${CHAT_ID}`
  const presentation = createDefaultChatPaneState('split-2')
  presentation.panes[0]!.target = { kind: 'BOUND_THREAD', threadId: THREAD_ID }
  presentation.panes.push({ id: 'pane-9', target: { kind: 'NEW_SESSION_DRAFT' } })
  await page.addInitScript(({ key, presentation }) => {
    sessionStorage.setItem(key, JSON.stringify({ presentation }))
  }, { key, presentation })
  await page.goto(HARNESS_URL)
  await expect(page.locator('.chat-pane-grid.layout-single')).toBeVisible()
  expect(await readPaneTarget(page, 'pane-1')).toEqual({ kind: 'NEW_SESSION_DRAFT' })
  await page.getByRole('button', { name: '打开命令表' }).click()
  await page.getByRole('option', { name: /^thread/ }).click()
  await page.getByRole('option', { name: /Session One/ }).click()
  await page.getByRole('option', { name: /thread-name/ }).click()
  await expect(page.getByText('Real selected ancestor', { exact: true })).toBeVisible()
  expect(await readPaneTarget(page, 'pane-1')).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
  expect(recorded.commandBatches).toEqual([])
})

test('shortcut rows follow pane width, scroll only in the body and Escape restores Composer', async ({ page }, testInfo) => {
  await installChatApi(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.setViewportSize({ width: 1100, height: 900 })
  await seedChatTab(page, 'split-3', 'pane-1')
  await page.goto(HARNESS_URL)
  const pane = page.locator('[data-pane-id="pane-1"]')
  const composer = pane.getByLabel('给 AI 发送消息')
  await composer.fill('shortcut draft')
  const dockBox = (await pane.locator('.thread-dock').boundingBox())!
  await pane.getByRole('button', { name: '打开命令表' }).click()
  await pane.getByRole('option', { name: /shortcuts/ }).click()
  const panel = pane.getByRole('region', { name: '键盘快捷键' })
  const body = panel.locator('.thread-shortcuts-body')
  const heading = panel.getByRole('heading', { name: '键盘快捷键' })
  await expect(panel).toBeFocused()
  for (const [width, stacked] of [[1100, true], [1440, false], [2400, false]] as const) {
    await page.setViewportSize({ width, height: 900 })
    const panelBox = (await panel.boundingBox())!
    if (stacked) {
      expect(panelBox.x).toBeCloseTo(dockBox.x)
      expect(panelBox.width).toBeCloseTo(dockBox.width)
    }
    const fits = await body.evaluate((el) => {
      const bounds = el.getBoundingClientRect()
      return el.scrollWidth <= el.clientWidth && [...el.querySelectorAll('kbd')].every((key) => {
        const rect = key.getBoundingClientRect()
        return rect.left >= bounds.left + 12 && rect.right <= bounds.right - 12
      })
    })
    expect(fits).toBe(true)
    const text = (await panel.locator('.shortcut-text').first().boundingBox())!
    const keys = (await panel.locator('kbd').first().boundingBox())!
    if (stacked) {
      expect(keys.y).toBeGreaterThanOrEqual(text.y + text.height)
      expect(keys.x).toBeCloseTo(text.x)
    } else {
      expect(keys.x).toBeGreaterThan(text.x)
      expect(keys.y).toBeLessThan(text.y + text.height)
    }
    if (width === 2400) {
      expect(keys.x + keys.width).toBeLessThan(panelBox.x + panelBox.width - 80)
    }
    await page.screenshot({ path: testInfo.outputPath(`shortcuts-${width}.png`) })
  }
  const headingBefore = await heading.boundingBox()
  expect(await body.evaluate((el) => {
    el.scrollTop = el.scrollHeight
    return el.scrollTop
  })).toBeGreaterThan(0)
  expect(await heading.boundingBox()).toEqual(headingBefore)
  await expect(panel.getByRole('button', { name: '关闭', exact: true })).toBeInViewport()
  for (const event of [{ isComposing: true }, { keyCode: 229 }, { repeat: true }]) {
    await panel.dispatchEvent('keydown', { key: 'Escape', ...event })
    await expect(panel).toBeVisible()
  }
  // 焦点交接期间，模态层已出现但按键仍可能来自下层面板。
  await page.evaluate(() => {
    const overlay = document.createElement('div')
    overlay.setAttribute('aria-modal', 'true')
    overlay.id = 'test-blocking-modal'
    document.body.append(overlay)
  })
  await panel.dispatchEvent('keydown', { key: 'Escape' })
  await expect(panel).toBeVisible()
  await page.locator('#test-blocking-modal').evaluate((overlay) => overlay.remove())
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
  await expect(composer).toBeFocused()
  await expect(composer).toHaveText('shortcut draft')
})

test('root slash selectors preserve the draft, close on Escape and keep Debug exclusive', async ({ page }) => {
  const recorded = await installChatApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await bindFirstPaneToThread(page)
  await page.goto(HARNESS_URL)
  const editor = page.getByRole('textbox', { name: '给 AI 发送消息' })
  await expect(editor).toBeEditable()
  await editor.fill('root panel draft')
  const header = page.locator('.chat-workspace-header')
  await expect(header.getByRole('button', { name: '查看子 Agent 执行' })).toHaveCount(0)
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
    await seedChatTab(page, layout, 'pane-2', {
      'pane-2': { kind: 'BOUND_THREAD', threadId: THREAD_ID },
    })
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
    const rootScroll = await readAt(page, rootLog, 180)
    await source.getByRole('button', { name: '打开命令表' }).click()
    await source.getByRole('option', { name: /subagent/ }).click()
    const tree = source.locator('.subagent-tree-panel')
    await expect(tree.locator('.subagent-card-list li')).toHaveCount(2)
    await expect(tree.locator('[data-depth], .thread-tree-connectors')).toHaveCount(0)
    await expect(tree.locator(`[data-thread-id="${THREAD_ID}"]`)).toHaveCount(0)
    expect(await tree.locator('.subagent-card-list li').evaluateAll((items) => items.map((item) => (item as HTMLElement).dataset.threadId)))
      .toEqual([CHILD_ID, GRANDCHILD_ID])
    await expect(tree.locator(`[data-thread-id="${GRANDCHILD_ID}"]`)).toContainText('轮次：4 · 工具：5')
    // 从根直接点开孙执行，返回必须先落在实际直属父，而非浏览栈的根。
    await tree.focus()
    await page.keyboard.press('ArrowDown')
    await expect(tree.locator(`[data-thread-id="${GRANDCHILD_ID}"] a`)).toBeFocused()
    await page.keyboard.press('Enter')
    const header = page.locator('.chat-workspace-header')
    await expect(header.getByText('main', { exact: true })).toHaveCount(0)
    await expect(header.locator('.workspace-view-identity')).toHaveText('researcher · MiniMax (deep)')
    await expect(source.locator('.chat-pane-layer:not([hidden]) .thread-composer')).toHaveCount(0)
    const childLog = source.locator('.chat-pane-layer:not([hidden])').getByRole('log')
    await expect(childLog).toContainText('Scroll marker 23')
    const childScroll = await readAt(page, childLog, 240)
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(header.getByRole('button', { name: 'Debug', exact: true })).toHaveCount(0)
    await expect(header.getByRole('button', { name: '查看子 Agent 执行' })).toHaveCount(0)
    await expect(header.getByRole('link', { name: '回到对话' })).toHaveCount(0)
    const backToParent = header.getByRole('button', { name: '回到父 Agent' })
    const parentButton = await backToParent.boundingBox()
    const identity = await header.locator('.workspace-view-identity').boundingBox()
    expect(parentButton!.x).toBeLessThan(identity!.x)
    await backToParent.click({ trial: true })
    await expect(source.locator('.chat-pane-layer[hidden]').first()).toHaveAttribute('inert', '')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    const treeReads = recorded.treeReads
    const snapshotReads = recorded.snapshotReads
    recorded.completeGrandchild()
    await expect(childLog).toContainText('Background completion persisted')
    await expect.poll(() => recorded.treeReads).toBe(treeReads)
    await expect.poll(() => recorded.snapshotReads).toBe(snapshotReads)
    await expect(header.locator('.workspace-view-identity')).toHaveText('researcher · MiniMax (deep)')
    await expect(source.locator('.chat-pane-layer:not([hidden]) .thread-composer')).toHaveCount(0)
    await expect(page.locator('.agent-pane-thread-heading:visible')).toHaveCount(0)
    await expect(header.getByRole('button', { name: '关闭 Debug', exact: true })).toHaveCount(0)
    await expect(page.locator('.thread-debug-back')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`${layout}-${width}-readonly-grandchild.png`) })
    await expect(page.locator('.chat-pane-grid')).toHaveClass(new RegExp(`layout-${layout}`))
    await expect(firstEditor).toContainText('background pane draft')
    expect(await savedEditor?.evaluate((element) => element.isConnected)).toBe(true)
    await expect.poll(() => childLog.evaluate((element) => element.scrollTop)).toBe(childScroll)
    await header.getByRole('button', { name: '回到父 Agent' }).click()
    await expect(header).toContainText('direct parent')
    await expect(header.locator('.workspace-view-identity')).toHaveText('worker · MiniMax (default)')
    await header.getByRole('button', { name: '回到父 Agent' }).click()
    await expect(sourceEditor).toBeVisible()
    await expect(sourceEditor).toContainText('root source draft')
    await source.getByRole('button', { name: '打开命令表' }).click()
    await source.getByRole('option', { name: /subagent/ }).click()
    const completedTree = source.locator('.subagent-tree-panel')
    const completedGrandchild = completedTree.locator(`[data-thread-id="${GRANDCHILD_ID}"]`)
    await expect(completedGrandchild).toHaveAttribute('data-status', 'IDLE')
    await expect(completedGrandchild.locator('[data-outcome="COMPLETED"]')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(completedTree).toHaveCount(0)
    await expect.poll(() => rootLog.evaluate((element) => element.scrollTop)).toBe(rootScroll)
    expect(recorded.commandBatches).toEqual([])
    expect(writes).toEqual([])
    await expect(page.getByRole('alert')).toHaveCount(0)
    await savedEditor?.dispose()
  })
}

/**
  * busy Thread 的设置选择走真实浏览器路径：立即提交纯 SET 批次（无 USER_MESSAGE），
 * 并以「待生效」chip 展示。
 */
test('busy thread settings selection submits a standalone SET batch and shows the pending chip', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const recorded = await installChatApi(page, { busyThread: true })
  await bindFirstPaneToThread(page)
  await page.goto(HARNESS_URL)

  const modelControl = page.getByRole('button', { name: '模型与预设' }).first()
  await expect(modelControl).toBeVisible()
  await modelControl.click()
  await page.getByRole('option', { name: 'Echo' }).click()

  await expect.poll(() => recorded.commandBatches.length).toBe(1)
  const batch = recorded.commandBatches[0] as {
    expectedHeadEntryId: string
    commands: Array<Record<string, unknown>>
  }
  // 纯设置批次：固定顺序的 SET_EXECUTION，且没有伪造末尾 USER_MESSAGE。
  expect(batch.commands.map((command) => command.type)).toEqual(['SET_EXECUTION'])
  expect(batch.expectedHeadEntryId).toBe('entry-3')

  const chip = page.locator('.thread-composer-settings-status[data-settings-status="pending"]').first()
  await expect(chip).toBeVisible()
  await expect(chip).toHaveText('待生效')
  await page.screenshot({ path: testInfo.outputPath('busy-settings-pending.png') })
})

/**
 * 空白 pane 的次级动作就是 `/thread` 的选择面板：真实打开、真实选中、真实绑定。
 *
 * 截图只记录稳定的用户可见状态（空白 pane、Session 列表、Thread 列表），不截布局下拉。
 */
for (const locale of LOCALES) {
  for (const viewport of VIEWPORTS) {
    test(`blank pane opens the same Session/Thread selection as /thread (${locale}, ${viewportKey(viewport)})`, async ({
      page,
    }, testInfo) => {
      const copy = COPY[locale]
      const recorded = await installChatApi(page, { markdownPreviews: true })
      await seedBlankPane(page)
      await openHarness(page, locale, viewport)

      const pane = page.locator('[data-pane-id="pane-1"]')
      // 空白 pane 保留原有空态与键盘提示，并多出一个可读名称的次级动作。
      await expect(pane.locator('.thread-empty')).toContainText(copy.emptyThread)
      const continueAction = pane.getByRole('button', { name: copy.continueAction, exact: true })
      await expect(continueAction).toBeVisible()
      await captureShot(page, testInfo, shotName(locale, viewport, 'blank-pane'))

      // 面板条目以名称与时间为主，UUID 只作等宽弱化诊断文本。
      await continueAction.click()
      const panel = pane.locator('.thread-selection-panel')
      await expect(panel).toBeVisible()
      await expect(panel.getByRole('heading', { name: copy.selectSession })).toBeVisible()
      const sessionRow = panel.getByRole('option', { name: /Session One/ })
      await expect(sessionRow).toBeVisible()
      const sessionSubtitle = sessionRow.locator('.thread-selection-item-subtitle')
      await expect(sessionSubtitle).toContainText(copy.singleThread)
      await expect(sessionSubtitle).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
      // 预览只显示去除 Markdown 标记后的文字；搜索仍能用预览里的词找到这一行。
      await expect(sessionSubtitle).toContainText(` · ${PREVIEW_TEXT}`)
      const search = panel.getByRole('searchbox', { name: copy.search })
      await search.fill('verify，细节见 运行手册')
      await expect(sessionRow).toBeVisible()
      // 命中就在预览开头：命中及之后从右侧省略，命中词本身不看字符数启发式截断。
      await search.fill('发布前必读')
      const shallow = await readSearchHitText(sessionSubtitle)
      expect(shallow.text.endsWith(PREVIEW_TEXT), 'DOM 保留完整预览').toBe(true)
      expectFirstMarkVisible(shallow, `${locale} ${viewportKey(viewport)} 会话副标题开头命中`)
      recordSearchHitMetrics(testInfo, shotName(locale, viewport, 'chat-page-subtitle-shallow-hit'), shallow)
      if (viewport.width >= WIDE) {
        // 1440 下副标题整行放得下：开头也必须从起点显示。
        expectNoLeadingClipping(shallow, `${locale} ${viewportKey(viewport)} 会话副标题开头命中`)
      }
      // 390 下 dock 列比整行文字窄：前文框按设计先收缩，只验收首个 mark 完整可见。
      // 预览末尾的命中：高亮的命中词完整落在可见区域内。
      await search.fill('引用结论照旧')
      const deepHit = sessionSubtitle.locator('mark')
      await expect(deepHit).toHaveText('引用结论照旧')
      const deep = await readSearchHitText(sessionSubtitle)
      expectFirstMarkVisible(deep, `${locale} ${viewportKey(viewport)} 会话副标题深层命中`)
      recordSearchHitMetrics(testInfo, shotName(locale, viewport, 'chat-page-subtitle-deep-hit'), deep)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(deep, `${locale} ${viewportKey(viewport)} 会话副标题深层命中`)
      }
      // 唯一的结果行就是活动行：命中底色必须与活动行底色不同。
      await expect(sessionRow).toHaveAttribute('aria-selected', 'true')
      expect(await deepHit.evaluate((element) => getComputedStyle(element).backgroundColor))
        .not.toBe(await sessionRow.evaluate((element) => getComputedStyle(element).backgroundColor))
      await captureShot(page, testInfo, shotName(locale, viewport, 'session-selection-deep-hit'))
      // 链接地址不是可见的预览文本，不参加匹配。
      await search.fill('example.test/runbook')
      await expect(panel.getByRole('option')).toHaveCount(0)
      await expect(panel.getByRole('status')).toBeVisible()
      await search.fill('')
      await expect(sessionRow).toBeVisible()
      const badge = sessionRow.locator('.thread-selection-item-title code')
      await expect(badge).toHaveText(SESSION_ID)
      await expect(badge).toHaveAttribute('title', SESSION_ID)
      expect(await badge.evaluate((element) => getComputedStyle(element).color))
        .toBe(await tokenColor(page, '--fg-muted'))
      await captureShot(page, testInfo, shotName(locale, viewport, 'session-selection'))

      // 选择会话后同一面板切到 Thread 列表：时间与状态在前，消息预览在后。
      await sessionRow.click()
      await expect(panel.getByRole('heading', { name: copy.selectThread })).toBeVisible()
      const threadRow = panel.getByRole('option', { name: /thread-name/ })
      await expect(threadRow).toBeVisible()
      const threadSubtitle = threadRow.locator('.thread-selection-item-subtitle')
      await expect(threadSubtitle).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
      await expect(threadSubtitle).toContainText(` · ${PREVIEW_TEXT}`)
      await search.fill('verify，细节见 运行手册')
      await expect(threadRow).toBeVisible()
      await search.fill('')
      await expect(threadRow).toBeVisible()
      await captureShot(page, testInfo, shotName(locale, viewport, 'thread-selection'))

      // 选中 Thread 就是继续已有会话：绑定真实目标并渲染历史，不提交任何命令。
      await threadRow.click()
      await expect(pane.getByText('Real selected ancestor', { exact: true })).toBeVisible()
      await expect.poll(() => readPaneTarget(page, 'pane-1'))
        .toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
      expect(recorded.commandBatches).toEqual([])
    })
  }
}

/** 该 Chat 没有任何会话时不给入口：不能把“没有会话”伪造成可以继续的对话。 */
test('a Chat without Sessions keeps the blank pane free of the continue action', async ({ page }) => {
  await installChatApi(page, { sessions: 'empty' })
  await seedBlankPane(page)
  await openHarness(page, 'zh-CN', { width: 1440, height: 900 })

  const pane = page.locator('[data-pane-id="pane-1"]')
  await expect(pane.locator('.thread-empty')).toContainText(COPY['zh-CN'].emptyThread)
  await expect(pane.getByRole('button', { name: COPY['zh-CN'].continueAction })).toHaveCount(0)
})

/**
 * 布局 2 → 1 → 2 后被隐藏 pane 的带附件草稿：真实浏览器里的挂载/卸载、真实附件上传句柄。
 *
 * 除了草稿文字与附件 chip，还要证明恢复后的附件仍指向同一个已就绪句柄并能真正发送。
 */
for (const locale of LOCALES) {
  for (const viewport of VIEWPORTS) {
    test(`pane 2 keeps its text and ready attachment across layout 2 → 1 → 2 (${locale}, ${viewportKey(viewport)})`, async ({
      page,
    }, testInfo) => {
      const copy = COPY[locale]
      const recorded = await installChatApi(page, { uploads: true })
      await seedSplitDraftPanes(page)
      await openHarness(page, locale, viewport)

      const grid = page.locator('.chat-pane-grid')
      await expect(grid).toHaveClass(/layout-split-2/)
      const paneTwo = page.locator('.chat-pane[data-pane-id="pane-2"]')
      const editor = paneTwo.locator('.composer-editor')
      await editor.click()
      await editor.fill(DRAFT_TEXT)
      const attachment = await uploadReadyAttachment(paneTwo)
      await expect.poll(() => recorded.uploadCalls).toEqual(['reserve', 'complete'])
      const uploadId = await attachment.pill.getAttribute('data-upload-id')
      expect(uploadId, 'composer pill 携带浏览器本地 localId').not.toBeNull()

      // 2 → 1：pane 2 隐藏；隐藏期间不能提交任何命令（既无点击也无快捷键入口）。
      await chooseLayout(page, locale, 1)
      await expect(grid).toHaveClass(/layout-single/)
      await expect(editor).toBeHidden()
      await expect(paneTwo).toHaveAttribute('inert', '')
      expect(await editor.evaluate((element: HTMLElement) => {
        element.focus()
        return document.activeElement === element
      })).toBe(false)
      await expect(page.locator('.chat-pane:visible')).toHaveCount(1)
      await page.keyboard.press('Escape')
      expect(recorded.commandBatches).toEqual([])

      // 1 → 2：文字与附件恢复到同一个已就绪句柄，上传注册表没有被重建或释放。
      await chooseLayout(page, locale, 2)
      await expect(grid).toHaveClass(/layout-split-2/)
      await expect(editor, '隐藏后恢复的 pane 2 仍保留未发送文字').toContainText(DRAFT_TEXT)
      const restoredPill = paneTwo.locator(`.composer-pill[data-filename="${ATTACHMENT_NAME}"]`)
      await expect(restoredPill, '隐藏后恢复的 pane 2 仍保留已就绪附件 chip').toHaveCount(1)
      await expect(restoredPill).toHaveAttribute('data-upload-id', uploadId!)
      await expect(paneTwo.locator(`.attachment-reference[data-filename="${ATTACHMENT_NAME}"]`))
        .toHaveClass(/is-ready/)
      expect(recorded.uploadCalls).toEqual(['reserve', 'complete'])
      expect(recorded.deletedUploads).toEqual([])
      await captureShot(page, testInfo, shotName(locale, viewport, 'restored-attachment-draft'))

      // 可发送：真实发送请求里的 USER_MESSAGE 同时带文字与该附件句柄。
      const send = paneTwo.getByRole('button', { name: copy.send, exact: true })
      await expect(send).toBeEnabled()
      await send.click()
      await expect.poll(() => recorded.commandBatches.length).toBe(1)
      const batch = recorded.commandBatches[0] as { commands: Array<{ type: string; contents?: unknown[] }> }
      expect(batch.commands.at(-1)).toMatchObject({
        type: 'USER_MESSAGE',
        contents: [
          { type: 'TEXT', text: DRAFT_TEXT },
          // 消息引用 reserve 返回的服务端句柄，而不是浏览器本地 localId。
          { type: 'ATTACHMENT', uploadId: UPLOAD_ID },
        ],
      })
    })
  }
}

/**
 * 第 2 轮返工：390 宽工作区顶栏面包屑的可辨认性与省略号。
 *
 * 夹具长度同时满足两个视口：三段在 1440 下整行放得下（桌面不回退），在 390 下必然
 * 溢出，只有上级段先收缩才能给当前 Thread 段腾出位置。会话名取自已发送的第一条消息
 * （真实默认命名的来源）；Thread 名同时覆盖短名 main 与长名。
 */
const BREADCRUMB_NAMES: Record<HarnessLocale, {
  chatTitle: string
  sessionName: string
  shortThread: string
  longThread: string
}> = {
  'zh-CN': {
    chatTitle: '季度发布复盘与回归计划',
    sessionName: '帮我把季度发布流程整理成可执行的检查清单并补上回滚步骤与责任人',
    shortThread: 'main',
    longThread: '回滚检查清单复核讨论会',
  },
  'en-US': {
    chatTitle: 'Quarterly release retrospective',
    sessionName: 'Turn the quarterly release checklist into verifiable steps with rollback notes and owners',
    shortThread: 'main',
    longThread: 'rollback checklist review',
  },
}

/**
 * 被压缩后当前 Thread 段至少要保留的可见字符数。中文单字约占两个西文字符宽度，
 * 5 个汉字的可辨认程度与 8 个西文字符相当。
 */
const THREAD_PREFIX_CHARS: Record<HarnessLocale, number> = { 'zh-CN': 5, 'en-US': 8 }

/** 一段面包屑的真实排版读数：可见字符数、截断状态与省略号是否有位置画。 */
interface BreadcrumbSegmentGeometry {
  text: string
  /** 在可见框内以同款字体完整容纳的最长字符前缀长度。 */
  visibleChars: number
  /** 完整文本比可见框宽：内容被截断。 */
  truncated: boolean
  /** 可见框放得下「…」：放不下时浏览器只会硬裁剪，用户看不到省略号。 */
  ellipsisRoom: boolean
  textOverflow: string
  overflowX: string
}

/** 用与元素一致的计算字体测文本：可见字符数与省略号位置都由真实字体宽度推导。 */
function readBreadcrumbSegment(segment: Locator): Promise<BreadcrumbSegmentGeometry> {
  return segment.evaluate((element) => {
    const style = window.getComputedStyle(element)
    const text = element.textContent ?? ''
    const canvas = document.createElement('canvas').getContext('2d')!
    canvas.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    const textWidth = (value: string) => canvas.measureText(value).width
    // clientWidth 为整数可见宽度（不含 padding/border），即能画出多少内容的上限。
    const limit = element.clientWidth
    let visibleChars = 0
    while (visibleChars < text.length && textWidth(text.slice(0, visibleChars + 1)) <= limit + 0.5) {
      visibleChars += 1
    }
    return {
      text,
      visibleChars,
      truncated: textWidth(text) > limit + 0.5,
      ellipsisRoom: limit >= textWidth('…'),
      textOverflow: style.textOverflow,
      overflowX: style.overflowX,
    }
  })
}

/** 被截断的段必须以省略号收尾：省略号截断样式 + 可见框真的容得下「…」。 */
function expectEllipsisTail(geometry: BreadcrumbSegmentGeometry, label: string) {
  expect(geometry.truncated, `${label} 被截断`).toBe(true)
  expect(geometry.textOverflow, `${label} 使用省略号截断`).toBe('ellipsis')
  expect(geometry.overflowX, `${label} 裁掉溢出的内容`).not.toBe('visible')
  expect(geometry.ellipsisRoom, `${label} 的可见宽度容得下省略号`).toBe(true)
}

/**
 * 面包屑几何验收。
 *
 * - 容器不横向溢出，三段都在容器内；
 * - 当前 Thread 段：短名整段显示，长名至少保留可辨认前缀，被截断时必须画出省略号；
 * - 上级段（Chat 标题与 Session）：390 先收缩并画省略号，1440 完整显示（桌面不回退）。
 */
async function expectBreadcrumb({
  page,
  locale,
  viewport,
  wholeThreadName,
}: {
  page: Page
  locale: HarnessLocale
  viewport: { width: number; height: number }
  wholeThreadName: boolean
}) {
  const names = BREADCRUMB_NAMES[locale]
  const narrow = viewport.width < WIDE
  const breadcrumb = page.locator('.chat-workspace-breadcrumb')
  await expect(breadcrumb).toBeVisible()
  const segments = {
    chat: breadcrumb.locator('[data-breadcrumb="chat"]'),
    session: breadcrumb.locator('[data-breadcrumb="session"]'),
    branch: breadcrumb.locator('[data-breadcrumb="branch"]'),
  }
  await expect(segments.chat).toHaveText(names.chatTitle)
  await expect(segments.session).toHaveText(names.sessionName)
  await expect(segments.branch).toHaveText(wholeThreadName ? names.shortThread : names.longThread)

  expect(await breadcrumb.evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
    `${locale} ${viewportKey(viewport)} 面包屑不横向溢出`).toBe(true)
  const header = page.locator('.chat-workspace-header')
  expect(await header.evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
    `${locale} ${viewportKey(viewport)} 顶栏不横向溢出`).toBe(true)
  if (narrow) {
    const availableWidth = await header.evaluate((element) => {
      const style = getComputedStyle(element)
      return element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
    })
    expect((await breadcrumb.boundingBox())!.width).toBeCloseTo(availableWidth, 0)
  }
  for (const [label, segment] of Object.entries(segments)) {
    expect(await isInside(segment, breadcrumb), `${label} 段落在面包屑内`).toBe(true)
    expect(await segment.evaluate((element) => element.getBoundingClientRect().width > 0),
      `${label} 段未被压成零宽`).toBe(true)
  }

  // 当前 Thread 段：短名完整、长名保留可辨认前缀。
  const branch = await readBreadcrumbSegment(segments.branch)
  if (wholeThreadName || !narrow) {
    expect(branch.truncated, `${locale} Thread 名 ${branch.text} 整段显示`).toBe(false)
    expect(branch.visibleChars, `${locale} Thread 名 ${branch.text} 全部字符可见`)
      .toBe(branch.text.length)
  } else {
    const keep = Math.min(THREAD_PREFIX_CHARS[locale], branch.text.length)
    expect(branch.visibleChars, `${locale} 长 Thread 名至少保留 ${keep} 个可辨认字符`)
      .toBeGreaterThanOrEqual(keep)
    if (branch.truncated) {
      expectEllipsisTail(branch, `${locale} 当前 Thread 段`)
    }
  }

  // 上级段先收缩：390 两个上级段都必须保留省略号，1440 完整显示。
  for (const [label, segment] of [
    [`${locale} Chat 标题段`, segments.chat],
    [`${locale} Session 段`, segments.session],
  ] as const) {
    const geometry = await readBreadcrumbSegment(segment)
    if (narrow) {
      expectEllipsisTail(geometry, label)
    } else {
      expect(geometry.truncated, `${label} 在 ${viewport.width} 宽完整显示`).toBe(false)
    }
  }
}

test('breadcrumb keeps main whole beside Back to conversation at IDE split width', async ({ page }, testInfo) => {
  const { chatTitle, sessionName, shortThread } = BREADCRUMB_NAMES['en-US']
  await installChatApi(page, { names: { chatTitle, sessionName, threadName: shortThread } })
  await bindFirstPaneToThread(page)
  const viewport = { width: 1440, height: 900 }
  await openHarness(page, 'en-US', viewport)
  // Model the width left by the 720px IDE and its 8px divider without starting the IDE.
  await page.getByTestId('scenario').evaluate((frame) => { frame.style.width = '712px' })
  await expect(page.getByRole('link', { name: 'Back to conversation' })).toBeVisible()
  const breadcrumb = page.locator('.chat-workspace-breadcrumb')
  const branch = breadcrumb.locator('[data-breadcrumb="branch"]')
  await expect(branch).toHaveText(shortThread)
  expect(await isInside(branch, breadcrumb)).toBe(true)
  expect((await readBreadcrumbSegment(branch)).truncated, 'main should fit in full').toBe(false)
  await captureShot(page, testInfo, 'breadcrumb-ide-split-en-US-1440x900')
})

for (const locale of LOCALES) {
  for (const viewport of VIEWPORTS) {
    test(`breadcrumb keeps a short Thread name whole while parents shrink (${locale}, ${viewportKey(viewport)})`, async ({
      page,
    }, testInfo) => {
      const names = BREADCRUMB_NAMES[locale]
      await installChatApi(page, {
        names: { chatTitle: names.chatTitle, sessionName: names.sessionName, threadName: names.shortThread },
      })
      await bindFirstPaneToThread(page)
      await openHarness(page, locale, viewport)

      await expect(page.locator('.chat-workspace-breadcrumb')).toHaveAttribute('data-focused-pane', 'pane-1')
      await expectBreadcrumb({ page, locale, viewport, wholeThreadName: true })
      await captureShot(page, testInfo, shotName(locale, viewport, 'breadcrumb-short-thread'))
    })

    test(`breadcrumb keeps a long Thread name recognizable in Chat and Debug topbar (${locale}, ${viewportKey(viewport)})`, async ({
      page,
    }, testInfo) => {
      const names = BREADCRUMB_NAMES[locale]
      await installChatApi(page, {
        names: { chatTitle: names.chatTitle, sessionName: names.sessionName, threadName: names.longThread },
      })
      await bindFirstPaneToThread(page)
      await openHarness(page, locale, viewport)

      await expectBreadcrumb({ page, locale, viewport, wholeThreadName: false })
      await captureShot(page, testInfo, shotName(locale, viewport, 'breadcrumb-long-thread'))

      // Debug 顶栏与 Chat 顶栏共用同一面包屑：覆盖层里同样先收缩上级段。
      const composer = page.getByLabel(COPY[locale].composer).first()
      await composer.click()
      await composer.pressSequentially('/debug')
      await page.keyboard.press('Enter')
      await expect(page.getByRole('button', { name: COPY[locale].closeDebug, exact: true })).toBeVisible()
      await expectBreadcrumb({ page, locale, viewport, wholeThreadName: false })
      await captureShot(page, testInfo, shotName(locale, viewport, 'breadcrumb-debug'))
    })
  }
}
