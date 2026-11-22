import fs from 'node:fs'
import path from 'node:path'
import type { Route } from '@playwright/test'
import { expect, test, tokenColor, type Page } from './fixture'
import type { InteractionDTO } from '../src/shared/api/generated/contracts'
import './runtime-visual-matrix'

const HARNESS_URL = '/browser-tests/interactions-harness.html'
const QUESTIONNAIRE_HARNESS_URL = '/browser-tests/questionnaire-card-harness.html'
/** 问卷卡自查截图目录：未设置时不输出截图、不跳过语义断言。 */
const QUESTIONNAIRE_SHOTS_DIR = process.env.KK_QUESTIONNAIRE_SHOTS_DIR
/** 问卷标题行窄屏修复的对照截图目录：未设置时不落盘、不跳过语义断言。 */
const QUESTIONNAIRE_TITLE_SHOTS_DIR = process.env.KK_QUESTIONNAIRE_TITLE_SHOTS_DIR

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
  command: 'npm --prefix frontend run test -- src/features/thread/runtime/thread-panel/messages/tool-message.view.test.tsx',
  workdir: '/srv/kk-studio/frontend/browser-tests/nested/deeply/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  timeout_seconds: 120,
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
  listFailureMessage?: string
  /** 审批决策返回的状态码，用于验证过期冲突文案。 */
  approvalStatus?: number
  /** 前 N 次审批决策返回 500，用于验证同 decisionId 的精确重试。 */
  approvalFailures?: number
  listGate?: Promise<void>
  empty?: boolean
  paginate?: boolean
  failFirstNextPage?: boolean
}

interface PendingApiState {
  listCalls: number
  decidedApprovals: Set<string>
  answeredQuestionnaires: Set<string>
}

function buildItems(): InteractionDTO[] {
  return [
    {
      toolInvocationId: APPROVAL_INTERACTION_ID,
      type: 'APPROVAL',
      threadId: 'thread-approval',
      rootThreadId: APPROVAL_ROOT_ID,
      sessionId: 'session-approval',
      owner: { kind: 'CHAT', id: 'chat-pending', title: '迁移审批', link: '/chats/chat-pending' },
      toolCallId: 'call-approval',
      toolName: 'bash',
      argumentsJson: LONG_ARGUMENTS,
      approval: { required: true, reason: LONG_REASON },
      queuedSameFileCount: 0,
      createdAt: 1791194220000,
    },
    {
      toolInvocationId: QUESTIONNAIRE_INTERACTION_ID,
      type: 'INPUT',
      threadId: 'thread-questionnaire',
      rootThreadId: QUESTIONNAIRE_ROOT_ID,
      sessionId: 'session-questionnaire',
      owner: { kind: 'ISSUE_AGENT', id: `${ISSUE_ID}:architect`, title: '迁移策略',
        link: `/projects/${PROJECT_ID}?issue=${ISSUE_ID}` },
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
      createdAt: 1791194280000,
    },
    {
      type: 'ENVIRONMENT_WAIT',
      rootThreadId: ENVIRONMENT_ROOT_ID,
      owner: { kind: 'CHAT', id: 'chat-environment', title: '等待迁移环境', link: '/chats/chat-environment' },
      environmentId: 'a0000000-0000-0000-0000-00000000a001',
      environmentName: 'migration-node',
      waitingCount: 2,
      createdAt: 1791194340000,
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
  await page.routeWebSocket(/\/ws\/events$/, () => {})
  const state: PendingApiState = {
    listCalls: 0,
    decidedApprovals: new Set(),
    answeredQuestionnaires: new Set(),
  }
  let approvalFailuresLeft = options.approvalFailures ?? 0
  let nextPageFailed = false

  await page.route(
    (url) => new URL(url).pathname.startsWith('/api/'),
    async (route: Route) => {
      const request = route.request()
      const requestPath = new URL(request.url()).pathname
      const method = request.method()
      // 决策只写原始调用；任何容器/目标 Thread 写入（包括未 mock 的请求）都必须失败。
      if (method !== 'GET'
        && !(method === 'PUT' && requestPath === '/api/harness/threads/thread-approval/tool-invocations/int-approval/approval')
        && !(method === 'POST'
          && requestPath === '/api/harness/threads/thread-questionnaire/tool-invocations/int-questionnaire/input')) {
        targetWrites.push(`${method} ${requestPath}`)
      }

      if (requestPath === '/api/harness/interactions' && method === 'GET') {
        await options.listGate
        state.listCalls += 1
        if (options.failFirstList && state.listCalls === 1) {
          await route.fulfill({
            status: 500,
            contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 500, code: 'internal', title: options.listFailureMessage ?? '服务暂时不可用' },
          })
          return
        }
        const allItems = (options.empty ? [] : buildItems()).filter((item) => item.type === 'ENVIRONMENT_WAIT'
          || (!state.decidedApprovals.has(item.toolInvocationId)
          && !state.answeredQuestionnaires.has(item.toolInvocationId)))
        const cursor = new URL(request.url()).searchParams.get('cursor')
        if (cursor && options.failFirstNextPage && !nextPageFailed) {
          nextPageFailed = true
          await route.fulfill({ status: 500, contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 500, code: 'internal', title: '下一页暂时不可用' } })
          return
        }
        const items = options.paginate ? (cursor ? allItems.slice(1) : allItems.slice(0, 1)) : allItems
        const nextCursor = options.paginate && !cursor ? 'page-2' : null
        // total 是同一过滤条件下的真实可见待处理总数，与游标分页位置无关。
        await route.fulfill({
          json: { items, nextCursor, total: allItems.length, freshnessAt: 1791194400000 },
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
            contentType: 'application/problem+json',
            json: { type: 'about:blank', status: options.approvalStatus, code: 'conflict',
              reason: 'INTERACTION_ALREADY_DECIDED', title: 'interaction already decided' },
          })
          return
        }
        if (approvalFailuresLeft > 0) {
          approvalFailuresLeft -= 1
          await route.fulfill({
            status: 500,
            contentType: 'application/problem+json',
            json: { type: 'about:blank', status: 500, code: 'internal', title: '审批服务暂时不可用' },
          })
          return
        }
        const invocationId = requestPath.split('/tool-invocations/')[1]?.split('/')[0]
        if (invocationId) {
          state.decidedApprovals.add(decodeURIComponent(invocationId))
        }
        await route.fulfill({ json: { id: 'inv-1' } })
        return
      }

      if (requestPath.endsWith('/input') && method === 'POST') {
        options.submissions?.push({
          url: request.url(),
          body: request.postDataJSON() as Record<string, unknown>,
        })
        const toolInvocationId = requestPath.split('/tool-invocations/')[1]?.split('/')[0]
        if (toolInvocationId) {
          state.answeredQuestionnaires.add(decodeURIComponent(toolInvocationId))
        }
        await route.fulfill({
          json: {
              threadId: 'thread-questionnaire',
              toolInvocationId: QUESTIONNAIRE_INTERACTION_ID,
              submissionId: 'sub-1',
              actor: 'user',
              acceptedAt: 1791194400000,
              materialized: true,
          },
        })
        return
      }

      if (requestPath === `/api/project/issues/${ISSUE_ID}` && method === 'GET') {
        await route.fulfill({
          json: {
              issue: {
                id: ISSUE_ID,
                projectId: PROJECT_ID,
                number: '42',
                title: 'Pending interactions',
                description: '',
                state: 'IN_PROGRESS',
                version: '1',
                createdAt: Date.UTC(2026, 9, 5, 9, 58),
                updatedAt: Date.UTC(2026, 9, 5, 9, 58),
              },
              activities: [],
              nextActivityCursor: null,
              runs: [],
              stageBudgets: [],
              agentThreads: [],
          },
        })
        return
      }

      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        json: { type: 'about:blank', status: 404, code: 'not_found', title: `unmocked ${method} ${requestPath}` },
      })
    },
  )
}

/** 通过探针元素解析 :root token 的实际计算值，避免在测试里硬编码色值。 */
/** 计算样式的颜色：`rgb()`/`rgba()` 或 color-mix 序列化出的 `color(srgb r g b / a)`（通道为 0–1）。 */
function rgbTriplet(value: string): [number, number, number] {
  const rgb = value.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
  if (rgb) {
    return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
  }
  const srgb = value.match(/color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/)
  if (srgb) {
    return [Number(srgb[1]) * 255, Number(srgb[2]) * 255, Number(srgb[3]) * 255]
  }
  throw new Error(`不支持的颜色格式: ${value}`)
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
    await mockPendingApi(page)

    for (const viewport of [
      { width: 1280, height: 900, name: 'desktop-1280' },
      { width: 390, height: 900, name: 'mobile-390' },
      { width: 320, height: 800, name: 'mobile-320' },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto(HARNESS_URL)

      const card = page.locator('.interaction-feed-item').first()
      await expect(card).toBeVisible()
      await expect(card.locator('.interaction-status-tag')).toHaveCount(0)
      await expect(card.locator('time')).toHaveCount(0)
      await expect(card.locator('.interaction-approval-reason')).toContainText('bash requires approval')
      await expect(card.locator('.thread-tool-approval-field')).toHaveCount(3)
      await expect(card.locator('.thread-tool-approval-field').filter({ has: page.locator('dt', { hasText: 'command' }) }).locator('pre'))
        .toContainText('npm --prefix frontend run test')
      await expect(page.getByRole('heading', { name: '迁移审批' })).toBeVisible()
      await expect(page.getByRole('button', { name: '对话 · 迁移审批' })).toBeVisible()

      const [
        surface,
        border,
        fg,
        fgMuted,
        warning,
      ] = await Promise.all([
        tokenColor(page, '--surface'),
        tokenColor(page, '--border'),
        tokenColor(page, '--fg'),
        tokenColor(page, '--fg-muted'),
        tokenColor(page, '--warning'),
      ])

      // 卡片、审批原因与原始参数必须落在现行暗色 token 上，不能残留浅色回退。
      const cardStyle = await card.evaluate((el) => {
        const style = window.getComputedStyle(el)
        const reason = el.querySelector<HTMLElement>('.interaction-approval-reason')!
        const pre = el.querySelector<HTMLElement>('.thread-tool-approval-field pre')!
        const icon = el.querySelector<HTMLElement>('.interaction-approval-icon')!
        return {
          cardBackground: style.backgroundColor,
          cardBorder: style.borderTopColor,
          reasonColor: window.getComputedStyle(reason).color,
          preColor: window.getComputedStyle(pre).color,
          preBackground: window.getComputedStyle(pre).backgroundColor,
          iconColor: window.getComputedStyle(icon).color,
        }
      })

      expect(cardStyle.cardBackground).toBe(surface)
      expect(cardStyle.cardBorder).toBe(border)
      expect(cardStyle.reasonColor).toBe(fgMuted)
      expect(cardStyle.preColor).toBe(fg)
      expect(cardStyle.preBackground).toBe('rgba(0, 0, 0, 0)')
      // 权限语义保留在唯一工具行的静态图标，不叠第二份状态标题。
      expect(cardStyle.iconColor).toBe(warning)

      // 正文对比度必须达到可读水平，这正是截图里失效的部分。
      expect(contrastRatio(cardStyle.preColor, cardStyle.cardBackground)).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(cardStyle.reasonColor, cardStyle.cardBackground)).toBeGreaterThanOrEqual(4.5)

      // 长原因、完整 command 与 workdir 必须可读，未展示原始 JSON。
      await expect(card.locator('.interaction-approval-reason')).toHaveText(LONG_REASON)
      await expect(card.locator('.thread-tool-approval-field').filter({ has: page.locator('dt', { hasText: 'command' }) }).locator('pre'))
        .toHaveText(JSON.parse(LONG_ARGUMENTS).command)
      expect((await card.textContent())?.split('/srv/kk-studio/frontend/browser-tests/nested/deeply/')).toHaveLength(2)

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
      expect(allowStyle.color).toBe(await tokenColor(page, '--fg-on-accent'))
      expect(allowStyle.border).toBe(await tokenColor(page, '--accent-border'))
      const denyStyle = await card.locator('.interaction-deny-btn').evaluate((el) => {
        const style = getComputedStyle(el)
        return { background: style.backgroundImage, border: style.borderTopColor, color: style.color }
      })
      expect(denyStyle.background).toBe('none')
      expect(denyStyle.border).toBe(await tokenColor(page, '--danger-border'))
      expect(denyStyle.color).toBe(await tokenColor(page, '--danger'))

      await expectNoHorizontalOverflow(page)

      await page.screenshot({
        path: test.info().outputPath(`interactions-pending-${viewport.name}.png`),
        fullPage: false,
      })
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
    await expect(card.locator('.interaction-status-tag')).toHaveCount(0)
    await expect(card.locator('.interaction-allow-btn, .interaction-deny-btn, .interaction-submit-btn')).toHaveCount(0)
    await expect(card.getByRole('textbox')).toHaveCount(0)
    const group = page.locator('.interactions-session-group').filter({ has: card })
    await expect(group.getByRole('button', { name: '对话 · 等待迁移环境' })).toBeVisible()
    const refresh = page.getByRole('button', { name: '刷新', exact: true })
    const refreshed = page.waitForResponse((response) =>
      new URL(response.url()).pathname === '/api/harness/interactions'
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

    const group = page.locator('.interactions-session-group').first()
    const source = group.getByRole('button', { name: '对话 · 迁移审批' })
    const subagentSource = group.getByRole('link', { name: '打开子 Agent' })
    await expect(source).toContainText('对话 · 迁移审批')
    await expect(subagentSource).toHaveAttribute('href', '/threads/thread-approval')
    await expect(group.getByRole('link')).toHaveCount(1)
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

    const source = page.locator('.interactions-session-group').nth(1)
      .getByRole('button', { name: 'Issue · 迁移策略' })
    await expect(source).toContainText('Issue · 迁移策略')
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
    await expect(page.getByRole('button', { name: 'Issue · 迁移策略' })).toBeVisible()
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
    await expect(page.getByRole('button', { name: 'Issue · 迁移策略' })).toBeVisible()
    expect(page.url()).toBe(beforeUrl)
    expect(approvals).toHaveLength(1)
    expect(approvals[0].url).toContain(
      '/api/harness/threads/thread-approval/tool-invocations/int-approval/approval',
    )
    expect(approvals[0].body).toMatchObject({ decision: 'DENY', reason: null })
    expect(String(approvals[0].body.decisionId)).not.toHaveLength(0)
  })

  test('optional denial reason is submitted with Enter at a narrow viewport', async ({ page }) => {
    const approvals: RecordedRequest[] = []
    await mockPendingApi(page, { approvals })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.interaction-feed-item').first()
    const reason = card.getByRole('textbox', { name: '拒绝原因（可选）' })
    await reason.fill('请先检查命令范围')
    await reason.press('Enter')
    await expect(card.locator('.interaction-approval-tool-name')).toHaveCount(0)
    expect(approvals).toHaveLength(1)
    expect(approvals[0].body).toMatchObject({ decision: 'DENY', reason: '请先检查命令范围' })
    await expectNoHorizontalOverflow(page)
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
    const accent = await tokenColor(page, '--accent')
    await expect(optionBtn).toHaveCSS('border-top-color', accent)
    await expect(optionBtn).toHaveCSS(
      'background-color',
      await tokenColor(page, '--accent-soft'),
    )
    const indicator = optionBtn.locator('.interaction-option-indicator')
    await expect(indicator).toHaveCSS('background-color', accent)
    await expect(indicator).toHaveCSS('color', await tokenColor(page, '--fg-on-accent'))

    // 自定义输入必须可聚焦填写；提交前保持禁用，填写后放行。
    const customInput = page.locator('.interaction-custom-input').nth(1)
    await expect(page.locator('.interaction-submit-btn')).toBeDisabled()
    await customInput.fill('请补充：先备份再执行')
    await expect(customInput).toHaveValue('请补充：先备份再执行')
    await expect(page.locator('.interaction-submit-btn')).toBeEnabled()

    await expectNoHorizontalOverflow(page)
    await page.screenshot({
      path: test.info().outputPath('interactions-questionnaire-390.png'),
      fullPage: false,
    })

    await page.locator('.interaction-submit-btn').click()

    await expect(page.locator('.interaction-questionnaire-card')).toHaveCount(0)
    expect(submissions).toHaveLength(1)
    expect(submissions[0].url).toContain(
      '/api/harness/threads/thread-questionnaire/tool-invocations/int-questionnaire/input',
    )
    expect(submissions[0].body).toMatchObject({
      type: 'ANSWER',
      submissionId: expect.any(String),
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

  for (const width of [1280, 580, 390, 320]) {
    test(`shared loading, empty and error feedback fits ${width}px and retains retry`, async ({ page }) => {
      let release!: () => void
      const gate = new Promise<void>((resolve) => { release = resolve })
      const failureMessage = `服务暂时不可用：${'long-diagnostic-context/'.repeat(12)}`
      await mockPendingApi(page, { listGate: gate, empty: true, failFirstList: true, listFailureMessage: failureMessage })
      await page.setViewportSize({ width, height: 900 })
      await page.goto(HARNESS_URL)
      const loadingState = page.locator('.interactions-loading-state')
      await expect(loadingState).toHaveAttribute('role', 'status')
      await expect(loadingState).toContainText('正在加载资源')
      await expect(loadingState.locator('.interactions-loading-spinner.animate-spin')).toBeVisible()
      await expect(page.locator('.interactions-empty-state')).toHaveCount(0)
      await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeDisabled()
      await expectNoHorizontalOverflow(page)
      await page.screenshot({ path: test.info().outputPath(`interactions-loading-${width}.png`) })
      release()
      const error = page.locator('.state-block.danger')
      await expect(error).toHaveText(failureMessage)
      await expect(error).toHaveCSS('color', await tokenColor(page, '--danger'))
      await expect(page.locator('.interactions-empty-state')).toHaveCount(0)
      await expectNoHorizontalOverflow(page)
      await page.screenshot({ path: test.info().outputPath(`interactions-error-${width}.png`) })
      await page.getByRole('button', { name: '重试', exact: true }).click()
      const emptyState = page.locator('.interactions-empty-state')
      await expect(emptyState).toBeVisible()
      await expect(page.locator('.state-block')).toHaveCount(0)
      await expect(emptyState.locator('.interactions-empty-title')).toHaveText('暂无待处理事项')
      await expect(emptyState.locator('.interactions-empty-description')).toHaveText(
        '需要审批、补充输入或等待环境的请求会显示在这里。',
      )
      await expect(emptyState.locator('button')).toHaveCount(0)
      const emptyStyles = await emptyState.evaluate((el) => {
        const icon = el.querySelector<HTMLElement>('.interactions-empty-icon')!
        const title = el.querySelector<HTMLElement>('.interactions-empty-title')!
        const desc = el.querySelector<HTMLElement>('.interactions-empty-description')!
        const iconStyle = getComputedStyle(icon)
        return {
          borderStyle: getComputedStyle(el).borderTopStyle,
          iconWidth: iconStyle.width,
          iconHeight: iconStyle.height,
          iconBg: iconStyle.backgroundColor,
          iconColor: iconStyle.color,
          titleColor: getComputedStyle(title).color,
          descColor: getComputedStyle(desc).color,
        }
      })
      expect(emptyStyles.borderStyle).toBe('none')
      expect(emptyStyles.iconWidth).toBe('40px')
      expect(emptyStyles.iconHeight).toBe('40px')
      expect(emptyStyles.iconBg).toBe(await tokenColor(page, '--accent-soft'))
      expect(emptyStyles.iconColor).toBe(await tokenColor(page, '--accent'))
      expect(emptyStyles.titleColor).toBe(await tokenColor(page, '--fg'))
      expect(emptyStyles.descColor).toBe(await tokenColor(page, '--fg-muted'))
      await expectNoHorizontalOverflow(page)
      await page.screenshot({ path: test.info().outputPath(`interactions-empty-${width}.png`) })
    })
  }

  test('pagination failure keeps loaded items visible and refresh retry restores the authoritative list', async ({ page }) => {
    await mockPendingApi(page, { paginate: true, failFirstNextPage: true })
    await page.setViewportSize({ width: 320, height: 900 })
    await page.goto(HARNESS_URL)
    await expect(page.locator('.interaction-feed-item')).toHaveCount(1)
    await page.getByRole('button', { name: '加载更多', exact: true }).click()
    await expect(page.locator('.state-block.danger')).toHaveText('下一页暂时不可用')
    await expect(page.locator('.interaction-approval-tool-name')).toHaveText('bash')
    await expectNoHorizontalOverflow(page)
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await expect(page.locator('.state-block.danger')).toHaveCount(0)
    await expect(page.locator('.interaction-feed-item')).toHaveCount(1)
    await page.getByRole('button', { name: '加载更多', exact: true }).click()
    await expect(page.locator('.interaction-feed-item')).toHaveCount(3)
    await expect(page.getByRole('button', { name: '加载更多', exact: true })).toHaveCount(0)
    await expectNoHorizontalOverflow(page)
  })

  test('a stale approval becomes actionable after rereading the still-pending interaction', async ({ page }) => {
    await mockPendingApi(page, { approvalStatus: 409 })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.interaction-feed-item').first()
    await card.locator('.interaction-allow-btn').click()

    const banner = card.locator('.interaction-error-banner')
    await expect(banner).toHaveText('此事项的最新状态已重新载入，请重新确认后提交。')
    const bannerStyle = await banner.evaluate((el) => ({
      color: window.getComputedStyle(el).color,
      background: window.getComputedStyle(el).backgroundColor,
      border: window.getComputedStyle(el).borderTopColor,
    }))
    expect(bannerStyle.color).toBe(await tokenColor(page, '--danger'))
    expect(bannerStyle.background).toBe(await tokenColor(page, '--danger-soft'))
    expect(bannerStyle.border).toBe(await tokenColor(page, '--danger-border'))

    await expect(card.locator('.interaction-allow-btn')).toBeEnabled()
    await expect(card.locator('.interaction-deny-btn')).toBeEnabled()
  })
})

/**
 * 问卷卡基座只渲染卡片本身：屏蔽应用事件 WS，再按语言与状态打开固定问卷。
 */
async function openQuestionnaireHarness(
  page: Page,
  state: 'single' | 'multi' | 'brief' | 'answered',
  locale: 'zh-CN' | 'en-US' = 'zh-CN',
): Promise<void> {
  await page.routeWebSocket(/\/ws\/events$/, () => {})
  await page.goto(`${QUESTIONNAIRE_HARNESS_URL}?locale=${locale}&state=${state}`)
  await expect(page.locator('.interaction-questionnaire-card')).toBeVisible()
}

/**
 * 问卷标题行的几何：编号/图标、题干与题型标记的相对位置。
 *
 * 三处布局都靠这份测量判定：编号/图标贴首行、宽屏标记在首行右侧、窄屏题干占满整行。
 */
async function measureQuestionTitle(page: Page) {
  const title = page.locator('.interaction-question-title')
  await expect(title).toBeVisible()
  return title.evaluate((element) => {
    const box = (selector: string) => element.querySelector(selector)?.getBoundingClientRect() ?? null
    const text = box('.interaction-question-text')!
    const tag = box('.interaction-question-type-tag')
    const icon = box('.ask-user-q-icon')
    return {
      titleRight: element.getBoundingClientRect().right,
      textRight: text.right,
      textTop: text.top,
      textBottom: text.bottom,
      numberOffset: box('.interaction-question-idx')!.top - text.top,
      tagTop: tag ? tag.top : null,
      iconCenter: icon ? icon.top + icon.height / 2 - text.top : null,
      lineHeight: parseFloat(getComputedStyle(element).lineHeight),
    }
  })
}

type QuestionTitleMeasurement = Awaited<ReturnType<typeof measureQuestionTitle>>

/** 编号与题干首行对齐；已提交记录的图标同样贴首行居中。 */
function expectTitleMarkersOnFirstLine(layout: QuestionTitleMeasurement, view: string) {
  expect(Math.abs(layout.numberOffset)).toBeLessThan(1)
  if (view === 'submitted') {
    expect(layout.iconCenter).not.toBeNull()
    expect(Math.abs(layout.iconCenter! - layout.lineHeight / 2)).toBeLessThan(1)
  }
}

/** 宽屏既有视觉：题型标记在题干首行右侧，题干保持自然宽度。 */
function expectTypeTagOnFirstLine(layout: QuestionTitleMeasurement) {
  expect(layout.tagTop).not.toBeNull()
  const tagOffset = layout.tagTop! - layout.textTop
  expect(tagOffset).toBeGreaterThanOrEqual(-1)
  expect(tagOffset).toBeLessThan(layout.lineHeight / 2)
}

/**
 * 窄屏要求：题干用整行宽度，题型标记不再占一列，而是跟在题干最后一行之后。
 *
 * 旧布局把标记固定在同一行右侧，390 宽下题干被压成 200 多像素的窄栏。
 */
function expectQuestionTitleUsesFullRow(layout: QuestionTitleMeasurement) {
  expect(layout.textRight).toBeGreaterThanOrEqual(layout.titleRight - 2)
  expect(layout.tagTop).not.toBeNull()
  expect(layout.tagTop!).toBeGreaterThanOrEqual(layout.textBottom - 1)
}

test.describe('questionnaire option semantics and read-only answers', () => {
  test('single choice is one radiogroup with roving focus, arrows moving focus and space/enter selecting', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await openQuestionnaireHarness(page, 'single')

    // 题目文本就是单选组的可访问名称；每个选项以 radio + aria-checked 暴露状态。
    const group = page.getByRole('radiogroup', { name: '请选择本次发布窗口' })
    await expect(group).toBeVisible()
    const radios = group.getByRole('radio')
    await expect(radios).toHaveCount(3)
    await expect(radios.nth(0)).toHaveAttribute('aria-checked', 'false')
    // 组内只有单一 Tab 停靠点。
    expect(await radios.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('tabindex'))))
      .toEqual(['0', '-1', '-1'])
    // 单选指示器是圆形。
    await expect(radios.nth(0).locator('.interaction-option-indicator.is-radio'))
      .toHaveCSS('border-radius', '50%')

    await radios.nth(0).focus()
    await page.keyboard.press('ArrowDown')
    await expect(radios.nth(1)).toBeFocused()
    // 方向键只移动焦点，不代替回车/空格完成选择。
    await expect(radios.nth(1)).toHaveAttribute('aria-checked', 'false')

    await page.keyboard.press('Space')
    await expect(radios.nth(1)).toHaveAttribute('aria-checked', 'true')
    await expect(radios.nth(0)).toHaveAttribute('aria-checked', 'false')

    await page.keyboard.press('ArrowDown')
    await expect(radios.nth(2)).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(radios.nth(2)).toHaveAttribute('aria-checked', 'true')
    await expect(radios.nth(1)).toHaveAttribute('aria-checked', 'false')
    // 选中项沿用既有选中态视觉，与 aria-checked 同步。
    await expect(radios.nth(2)).toHaveClass(/is-selected/)
    await expect(radios.nth(2)).toHaveCSS('border-top-color', await tokenColor(page, '--accent'))
    await page.keyboard.press('Space')
    await expect(radios.nth(2)).toHaveAttribute('aria-checked', 'true')

    // 向下到末尾后环绕回第一项，焦点始终留在组内。
    await page.keyboard.press('ArrowDown')
    await expect(radios.nth(0)).toBeFocused()
    await expect(radios.nth(2)).toHaveAttribute('aria-checked', 'true')
  })

  test('multiple choice stays a checkbox group with square indicators and independent toggles', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await openQuestionnaireHarness(page, 'multi')

    await expect(page.getByRole('radiogroup')).toHaveCount(0)
    const group = page.getByRole('group', { name: '请选择本次发布需要归档到长期存储并同步给值班同学的产物' })
    await expect(group).toBeVisible()
    const boxes = group.getByRole('checkbox')
    await expect(boxes).toHaveCount(3)
    // 复选框各自可 Tab 进入，不共享 Tab 停靠点。
    expect(await boxes.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('tabindex'))))
      .toEqual([null, null, null])

    await boxes.nth(0).click()
    await boxes.nth(2).click()
    await expect(boxes.nth(0)).toHaveAttribute('aria-checked', 'true')
    await expect(boxes.nth(1)).toHaveAttribute('aria-checked', 'false')
    await expect(boxes.nth(2)).toHaveAttribute('aria-checked', 'true')
    // 多选指示器是方形，选中态显示勾。
    await expect(boxes.nth(0).locator('.interaction-option-indicator.is-checkbox'))
      .toHaveCSS('border-radius', '3px')
    await expect(boxes.nth(0).locator('.interaction-option-indicator svg')).toHaveCount(1)

    await boxes.nth(0).click()
    await expect(boxes.nth(0)).toHaveAttribute('aria-checked', 'false')
    await expect(boxes.nth(2)).toHaveAttribute('aria-checked', 'true')
  })

  // 窄屏长题目换行时，编号保持完整：不被挤成「1」「.」两行。
  for (const locale of ['zh-CN', 'en-US'] as const) {
    test(`a long question wraps while its number stays on one line at 390px (${locale})`, async ({ page }, info) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await openQuestionnaireHarness(page, 'multi', locale)

      const title = page.locator('.interaction-question-title')
      const number = title.locator('.interaction-question-idx')
      const lineHeight = await number.evaluate((element) => parseFloat(getComputedStyle(element).lineHeight))
      const numberBox = (await number.boundingBox())!
      const textBox = (await title.locator('.interaction-question-text').boundingBox())!
      expect(textBox.height).toBeGreaterThan(lineHeight * 1.5)
      expect(numberBox.height).toBeLessThanOrEqual(lineHeight + 1)
      await expect(number).toHaveText('1.')
      // 编号与题干首行对齐，而不是与整个题干居中。
      const layout = await measureQuestionTitle(page)
      expectTitleMarkersOnFirstLine(layout, 'pending')
      await title.screenshot({ path: info.outputPath(`question-pending-${locale}-390.png`) })
    })

    for (const width of [390, 1440]) {
      test(`question title keeps its markers on the first line (${locale}, ${width}px)`, async ({ page }, info) => {
        // 已提交记录的图标和已完成卡片的编号遵守与待答卡片相同的首行对齐规则。
        await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
        await page.routeWebSocket(/\/ws\/events$/, () => {})
        for (const view of width === 390 ? ['submitted', 'completed'] : ['pending', 'submitted', 'completed']) {
          await page.goto(`${QUESTIONNAIRE_HARNESS_URL}?locale=${locale}&state=multi&titleView=${view}`)
          const layout = await measureQuestionTitle(page)
          expectTitleMarkersOnFirstLine(layout, view)
          if (view !== 'completed') {
            // 1440 保持标记并在首行的既有视觉；390 改为题干占满整行、标记跟在题干之后。
            if (width === 1440) {
              expectTypeTagOnFirstLine(layout)
            } else {
              expectQuestionTitleUsesFullRow(layout)
            }
          }
          await page.locator('.interaction-question-title').screenshot({
            path: info.outputPath(`question-${view}-${locale}-${width}.png`),
          })
        }
      })
    }
  }

  // 审查 R2-3：390 宽下题干被右侧题型徽标挤成窄栏（en-US 还出现孤词「release?」）。
  for (const locale of ['zh-CN', 'en-US'] as const) {
    test(`the question takes the whole title row and the type tag follows it at 390px (${locale})`, async ({
      page,
    }, info) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await openQuestionnaireHarness(page, 'multi', locale)

      const layout = await measureQuestionTitle(page)
      expectQuestionTitleUsesFullRow(layout)
      // 题干仍真实换行：证明占满整行靠的是布局而不是把文字缩短。
      expect(layout.textBottom - layout.textTop).toBeGreaterThan(layout.lineHeight * 1.5)
      await expectNoHorizontalOverflow(page)
      await page.locator('.interaction-questionnaire-card').screenshot({
        path: info.outputPath(`questionnaire-title-full-width-${locale}-390.png`),
      })
    })
  }

  test('a short question fits one line at 390px instead of leaving an orphan word (en-US)', async ({
    page,
  }, info) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openQuestionnaireHarness(page, 'brief', 'en-US')

    const layout = await measureQuestionTitle(page)
    expectQuestionTitleUsesFullRow(layout)
    expect(layout.textBottom - layout.textTop).toBeLessThanOrEqual(layout.lineHeight + 1)
    await expectNoHorizontalOverflow(page)
    await page.locator('.interaction-questionnaire-card').screenshot({
      path: info.outputPath('questionnaire-title-brief-en-US-390.png'),
    })
  })

  test('wide layout keeps the type tag beside the first line (zh-CN and en-US at 1440px)', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    for (const locale of ['zh-CN', 'en-US'] as const) {
      await openQuestionnaireHarness(page, 'multi', locale)
      const layout = await measureQuestionTitle(page)
      expectTypeTagOnFirstLine(layout)
      // 窄屏修复不得带回宽屏：题干在 1440 仍是单行自然宽度。
      expect(layout.textBottom - layout.textTop).toBeLessThanOrEqual(layout.lineHeight + 1)
    }
  })

  test('answered questionnaire is read-only and shows the chosen answers', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openQuestionnaireHarness(page, 'answered')

    const card = page.locator('.interaction-questionnaire-card')
    await expect(card.getByText('已完成')).toBeVisible()
    await expect(card.getByText('本周日 01:00')).toBeVisible()
    // 已回答只展示物化结果：没有选择控件、输入框或提交动作。
    await expect(page.getByRole('radio')).toHaveCount(0)
    await expect(page.getByRole('checkbox')).toHaveCount(0)
    await expect(page.getByRole('textbox')).toHaveCount(0)
    await expect(page.getByRole('button')).toHaveCount(0)
  })
})

test.describe('questionnaire self-check screenshots', () => {
  test.skip(
    QUESTIONNAIRE_SHOTS_DIR == null,
    '设置 KK_QUESTIONNAIRE_SHOTS_DIR 才输出问卷卡自查截图',
  )

  test('captures single, multiple and answered states at 1440x900 and 390x844 in both locales', async ({
    page,
  }) => {
    const shotsDir = QUESTIONNAIRE_SHOTS_DIR!
    fs.mkdirSync(shotsDir, { recursive: true })
    await page.routeWebSocket(/\/ws\/events$/, () => {})

    for (const locale of ['zh-CN', 'en-US'] as const) {
      for (const viewport of [
        { width: 1440, height: 900, name: '1440x900' },
        { width: 390, height: 844, name: '390x844' },
      ]) {
        for (const state of ['single', 'multi', 'answered'] as const) {
          await page.setViewportSize({ width: viewport.width, height: viewport.height })
          await page.goto(`${QUESTIONNAIRE_HARNESS_URL}?locale=${locale}&state=${state}`)
          await expect(page.locator('.interaction-questionnaire-card')).toBeVisible()
          if (state !== 'answered') {
            // 先选中一项，让截图同时呈现选中与未选中态。
            await page.locator('.interaction-option-btn').nth(0).click()
          }
          if (state === 'multi') {
            await page.locator('.interaction-option-btn').nth(2).click()
          }
          const metrics = await page.evaluate(() => ({
            scrollWidth: document.documentElement.scrollWidth,
            clientWidth: document.documentElement.clientWidth,
          }))
          expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
          await page.screenshot({
            path: path.join(shotsDir, `questionnaire-${locale}-${viewport.name}-${state}.png`),
            fullPage: true,
          })
        }
      }
    }
  })
})

test.describe('questionnaire title row screenshots', () => {
  test.skip(
    QUESTIONNAIRE_TITLE_SHOTS_DIR == null,
    '设置 KK_QUESTIONNAIRE_TITLE_SHOTS_DIR 才输出题干/徽标行对照图',
  )

  // 审查 R2-3 的四张对照图：题干/题型徽标行，中英 × 1440/390，看同一行怎么排。
  test('captures the pending question title row at 1440x900 and 390x844 in both locales', async ({
    page,
  }) => {
    const shotsDir = QUESTIONNAIRE_TITLE_SHOTS_DIR!
    fs.mkdirSync(shotsDir, { recursive: true })
    await page.routeWebSocket(/\/ws\/events$/, () => {})

    for (const locale of ['zh-CN', 'en-US'] as const) {
      for (const viewport of [
        { width: 1440, height: 900, name: '1440x900' },
        { width: 390, height: 844, name: '390x844' },
      ]) {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.goto(`${QUESTIONNAIRE_HARNESS_URL}?locale=${locale}&state=multi`)
        await expect(page.locator('.interaction-questionnaire-card')).toBeVisible()
        await page.screenshot({
          path: path.join(shotsDir, `questionnaire-title-${locale}-${viewport.name}.png`),
        })
      }
    }
  })
})
