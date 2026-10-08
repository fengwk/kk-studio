import { expect, test, type Page, type Locator } from './fixture'
import type { IssueDetailDTO } from '@/features/projects/types'

const PROJECT = 'a0000000-0000-0000-0000-000000000001'
const ISSUE = 'a0000000-0000-0000-0000-000000000002'
const RUN = 'a0000000-0000-0000-0000-000000000003'
const STAMP = '2026-10-01T00:00:00Z'
const STORAGE_KEY = `kkstudio.projects.pending-action.v1:${ISSUE}`
const detail: IssueDetailDTO = {
  issue: {
    id: ISSUE, projectId: PROJECT, number: '12', title: 'Offline visual issue',
    description: 'Actual IssueDetailModal with offline API transport', state: 'WORK',
    blockedFromState: null, blockReason: null, pauseReason: null, pauseDetail: null,
    version: '1', archivedAt: null, createdAt: STAMP, updatedAt: STAMP,
  },
  activities: [], nextActivityCursor: null, agentThreads: [], currentRun: null, latestRun: null,
  runs: [{
    id: RUN, issueId: ISSUE, ordinal: '1', state: 'WORK', agentName: null,
    sessionId: RUN, threadId: RUN, status: 'COMPLETED', startEntryId: RUN,
    endEntryId: RUN, finalAnswerEntryId: null, nextState: 'DONE',
    observedActivitySequence: '0', remainingExecutionMs: '0', error: null,
    version: '1', startedAt: STAMP, endedAt: STAMP,
  }],
  stageBudgets: [
    { state: 'WORK', maxRuns: 5, budgetAfterOrdinal: '0', usedRuns: '1', remainingRuns: '4' },
    { state: 'DONE', maxRuns: 1, budgetAfterOrdinal: '0', usedRuns: '1', remainingRuns: '0' },
  ],
}

async function transport(page: Page) {
  await page.routeWebSocket('**/api/events/v1', (socket) => {
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw))
      if (message.type === 'subscribe') socket.send(JSON.stringify({
        version: 1, type: 'subscribed', resource: message.resource, cursor: '0',
      }))
    })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/issues/${ISSUE}`) await route.fulfill({ json: { status: 200, data: detail } })
    else if (path === `/api/issues/${ISSUE}/evidence`) await route.fulfill({ json: { status: 200, data: [] } })
    else throw new Error(`Unexpected offline request: ${path}`)
  })
}

// 只用探针解析主题 token，不以静态 HTML 代替正式组件的 computed style。
async function token(locator: Locator, property: 'color' | 'backgroundColor' | 'borderColor', name: string) {
  return locator.evaluate((el, args) => {
    const probe = document.createElement('span')
    probe.style[args.property] = args.name.startsWith('--') ? `var(${args.name})` : args.name
    el.appendChild(probe)
    const value = getComputedStyle(probe)[args.property]
    probe.remove()
    return value
  }, { property, name })
}

async function noOverflow(dialog: Locator) {
  expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
}

for (const width of [320, 1280]) {
  test(`actual project warnings, danger, budgets and task link consume theme tokens at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await transport(page)
    await page.addInitScript(({ key, issue, stamp }) => {
      localStorage.setItem(key, JSON.stringify({
        issueId: issue, kind: 'BLOCK', requestKey: 'offline-request-with-long-identity',
        expectedVersion: '1', payload: { reason: 'Offline test' }, createdAt: stamp, isUnknown: true,
      }))
    }, { key: STORAGE_KEY, issue: ISSUE, stamp: STAMP })
    await page.goto('/browser-tests/project-workflow-harness.html?issue')
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('Offline visual issue', { exact: true })).toBeVisible()
    const banner = dialog.locator('.issue-detail-alert-warning')
    await expect(banner).toHaveCSS('background-color', await token(banner, 'backgroundColor', '--warning-soft'))
    await expect(banner).toHaveCSS('border-top-color', await token(banner, 'borderColor', '--warning-border'))
    await expect(banner.locator('strong')).toHaveCSS('color', await token(banner, 'color', '--warning'))
    const discard = banner.getByRole('button', { name: '放弃未决操作' })
    await expect(discard).toHaveCSS('height', '28px')
    await expect(discard).toHaveCSS('color', await token(discard, 'color', '--danger'))
    await discard.click()
    const confirmation = banner.locator('.issue-detail-alert-discard')
    await expect(confirmation).toHaveCSS('color', await token(confirmation, 'color', '--danger'))
    await noOverflow(dialog)
    await page.screenshot({ path: info.outputPath(`warning-discard-${width}.png`) })
    await banner.getByRole('button', { name: '确认放弃' }).click()
    await expect(banner).toHaveCount(0)
    await dialog.getByRole('tab', { name: /Run/ }).click()
    const next = dialog.locator('.run-detail-row strong')
    await expect(next).toHaveText('DONE')
    await expect(next).toHaveCSS('color', await token(next, 'color', '--accent'))
    await dialog.getByRole('tab', { name: '阶段预算' }).click()
    const budgets = dialog.locator('.budget-card')
    const positive = budgets.nth(0).locator('.budget-stats > div').nth(1).locator('strong')
    const exhausted = budgets.nth(1).locator('.budget-stats > div').nth(1).locator('strong')
    await expect(positive).toHaveText('4')
    await expect(positive).toHaveCSS('color', await token(positive, 'color', '--green-primary'))
    await expect(exhausted).toHaveText('0')
    await expect(exhausted).toHaveCSS('color', await token(exhausted, 'color', '--danger'))
    await noOverflow(dialog)
    await page.screenshot({ path: info.outputPath(`budgets-${width}.png`) })
    const link = page.getByRole('link', { name: 'Task thread' })
    await expect(link).toHaveCSS('color', await token(link, 'color', '--accent'))
    await dialog.getByRole('button', { name: '关闭', exact: true }).first().click()
    await expect(dialog).toHaveCount(0)
    await link.hover()
    await expect(link).toHaveCSS('color', await token(link, 'color', 'color-mix(in srgb, var(--accent) 80%, var(--fg))'))
  })
}

test('storage failure renders actual danger banner', async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 900 })
  await transport(page)
  await page.addInitScript((key) => {
    const original = Storage.prototype.getItem
    Storage.prototype.getItem = function (name) {
      if (name === key) throw new Error('Offline storage unavailable')
      return original.call(this, name)
    }
  }, STORAGE_KEY)
  await page.goto('/browser-tests/project-workflow-harness.html?issue')
  const danger = page.locator('.issue-detail-alert-danger')
  await expect(danger).toBeVisible()
  await expect(danger).toHaveCSS('background-color', await token(danger, 'backgroundColor', '--danger-soft'))
  await expect(danger).toHaveCSS('border-top-color', await token(danger, 'borderColor', '--danger-border'))
  await expect(danger.locator('svg')).toHaveCSS('color', await token(danger, 'color', '--danger'))
  await noOverflow(page.getByRole('dialog'))
  await page.screenshot({ path: info.outputPath('storage-danger-320.png') })
})

test('corrupt pending record renders warning and compact danger discard without overflow', async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 900 })
  await transport(page)
  await page.addInitScript((key) => localStorage.setItem(key, '{broken'), STORAGE_KEY)
  await page.goto('/browser-tests/project-workflow-harness.html?issue')
  const banner = page.locator('.issue-detail-alert-warning')
  await expect(banner).toContainText('检测到损坏的本地未决操作记录')
  await expect(banner).toHaveCSS('background-color', await token(banner, 'backgroundColor', '--warning-soft'))
  const discard = banner.getByRole('button', { name: '放弃损坏记录并解锁' })
  await expect(discard).toHaveCSS('height', '28px')
  await expect(discard).toHaveCSS('color', await token(discard, 'color', '--fg'))
  await expect(discard).toHaveCSS('border-top-color', await token(discard, 'borderColor', '--danger-strong'))
  await noOverflow(page.getByRole('dialog'))
  await page.screenshot({ path: info.outputPath('corrupt-warning-320.png') })
  await discard.click()
  await expect(banner).toHaveCount(0)
})
