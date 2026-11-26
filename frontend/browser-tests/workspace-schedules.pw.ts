import { expect, test, type Page } from './fixture'
import type { ScheduleDTO, ScheduleTargetDTO, ScheduleTriggerDTO } from '../src/shared/api/generated/contracts'

const path = '/browser-tests/workspace-shell-harness.html'
const id = (suffix: number) => `10000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`
const spaceId = id(1)
const rootFolderId = id(4)
const childFolderId = id(8)
const sessionId = id(5)
const thread1Id = id(6)
const thread2Id = id(9)
const canvasId = id(7)
const sched1Id = id(40)
const sched2Id = id(41)
const sched3Id = id(42)
const cut1Id = id(51)
const cut2Id = id(52)

async function setupSchedulesHarness(page: Page, dropFirstRunResponse?: Promise<void>) {
  const manualAttempts: string[] = []
  const runReceipts = new Map<string, unknown>()
  const futureTs = Date.now() + 24 * 3_600_000
  const tree = {
    treeVersion: '1',
    folders: [
      {
        id: rootFolderId,
        name: 'Projects',
        children: [{ id: childFolderId, name: 'Daily', children: [], resources: [] }],
        resources: [
          {
            id: sessionId,
            title: 'Release planning',
            kind: 'SESSION',
            threads: [
              { id: thread1Id, title: 'Release notes', state: 'IDLE', lastActivityAt: 1, version: '0' },
              { id: thread2Id, title: 'Checklist', state: 'IDLE', lastActivityAt: 2, version: '0' },
            ],
          },
          { id: canvasId, title: 'Diagram', kind: 'CANVAS' },
        ],
      },
    ],
  }

  const spaces = [
    {
      id: spaceId,
      title: 'Studio',
      version: '1',
      archivedAt: null as number | null,
      defaultAgentName: 'Coder',
      defaultEnvironmentId: null,
      defaultYoloEnabled: false,
    },
  ]

  const schedules: ScheduleDTO[] = [
    {
      id: sched1Id,
      title: 'Daily build digest',
      message: 'Summarize CI failures from the last 24 hours.',
      enabled: true,
      trigger: { type: 'REPEAT', frequency: 'WEEKDAYS', time: '09:00' },
      timeZone: 'Asia/Shanghai',
      target: { type: 'NEW_SESSION', folderId: rootFolderId, agentName: 'Coder' },
      nextDueAt: futureTs,
      version: '1',
    },
    {
      id: sched2Id,
      title: 'Cache hit regression',
      message: 'Compare prompt cache metrics across main and branch.',
      enabled: true,
      trigger: { type: 'REPEAT', frequency: 'DAILY', time: '10:00' },
      timeZone: 'Asia/Shanghai',
      target: { type: 'CONTINUE', sessionId, threadId: thread2Id },
      nextDueAt: futureTs + 3_600_000,
      version: '2',
    },
    {
      id: sched3Id,
      title: 'Weekly draft',
      message: 'Draft weekly summary notes.',
      enabled: false,
      trigger: { type: 'REPEAT', frequency: 'WEEKLY', weekday: 'FRIDAY', time: '17:00' },
      timeZone: 'Asia/Shanghai',
      target: { type: 'BRANCH', sessionId, sourceThreadId: thread1Id, turnEndEntryId: cut1Id },
      nextDueAt: null as number | null,
      version: '1',
    },
  ]

  const runsBySchedule: Record<string, Array<Record<string, unknown>>> = {
    [sched1Id]: [
      {
        id: id(101),
        scheduledAt: Date.UTC(2026, 10, 25, 1, 0),
        startedAt: Date.UTC(2026, 10, 25, 1, 0),
        timeZone: 'Asia/Shanghai',
        result: 'SUBMITTED',
        reason: null,
        thread: { sessionId, id: thread1Id, title: 'Digest 11-25' },
      },
      {
        id: id(102),
        scheduledAt: Date.UTC(2026, 10, 24, 1, 0),
        startedAt: null,
        timeZone: 'Asia/Shanghai',
        result: 'SUBMITTED',
        reason: null,
        thread: null,
      },
    ],
    [sched2Id]: Array.from({ length: 7 }, (_, index) => {
      if (index === 2) {
        return {
          id: id(200 + index),
          scheduledAt: Date.UTC(2026, 10, 25 - index, 2, 0),
          startedAt: null,
          timeZone: 'Asia/Shanghai',
          result: 'SKIPPED',
          reason: 'EXECUTION_NOT_SETTLED',
          thread: null,
        }
      }
      return {
        id: id(200 + index),
        scheduledAt: Date.UTC(2026, 10, 25 - index, 2, 0),
        startedAt: Date.UTC(2026, 10, 25 - index, 2, 0),
        timeZone: 'Asia/Shanghai',
        result: 'SUBMITTED',
        reason: null,
        thread: { sessionId, id: index === 1 ? thread1Id : thread2Id, title: `Regression #${7 - index}` },
      }
    }),
    [sched3Id]: [],
  }

  let sendWsEvent: ((frame: unknown) => void) | undefined
  let conflictNextPatch = false
  let nextSeq = 300

  await page.routeWebSocket('**/ws/events', socket => {
    sendWsEvent = frame => socket.send(JSON.stringify(frame))
    socket.send(JSON.stringify({
      type: 'connected',
      eventNodeId: id(20),
      connectionId: id(21),
      heartbeatIntervalMillis: '60000',
    }))
    socket.onMessage(raw => {
      const frame = JSON.parse(String(raw))
      if (frame.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: frame.resource, cursor: '0' }))
      }
    })
  })

  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    const method = route.request().method()

    if (url.pathname === '/api/chat-spaces' && method === 'GET') {
      return route.fulfill({ json: spaces })
    }
    if (url.pathname === `/api/chat-spaces/${spaceId}/tree` && method === 'GET') {
      return route.fulfill({ json: tree })
    }
    if (url.pathname === '/api/harness/interactions' && method === 'GET') {
      return route.fulfill({ json: { items: [], total: 0, nextCursor: null } })
    }
    if (url.pathname === '/api/platform/agent-summaries' && method === 'GET') {
      return route.fulfill({ json: [{ name: 'Coder', description: '' }, { name: 'Reviewer', description: '' }] })
    }
    if (url.pathname.startsWith('/api/harness/threads/') && method === 'GET') {
      const targetThreadId = url.pathname.split('/').pop()!
      return route.fulfill({
        json: {
          version: '1',
          thread: {
            threadId: targetThreadId,
            parentThreadId: null,
            name: 'main',
            sessionId,
            headEntryId: cut2Id,
            rootThreadId: targetThreadId,
            yoloEnabled: false,
            nextCommandSequence: '1',
            version: '1',
            status: 'IDLE',
            executionControl: 'IDLE',
            branchSettings: { agentName: 'Coder', environmentId: null, yoloEnabled: false },
            createdAt: 1,
            updatedAt: 2,
          },
          baseEntryId: null,
          entries: [
            { entryId: id(401), sessionId, parentEntryId: null, entryType: 'TURN_START', payload: {}, createdAt: 1, usageCost: null },
            {
              entryId: id(402),
              sessionId,
              parentEntryId: id(401),
              entryType: 'MESSAGE',
              payload: { message: { role: 'USER', contents: [{ type: 'TEXT', text: 'Record cache baseline' }] } },
              createdAt: 2,
              usageCost: null,
            },
            { entryId: cut1Id, sessionId, parentEntryId: id(402), entryType: 'TURN_END', payload: { outcome: 'COMPLETED' }, createdAt: 3, usageCost: null },
            { entryId: id(403), sessionId, parentEntryId: cut1Id, entryType: 'TURN_START', payload: {}, createdAt: 4, usageCost: null },
            {
              entryId: id(404),
              sessionId,
              parentEntryId: id(403),
              entryType: 'MESSAGE',
              payload: { message: { role: 'USER', contents: [{ type: 'TEXT', text: 'Analyze prompt cache hit ratio' }] } },
              createdAt: 5,
              usageCost: null,
            },
            { entryId: cut2Id, sessionId, parentEntryId: id(404), entryType: 'TURN_END', payload: { outcome: 'COMPLETED' }, createdAt: 6, usageCost: null },
          ],
          queuedCommands: [],
          activeAssistantOutput: null,
          generationFailures: [],
          toolInvocations: [],
          stopReceipts: [],
          unconfirmedExecutions: [],
        },
      })
    }
    if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'GET') {
      return route.fulfill({ json: schedules })
    }
    if (url.pathname === `/api/chat-spaces/${spaceId}/schedules/preview` && method === 'POST') {
      const body = route.request().postDataJSON() as { trigger: { type: string; localDate?: string; time?: string; cron?: string } }
      if (body.trigger.type === 'ONCE' && body.trigger.localDate && body.trigger.localDate < '2026-01-01') {
        return route.fulfill({ json: { next: [] } })
      }
      if (body.trigger.type === 'ONCE') {
        return route.fulfill({ json: { next: [futureTs] } })
      }
      return route.fulfill({ json: { next: [futureTs, futureTs + 86_400_000, futureTs + 172_800_000] } })
    }
    if (url.pathname === `/api/chat-spaces/${spaceId}/schedules` && method === 'POST') {
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      const body = route.request().postDataJSON() as Record<string, unknown>
      const created: ScheduleDTO = {
        id: id(nextSeq++),
        title: String(body.title),
        message: String(body.message),
        enabled: true,
        trigger: body.trigger as ScheduleTriggerDTO,
        timeZone: String(body.timeZone),
        target: body.target as ScheduleTargetDTO,
        nextDueAt: futureTs,
        version: '1',
      }
      schedules.push(created)
      runsBySchedule[created.id] = []
      return route.fulfill({ status: 201, json: created })
    }

    const runsMatch = new RegExp(`^/api/chat-spaces/${spaceId}/schedules/([^/]+)/runs$`).exec(url.pathname)
    if (runsMatch && method === 'GET') {
      const targetId = runsMatch[1]!
      const all = runsBySchedule[targetId] ?? []
      const before = url.searchParams.get('before')
      const offset = before ? Number(before) : 0
      const pageItems = all.slice(offset, offset + 5)
      const olderCursor = offset + 5 < all.length ? String(offset + 5) : null
      return route.fulfill({ json: { items: pageItems, olderCursor } })
    }
    if (runsMatch && method === 'POST') {
      const key = route.request().headers()['idempotency-key']!
      expect(key).toBeTruthy()
      manualAttempts.push(key)
      const targetId = runsMatch[1]!
      const scope = `${targetId}:${key}`
      const receipt = runReceipts.get(scope)
      if (receipt) return route.fulfill({ status: 201, json: receipt })
      const added = {
        id: id(nextSeq++),
        scheduledAt: Date.now(),
        startedAt: Date.now(),
        timeZone: 'Asia/Shanghai',
        result: 'SUBMITTED',
        reason: null,
        thread: { sessionId, id: thread1Id, title: 'Manual run output' },
      }
      runsBySchedule[targetId] = [added, ...(runsBySchedule[targetId] ?? [])]
      runReceipts.set(scope, added)
      if (dropFirstRunResponse && manualAttempts.length === 1) {
        return dropFirstRunResponse.then(() => route.abort('failed'))
      }
      return route.fulfill({ status: 201, json: added })
    }

    const itemMatch = new RegExp(`^/api/chat-spaces/${spaceId}/schedules/([^/]+)$`).exec(url.pathname)
    if (itemMatch && method === 'PATCH') {
      const targetId = itemMatch[1]!
      const index = schedules.findIndex(item => item.id === targetId)
      if (index < 0) return route.fulfill({ status: 404 })
      const current = schedules[index]!
      const body = route.request().postDataJSON() as { expectedVersion: string; enabled?: boolean; title?: string; message?: string; trigger?: typeof current.trigger; timeZone?: string; target?: typeof current.target }
      if (conflictNextPatch) {
        conflictNextPatch = false
        current.version = String(Number(current.version) + 1)
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({ code: 'VERSION_CONFLICT', reason: 'VERSION_CONFLICT', detail: 'Stale version' }),
        })
      }
      expect(body.expectedVersion).toBe(current.version)
      current.version = String(Number(current.version) + 1)
      if (typeof body.enabled === 'boolean') {
        current.enabled = body.enabled
        current.nextDueAt = body.enabled ? futureTs : null
      }
      if (typeof body.title === 'string') current.title = body.title
      if (typeof body.message === 'string') current.message = body.message
      if (body.trigger) current.trigger = body.trigger
      if (body.timeZone) current.timeZone = body.timeZone
      if (body.target) current.target = body.target
      return route.fulfill({ json: current })
    }
    if (itemMatch && method === 'DELETE') {
      const targetId = itemMatch[1]!
      const index = schedules.findIndex(item => item.id === targetId)
      if (index < 0) return route.fulfill({ status: 404 })
      expect(url.searchParams.get('expectedVersion')).toBe(schedules[index]!.version)
      schedules.splice(index, 1)
      delete runsBySchedule[targetId]
      return route.fulfill({ status: 204 })
    }

    return route.fulfill({ status: 404, body: `Unhandled ${method} ${url.pathname}` })
  })

  return {
    schedules,
    runsBySchedule,
    manualAttempts,
    setConflictOnNextPatch: () => {
      conflictNextPatch = true
    },
    pushSchedulesEvent: (scheduleId?: string) =>
      sendWsEvent?.({
        type: 'event',
        resource: { kind: 'chat-space', id: spaceId },
        name: 'schedules',
        data: scheduleId ? { scheduleId } : {},
      }),
  }
}

async function assertNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    docScrollWidth: document.documentElement.scrollWidth,
  }))
  expect(metrics.docScrollWidth).toBeLessThanOrEqual(metrics.innerWidth + 1)
}

test('captures responsive schedules list and dialog acceptance screenshots at 1440, 390 and 320', async ({ page }) => {
  await setupSchedulesHarness(page)

  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(path)
    if (width <= 390) {
      await page.getByRole('button', { name: 'Open sidebar' }).click()
      await expect(page.locator('.workspace-shell')).toHaveAttribute('data-drawer', 'true')
    }
    await page.getByRole('tab', { name: 'Schedules' }).click()
    await expect(page.getByText('Daily build digest')).toBeVisible()

    const historyToggle = page.getByRole('button', { name: 'Run history for Cache hit regression' })
    await historyToggle.click()
    await expect(page.getByRole('button', { name: /Regression #7/ })).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: `../reports/r130-schedules-ui/schedules-list-${width}.png`,
      animations: 'disabled',
    })

    await page.getByRole('button', { name: 'New schedule' }).click()
    const dialog = page.getByRole('dialog', { name: 'New schedule' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText(/Next:/)).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: `../reports/r130-schedules-ui/schedules-dialog-${width}.png`,
      animations: 'disabled',
    })
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  }
})

test('recovers a lost manual run receipt across sidebar tab changes and reload', async ({ page }) => {
  let drop!: () => void
  const dropFirstRunResponse = new Promise<void>(resolve => { drop = resolve })
  const harness = await setupSchedulesHarness(page, dropFirstRunResponse)
  await page.goto(path)
  const directory = page.getByRole('tab', { name: 'Directory' })
  const schedules = page.getByRole('tab', { name: 'Schedules' })
  const menu = page.getByRole('button', { name: 'Actions for Weekly draft' })
  const run = page.getByRole('menuitem', { name: 'Run now' })
  await schedules.click()
  const originalCount = harness.runsBySchedule[sched3Id]!.length
  await menu.click()
  await run.click()
  await expect.poll(() => harness.manualAttempts.length).toBe(1)

  // The panel remounts, but an active request stays locked until its response is lost.
  await directory.click()
  await schedules.click()
  await menu.click()
  await expect(run).toBeDisabled()
  drop()
  await expect(page.getByRole('status')).toHaveText('No run receipt received.')
  await page.reload()
  await schedules.click()
  await expect(page.getByRole('status')).toHaveText('No run receipt received.')
  expect(harness.manualAttempts).toHaveLength(1)

  await menu.click()
  await page.getByRole('menuitem', { name: 'Retry run receipt' }).click()
  await expect(page.getByRole('button', { name: /Manual run output/ })).toBeVisible()
  expect(harness.manualAttempts).toEqual([harness.manualAttempts[0], harness.manualAttempts[0]])
  expect(harness.runsBySchedule[sched3Id]).toHaveLength(originalCount + 1)
  await menu.click()
  await run.click()
  await expect.poll(() => harness.manualAttempts.length).toBe(3)
  expect(harness.manualAttempts[2]).not.toBe(harness.manualAttempts[0])
  expect(harness.runsBySchedule[sched3Id]).toHaveLength(originalCount + 2)
})

test('covers schedule controls, history pagination, unified thread open, 409 recovery, focus restoration and WS refresh', async ({ page }) => {
  const harness = await setupSchedulesHarness(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(path)

  // Keep directory collapsed/expanded baseline to ensure opening a schedule run does not mutate tree expansion.
  const projectsRow = page.getByRole('treeitem', { name: 'Projects', exact: true })
  await expect(projectsRow).toHaveAttribute('aria-expanded', 'true')

  await page.getByRole('tab', { name: 'Schedules' }).click()

  // Name is plain text, non-clickable.
  const dailyTitle = page.getByText('Daily build digest', { exact: true })
  await expect(page.getByRole('button', { name: 'Daily build digest', exact: true })).toHaveCount(0)
  await dailyTitle.click()
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // Independent switch toggles enabled state and hides/restores next run time.
  const dailySwitch = page.getByRole('switch', { name: 'Enable Daily build digest' })
  await expect(dailySwitch).toHaveAttribute('aria-checked', 'true')
  await dailySwitch.press('Space')
  await expect(dailySwitch).toHaveAttribute('aria-checked', 'false')
  await dailySwitch.press('Space')
  await expect(dailySwitch).toHaveAttribute('aria-checked', 'true')

  // Single-expanded inline history + cursor pagination keeping focus on Older.
  const dailyHistory = page.getByRole('button', { name: 'Run history for Daily build digest' })
  await dailyHistory.click()
  await expect(page.getByText('Thread deleted')).toBeVisible()
  await expect(page.getByText('Thread deleted').locator('xpath=ancestor::button')).toHaveCount(0)

  const cacheHistory = page.getByRole('button', { name: 'Run history for Cache hit regression' })
  await cacheHistory.click()
  await expect(dailyHistory).toHaveAttribute('aria-expanded', 'false')
  await expect(cacheHistory).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByText('Skipped: target is still running')).toBeVisible()

  const newer = page.getByRole('button', { name: 'Newer', exact: true })
  const older = page.getByRole('button', { name: 'Older', exact: true })
  await expect(newer).toBeDisabled()
  await older.press('Enter')
  await expect(page.getByRole('button', { name: /Regression #2/ })).toBeVisible()
  await expect(older).toBeDisabled()
  await expect(newer).toBeFocused()
  await newer.press('Enter')
  await expect(page.getByRole('button', { name: /Regression #7/ })).toBeVisible()
  await expect(newer).toBeDisabled()
  await expect(older).toBeFocused()

  // Opening a run thread goes through the shell open flow:
  // 1) From an empty draft pane it opens directly;
  // 2) Once the pane holds a thread, opening another run opens PanePicker, Escape restores focus to the run button, and confirming opens it without altering directory tree state.
  await page.getByRole('button', { name: /Regression #6/ }).click()
  await expect(page.getByTestId('thread')).toHaveText(thread1Id)

  const runThreadBtn = page.getByRole('button', { name: /Regression #7/ })
  await runThreadBtn.click()
  const panePicker = page.getByRole('dialog', { name: 'Open in pane' })
  await expect(panePicker).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(panePicker).toBeHidden()
  await expect(runThreadBtn).toBeFocused()

  await runThreadBtn.click()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('thread')).toHaveText(thread2Id)

  // Directory expansion & selection stayed untouched.
  await page.getByRole('tab', { name: 'Directory' }).click()
  await expect(projectsRow).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('tab', { name: 'Schedules' }).click()

  // Run now via menu creates a run and expands newest page without opening a pane.
  const panesBefore = await page.getByTestId('thread').count()
  const weeklyMenu = page.getByRole('button', { name: 'Actions for Weekly draft' })
  await weeklyMenu.click()
  await page.getByRole('menuitem', { name: 'Run now' }).click()
  await expect(page.getByRole('button', { name: /Manual run output/ })).toBeVisible()
  expect(await page.getByTestId('thread').count()).toBe(panesBefore)
  await expect(page.getByTestId('thread')).toHaveText(thread2Id)

  // Open editor for Weekly draft: verify BRANCH turn cut options, HelpHint, 409 recovery, and focus restoration.
  await weeklyMenu.click()
  await page.getByRole('menuitem', { name: 'Edit' }).click()
  const editDialog = page.getByRole('dialog', { name: 'Edit schedule' })
  await expect(editDialog).toBeVisible()
  await expect(editDialog.locator('input:is([type=date],[type=time]):visible')).toHaveCount(0)

  // Switch to Once to verify shared DateInput & TimeInput without native date/time inputs.
  await editDialog.getByRole('button', { name: 'Once', exact: true }).click()
  await expect(editDialog.locator('input:is([type=date],[type=time]):visible')).toHaveCount(0)
  await editDialog.getByRole('button', { name: 'Repeat', exact: true }).click()

  // Switch frequency to Custom cron and verify HelpHint.
  await editDialog.getByRole('button', { name: /^Frequency/ }).click()
  await page.getByRole('option', { name: 'Custom cron' }).click()
  await editDialog.getByRole('button', { name: /Help.*Cron expression/ }).click()
  await expect(page.getByText(/Five fields: minute, hour/)).toBeVisible()
  await page.keyboard.press('Escape')

  // A conflict preserves the user's draft but requires an explicit reload before another save.
  const nameField = editDialog.getByLabel(/Name/)
  await nameField.fill('Weekly draft v2')
  harness.setConflictOnNextPatch()
  const save = editDialog.getByRole('button', { name: 'Save' })
  await save.click()
  await expect(editDialog.getByRole('alert')).toHaveText('This schedule changed. Reload it and review the latest settings before saving.')
  await expect(nameField).toHaveValue('Weekly draft v2')
  await expect(save).toBeDisabled()
  await editDialog.getByRole('button', { name: 'Discard edits and reload' }).click()
  await expect(nameField).toHaveValue('Weekly draft')
  await nameField.fill('Weekly draft v2')
  await save.click()
  await expect(editDialog).toBeHidden()
  await expect(page.getByText('Weekly draft v2')).toBeVisible()

  // Cancel on delete modal returns focus to task menu button; confirming deletes task and moves focus to New schedule.
  const renamedMenu = page.getByRole('button', { name: 'Actions for Weekly draft v2' })
  await renamedMenu.click()
  await page.getByRole('menuitem', { name: 'Edit' }).click()
  await page.getByRole('dialog', { name: 'Edit schedule' }).getByRole('button', { name: 'Delete schedule' }).click()
  const deleteDialog = page.getByRole('alertdialog', { name: 'Delete schedule "Weekly draft v2"?' })
  await deleteDialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(renamedMenu).toBeFocused()

  await renamedMenu.click()
  await page.getByRole('menuitem', { name: 'Edit' }).click()
  await page.getByRole('dialog', { name: 'Edit schedule' }).getByRole('button', { name: 'Delete schedule' }).click()
  await page.getByRole('alertdialog', { name: 'Delete schedule "Weekly draft v2"?' }).getByRole('button', { name: 'Delete schedule' }).click()
  await expect(page.getByText('Weekly draft v2')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'New schedule' })).toBeFocused()

  // WebSocket schedules event refreshes list and expanded first history page.
  await cacheHistory.click()
  await expect(page.getByRole('button', { name: /Regression #7/ })).toBeVisible()
  harness.schedules[1]!.title = 'Cache hit regression v2'
  harness.runsBySchedule[sched2Id]!.unshift({
    id: id(299),
    scheduledAt: Date.now(),
    startedAt: Date.now(),
    timeZone: 'Asia/Shanghai',
    result: 'SUBMITTED',
    reason: null,
    thread: { sessionId, id: thread2Id, title: 'WS refreshed run' },
  })
  harness.pushSchedulesEvent(sched2Id)
  await expect(page.getByText('Cache hit regression v2')).toBeVisible()
  await expect(page.getByRole('button', { name: /WS refreshed run/ })).toBeVisible()
})
