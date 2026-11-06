import { expect, test, type Page } from './fixture'
import type { Route } from '@playwright/test'

/**
 * Pane 根控制面真实浏览器回归（独立基座、独立端口 5184）。
 *
 * 全部数据由 `page.route` 提供，覆盖真实 AgentPane 的根 Composer、活跃子代理树与
 * 根交互汇聚，以及子代理独立地址。这里验证的正是 jsdom 无法证明的行为：
 * `inert` 隐藏根不接收点击、不响应快捷键，上传注册表在查看子代理期间保持存活。
 */

const ROOT_ID = 'a1000000-0000-4000-8000-0000000000a1'
const CHILD_ID = 'a1000000-0000-4000-8000-0000000000a2'
const HARNESS_URL = '/browser-tests/pane-control-harness.html'
const ATTACHMENT_NAME = 'pane-note.txt'

interface Recorded {
  stopRequests: Array<Record<string, unknown>>
  commandRequests: Array<Record<string, unknown>>
  uploadCalls: string[]
  deletedUploads: string[]
}

function rootThread(overrides: Record<string, unknown> = {}) {
  return {
    name: '根 Thread',
    threadId: ROOT_ID,
    sessionId: 'b1000000-0000-4000-8000-0000000000b1',
    headEntryId: null,
    parentThreadId: null,
    rootThreadId: ROOT_ID,
    yoloEnabled: false,
    nextCommandSequence: '2',
    version: '1',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
      environmentName: null,
      goal: null,
    },
    createdAt: 1790812800,
    updatedAt: 1790812800,
    ...overrides,
  }
}

function childThread() {
  const thread = rootThread()
  return {
    ...thread,
    name: 'worker child',
    threadId: CHILD_ID,
    parentThreadId: ROOT_ID,
    status: 'RUNNING',
    // 子线程不保存环境选择：快照冻结的是规划该回合时执行根已生效的环境。
    branchSettings: { ...thread.branchSettings, environmentName: 'NAS' },
  }
}

/** 环境注册表：名称与子线程快照冻结值一致。 */
function environmentCards() {
  return [{
    id: 'a2000000-0000-4000-8000-0000000000e1',
    name: 'NAS',
    status: 'READY',
    statusExpiresAt: null,
    ready: true,
    lastSeenAt: 1790812800000,
    capabilities: [],
    userName: null,
    homeDirectory: null,
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
  }]
}

function snapshot(thread: Record<string, unknown>) {
  return {
    version: thread.version ?? '1',
    thread,
    baseEntryId: null,
    entries: [],
    queuedCommands: [],
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

/** 根空闲、子代理处理中的执行树：Stop 必须仍然停在执行根上。 */
function treeNodes() {
  return [
    {
      threadId: ROOT_ID,
      parentThreadId: null,
      name: '根 Thread',
      agentName: 'assistant',
      model: { modelName: 'VeryLongModelName', variant: 'default' },
      status: 'IDLE',
      executionControl: 'RUNNABLE',
      turnCount: 1,
      toolCallCount: 0,
      outcome: null,
      updatedAt: 1774958400,
    },
    {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'worker child',
      agentName: 'coder',
      model: { modelName: 'VeryLongModelName', variant: 'default' },
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      turnCount: 2,
      toolCallCount: 1,
      outcome: null,
      updatedAt: 1774958400,
    },
  ]
}

/** 根交互汇聚：来源是子 Thread 的待决审批。 */
function interactionItems() {
  return {
    items: [
      {
        toolInvocationId: 'pane-approval-1',
        threadId: CHILD_ID,
        rootThreadId: ROOT_ID,
        sessionId: 'b1000000-0000-4000-8000-0000000000b1',
        owner: {
          kind: 'CHAT', id: 'chat-pane-harness', title: 'Pane harness',
          link: '/chats/chat-pane-harness',
        },
        toolCallId: 'call-pane-1',
        type: 'APPROVAL',
        toolName: 'bash',
        argumentsJson: JSON.stringify({ command: 'npm test' }),
        approval: { required: true, reason: '需要确认' },
        createdAt: 1791194220000,
      },
    ],
    total: 1,
    freshnessAt: 1791194220000,
    nextCursor: null,
  }
}

async function installPaneApi(
  page: Page,
  options: {
    rootEntries?: Array<Record<string, unknown>>
    rootThreadOverrides?: Record<string, unknown>
    rootSnapshotOverrides?: Record<string, unknown>
  } = {},
): Promise<Recorded> {
  const recorded: Recorded = {
    stopRequests: [],
    commandRequests: [],
    uploadCalls: [],
    deletedUploads: [],
  }
  await page.routeWebSocket(/\/ws\/events$/, () => {})
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path === `/api/harness/threads/${ROOT_ID}/stop` && method === 'POST') {
      recorded.stopRequests.push(request.postDataJSON())
      await route.fulfill({
        json: {
          status: 'STOPPED',
          thread: rootThread({ status: 'IDLE', executionControl: 'STOPPED', version: '2' }),
          stoppedThreads: [],
        },
      })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}/command-batches` && method === 'POST') {
      recorded.commandRequests.push(request.postDataJSON())
      await route.fulfill({ json: { accepted: true, thread: rootThread() } })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}/tree`) {
      await route.fulfill({ json: treeNodes() })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}`) {
      const thread = rootThread(options.rootThreadOverrides)
      const data = {
        ...snapshot(thread),
        entries: options.rootEntries ?? [],
        ...(options.rootSnapshotOverrides ?? {}),
      }
      await route.fulfill({ json: data })
      return
    }
    if (path === `/api/harness/threads/${CHILD_ID}`) {
      await route.fulfill({ json: snapshot(childThread()) })
      return
    }
    if (path === '/api/harness/interactions' && method === 'GET') {
      await route.fulfill({ json: interactionItems() })
      return
    }
    if (path === '/api/platform/agents') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/platform/models') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/platform/environments') {
      await route.fulfill({ json: environmentCards() })
      return
    }
    if (path === '/api/platform/storage/uploads' && method === 'POST') {
      recorded.uploadCalls.push('reserve')
      await route.fulfill({
        json: {
          id: 'c1000000-0000-4000-8000-0000000000c1',
          state: 'PENDING',
          blobId: null,
          presignedPut: { method: 'PUT', url: 'https://storage.test/pane-put', headers: {} },
          expiresAt: null,
        },
      })
      return
    }
    if (path === '/api/platform/storage/uploads/c1000000-0000-4000-8000-0000000000c1/complete') {
      recorded.uploadCalls.push('complete')
      await route.fulfill({
        json: {
          id: 'c1000000-0000-4000-8000-0000000000c1',
          state: 'READY',
          blobId: 'blob-pane-1',
          presignedPut: null,
          expiresAt: null,
        },
      })
      return
    }
    if (path.startsWith('/api/platform/storage/uploads/') && method === 'DELETE') {
      recorded.deletedUploads.push(path)
      await route.fulfill({ status: 204 })
      return
    }
    await route.fulfill({ json: {} })
  })
  await page.route('https://storage.test/**', async (route) => {
    await route.fulfill({ status: 200, body: '' })
  })
  return recorded
}

/**
 * 查看层/独立地址的返回入口必须在顶部标题区、且在 transcript 之上且不在滚动容器内：
 * 这里用真实布局（boundingBox + computed overflow）证明，而不是只断言元素存在。
 */
async function expectBackEntryOnTop(page: Page, name: string, role: 'button' | 'link' = 'button') {
  const back = page.getByRole(role, { name })
  const box = await back.boundingBox()
  expect(box).not.toBeNull()
  const transcriptBox = await page.locator('[role="log"]:visible').first().boundingBox()
  expect(transcriptBox).not.toBeNull()
  expect(box!.y + box!.height).toBeLessThanOrEqual(transcriptBox!.y)
  expect(await back.evaluate((node) => (
    node.closest('.chat-workspace-header, .agent-pane-thread-heading') != null
      && node.closest('.chat-log, .thread-transcript') == null
  ))).toBe(true)
  // 标题区不随 transcript 滚动：返回入口与滚动容器之间没有滚动祖先。
  expect(await back.evaluate((node) => {
    let current = node.parentElement
    while (current != null && !current.classList.contains('chat-main')) {
      const { overflowY, overflow } = getComputedStyle(current)
      if (['auto', 'scroll'].includes(overflowY) || ['auto', 'scroll'].includes(overflow)) {
        return current.className
      }
      current = current.parentElement
    }
    return null
  })).toBeNull()
  return back
}

async function openRootPane(page: Page, recorded: Recorded) {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HARNESS_URL)
  const composer = page.locator('.thread-composer')
  await expect(composer).toBeVisible()
  // 执行根身份由 Chat workspace 顶栏呈现；根控制面挂载才是就绪判据。
  await expect(page.locator('.thread-control-area')).toBeVisible()
  return { composer, recorded }
}

/** 用编辑器 pill 与附件条一起证明上传注册表还活着（两者都来自该注册表）。 */
async function expectAttachmentAlive(page: Page) {
  await expect(
    page.locator(`.composer-pill[data-part-type="attachment"][data-filename="${ATTACHMENT_NAME}"]`),
  ).toHaveCount(1)
  await expect(
    page.locator(`.attachment-reference[data-filename="${ATTACHMENT_NAME}"]`),
  ).toHaveCount(1)
}

test('root draft and uploaded attachment survive observing a child and coming back', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)

  // 1. 根草稿 + 已上传附件：两者都只存在于根控制面自己的状态里。
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('根草稿要保留')
  await page.locator('input[type="file"].composer-file-input-hidden').setInputFiles({
    name: ATTACHMENT_NAME,
    mimeType: 'text/plain',
    buffer: Buffer.from('pane attachment'),
  })
  await expectAttachmentAlive(page)
  // 直传完成是异步的：等到注册表走完 reserve → complete，再断言没有多余上传。
  await expect.poll(() => recorded.uploadCalls).toEqual(['reserve', 'complete'])

  // 2. 活跃子代理树里点进子层：同一 pane 内观察，URL 不变。
  const treeRow = page.locator('.active-thread-tree').getByRole('link', { name: /worker child/ })
  await expect(treeRow).toBeVisible()
  await treeRow.click()

  await expect(page.getByText('只读查看')).toBeVisible()
  const backToRootLayer = await expectBackEntryOnTop(page, '回到父 agent')
  await expect(backToRootLayer).toBeVisible()
  expect(new URL(page.url()).pathname).toBe(HARNESS_URL)
  // 上传注册表没有被卸载释放：没有 DELETE，也没有第二次 reserve。
  expect(recorded.deletedUploads).toEqual([])
  expect(recorded.uploadCalls).toEqual(['reserve', 'complete'])

  // 3. 隐藏的根层仍是同一个挂载树：草稿与附件原地保留。
  const hiddenRoot = page.locator('.chat-pane-layer[hidden]')
  await expect(hiddenRoot.locator('.composer-editor')).toContainText('根草稿要保留')
  await expectAttachmentAlive(page)

  // 只读子层无命令或顶栏入口，隐藏根上传注册表不能释放或重建。
  await expect(page.getByRole('button', { name: 'Debug', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看 subagent 执行' })).toHaveCount(0)
  await expect(composer).toBeHidden()
  expect(recorded.uploadCalls).toEqual(['reserve', 'complete'])
  expect(recorded.deletedUploads).toEqual([])
  await expectAttachmentAlive(page)

  // 4. 逐层返回后根层重新可见，草稿与附件都还在。
  await backToRootLayer.click()
  await expect(composer).toBeVisible()
  await expect(editor).toContainText('根草稿要保留')
  await expectAttachmentAlive(page)
  expect(recorded.deletedUploads).toEqual([])
  // 根仍从命令进入 Debug；挂起/恢复不重建上传注册表，也不丢草稿。
  await page.getByRole('button', { name: '打开命令表' }).click()
  await page.getByRole('option', { name: /debug/ }).click()
  await expect(page.getByRole('button', { name: '关闭 Debug', exact: true })).toHaveCount(1)
  await expect(composer).toBeHidden()
  await page.getByRole('button', { name: '关闭 Debug', exact: true }).click()
  await expect(editor).toContainText('根草稿要保留')
  await expectAttachmentAlive(page)
  expect(recorded.uploadCalls).toEqual(['reserve', 'complete'])
  expect(recorded.deletedUploads).toEqual([])
})

test('the hidden inert root ignores Escape and cannot be clicked', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('根草稿要保留')

  await page.locator('.active-thread-tree').getByRole('link', { name: /worker child/ }).click()
  await expect(page.getByRole('button', { name: '回到父 agent' })).toBeVisible()

  const hiddenLayer = page.locator('.chat-pane-layer[hidden]')
  await expect(hiddenLayer).toHaveCount(1)
  // inert 是真实浏览器语义：隐藏层不可聚焦、不可点击、不参与无障碍树。
  await expect(hiddenLayer).toHaveAttribute('inert', '')
  expect(await hiddenLayer.evaluate((element) => (element as HTMLElement).inert)).toBe(true)
  expect(await hiddenLayer.evaluate((element) => (
    element.querySelector('.composer-editor')?.matches(':focus-within') ?? true
  ))).toBe(false)

  // Escape：既不把焦点抢回隐藏的根，也不提交任何命令。
  await page.keyboard.press('Escape')
  const focusInsideHidden = await page.evaluate(() => (
    document.activeElement?.closest('[hidden]') != null
  ))
  expect(focusInsideHidden).toBe(false)
  expect(recorded.commandRequests).toEqual([])
  expect(recorded.stopRequests).toEqual([])

  // 隐藏层的按钮在真实浏览器里收不到用户点击（inert）：强行派发也不会提交。
  await hiddenLayer.locator('.composer-editor').dispatchEvent('click')
  await page.keyboard.type('不应该进入隐藏根')
  await page.keyboard.press('Enter')
  expect(recorded.commandRequests).toEqual([])
  await expect(hiddenLayer.locator('.composer-editor')).toContainText('根草稿要保留')

  // 返回后根层重新可交互：焦点回到可见层，输入继续落在根草稿上。
  await page.getByRole('button', { name: '回到父 agent' }).click()
  await expect(composer).toBeVisible()
  expect(await page.evaluate(() => document.activeElement?.closest('[hidden]') == null)).toBe(true)
  await editor.click()
  await page.keyboard.press('End')
  await page.keyboard.type('（返回后继续）')
  await expect(editor).toContainText('（返回后继续）')
  expect(recorded.commandRequests).toEqual([])
})

test('a child thread address mounts no composer at all', async ({ page }) => {
  const recorded = await installPaneApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(`${HARNESS_URL}?scenario=thread-child`)

  await expect(page.getByRole('heading', { name: 'worker child', level: 1 })).toBeVisible()
  await expect(page.locator('.chat-workspace-header .workspace-view-identity')).toContainText('VeryLongModelName')
  await expect(page.getByText('只读查看')).toHaveCount(0)
  const backToRoot = await expectBackEntryOnTop(page, '回到父 agent', 'link')
  await expect(backToRoot).toBeVisible()
  await expect(backToRoot).toHaveAttribute('href', '/threads/' + ROOT_ID)
  // 子线程只读展示快照冻结的环境，并标明跟随主对话；执行根不可在此切换。
  await expect(page.getByText('环境：NAS · 跟随主对话')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('child-thread-environment.png'), fullPage: false })
  await expect(page.locator('.thread-composer')).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '回到对话' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Debug', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看 subagent 执行' })).toHaveCount(0)
  expect(recorded.uploadCalls).toEqual([])
  expect(recorded.commandRequests).toEqual([])
  expect(recorded.stopRequests).toEqual([])

  // 绑定子代理目标的 pane 同样没有 Composer，并保留同一环境行。
  await page.goto(`${HARNESS_URL}?scenario=pane-child`)
  await expect(page.getByRole('heading', { name: 'worker child' })).toBeVisible()
  await expect(page.getByText('环境：NAS · 跟随主对话')).toBeVisible()
  await expect(page.locator('.thread-composer')).toHaveCount(0)
})

test('an idle root still stops its running subtree with a root-scoped request', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)

  // 根自身 IDLE，子代理在处理：Stop 仍可用，并且只落在执行根上。
  await expect(page.locator('.active-thread-tree')).toBeVisible()
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('/stop')
  const palette = composer.locator('.thread-command-palette')
  await expect(palette).toBeVisible()
  const stopItem = palette.locator('button', { hasText: 'stop' })
  await expect(stopItem).not.toHaveAttribute('disabled', '')
  await stopItem.click()

  await expect.poll(() => recorded.stopRequests.length).toBe(1)
  expect(Object.keys(recorded.stopRequests[0] ?? {}).sort())
    .toEqual(['expectedVersion', 'stopRequestId'])
  expect(recorded.stopRequests[0]?.expectedVersion).toBe('1')
  // 根交互汇聚的来源显示会话归属，不暴露来源 Thread 身份；决策仍写回原始 Thread。
  await expect(page.getByRole('button', { name: 'Chat · Pane harness' })).toBeVisible()
})

test('switching environment on a root with running subagents warns it applies to them', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)
  await expect(page.locator('.active-thread-tree')).toBeVisible()

  const environmentTrigger = composer.getByRole('button', { name: '环境', exact: true })
  await expect(environmentTrigger).toContainText('None')
  await environmentTrigger.click()
  await expect(page.locator('.thread-composer-menu-hint'))
    .toHaveText('切换对正在运行的子 Agent 同样生效')
  await expect(page.getByRole('option', { name: 'NAS' })).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('environment-switch-notice.png'), fullPage: false })

  await page.getByRole('option', { name: 'NAS' }).click()
  await expect(environmentTrigger).toContainText('NAS')
  // 提示不替代连接动作：空闲根只改面板草稿，不立即写命令。
  expect(recorded.commandRequests).toEqual([])
})

test('the goal panel closes on Escape and restores focus to the editable composer', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('/goal')
  const palette = composer.locator('.thread-command-palette')
  const goalItem = palette.locator('button', { hasText: 'goal' })
  await expect(goalItem).toBeVisible()
  await goalItem.click()

  const panel = page.locator('.thread-interaction-panel[aria-label="thread 目标"]')
  await expect(panel).toBeVisible()
  // 打开即把焦点放进面板内的目标输入框：Esc 不再依赖被接管（active=false）的 Composer。
  await expect(panel.locator('.ui-textarea')).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(panel).toBeHidden()
  // 关闭后焦点回到当前可编辑 Composer 编辑器；输入直接落在草稿上，且不提交/不停。
  await expect(editor).toBeFocused()
  await page.keyboard.type('继续输入')
  await expect(editor).toContainText('继续输入')
  expect(recorded.commandRequests).toEqual([])
  expect(recorded.stopRequests).toEqual([])
})

test('recognizes SETTINGS once and uses the same model label in the footer', async ({ page }, testInfo) => {
  // 安全点立即展示变更；后续相同回合快照不重复，底栏文字与提示同源。
  const rootSettings = {
    agentName: 'assistant',
    execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
    environmentName: null,
  }
  const changedSettings = {
    agentName: 'researcher',
    execution: { driver: 'builtin', settings: { modelName: 'grok-4.7', variant: 'xhigh' } },
    environmentName: null,
    goal: null,
  }
  const rootEntries = [
    {
      entryId: 'entry-root',
      sessionId: 'b1000000-0000-4000-8000-0000000000b1',
      parentEntryId: null,
      entryType: 'ROOT',
      payload: { settings: rootSettings },
      createdAt: 1790812800,
    },
    {
      entryId: 'entry-turn-1',
      sessionId: 'b1000000-0000-4000-8000-0000000000b1',
      parentEntryId: 'entry-root',
      entryType: 'TURN_START',
      payload: { reason: 'INPUT', settings: rootSettings },
      createdAt: 1790812801,
    },
    {
      entryId: 'entry-user-1',
      sessionId: 'b1000000-0000-4000-8000-0000000000b1',
      parentEntryId: 'entry-turn-1',
      entryType: 'MESSAGE',
      payload: {
        message: { role: 'USER', contents: [{ type: 'text', text: 'hi' }] },
      },
      createdAt: 1790812802,
    },
    {
      entryId: 'entry-turn-end-1',
      sessionId: 'b1000000-0000-4000-8000-0000000000b1',
      parentEntryId: 'entry-user-1',
      entryType: 'TURN_END',
      payload: { outcome: 'COMPLETED', turnStartEntryId: 'entry-turn-1' },
      createdAt: 1790812803,
    },
    {
      entryId: 'entry-settings-head',
      sessionId: 'b1000000-0000-4000-8000-0000000000b1',
      parentEntryId: 'entry-turn-end-1',
      entryType: 'SETTINGS',
      payload: {
        settings: changedSettings,
        ownerThreadId: ROOT_ID,
      },
      createdAt: 1790812804,
    },
  ]

  const threadOverrides = {
    branchSettings: changedSettings,
    headEntryId: 'entry-settings-head',
    version: '1',
  }
  const recorded = await installPaneApi(page, {
    rootEntries,
    rootThreadOverrides: threadOverrides,
  })

  await openRootPane(page, recorded)

  await expect(page.getByText(/未识别 Entry|未知条目/)).toHaveCount(0)
  await expect(page.getByText('SETTINGS', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)

  const agentChange = page.getByText('Agent 改为 researcher', { exact: true })
  const modelChange = page.getByText('模型改为 grok-4.7 (xhigh)', { exact: true })
  await expect(agentChange).toBeVisible()
  await expect(agentChange).toHaveCount(1)
  await expect(modelChange).toBeVisible()
  await expect(modelChange).toHaveCount(1)

  const modelControl = page.locator('.thread-composer-model-control')
  await expect(modelControl).toBeVisible()
  await expect(modelControl).toHaveText('grok-4.7 (xhigh)')
  await expect(modelControl).toHaveAttribute('title', 'grok-4.7 (xhigh)')

  rootEntries.push({
    entryId: 'entry-next-turn',
    sessionId: 'b1000000-0000-4000-8000-0000000000b1',
    parentEntryId: 'entry-settings-head',
    entryType: 'TURN_START',
    payload: { reason: 'INPUT', settings: changedSettings },
    createdAt: 1790812805,
  })
  threadOverrides.headEntryId = 'entry-next-turn'
  threadOverrides.version = '2'
  await page.reload()
  await expect(agentChange).toHaveCount(1)
  await expect(modelChange).toHaveCount(1)
  await expect(page.getByText(/未识别 Entry|未知条目/)).toHaveCount(0)
  await expect(modelControl).toHaveText('grok-4.7 (xhigh)')
  await page.screenshot({ path: testInfo.outputPath('settings-model-label.png'), fullPage: true })
})

test('thread status three-state and execution facts in real browser', async ({ page }) => {
  // 1. WAITING 状态：展示“等待异步结果”，且无脉冲动画（animationName 为 none，data-animated=false）
  const waitingRecorded = await installPaneApi(page, {
    rootThreadOverrides: { status: 'WAITING', executionControl: 'RUNNABLE' },
  })
  await openRootPane(page, waitingRecorded)
  const waitingWorking = page.locator('.thread-working')
  await expect(waitingWorking).toBeVisible()
  await expect(waitingWorking).toContainText('等待异步结果')
  await expect(waitingWorking).toHaveAttribute('data-animated', 'false')
  const waitingDot = waitingWorking.locator('.thread-working-dot')
  await expect(waitingDot).toHaveClass(/is-static/)
  const waitingAnim = await waitingDot.evaluate((el) => getComputedStyle(el).animationName)
  expect(waitingAnim).toBe('none')

  // 2. RUNNING 且无 active model/tool：展示“运行中”，绝不直接显示生成，且无动画
  await page.route(`/api/harness/threads/${ROOT_ID}`, async (route) => {
    const thread = rootThread({ status: 'RUNNING', executionControl: 'RUNNABLE' })
    await route.fulfill({ json: snapshot(thread) })
  })
  await page.reload()
  const runningWorking = page.locator('.thread-working')
  await expect(runningWorking).toBeVisible()
  await expect(runningWorking).toContainText('运行中')
  await expect(runningWorking).not.toContainText('生成')
  await expect(runningWorking).toHaveAttribute('data-animated', 'false')
  const runningDot = runningWorking.locator('.thread-working-dot')
  await expect(runningDot).toHaveClass(/is-static/)
  const runningAnim = await runningDot.evaluate((el) => getComputedStyle(el).animationName)
  expect(runningAnim).toBe('none')

  // 3. STOPPED 独立控制不可遮蔽在途执行中的 model/tool：优先展示“执行中”，且带生成动画
  await page.route(`/api/harness/threads/${ROOT_ID}`, async (route) => {
    const thread = rootThread({ status: 'RUNNING', executionControl: 'STOPPED' })
    const data = {
      ...snapshot(thread),
      toolInvocations: [{
        id: 'running-tool-browser',
        threadId: ROOT_ID,
        turnStartEntryId: 'turn-1',
        assistantEntryId: 'assistant-1',
        callIndex: 0,
        status: 'RUNNING',
        attempt: 1,
        toolCallId: 'call-active-1',
        toolName: 'bash',
        rendererKey: 'bash',
        environmentId: null,
        requiredEnvironmentId: null,
        waitingForEnvironment: false,
        requiredEnvironmentName: null,
        environmentWaitFreshnessAt: null,
        argumentsJson: '{"command":"ls"}',
        approval: null,
        result: null,
        error: null,
        createdAt: 1790812800,
        updatedAt: 1790812800,
      }],
    }
    await route.fulfill({ json: data })
  })
  await page.reload()
  const activeWorking = page.locator('.thread-working')
  await expect(activeWorking).toBeVisible()
  await expect(activeWorking).toContainText('执行中')
  await expect(activeWorking).not.toContainText('已停止')
  await expect(activeWorking).toHaveAttribute('data-animated', 'true')
  const activeDot = activeWorking.locator('.thread-working-dot')
  await expect(activeDot).not.toHaveClass(/is-static/)
  const activeAnim = await activeDot.evaluate((el) => getComputedStyle(el).animationName)
  expect(activeAnim).toContain('thread-pulse')

  // 4. 审批阻塞准确展示文字且无动画
  await page.route(`/api/harness/threads/${ROOT_ID}`, async (route) => {
    const thread = rootThread({ status: 'RUNNING', executionControl: 'RUNNABLE' })
    const data = {
      ...snapshot(thread),
      toolInvocations: [{
        id: 'approval-tool-browser',
        threadId: ROOT_ID,
        turnStartEntryId: 'turn-1',
        assistantEntryId: 'assistant-1',
        callIndex: 0,
        status: 'WAITING_APPROVAL',
        attempt: 1,
        toolCallId: 'call-approval-1',
        toolName: 'bash',
        rendererKey: 'bash',
        environmentId: null,
        requiredEnvironmentId: null,
        waitingForEnvironment: false,
        requiredEnvironmentName: null,
        environmentWaitFreshnessAt: null,
        argumentsJson: '{"command":"rm -rf /"}',
        approval: null,
        result: null,
        error: null,
        createdAt: 1790812800,
        updatedAt: 1790812800,
      }],
    }
    await route.fulfill({ json: data })
  })
  await page.reload()
  const approvalWorking = page.locator('.thread-working')
  await expect(approvalWorking).toBeVisible()
  await expect(approvalWorking).toContainText('等待审批')
  await expect(approvalWorking).toHaveAttribute('data-animated', 'false')
  const approvalDot = approvalWorking.locator('.thread-working-dot')
  await expect(approvalDot).toHaveClass(/is-static/)
  const approvalAnim = await approvalDot.evaluate((el) => getComputedStyle(el).animationName)
  expect(approvalAnim).toBe('none')
})
