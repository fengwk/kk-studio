import { expect, test, type Page, type Route } from './fixture'

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
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '2',
    version: '1',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'VeryLongModelName', variant: 'default' },
      environmentName: null,
    },
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function childThread() {
  return {
    ...rootThread(),
    name: 'worker child',
    threadId: CHILD_ID,
    parentThreadId: ROOT_ID,
    status: 'MODEL_STREAM',
    processing: true,
    yoloPolicy: { mode: 'FOLLOW', rootThreadId: ROOT_ID },
  }
}

function snapshot(thread: Record<string, unknown>) {
  return {
    version: thread.version ?? '1',
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

/** 根空闲、子代理处理中的执行树：Stop 必须仍然停在执行根上。 */
function treeNodes() {
  return [
    {
      threadId: ROOT_ID,
      parentThreadId: null,
      name: '根 Thread',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'VeryLongModelName', variant: 'default' },
      status: 'IDLE',
      processing: false,
      turnCount: 1,
      toolCallCount: 0,
      outcome: null,
    },
    {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'worker child',
      agentName: 'coder',
      model: { providerName: 'minimax', modelName: 'VeryLongModelName', variant: 'default' },
      status: 'MODEL_STREAM',
      processing: true,
      turnCount: 2,
      toolCallCount: 1,
      outcome: null,
    },
  ]
}

/** 根交互汇聚：来源是子 Thread 的待决审批。 */
function interactionItems() {
  return {
    items: [
      {
        interactionId: 'pane-approval-1',
        status: 'WAITING_APPROVAL',
        threadId: CHILD_ID,
        rootThreadId: ROOT_ID,
        sessionId: 'b1000000-0000-4000-8000-0000000000b1',
        owner: { type: 'CHAT', chatId: 'chat-pane-harness', issueId: null, agentName: null },
        toolCallId: 'call-pane-1',
        toolName: 'bash',
        argumentsJson: JSON.stringify({ command: 'npm test' }),
        approvalJson: JSON.stringify({ required: true, reason: '需要确认' }),
        createTime: '2026-10-05T09:57:00Z',
      },
    ],
    nextCursor: null,
  }
}

async function installPaneApi(page: Page): Promise<Recorded> {
  const recorded: Recorded = {
    stopRequests: [],
    commandRequests: [],
    uploadCalls: [],
    deletedUploads: [],
  }
  await page.routeWebSocket(/\/api\/events\/v1$/, () => {})
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path === `/api/harness/threads/${ROOT_ID}/stop` && method === 'POST') {
      recorded.stopRequests.push(request.postDataJSON())
      await route.fulfill({
        json: {
          status: 200,
          data: {
            status: 'STOPPED',
            thread: rootThread({ status: 'STOPPED', executionControl: 'STOPPED', version: '2' }),
            stoppedThreads: [],
          },
        },
      })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}/command-batches` && method === 'POST') {
      recorded.commandRequests.push(request.postDataJSON())
      await route.fulfill({ json: { status: 200, data: { accepted: true, thread: rootThread() } } })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}/tree`) {
      await route.fulfill({ json: { status: 200, data: treeNodes() } })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}`) {
      await route.fulfill({ json: { status: 200, data: snapshot(rootThread()) } })
      return
    }
    if (path === `/api/harness/threads/${CHILD_ID}`) {
      await route.fulfill({ json: { status: 200, data: snapshot(childThread()) } })
      return
    }
    if (path === '/api/interactions' && method === 'GET') {
      await route.fulfill({ json: { status: 200, data: interactionItems() } })
      return
    }
    if (path === '/api/ai/catalog/agents' || path === '/api/ai/catalog/models') {
      await route.fulfill({
        json: { status: 200, data: { pageNumber: 1, pageSize: 50, totalCount: 0, results: [] } },
      })
      return
    }
    if (path === '/api/harness/environments') {
      await route.fulfill({ json: { status: 200, data: [] } })
      return
    }
    if (path === '/api/storage/uploads' && method === 'POST') {
      recorded.uploadCalls.push('reserve')
      await route.fulfill({
        json: {
          status: 200,
          data: {
            id: 'c1000000-0000-4000-8000-0000000000c1',
            state: 'PENDING',
            blobId: null,
            presignedPut: { method: 'PUT', url: 'https://storage.test/pane-put', headers: {} },
            expiresAt: null,
          },
        },
      })
      return
    }
    if (path === '/api/storage/uploads/c1000000-0000-4000-8000-0000000000c1/complete') {
      recorded.uploadCalls.push('complete')
      await route.fulfill({
        json: {
          status: 200,
          data: {
            id: 'c1000000-0000-4000-8000-0000000000c1',
            state: 'READY',
            blobId: 'blob-pane-1',
            presignedPut: null,
            expiresAt: null,
          },
        },
      })
      return
    }
    if (path.startsWith('/api/storage/uploads/') && method === 'DELETE') {
      recorded.deletedUploads.push(path)
      await route.fulfill({ json: { status: 200, data: null } })
      return
    }
    await route.fulfill({ json: { status: 200, data: {} } })
  })
  await page.route('https://storage.test/**', async (route) => {
    await route.fulfill({ status: 200, body: '' })
  })
  return recorded
}

async function openRootPane(page: Page, recorded: Recorded) {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(HARNESS_URL)
  const composer = page.locator('.thread-composer')
  await expect(composer).toBeVisible()
  await expect(page.getByRole('heading', { name: '根 Thread' })).toBeVisible()
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

  await expect(page.getByRole('button', { name: '返回上一层' })).toBeVisible()
  await expect(page.getByText('只读查看')).toBeVisible()
  expect(new URL(page.url()).pathname).toBe(HARNESS_URL)
  // 上传注册表没有被卸载释放：没有 DELETE，也没有第二次 reserve。
  expect(recorded.deletedUploads).toEqual([])
  expect(recorded.uploadCalls).toEqual(['reserve', 'complete'])

  // 3. 隐藏的根层仍是同一个挂载树：草稿与附件原地保留。
  const hiddenRoot = page.locator('.chat-pane-layer[hidden]')
  await expect(hiddenRoot.locator('.composer-editor')).toContainText('根草稿要保留')
  await expectAttachmentAlive(page)

  // 4. 逐层返回后根层重新可见，草稿与附件都还在。
  await page.getByRole('button', { name: '返回上一层' }).click()
  await expect(composer).toBeVisible()
  await expect(editor).toContainText('根草稿要保留')
  await expectAttachmentAlive(page)
  expect(recorded.deletedUploads).toEqual([])
})

test('the hidden inert root ignores Escape and cannot be clicked', async ({ page }) => {
  const recorded = await installPaneApi(page)
  const { composer } = await openRootPane(page, recorded)
  const editor = composer.locator('.composer-editor')
  await editor.click()
  await editor.fill('根草稿要保留')

  await page.locator('.active-thread-tree').getByRole('link', { name: /worker child/ }).click()
  await expect(page.getByRole('button', { name: '返回上一层' })).toBeVisible()

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
  await page.getByRole('button', { name: '返回上一层' }).click()
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
  await expect(page.getByText('只读查看')).toBeVisible()
  await expect(page.getByRole('link', { name: '返回执行根' })).toBeVisible()
  await expect(page.locator('.thread-composer')).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(0)
  expect(recorded.uploadCalls).toEqual([])
  expect(recorded.commandRequests).toEqual([])
  expect(recorded.stopRequests).toEqual([])

  // 绑定子代理目标的 pane 同样没有 Composer。
  await page.goto(`${HARNESS_URL}?scenario=pane-child`)
  await expect(page.getByRole('heading', { name: 'worker child' })).toBeVisible()
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
  // 根交互汇聚的来源指向子 Thread，但决策写回仍用原始 Thread 身份。
  await expect(page.getByRole('link', { name: /worker child/ }).first()).toBeVisible()
})
