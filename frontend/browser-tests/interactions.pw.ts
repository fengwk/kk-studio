import type { Route } from '@playwright/test'
import { expect, test, type Page } from './fixture'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { InteractionDTO } from '../src/shared/api/contracts/ai-interaction'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPORTS_DIR = path.resolve(__dirname, '../../reports/layout')
const HARNESS_URL = '/browser-tests/interactions-harness.html'

const APPROVAL_INTERACTION_ID = 'int-approval'
const QUESTIONNAIRE_INTERACTION_ID = 'int-questionnaire'
const APPROVAL_ROOT_ID = 'f0000000-0000-0000-0000-00000000f001'
const QUESTIONNAIRE_ROOT_ID = 'f0000000-0000-0000-0000-00000000f002'
const ENVIRONMENT_ROOT_ID = 'f0000000-0000-0000-0000-00000000f003'
const ISSUE_ID = 'b0000000-0000-0000-0000-000000000042'
const PROJECT_ID = 'a0000000-0000-0000-0000-000000000001'
const targetWritesByPage = new WeakMap<Page, string[]>()
const LONG_OPTION_LABEL = '先备份再迁移并在完成后立即校验一致性'
const LONG_REASON =
  'bash requires approval in /srv/kk-studio/frontend/browser-tests/'
  + 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/reason.json'
const LONG_ARGUMENTS = JSON.stringify({
  command: 'npm --prefix frontend run test -- src/features/ai/runtime/thread-panel/messages/tool-message.view.test.tsx',
  cwd: '/srv/kk-studio/frontend/browser-tests/nested/deeply/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  env: { NODE_ENV: 'test', CI: 'true' },
  timeoutMs: 120000,
})

interface RecordedRequest {
  url: string
  body: Record<string, unknown>
}

interface PendingApiOptions {
  approvals?: RecordedRequest[]
  submissions?: RecordedRequest[]
  /** 首次列表请求返回 500，用于验证错误态与重试恢复。 */
  failFirstList?: boolean
  /** 审批决策返回的状态码，用于验证过期冲突文案。 */
  approvalStatus?: number
  /** 前 N 次审批决策返回 500，用于验证同 decisionId 的精确重试。 */
  approvalFailures?: number
}

interface PendingApiState {
  listCalls: number
  decidedApprovals: Set<string>
  answeredQuestionnaires: Set<string>
}

function buildItems(): InteractionDTO[] {
  return [
    {
      interactionId: APPROVAL_INTERACTION_ID,
      type: 'APPROVAL',
      status: 'WAITING_APPROVAL',
      threadId: 'thread-approval',
      rootThreadId: APPROVAL_ROOT_ID,
      sessionId: 'session-approval',
      owner: { type: 'CHAT', chatId: 'chat-pending', chatTitle: '迁移审批',
        issueId: null, issueTitle: null, agentName: null, rootThreadName: '审批执行根' },
      environmentId: null,
      environmentName: null,
      waitingCount: null,
      toolCallId: 'call-approval',
      toolName: 'bash',
      argumentsJson: LONG_ARGUMENTS,
      approvalJson: JSON.stringify({ required: true, reason: LONG_REASON }),
      createTime: '2026-10-05T09:57:00Z',
    },
    {
      interactionId: QUESTIONNAIRE_INTERACTION_ID,
      type: 'INPUT',
      status: 'WAITING_INPUT',
      threadId: 'thread-questionnaire',
      rootThreadId: QUESTIONNAIRE_ROOT_ID,
      sessionId: 'session-questionnaire',
      owner: { type: 'ISSUE_AGENT', chatId: null, chatTitle: null,
        issueId: ISSUE_ID, issueTitle: '迁移策略', agentName: 'architect', rootThreadName: '问卷执行根' },
      environmentId: null,
      environmentName: null,
      waitingCount: null,
      toolCallId: 'call-questionnaire',
      toolName: 'ask_user',
      argumentsJson: JSON.stringify({
        questions: [
          {
            question: '请选择要执行的迁移策略与回滚预案？',
            multiple: true,
            options: [
              {
                label: LONG_OPTION_LABEL,
                description: '保守策略，耗时较长但每一步都可回滚',
                recommended: true,
              },
              {
                label: '在线双写迁移并在观察期结束后切换',
                description: '无需停机，但需要额外的对账脚本',
              },
              {
                label: '仅迁移本次变更涉及的表',
                description: '范围最小，风险集中',
              },
            ],
          },
          { question: '补充说明（可自定义）', options: [] },
        ],
      }),
      approvalJson: null,
      createTime: '2026-10-05T09:58:00Z',
    },
    {
      type: 'ENVIRONMENT_WAIT',
      interactionId: null,
      status: null,
      threadId: null,
      rootThreadId: ENVIRONMENT_ROOT_ID,
      sessionId: null,
      owner: { type: 'CHAT', chatId: 'chat-environment', chatTitle: '等待迁移环境',
        issueId: null, issueTitle: null, agentName: null, rootThreadName: '环境执行根' },
      toolCallId: null,
      toolName: null,
      argumentsJson: null,
      approvalJson: null,
      environmentId: 'a0000000-0000-0000-0000-00000000a001',
      environmentName: 'migration-node',
      waitingCount: 2,
      createTime: '2026-10-05T09:59:00Z',
    },
  ]
}

/**
 * 拦截全部 /api 请求：已决策的交互从后续列表中移除，与真实后端一致，
 * 避免因失效重取而把已完成的卡片重新渲染出来。
 */
async function mockPendingApi(page: Page, options: PendingApiOptions = {}): Promise<void> {
  const targetWrites: string[] = []
  targetWritesByPage.set(page, targetWrites)
  await page.routeWebSocket(/\/api\/events\/v1$/, () => {})
  const state: PendingApiState = {
    listCalls: 0,
    decidedApprovals: new Set(),
    answeredQuestionnaires: new Set(),
  }
  let approvalFailuresLeft = options.approvalFailures ?? 0

  await page.route(
    (url) => new URL(url).pathname.startsWith('/api/'),
    async (route: Route) => {
      const request = route.request()
      const requestPath = new URL(request.url()).pathname
      const method = request.method()
      // 决策只写原始调用；任何容器/目标 Thread 写入（包括未 mock 的请求）都必须失败。
      if (method !== 'GET'
        && !(method === 'PUT' && requestPath === '/api/harness/threads/thread-approval/tool-invocations/int-approval/approval')
        && !(method === 'POST' && requestPath === '/api/interactions/int-questionnaire/input')) {
        targetWrites.push(`${method} ${requestPath}`)
      }

      if (requestPath === '/api/interactions' && method === 'GET') {
        state.listCalls += 1
        if (options.failFirstList && state.listCalls === 1) {
          await route.fulfill({
            status: 500,
            json: { status: 500, message: '服务暂时不可用' },
          })
          return
        }
        const items = buildItems().filter((item) => item.type === 'ENVIRONMENT_WAIT'
          || (!state.decidedApprovals.has(item.interactionId)
          && !state.answeredQuestionnaires.has(item.interactionId)))
        // total 是同一过滤条件下的真实可见待处理总数，与游标分页位置无关。
        await route.fulfill({
          json: { status: 200, data: { items, nextCursor: null, total: items.length,
            freshnessAt: '2026-10-05T10:00:00Z' } },
        })
        return
      }

      if (requestPath.endsWith('/approval') && method === 'PUT') {
        options.approvals?.push({
          url: request.url(),
          body: request.postDataJSON() as Record<string, unknown>,
        })
        if (options.approvalStatus && options.approvalStatus >= 400) {
          await route.fulfill({
            status: options.approvalStatus,
            json: { status: options.approvalStatus, message: 'interaction already decided' },
          })
          return
        }
        if (approvalFailuresLeft > 0) {
          approvalFailuresLeft -= 1
          await route.fulfill({
            status: 500,
            json: { status: 500, message: '审批服务暂时不可用' },
          })
          return
        }
        const invocationId = requestPath.split('/tool-invocations/')[1]?.split('/')[0]
        if (invocationId) {
          state.decidedApprovals.add(decodeURIComponent(invocationId))
        }
        await route.fulfill({ json: { status: 200, data: { id: 'inv-1' } } })
        return
      }

      if (requestPath.endsWith('/input') && method === 'POST') {
        options.submissions?.push({
          url: request.url(),
          body: request.postDataJSON() as Record<string, unknown>,
        })
        const interactionId = requestPath.split('/interactions/')[1]?.split('/')[0]
        if (interactionId) {
          state.answeredQuestionnaires.add(decodeURIComponent(interactionId))
        }
        await route.fulfill({
          json: {
            status: 200,
            data: {
              threadId: 'thread-questionnaire',
              interactionId: QUESTIONNAIRE_INTERACTION_ID,
              submissionId: 'sub-1',
              actor: 'user',
              acceptedAt: '2026-10-05T10:00:00Z',
              materialized: true,
            },
          },
        })
        return
      }

      if (requestPath === `/api/issues/${ISSUE_ID}` && method === 'GET') {
        await route.fulfill({
          json: {
            status: 200,
            data: {
              issue: {
                id: ISSUE_ID,
                projectId: PROJECT_ID,
                number: '42',
                title: 'Pending interactions',
                description: '',
                state: 'IN_PROGRESS',
                version: '1',
                archivedAt: null,
                createdAt: '2026-10-05T09:58:00Z',
                updatedAt: '2026-10-05T09:58:00Z',
              },
              activities: [],
              nextActivityCursor: null,
              runs: [],
              currentRun: null,
              latestRun: null,
              stageBudgets: [],
              agentThreads: [],
            },
          },
        })
        return
      }

      await route.fulfill({
        status: 404,
        json: { status: 404, message: `unmocked ${method} ${requestPath}` },
      })
    },
  )
}

/** 通过探针元素解析 :root token 的实际计算值，避免在测试里硬编码色值。 */
function resolveToken(page: Page, token: string): Promise<string> {
  return page.evaluate((name) => {
    const probe = document.createElement('span')
    probe.style.color = `var(${name})`
    document.body.appendChild(probe)
    const value = window.getComputedStyle(probe).color
    probe.remove()
    return value
  }, token)
}

function rgbTriplet(value: string): [number, number, number] {
  const match = value.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
  if (!match) {
    throw new Error(`不支持的颜色格式: ${value}`)
  }
  return [Number(match[1]), Number(match[2]), Number(match[3])]
}

function relativeLuminance([r, g, b]: [number, number, number]): number {
  const channel = (value: number) => {
    const scaled = value / 255
    return scaled <= 0.03928 ? scaled / 12.92 : ((scaled + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

function contrastRatio(foreground: string, background: string): number {
  const a = relativeLuminance(rgbTriplet(foreground))
  const b = relativeLuminance(rgbTriplet(background))
  const [light, dark] = a > b ? [a, b] : [b, a]
  return (light + 0.05) / (dark + 0.05)
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => {
    const content = document.querySelector<HTMLElement>('.interactions-page-content')
    const layout = document.querySelector<HTMLElement>('.interactions-page-layout')
    return {
      docScrollWidth: document.documentElement.scrollWidth,
      docClientWidth: document.documentElement.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      bodyClientWidth: document.body.clientWidth,
      contentScrollWidth: content?.scrollWidth ?? 0,
      contentClientWidth: content?.clientWidth ?? 0,
      layoutScrollWidth: layout?.scrollWidth ?? 0,
      layoutClientWidth: layout?.clientWidth ?? 0,
    }
  })
  expect(metrics.docScrollWidth).toBeLessThanOrEqual(metrics.docClientWidth + 1)
  expect(metrics.bodyScrollWidth).toBeLessThanOrEqual(metrics.bodyClientWidth + 1)
  expect(metrics.contentScrollWidth).toBeLessThanOrEqual(metrics.contentClientWidth + 1)
  expect(metrics.layoutScrollWidth).toBeLessThanOrEqual(metrics.layoutClientWidth + 1)
}

test.describe('Pending Interactions real browser appearance and decisions', () => {
  test.afterEach(({ page }) => {
    expect(targetWritesByPage.get(page)).toEqual([])
  })

  test('approval and questionnaire cards use the current dark tokens with readable text at 1280/390/320', async ({
    page,
  }) => {
    fs.mkdirSync(REPORTS_DIR, { recursive: true })
    await mockPendingApi(page)
    const statusTagStyles: Array<{ color: string; background: string; warning: string; warningSoft: string }> = []

    for (const viewport of [
      { width: 1280, height: 900, name: 'desktop-1280' },
      { width: 390, height: 900, name: 'mobile-390' },
      { width: 320, height: 800, name: 'mobile-320' },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto(HARNESS_URL)

      const card = page.locator('.interaction-feed-item').first()
      await expect(card).toBeVisible()
      await expect(card.locator('.interaction-status-tag')).toHaveClass(/approval/)
      await expect(card.locator('.interaction-approval-reason-text')).toContainText('bash requires approval')
      await expect(card.locator('.interaction-raw-pre')).toContainText('npm --prefix frontend run test')

      const [
        surface,
        border,
        bg,
        fg,
        fgMuted,
        warning,
        warningSoft,
      ] = await Promise.all([
        resolveToken(page, '--surface'),
        resolveToken(page, '--border'),
        resolveToken(page, '--bg'),
        resolveToken(page, '--fg'),
        resolveToken(page, '--fg-muted'),
        resolveToken(page, '--warning'),
        resolveToken(page, '--warning-soft'),
      ])

      // 卡片、审批原因与原始参数必须落在现行暗色 token 上，不能残留浅色回退。
      const cardStyle = await card.evaluate((el) => {
        const style = window.getComputedStyle(el)
        const reason = el.querySelector<HTMLElement>('.interaction-approval-reason-text')!
        const pre = el.querySelector<HTMLElement>('.interaction-raw-pre')!
        const tag = el.querySelector<HTMLElement>('.interaction-status-tag')!
        return {
          cardBackground: style.backgroundColor,
          cardBorder: style.borderTopColor,
          reasonColor: window.getComputedStyle(reason).color,
          preColor: window.getComputedStyle(pre).color,
          preBackground: window.getComputedStyle(pre).backgroundColor,
          tagColor: window.getComputedStyle(tag).color,
          tagBackground: window.getComputedStyle(tag).backgroundColor,
        }
      })

      expect(cardStyle.cardBackground).toBe(surface)
      expect(cardStyle.cardBorder).toBe(border)
      expect(cardStyle.reasonColor).toBe(fgMuted)
      expect(cardStyle.preColor).toBe(fg)
      expect(cardStyle.preBackground).toBe(bg)
      statusTagStyles.push({
        color: cardStyle.tagColor, background: cardStyle.tagBackground, warning, warningSoft,
      })

      // 正文对比度必须达到可读水平，这正是截图里失效的部分。
      expect(contrastRatio(cardStyle.preColor, cardStyle.preBackground)).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(cardStyle.reasonColor, cardStyle.cardBackground)).toBeGreaterThanOrEqual(4.5)

      // 长原因与长 JSON 必须完整保留（真实审批所需信息）而不是被截断。
      await expect(card.locator('.interaction-approval-reason-text')).toHaveText(LONG_REASON)
      await expect(card.locator('.interaction-raw-pre')).toHaveText(LONG_ARGUMENTS)

      const optionLabel = page.locator('.interaction-option-label').first()
      await expect(optionLabel).toBeVisible()
      await expect(optionLabel).toHaveText(LONG_OPTION_LABEL)
      const optionStyle = await optionLabel.evaluate((el) => ({
        color: window.getComputedStyle(el).color,
        cardBackground: window.getComputedStyle(el.closest('.interaction-feed-item')!).backgroundColor,
      }))
      expect(optionStyle.color).toBe(fg)
      expect(contrastRatio(optionStyle.color, optionStyle.cardBackground)).toBeGreaterThanOrEqual(4.5)

      // 允许与拒绝都必须可达；允许按钮沿用共享 btn-primary，而不是硬编码绿色。
      await expect(card.locator('.interaction-allow-btn')).toBeEnabled()
      await expect(card.locator('.interaction-deny-btn')).toBeEnabled()
      const allowStyle = await card.locator('.interaction-allow-btn').evaluate((el) => {
        const style = window.getComputedStyle(el)
        return {
          backgroundImage: style.backgroundImage,
          color: style.color,
          border: style.borderTopColor,
        }
      })
      const referencePrimary = await page.locator('.interaction-submit-btn').evaluate(
        (el) => window.getComputedStyle(el).backgroundImage,
      )
      expect(allowStyle.backgroundImage).toBe(referencePrimary)
      expect(allowStyle.color).toBe(await resolveToken(page, '--green-on'))
      expect(allowStyle.border).toBe(await resolveToken(page, '--green-border'))

      await expectNoHorizontalOverflow(page)

      await page.screenshot({
        path: path.join(REPORTS_DIR, `interactions-pending-${viewport.name}.png`),
        fullPage: false,
      })
    }
    // 在所有宽度完成正文、可操作性和溢出校验后，仍严格验证审批语义色。
    for (const style of statusTagStyles) {
      expect(style.color).toBe(style.warning)
      expect(style.background).toBe(style.warningSoft)
    }
  })

  test('environment wait is read-only and refresh never submits a decision', async ({ page }) => {
    const approvals: RecordedRequest[] = []
    const submissions: RecordedRequest[] = []
    await mockPendingApi(page, { approvals, submissions })
    await page.setViewportSize({ width: 320, height: 800 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.interaction-feed-item').filter({ has: page.getByRole('status') })
    await expect(card).toHaveCount(1)
    await expect(card.getByRole('status')).toContainText('migration-node')
    await expect(card.getByRole('status')).toContainText('2')
    await expect(card.locator('.interaction-status-tag')).toHaveClass(/environment_wait/)
    await expect(card.locator('.interaction-allow-btn, .interaction-deny-btn, .interaction-submit-btn')).toHaveCount(0)
    await expect(card.getByRole('textbox')).toHaveCount(0)
    await expect(card.locator('.interaction-source-link')).toContainText('环境执行根')
    const refresh = page.getByRole('button', { name: '刷新', exact: true })
    const refreshed = page.waitForResponse((response) =>
      new URL(response.url()).pathname === '/api/interactions'
      && response.request().method() === 'GET')
    await refresh.click()
    await refreshed
    await expect(refresh).toBeEnabled()
    await expect(card.getByRole('status')).toBeVisible()
    expect(approvals).toEqual([])
    expect(submissions).toEqual([])
    await expectNoHorizontalOverflow(page)
  })

  test('chat source opens its execution root without changing the original approval identity', async ({ page }) => {
    const approvals: RecordedRequest[] = []
    const submissions: RecordedRequest[] = []
    await mockPendingApi(page, { approvals, submissions })
    await page.goto(HARNESS_URL)

    const source = page.locator('.interaction-feed-item').first().locator('.interaction-source-link')
    await expect(source).toContainText('迁移审批')
    await expect(source).toContainText('审批执行根')
    await source.click()
    await expect(page).toHaveURL(new RegExp(`/chats/chat-pending\\?thread=${APPROVAL_ROOT_ID}$`))
    expect(approvals).toEqual([])
    expect(submissions).toEqual([])
  })

  test('issue source opens its execution root without submitting questionnaire input', async ({ page }) => {
    const approvals: RecordedRequest[] = []
    const submissions: RecordedRequest[] = []
    await mockPendingApi(page, { approvals, submissions })
    await page.goto(HARNESS_URL)

    const source = page.locator('.interaction-feed-item').nth(1).locator('.interaction-source-link')
    await expect(source).toContainText('迁移策略')
    await expect(source).toContainText('问卷执行根')
    await expect(source).toContainText('architect')
    await source.click()
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_ID}\\?issue=${ISSUE_ID}&thread=${QUESTIONNAIRE_ROOT_ID}$`))
    expect(approvals).toEqual([])
    expect(submissions).toEqual([])
  })

  test('allow is submitted once and never rewrites the target thread', async ({ page }) => {
    const approvals: RecordedRequest[] = []
    await mockPendingApi(page, { approvals })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const beforeUrl = page.url()
    const approvalCard = page.locator('.interaction-feed-item').first()
    const toolName = page.locator('.interaction-approval-tool-name')
    await expect(toolName).toHaveText('bash')

    await approvalCard.locator('.interaction-allow-btn').click()

    // 只移除本卡，其余待处理项与目标 thread 均不受影响。
    await expect(toolName).toHaveCount(0)
    await expect(page.getByText('architect')).toBeVisible()
    expect(page.url()).toBe(beforeUrl)
    expect(approvals).toHaveLength(1)
    expect(approvals[0].url).toContain(
      '/api/harness/threads/thread-approval/tool-invocations/int-approval/approval',
    )
    expect(approvals[0].body).toMatchObject({ decision: 'ALLOW', reason: null })
    expect(String(approvals[0].body.decisionId)).not.toHaveLength(0)
  })

  test('deny is submitted once with its own decision id and never rewrites the target thread', async ({
    page,
  }) => {
    const approvals: RecordedRequest[] = []
    await mockPendingApi(page, { approvals })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const beforeUrl = page.url()
    await page.locator('.interaction-feed-item').first().locator('.interaction-deny-btn').click()

    await expect(page.locator('.interaction-approval-tool-name')).toHaveCount(0)
    await expect(page.getByText('architect')).toBeVisible()
    expect(page.url()).toBe(beforeUrl)
    expect(approvals).toHaveLength(1)
    expect(approvals[0].url).toContain(
      '/api/harness/threads/thread-approval/tool-invocations/int-approval/approval',
    )
    expect(approvals[0].body).toMatchObject({ decision: 'DENY', reason: null })
    expect(String(approvals[0].body.decisionId)).not.toHaveLength(0)
  })

  test('retrying an approval reuses one decision id and never rewrites the target thread', async ({
    page,
  }) => {
    const approvals: RecordedRequest[] = []
    await mockPendingApi(page, { approvals, approvalFailures: 1 })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const beforeUrl = page.url()
    const card = page.locator('.interaction-feed-item').first()
    const toolName = page.locator('.interaction-approval-tool-name')
    await expect(toolName).toHaveText('bash')

    // 第一次失败：保留卡片与目标 thread，允许用户直接用同一决策重试。
    await card.locator('.interaction-allow-btn').click()
    await expect(card.locator('.interaction-error-banner')).toHaveText('审批服务暂时不可用')
    await expect(toolName).toHaveText('bash')
    expect(page.url()).toBe(beforeUrl)

    await card.locator('.interaction-allow-btn').click()
    await expect(toolName).toHaveCount(0)
    expect(page.url()).toBe(beforeUrl)
    expect(approvals).toHaveLength(2)
    // 幂等：重试必须复用首次生成的 decisionId，避免重复决策。
    expect(approvals[1].body.decisionId).toBe(approvals[0].body.decisionId)
    expect(approvals[1].body).toMatchObject({ decision: 'ALLOW', reason: null })
    expect(approvals[1].url).toBe(approvals[0].url)
  })

  test('long questionnaire options and custom input stay selectable and submittable at 390px', async ({ page }) => {
    const submissions: RecordedRequest[] = []
    await mockPendingApi(page, { submissions })
    await page.setViewportSize({ width: 390, height: 900 })
    await page.goto(HARNESS_URL)

    const optionBtn = page.locator('.interaction-option-btn').first()
    await expect(optionBtn).toBeVisible()
    await optionBtn.click()

    // 等待过渡后的计算值，并确保悬停不覆盖选中态。
    await optionBtn.hover()
    const greenPrimary = await resolveToken(page, '--green-primary')
    await expect(optionBtn).toHaveCSS('border-top-color', greenPrimary)
    await expect(optionBtn).toHaveCSS(
      'background-color',
      await resolveToken(page, '--green-soft'),
    )
    const indicator = optionBtn.locator('.interaction-option-indicator')
    await expect(indicator).toHaveCSS('background-color', greenPrimary)
    await expect(indicator).toHaveCSS('color', await resolveToken(page, '--green-on'))

    // 自定义输入必须可聚焦填写；提交前保持禁用，填写后放行。
    const customInput = page.locator('.interaction-custom-input').nth(1)
    await expect(page.locator('.interaction-submit-btn')).toBeDisabled()
    await customInput.fill('请补充：先备份再执行')
    await expect(customInput).toHaveValue('请补充：先备份再执行')
    await expect(page.locator('.interaction-submit-btn')).toBeEnabled()

    await expectNoHorizontalOverflow(page)
    await page.screenshot({
      path: path.join(REPORTS_DIR, 'interactions-questionnaire-390.png'),
      fullPage: false,
    })

    await page.locator('.interaction-submit-btn').click()

    await expect(page.locator('.interaction-questionnaire-card')).toHaveCount(0)
    expect(submissions).toHaveLength(1)
    expect(submissions[0].url).toContain('/api/interactions/int-questionnaire/input')
    expect(submissions[0].body).toMatchObject({
      threadId: 'thread-questionnaire',
      declined: false,
    })
    expect(submissions[0].body.answers).toEqual([
      [LONG_OPTION_LABEL],
      ['请补充：先备份再执行'],
    ])
  })

  test('list failure surfaces a retry action and recovers in place', async ({ page }) => {
    await mockPendingApi(page, { failFirstList: true })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    await expect(page.getByText('服务暂时不可用')).toBeVisible()
    await page.getByRole('button', { name: '重试' }).click()
    await expect(page.locator('.interaction-approval-tool-name')).toHaveText('bash')
  })

  test('a stale approval explains the change instead of claiming it is unrecoverable', async ({ page }) => {
    await mockPendingApi(page, { approvalStatus: 409 })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.interaction-feed-item').first()
    await card.locator('.interaction-allow-btn').click()

    const banner = card.locator('.interaction-error-banner')
    await expect(banner).toHaveText('此事项已变更，请刷新查看最新状态。')
    const bannerStyle = await banner.evaluate((el) => ({
      color: window.getComputedStyle(el).color,
      background: window.getComputedStyle(el).backgroundColor,
      border: window.getComputedStyle(el).borderTopColor,
    }))
    expect(bannerStyle.color).toBe(await resolveToken(page, '--warning'))
    expect(bannerStyle.background).toBe(await resolveToken(page, '--warning-soft'))
    expect(bannerStyle.border).toBe(await resolveToken(page, '--warning-border'))

    // 过期决策禁止再次提交，但仍可刷新。
    await expect(card.locator('.interaction-allow-btn')).toBeDisabled()
    await expect(card.locator('.interaction-deny-btn')).toBeDisabled()
  })
})
