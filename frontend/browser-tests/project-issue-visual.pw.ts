import { expect, test, type Page, type Locator } from './fixture'
import type { IssueDetailDTO, ResumeIssueRequestDTO } from '@/shared/api/generated/contracts'

const PROJECT = 'a0000000-0000-0000-0000-000000000001'
const ISSUE = 'a0000000-0000-0000-0000-000000000002'
const RUN = 'a0000000-0000-0000-0000-000000000003'
const STAMP = Date.UTC(2026, 9, 1)
/** 侧车记录自己的 createdAt 是本地 ISO 文本，不是后端 Instant。 */
const SIDECAR_STAMP = '2026-10-01T00:00:00Z'
const STORAGE_KEY = `kk-studio.projects.pending-action:${ISSUE}`
const PAUSE_DETAIL = '外部支付请求结果未知，请核查交易是否已完成。'
const detail: IssueDetailDTO = {
  issue: {
    id: ISSUE, projectId: PROJECT, number: '12', title: 'Offline visual issue',
    description: 'Actual IssueDetailModal with offline API transport', state: 'WORK',
    blockedFromState: null, blockReason: null, pauseReason: null, pauseDetail: null,
    version: '1', createdAt: STAMP, updatedAt: STAMP,
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

async function transport(page: Page, pauseReason: 'USER' | 'ERROR' | null = null, failResumeOnce = false) {
  const state = {
    issue: { ...detail.issue, pauseReason, pauseDetail: pauseReason === 'ERROR' ? PAUSE_DETAIL : null },
    resumeRequests: [] as ResumeIssueRequestDTO[],
    committed: new Map<string, string>(),
    issueReads: 0,
  }
  await page.routeWebSocket('**/ws/events', (socket) => {
    let connected = false
    socket.onMessage((raw) => {
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({
          type: 'connected',
          eventNodeId: 'b0000000-0000-4000-8000-000000000001',
          connectionId: 'b0000000-0000-4000-8000-000000000002',
          // connected 帧必须携带服务端 heartbeat 间隔，否则客户端判定畸形且永不确认订阅。
          heartbeatIntervalMillis: '20000',
        }))
      }
      const message = JSON.parse(String(raw))
      if (message.type === 'subscribe') socket.send(JSON.stringify({
        type: 'subscribed', resource: message.resource, cursor: '0',
      }))
    })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/project/issues/${ISSUE}` && route.request().method() === 'GET') {
      state.issueReads++
      await route.fulfill({ json: { ...detail, issue: state.issue } })
    } else if (path === `/api/project/issues/${ISSUE}/evidence`) {
      await route.fulfill({ json: [] })
    } else if (path === `/api/project/issues/${ISSUE}/resume` && route.request().method() === 'POST') {
      const request = route.request().postDataJSON() as ResumeIssueRequestDTO
      state.resumeRequests.push(request)
      const previousVersion = state.committed.get(request.requestKey)
      if (previousVersion !== undefined) {
        if (previousVersion !== request.expectedVersion) {
          await route.fulfill({ status: 409, contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 409, code: 'conflict', reason: 'IDENTITY_CONFLICT', title: 'Identity conflict' } })
          return
        }
      } else {
        if (request.expectedVersion !== state.issue.version) {
          await route.fulfill({ status: 409, contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 409, code: 'version_conflict', title: 'Version conflict' } })
          return
        }
        state.committed.set(request.requestKey, request.expectedVersion)
        state.issue = { ...state.issue, pauseReason: null, pauseDetail: null, version: '2' }
        if (failResumeOnce) {
          // 模拟服务端已提交而网关返回 503：后续同身份重放只返回现状。
          await route.fulfill({ status: 503, contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 503, code: 'unavailable', title: 'Outcome unknown' } })
          return
        }
      }
      await route.fulfill({ json: state.issue })
    } else {
      throw new Error(`Unexpected offline request: ${path}`)
    }
  })
  return state
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
    }, { key: STORAGE_KEY, issue: ISSUE, stamp: SIDECAR_STAMP })
    await page.goto('/browser-tests/project-workflow-harness.html?issue')
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('Offline visual issue', { exact: true })).toBeVisible()
    const banner = dialog.locator('.issue-detail-alert-warning')
    await expect(banner).toHaveCSS('background-color', await token(banner, 'backgroundColor', '--warning-soft'))
    await expect(banner).toHaveCSS('border-top-color', await token(banner, 'borderColor', '--warning-border'))
    await expect(banner.locator('strong')).toHaveCSS('color', await token(banner, 'color', '--warning'))
    const discard = banner.getByRole('button', { name: '放弃未决操作' })
    // 窄屏由 modal-touch-target 统一提供 44px 触区；桌面仍保持紧凑高度。
    await expect(discard).toHaveCSS('height', width <= 860 ? '44px' : '28px')
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
    // task 导航沿用周围正文的字体与颜色，hover 也不改为强调色。
    const bodyStyle = await link.evaluate((element) => {
      const style = getComputedStyle(element.parentElement!)
      return { color: style.color, fontFamily: style.fontFamily, fontSize: style.fontSize, fontWeight: style.fontWeight }
    })
    await expect(link).toHaveCSS('color', bodyStyle.color)
    await expect(link).toHaveCSS('font-family', bodyStyle.fontFamily)
    await expect(link).toHaveCSS('font-size', bodyStyle.fontSize)
    await expect(link).toHaveCSS('font-weight', bodyStyle.fontWeight)
    await dialog.getByRole('button', { name: '关闭', exact: true }).first().click()
    await expect(dialog).toHaveCount(0)
    await link.hover()
    await expect(link).toHaveCSS('color', bodyStyle.color)
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
  await expect(discard).toHaveCSS('height', '44px')
  await expect(discard).toHaveCSS('color', await token(discard, 'color', '--fg'))
  await expect(discard).toHaveCSS('border-top-color', await token(discard, 'borderColor', '--danger-strong'))
  await noOverflow(page.getByRole('dialog'))
  await page.screenshot({ path: info.outputPath('corrupt-warning-320.png') })
  await discard.click()
  await expect(banner).toHaveCount(0)
})

for (const width of [320, 1280]) {
  test(`ERROR pause requires confirmation before continuing at ${width}px`, async ({ page }, info) => {
    // 验证真实弹窗中的详情、风险提醒、键盘取消及成功刷新，覆盖窄屏和桌面。
    await page.setViewportSize({ width, height: 900 })
    const state = await transport(page, 'ERROR')
    await page.goto('/browser-tests/project-workflow-harness.html?issue')
    const issueDialog = page.getByRole('dialog', { name: 'Issue #12 详情' })
    await expect(issueDialog.getByText('ERROR', { exact: true })).toBeVisible()
    const resume = issueDialog.getByRole('button', { name: '继续', exact: true })
    await resume.click()
    const confirmation = page.getByRole('dialog', { name: '继续执行' })
    await expect(confirmation).toBeVisible()
    await expect(confirmation).toContainText(PAUSE_DETAIL)
    await expect(confirmation).toContainText('请先核查外部副作用及其结果')
    await expect(confirmation.getByRole('button', { name: '取消' })).toBeFocused()
    await noOverflow(confirmation)
    await page.screenshot({ path: info.outputPath(`resume-error-confirm-${width}.png`) })

    await page.keyboard.press('Escape')
    await expect(confirmation).toHaveCount(0)
    await expect(resume).toBeFocused()
    expect(state.resumeRequests).toEqual([])
    await resume.click()
    await confirmation.getByRole('button', { name: '确认继续' }).click()
    await expect.poll(() => state.resumeRequests.length).toBe(1)
    expect(state.resumeRequests[0]).toEqual({
      expectedVersion: '1',
      requestKey: expect.any(String),
    })
    expect(state.resumeRequests[0].requestKey).not.toBe('')
    await expect(confirmation).toHaveCount(0)
    await expect(resume).toHaveCount(0)
    await expect(issueDialog.getByText('ERROR', { exact: true })).toHaveCount(0)
    expect(state.issueReads).toBeGreaterThanOrEqual(2)
  })
}

test('USER pause continues directly and refreshes the issue', async ({ page }, info) => {
  // USER 暂停无需额外确认，点击后应直接提交并失效 Issue 查询。
  await page.setViewportSize({ width: 1280, height: 900 })
  const state = await transport(page, 'USER')
  await page.goto('/browser-tests/project-workflow-harness.html?issue')
  const issueDialog = page.getByRole('dialog', { name: 'Issue #12 详情' })
  const resume = issueDialog.getByRole('button', { name: '继续', exact: true })
  await expect(issueDialog.getByText('PAUSED', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('resume-user-1280.png') })
  await resume.click()
  await expect.poll(() => state.resumeRequests.length).toBe(1)
  expect(state.resumeRequests[0]).toEqual({ expectedVersion: '1', requestKey: expect.any(String) })
  await expect(page.getByRole('dialog', { name: '继续执行' })).toHaveCount(0)
  await expect(resume).toHaveCount(0)
  await expect(issueDialog.getByText('PAUSED', { exact: true })).toHaveCount(0)
  expect(state.issueReads).toBeGreaterThanOrEqual(2)
})

test('unknown ERROR resume remains accessible and replays the same request after reload', async ({ page }, info) => {
  // 首次请求已提交但结果未知；刷新后必须保留身份并让用户能原样重试。
  await page.setViewportSize({ width: 320, height: 900 })
  const state = await transport(page, 'ERROR', true)
  await page.goto('/browser-tests/project-workflow-harness.html?issue')
  await page.getByRole('button', { name: '继续', exact: true }).click()
  await page.getByRole('dialog', { name: '继续执行' }).getByRole('button', { name: '确认继续' }).click()
  const banner = page.locator('.issue-detail-alert-warning')
  await expect(banner).toContainText('RESUME')
  await expect(page.getByRole('dialog', { name: '继续执行' })).toHaveCount(0)
  await expect(banner.getByRole('button', { name: '重试原操作' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('resume-unknown-320.png') })
  await page.reload()
  await expect(page.getByRole('dialog', { name: 'Issue #12 详情' })).toBeVisible()
  await expect(banner).toContainText('RESUME')
  await banner.getByRole('button', { name: '重试原操作' }).click()
  await expect.poll(() => state.resumeRequests.length).toBe(2)
  expect(state.resumeRequests[1]).toEqual(state.resumeRequests[0])
  await expect(banner).toHaveCount(0)
  expect(await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)).toBeNull()
})
