import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type Page } from './fixture'
import type { IssueDetailDTO, ProjectSnapshotDTO } from '@/shared/api/generated/contracts'

const PROJECT = 'a0000000-0000-0000-0000-000000000001'
const ISSUE_INIT = 'b0000000-0000-0000-0000-000000000001'
const ISSUE_WORK = 'b0000000-0000-0000-0000-000000000002'
const ISSUE_BLOCKED = 'b0000000-0000-0000-0000-000000000003'
const ISSUE_DETAIL = 'a0000000-0000-0000-0000-000000000002'
const STAMP = Date.UTC(2026, 9, 1)

/**
 * 切片自查截图目录：只有显式设置 KK_SLICE_SHOTS 时才写出图片。
 * 断言始终执行，截图只是可选产物。
 */
const sliceShotsDir = process.env.KK_SLICE_SHOTS?.trim()

test.beforeAll(() => {
  if (sliceShotsDir) {
    mkdirSync(sliceShotsDir, { recursive: true })
  }
})

async function sliceShot(page: Page, name: string): Promise<void> {
  if (!sliceShotsDir) {
    return
  }
  await page.screenshot({ path: join(sliceShotsDir, `${name}.png`) })
}

/** 看板与证据的双语文案：一律取产品文案，用例里不拼英文。 */
const copy = {
  'zh-CN': {
    project: 'Refund Gateway',
    columns: { INIT: '待开始', WORK: '处理中', BLOCKED: '业务阻塞', DONE: '完成' },
    transitionToWork: '流转至 处理中',
    createIssue: '新建 Issue',
    block: '阻塞',
    evidenceTab: '公开证据',
    evidenceTabEmpty: '公开证据 (0)',
    evidenceFailed: '公开证据读取失败',
    evidenceEmpty: '暂无公开证据',
    retry: '重试',
  },
  'en-US': {
    project: 'Refund Gateway',
    columns: { INIT: 'Not started', WORK: 'In progress', BLOCKED: 'Blocked', DONE: 'Done' },
    transitionToWork: 'Move to In progress',
    createIssue: 'New Issue',
    block: 'Block',
    evidenceTab: 'Public evidence',
    evidenceTabEmpty: 'Public evidence (0)',
    evidenceFailed: 'Failed to load public evidence',
    evidenceEmpty: 'No public evidence yet',
    retry: 'Retry',
  },
} as const

type Locale = keyof typeof copy

function snapshotFor(locale: Locale): ProjectSnapshotDTO {
  const columns = copy[locale].columns
  return {
    project: {
      id: PROJECT,
      title: copy[locale].project,
      description: 'Board and evidence vertical',
      workflow: {
        states: [
          { state: 'INIT', name: columns.INIT, next: ['WORK'], agent: null, environment: null, instructions: null, maxRuns: null },
          { state: 'WORK', name: columns.WORK, next: ['DONE'], agent: null, environment: null, instructions: null, maxRuns: null },
          { state: 'BLOCKED', name: columns.BLOCKED, agent: null, environment: null, instructions: null, maxRuns: null },
          { state: 'DONE', name: columns.DONE, agent: null, environment: null, instructions: null, maxRuns: null },
        ],
      },
      yoloEnabled: false,
      nextIssueNumber: '4',
      version: '1',
      archivedAt: null,
      createdAt: STAMP,
      updatedAt: STAMP,
    },
    issues: [
      {
        issue: {
          id: ISSUE_INIT, projectId: PROJECT, number: '1', title: 'Split refund callbacks',
          description: '', state: 'INIT', blockedFromState: null, blockReason: null,
          pauseReason: null, pauseDetail: null,
          version: '1', createdAt: STAMP, updatedAt: STAMP,
        },
        currentOrLatestRun: null,
      },
      {
        issue: {
          id: ISSUE_WORK, projectId: PROJECT, number: '2', title: 'Verify settlement ledger',
          description: '', state: 'WORK', blockedFromState: null, blockReason: null,
          pauseReason: null, pauseDetail: null,
          version: '1', createdAt: STAMP, updatedAt: STAMP,
        },
        // 当前阶段的 Run：卡片不应再重复一次当前阶段。
        currentOrLatestRun: {
          id: 'c0000000-0000-0000-0000-000000000001', issueId: ISSUE_WORK, ordinal: '1',
          state: 'WORK', agentName: 'backend-dev', status: 'RUNNING',
          startedAt: STAMP, endedAt: null,
        },
      },
      {
        issue: {
          id: ISSUE_BLOCKED, projectId: PROJECT, number: '3', title: 'Replay gateway timeouts',
          description: '', state: 'BLOCKED', blockedFromState: 'WORK',
          blockReason: 'Waiting for the settlement decision', pauseReason: null, pauseDetail: null,
          version: '1', createdAt: STAMP, updatedAt: STAMP,
        },
        currentOrLatestRun: null,
      },
    ],
    referencedStateCodes: ['INIT', 'WORK', 'BLOCKED'],
  }
}

function issueDetail(): IssueDetailDTO {
  return {
    issue: {
      id: ISSUE_DETAIL, projectId: PROJECT, number: '12', title: 'Refund callback drift',
      description: 'Evidence vertical', state: 'WORK', blockedFromState: null,
      blockReason: null, pauseReason: null, pauseDetail: null,
      version: '1', createdAt: STAMP, updatedAt: STAMP,
    },
    activities: [], nextActivityCursor: null, agentThreads: [], runs: [], stageBudgets: [],
  }
}

interface TransportState {
  failEvidence: boolean
  evidenceReads: number
}

async function transport(page: Page, locale: Locale): Promise<TransportState> {
  const state: TransportState = { failEvidence: false, evidenceReads: 0 }
  await page.routeWebSocket('**/ws/events', (socket) => {
    let connected = false
    socket.onMessage((raw) => {
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({
          type: 'connected',
          eventNodeId: 'd0000000-0000-4000-8000-000000000001',
          connectionId: 'd0000000-0000-4000-8000-000000000002',
          // connected 帧必须携带服务端 heartbeat 间隔，否则客户端判定畸形且永不确认订阅。
          heartbeatIntervalMillis: '20000',
        }))
      }
      const message = JSON.parse(String(raw))
      if (message.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: message.resource, cursor: '0' }))
      }
    })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/project/projects/${PROJECT}/snapshot`) {
      await route.fulfill({ json: snapshotFor(locale) })
      return
    }
    if (path === `/api/project/issues/${ISSUE_DETAIL}/evidence`) {
      state.evidenceReads++
      if (state.failEvidence) {
        await route.fulfill({ status: 500, contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 500, code: 'internal', title: 'Evidence read failed' } })
        return
      }
      await route.fulfill({ json: [] })
      return
    }
    if (path === `/api/project/issues/${ISSUE_DETAIL}`) {
      await route.fulfill({ json: issueDetail() })
      return
    }
    throw new Error(`Unexpected harness request: ${path}`)
  })
  return state
}

for (const locale of Object.keys(copy) as Locale[]) {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`board keeps stage names primary and one primary action in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?locale=${locale}`)

      await expect(page.getByRole('heading', { name: copy[locale].project, exact: true })).toBeVisible()

      // 列头只写阶段名称与计数，不再并排内部状态码胶囊。
      const columns = page.locator('.project-board-column')
      await expect(columns).toHaveCount(4)
      for (const [code, name] of Object.entries(copy[locale].columns)) {
        const column = page.locator(`.project-board-column[data-state="${code}"]`)
        await expect(column.locator('.column-header')).toHaveText(
          new RegExp(`^${name}\\s*\\d+$`),
        )
        await expect(column.locator('.column-header .ui-badge')).toHaveCount(0)
      }
      for (const code of Object.keys(copy[locale].columns)) {
        await expect(page.getByText(code, { exact: true })).toHaveCount(0)
      }

      // 卡片所属列已表达当前阶段：卡头只留编号，进度动作用目标阶段名称且是次级按钮。
      const initCard = page.getByTestId(`issue-card-${ISSUE_INIT}`)
      await expect(initCard.locator('.issue-number')).toHaveText('#1')
      await expect(initCard.locator('.ui-badge')).toHaveCount(0)
      const transition = initCard.getByRole('button', { name: copy[locale].transitionToWork })
      await expect(transition).toHaveClass(/ghost-btn/)
      await expect(transition).not.toHaveClass(/btn-primary/)
      await expect(initCard.getByRole('button', { name: copy[locale].block })).toHaveClass(/ghost-btn/)

      // 当前阶段的 Run 不重复阶段名，执行状态用可读文字。
      const workCard = page.getByTestId(`issue-card-${ISSUE_WORK}`)
      await expect(workCard.locator('.issue-card-meta')).toContainText('#1')
      await expect(workCard.locator('.issue-card-meta')).not.toContainText(copy[locale].columns.WORK)
      await expect(workCard.getByText('WORK', { exact: true })).toHaveCount(0)

      // 页头「新建」是看板区域唯一主动作，卡片区没有主按钮。
      await expect(page.locator('header.project-detail-header .btn-primary')).toHaveCount(1)
      await expect(page.getByRole('button', { name: copy[locale].createIssue })).toHaveClass(/btn-primary/)
      await expect(page.locator('.project-board-section .btn-primary')).toHaveCount(0)

      if (viewport.width < 900) {
        // 窄屏：列横向滚动，卡片操作仍完整可见、不被挤出卡片。
        const scroller = page.locator('.project-board-columns')
        await expect(page.locator('.issue-card')).toHaveCount(3)
        const scroll = await scroller.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }))
        expect(scroll.scroll).toBeGreaterThan(scroll.client)
        for (const card of await page.locator('.issue-card').all()) {
          const box = await card.boundingBox()
          for (const action of await card.getByRole('button').all()) {
            const actionBox = await action.boundingBox()
            await expect(action).toBeVisible()
            expect(actionBox!.x).toBeGreaterThanOrEqual(box!.x - 1)
            expect(actionBox!.x + actionBox!.width).toBeLessThanOrEqual(box!.x + box!.width + 1)
          }
        }
        await scroller.evaluate((el) => el.scrollTo({ left: el.scrollWidth }))
        await expect(page.getByTestId(`issue-card-${ISSUE_BLOCKED}`)).toBeInViewport()
        await scroller.evaluate((el) => el.scrollTo({ left: 0 }))
        await expect(transition).toBeInViewport()
      }

      await sliceShot(page, `project-board-${locale}-${viewport.width}`)
    })

    test(`issue evidence separates failure, retry and real empty in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      const state = await transport(page, locale)
      state.failEvidence = true
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()

      // 读取失败：标签不写数字，正文给失败与重试，不冒充空数据。
      const failedTab = dialog.getByRole('tab', { name: copy[locale].evidenceTab, exact: true })
      await expect(failedTab).toBeVisible()
      await failedTab.click()
      await expect(dialog.getByText(copy[locale].evidenceFailed)).toBeVisible()
      await expect(dialog.getByRole('button', { name: copy[locale].retry })).toBeEnabled()
      await expect(dialog.getByText(copy[locale].evidenceEmpty)).toHaveCount(0)
      await expect(failedTab).not.toContainText('(')
      await sliceShot(page, `project-evidence-failed-${locale}-${viewport.width}`)

      // 重试成功且真的为空：同一处换成权威空态，计数才变成 0。
      const failedReads = state.evidenceReads
      state.failEvidence = false
      await dialog.getByRole('button', { name: copy[locale].retry }).click()
      await expect(dialog.getByText(copy[locale].evidenceEmpty)).toBeVisible()
      await expect(dialog.getByText(copy[locale].evidenceFailed)).toHaveCount(0)
      const emptyTab = dialog.getByRole('tab', { name: copy[locale].evidenceTabEmpty, exact: true })
      await expect(emptyTab).toBeVisible()
      await emptyTab.scrollIntoViewIfNeeded()
      expect(state.evidenceReads).toBeGreaterThan(failedReads)
      await sliceShot(page, `project-evidence-empty-${locale}-${viewport.width}`)
    })
  }
}
