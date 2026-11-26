import type { BrowserContext } from '@playwright/test'
import { expect, test, type Page } from './fixture'

const harness = '/browser-tests/workspace-thread-harness.html'
const spaceId = '10000000-0000-4000-8000-000000000001'
const draftId = '10000000-0000-4000-8000-000000000003'
const folderId = '10000000-0000-4000-8000-000000000004'
const sessionId = '10000000-0000-4000-8000-000000000005'
const ids = ['10000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000007', '10000000-0000-4000-8000-000000000008']
const secondThreadId = '10000000-0000-4000-8000-000000000009'
const secondSessionId = '10000000-0000-4000-8000-000000000010'
const execution = { driver: 'builtin', settings: { modelName: 'Code model', variant: 'max' } }
const branchSettings = { agentName: 'assistant', execution, environmentName: null }
const api = `/api/chat-spaces/${spaceId}`
const referenceMessage = '对比三种缓存 ID 方案对 prompt cache 命中率的影响。'

async function captureWidths(page: Page, state: string) {
  const panel = page.locator('.thread-panel')
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await expect.poll(async () => (await panel.boundingBox())?.width).toBe(width)
    if (width === 320 && state === 'draft') {
      const environment = page.getByRole('button', { name: 'Environment', exact: true })
      await expect(environment).toHaveText('None')
      expect(await environment.locator('span').evaluate(label => label.scrollWidth - label.clientWidth)).toBe(0)
      const footerLine = panel.locator('.thread-status-line')
      await expect(footerLine).toHaveCSS('overflow-x', 'auto')
      expect(await footerLine.evaluate(line => {
        line.scrollLeft = line.scrollWidth
        const offset = line.scrollLeft
        line.scrollLeft = 0
        return offset
      })).toBeGreaterThan(0)
    }
    await page.screenshot({ path: `../reports/r130-thread/product-${state}-${width}.png`, animations: 'disabled' })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
}

async function serve(target: Page | BrowserContext, { agents = true, admin = false, rejectFolder = false, rejectFirstSession = false, loseFirstResponse = false } = {}) {
  const posts: { path: string; key: string | undefined; body: unknown }[] = []
  const creations = new Map<string, { body: string | null; result: { sessionId: string; threadId: string; commandId: string; treeVersion: string } }>()
  const messages = new Map<string, string>()
  let rejectedFirstSession = false
  await target.routeWebSocket('**/ws/events', () => {})
  await target.route('**/api/**', async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    const json = (value: unknown) => route.fulfill({ json: value })
    if (path === '/api/auth/me') return json({ id: 'user', username: 'tester', role: admin ? 'ADMIN' : 'USER', enabled: true })
    if (path === '/api/platform/agent-summaries') return json(agents ? [{ name: 'assistant', driver: 'builtin', modelName: 'Code model', variant: 'max' }] : [])
    if (path === '/api/platform/model-summaries') return json([{ name: 'Code model', type: 'MODEL', defaultVariant: 'max', variantIds: ['max'] }])
    if (path === '/api/platform/models' || path === '/api/platform/environment-summaries') return json([])
    if (path === '/api/harness/interactions') return json({ items: [], total: 0, nextCursor: null })
    if (path === `${api}/tree`) return json({ treeVersion: '1', folders: [{ id: folderId, name: 'Work', children: [], resources: [] }] })
    if (path.startsWith(`${api}/sessions`) && req.method() === 'POST') {
      const body = req.postDataJSON()
      posts.push({ path, key: req.headers()['idempotency-key'], body })
      if (path === `${api}/sessions`) {
        if (rejectFolder) return route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ code: 'validation', reason: 'INVALID_FOLDER' }) })
        if (rejectFirstSession && !rejectedFirstSession) {
          rejectedFirstSession = true
          return route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ code: 'validation', reason: 'INVALID_REQUEST' }) })
        }
        const key = req.headers()['idempotency-key']
        const prior = creations.get(key)
        if (prior) {
          if (prior.body !== req.postData()) return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ code: 'conflict', reason: 'IDEMPOTENCY_KEY_REUSED' }) })
          return json(prior.result)
        }
        const first = creations.size === 0
        const result = { sessionId: first ? sessionId : secondSessionId, threadId: first ? ids[0] : secondThreadId, commandId: key, treeVersion: first ? '2' : '3' }
        creations.set(key, { body: req.postData(), result })
        messages.set(result.threadId, body.message[0].text)
        if (loseFirstResponse && creations.size === 1) return route.abort('connectionreset')
        return json(result)
      }
      if (body.title === 'Taken') return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ reason: 'THREAD_NAME_EXISTS' }) })
      return json({ sessionId, threadId: path.endsWith('/branches') ? ids[1] : ids[2], treeVersion: '3' })
    }
    for (const id of [...ids, secondThreadId]) {
      const responseSessionId = id === secondThreadId ? secondSessionId : sessionId
      const text = messages.get(id) ?? messages.get(ids[0]) ?? ''
      const referenceThread = text === referenceMessage
      const thread = { name: 'main', threadId: id, sessionId: responseSessionId, parentThreadId: null, rootThreadId: id,
        headEntryId: referenceThread ? 'current-assistant' : 'end', yoloEnabled: false, nextCommandSequence: '2', version: '1',
        status: referenceThread ? 'RUNNING' : 'IDLE', executionControl: 'RUNNABLE',
        branchSettings, createdAt: 1790812800, updatedAt: 1790812800 }
      if (path === `/api/harness/threads/${id}/tree`) return json([{ ...thread, kind: 'USER', agentName: 'assistant', execution, turnCount: 1, toolCallCount: 0 }])
      if (path === `/api/harness/threads/${id}`) {
        const base = { sessionId: responseSessionId, createdAt: 1790812800, usageCost: null }
        return json({ version: '1', thread, baseEntryId: null, entries: [
          { ...base, entryId: 'root', parentEntryId: null, entryType: 'ROOT', payload: { settings: branchSettings } },
          { ...base, entryId: 'message', parentEntryId: 'root', entryType: 'MESSAGE', payload: { message: { role: 'USER', contents: [{ type: 'text', text }] } } },
          ...(referenceThread ? [{ ...base, entryId: 'assistant', parentEntryId: 'message', entryType: 'MESSAGE',
            usageCost: { currency: 'USD', amount: '0.021344' },
            payload: {
              message: { role: 'ASSISTANT', contents: [{ type: 'text', text: '三种方案：每个 Thread 独立 ID、同一会话共享 ID、按账号亲和。分别在分支里验证，主线记录结论。' }] },
              assistantMetadata: { usage: { inputTokens: 11100, outputTokens: 371, cacheReadTokens: 86000, cacheWriteTokens: 0, reasoningTokens: 150 }, decodeDurationMillis: 930 },
            } }] : []),
          { ...base, entryId: 'end', parentEntryId: referenceThread ? 'assistant' : 'message', entryType: 'TURN_END', payload: { outcome: 'COMPLETED' } },
          ...(referenceThread ? [
            { ...base, entryId: 'current-user', parentEntryId: 'end', entryType: 'MESSAGE', payload: {
              message: { role: 'USER', contents: [{ type: 'text', text: '验证方案 B：同一会话的 Thread 共用 prompt_cache_key。' }] },
            } },
            { ...base, entryId: 'current-assistant', parentEntryId: 'current-user', entryType: 'MESSAGE', payload: {
              message: { role: 'ASSISTANT', contents: [{ type: 'text', text: '正在用 gpt-6-luna 跑 6 组请求，已完成 4 组。' }] },
            } },
          ] : []),
        ], queuedCommands: [], activeAssistantOutput: null, toolInvocations: [], generationFailures: [], stopReceipts: [], unconfirmedExecutions: [] })
      }
    }
    return route.fulfill({ status: 404, body: `Unhandled ${path}` })
  })
  return Object.assign(posts, { creations })
}

const sendButton = (page: Page) => page.getByRole('button', { name: 'Send message' })
const unsent = (page: Page) => page.locator('[data-unsent]')

test('a draft stays in the same pane, shows its first message, and cuts a completed turn', async ({ page }) => {
  const posts = await serve(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(harness)
  const pane = page.locator('[data-pane-id]')
  const id = await pane.getAttribute('data-pane-id')
  await page.getByRole('textbox').fill(referenceMessage)
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')
  await page.reload()
  await expect(page.getByRole('textbox')).toHaveText(referenceMessage)
  await captureWidths(page, 'draft')
  await sendButton(page).click()
  await expect(page.getByText(referenceMessage, { exact: true })).toBeVisible()
  await expect(pane).toHaveAttribute('data-pane-id', id!)
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'false')
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  expect(posts[0]).toMatchObject({ path: `${api}/sessions`, key: draftId, body: { message: [{ type: 'TEXT', text: referenceMessage }] } })
  expect(posts[0].body).not.toHaveProperty('folderId')
  await captureWidths(page, 'thread')
  await page.getByRole('textbox').fill('Thread draft')
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')

  const branch = page.getByRole('button', { name: 'Branch from here' })
  const dialog = page.getByRole('dialog')
  const continueWithoutDraft = dialog.getByRole('checkbox', { name: /without carrying over unsent input/ })
  const create = dialog.getByRole('button', { name: 'Create' })
  await branch.click()
  await expect(dialog.getByRole('textbox')).toHaveValue('Branch 1')
  await expect(create).toBeDisabled()
  await captureWidths(page, 'branch')
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  expect(posts).toHaveLength(1)
  await expect(page.getByRole('textbox')).toHaveText('Thread draft')
  await branch.click()
  await dialog.getByText(/without carrying over unsent input/).click()
  await expect(continueWithoutDraft).toBeChecked()
  await dialog.getByRole('textbox').fill('Taken')
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByRole('alert')).toContainText('Choose another name')
  await dialog.getByRole('textbox').fill('Fix widgets')
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('Thread 7', { exact: true })).toBeVisible()
  await expect(pane).toHaveAttribute('data-pane-id', id!)
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'false')
  expect(posts[2]).toMatchObject({ path: `${api}/sessions/${sessionId}/branches`, body: { sourceThreadId: ids[0], turnEndEntryId: 'end', title: 'Fix widgets' } })
  expect(posts[2].key).not.toBe(posts[1].key)

  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await page.getByRole('textbox').fill('Draft before fork')
  const fork = page.getByRole('button', { name: 'Fork to new session' })
  await fork.click()
  await expect(dialog.getByRole('textbox')).toHaveValue('main · Fork')
  await expect(create).toBeDisabled()
  await captureWidths(page, 'fork')
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  expect(posts).toHaveLength(3)
  await expect(page.getByRole('textbox')).toHaveText('Draft before fork')
  await fork.click()
  await dialog.getByText(/without carrying over unsent input/).click()
  await expect(continueWithoutDraft).toBeChecked()
  await dialog.getByRole('textbox').fill('Separate session')
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('Thread 8', { exact: true })).toBeVisible()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  expect(posts[3]).toMatchObject({ path: `${api}/sessions/${sessionId}/forks`, body: { sourceThreadId: ids[1], turnEndEntryId: 'end', title: 'Separate session' } })
  expect(new Set(posts.map(({ key }) => key)).size).toBe(posts.length)
})

for (const scenario of ['available', 'admin', 'user'] as const) {
  test(`sending without a default Agent: ${scenario}`, async ({ page }) => {
    const posts = await serve(page, { agents: scenario === 'available', admin: scenario === 'admin' })
    await page.goto(`${harness}?no-default`)
    await page.getByRole('textbox').fill(referenceMessage)
    await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')
    await expect(sendButton(page)).toBeDisabled()
    if (scenario === 'available') {
      await page.getByRole('button', { name: 'Space settings' }).click()
      await expect(page.locator('[data-settings]')).toHaveAttribute('data-settings', 'true')
    } else {
      await expect(page.getByText('No Agents available')).toBeVisible()
      const link = page.getByRole('link', { name: 'Agent settings' })
      if (scenario === 'admin') await expect(link).toHaveAttribute('href', '/settings/agents')
      else await expect(link).toHaveCount(0)
    }
    expect(posts).toHaveLength(0)
    await captureWidths(page, `no-agent-${scenario}`)
  })
}

for (const reload of [false, true]) {
  test(`replays the exact first send after a lost response ${reload ? 'across reload' : 'on the same page'}`, async ({ page }) => {
    const posts = await serve(page, { loseFirstResponse: true })
    await page.goto(`${harness}?folder=${folderId}`)
    const pane = page.locator('[data-pane-id]')
    const paneId = await pane.getAttribute('data-pane-id')
    const editor = page.getByRole('textbox')
    const message = 'Keep this first message through replay'
    // This non-default choice is in memory only; rebuilding the body after reload would lose it.
    await page.getByRole('button', { name: 'Permission mode' }).click()
    await page.getByRole('option', { name: 'YOLO' }).click()
    await editor.fill(message)
    await sendButton(page).click()
    const alert = page.getByRole('alert')
    await expect(alert).toContainText('The send result is unknown')
    await expect(editor).toHaveText(message)
    await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')
    await expect(sendButton(page)).toBeDisabled()
    expect(posts).toHaveLength(1)
    expect(posts.creations.size).toBe(1)
    if (reload) {
      await page.reload()
      await expect(alert).toContainText('The send result is unknown')
      await expect(editor).toHaveText(message)
      await expect(sendButton(page)).toBeDisabled()
      expect(posts).toHaveLength(1)
    }
    await alert.getByRole('button', { name: 'Retry' }).click()
    await expect(page.getByText('Thread 6', { exact: true })).toBeVisible()
    await expect(page.getByText(message, { exact: true })).toBeVisible()
    await expect(pane).toHaveAttribute('data-pane-id', paneId!)
    await expect(unsent(page)).toHaveAttribute('data-unsent', 'false')
    expect(posts).toHaveLength(2)
    expect(posts[0]).toMatchObject({ key: draftId, body: { yoloEnabled: true } })
    expect(posts[1]).toEqual(posts[0])
    expect(posts.creations.size).toBe(1)
  })
}

test('a first-send key conflict in another tab preserves the draft until an explicit new send', async ({ page, context }) => {
  // Both tabs restore the same draft identity; the server accepts A but drops its receipt.
  const posts = await serve(context, { loseFirstResponse: true })
  await page.goto(harness)
  await page.getByRole('textbox').fill('First tab message')
  await sendButton(page).click()
  await expect(page.getByRole('alert')).toContainText('The send result is unknown')

  const second = await context.newPage()
  await second.goto(harness)
  const editor = second.getByRole('textbox')
  await expect(editor).toHaveText('First tab message')
  const message = 'Second tab message'
  await editor.fill(message)
  await sendButton(second).click()
  const conflict = second.getByRole('alert')
  await expect(conflict).toContainText('This draft was already used to send a different message')
  await expect(editor).toHaveText(message)
  await expect(editor).toBeEditable()
  await expect(unsent(second)).toHaveAttribute('data-unsent', 'true')
  expect(posts.map(({ key }) => key)).toEqual([draftId, draftId])
  expect(posts.creations.size).toBe(1)

  // Reload must keep the conflict editable, without offering to repeat its frozen request.
  await second.reload()
  await expect(conflict).toContainText('Your message was not sent')
  await expect(second.getByRole('button', { name: 'Retry' })).toHaveCount(0)
  await expect(editor).toHaveText(message)
  await expect(editor).toBeEditable()
  expect(posts).toHaveLength(2)

  const corrected = 'Edited second tab message'
  await editor.fill(corrected)
  await sendButton(second).click()
  await expect(second.getByText('Thread 9', { exact: true })).toBeVisible()
  await expect(second.getByText(corrected, { exact: true })).toBeVisible()
  expect(posts).toHaveLength(3)
  expect(posts[2].key).not.toBe(draftId)
  expect(posts[2].key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  expect(posts[2].body).toMatchObject({ message: [{ type: 'TEXT', text: corrected }] })
  expect(posts.creations.size).toBe(2)
})

test('a determined application rejection allows correcting and resending the first message', async ({ page }) => {
  const posts = await serve(page, { rejectFirstSession: true })
  await page.goto(harness)
  const editor = page.getByRole('textbox')
  await editor.fill('Request the server rejects')
  await sendButton(page).click()
  await expect(page.getByRole('alert')).toContainText('Request failed')
  await expect(editor).toHaveText('Request the server rejects')
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')
  await expect(editor).toBeEditable()
  expect(posts).toHaveLength(1)

  await page.reload()
  await expect(editor).toHaveText('Request the server rejects')
  await expect(editor).toBeEditable()
  expect(posts).toHaveLength(1)
  await editor.fill('Corrected first message')
  await sendButton(page).click()
  await expect(page.getByText('Thread 6', { exact: true })).toBeVisible()
  await expect(page.getByText('Corrected first message', { exact: true })).toBeVisible()
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'false')
  expect(posts).toHaveLength(2)
  expect(posts[0].body).toMatchObject({ message: [{ type: 'TEXT', text: 'Request the server rejects' }] })
  expect(posts[1].body).toMatchObject({ message: [{ type: 'TEXT', text: 'Corrected first message' }] })
  expect(posts[0].key).toBe(draftId)
  expect(posts[1].key).toBe(draftId)
})

test('an invalid folder leaves the unsent draft and explains the failure', async ({ page }) => {
  const posts = await serve(page, { rejectFolder: true })
  await page.goto(`${harness}?folder=${folderId}`)
  const editor = page.getByRole('textbox')
  await editor.fill('Do not drop this message')
  await sendButton(page).click()
  await expect(page.getByRole('alert')).toContainText('Choose another folder')
  expect(posts[0].body).toMatchObject({ folderId })
  await expect(editor).toHaveText('Do not drop this message')
  await expect(unsent(page)).toHaveAttribute('data-unsent', 'true')
})
