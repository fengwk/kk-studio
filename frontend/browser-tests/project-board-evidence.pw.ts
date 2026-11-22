import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type Locator, type Page } from './fixture'
import type {
  IssueDTO, IssueDetailDTO, IssueRunSummaryDTO, PauseReason, ProjectSnapshotDTO,
} from '@/shared/api/generated/contracts'

const PROJECT = 'a0000000-0000-0000-0000-000000000001'
const ISSUE_INIT = 'b0000000-0000-0000-0000-000000000001'
const ISSUE_WORK = 'b0000000-0000-0000-0000-000000000002'
const ISSUE_BLOCKED = 'b0000000-0000-0000-0000-000000000003'
const ISSUE_PAUSED = 'b0000000-0000-0000-0000-000000000004'
const ISSUE_ERROR = 'b0000000-0000-0000-0000-000000000005'
const ISSUE_WAITING = 'b0000000-0000-0000-0000-000000000006'
const ISSUE_FAILED = 'b0000000-0000-0000-0000-000000000007'
const ISSUE_DONE = 'b0000000-0000-0000-0000-000000000008'
const ISSUE_DETAIL = 'a0000000-0000-0000-0000-000000000002'
const STAMP = Date.UTC(2026, 9, 1)
const BLOCK_REASON = 'Waiting for the settlement decision'
/** 详情夹具的标题足够长：窄屏下标题必须换行，而不是把编辑动作挤出标题行。 */
const DETAIL_TITLE = 'Refund callback drift across settlement ledger and payout batches'
// 终态 Run 的耗时必须只由起止时戳得出；活动 Run 的剩余预算只来自服务端余额。
const ELAPSED_MS = 3_723_000
const REMAINING_MS = '5400000'
const RUN_TERMINAL = 'e2000000-0000-4000-8000-000000000001'
const RUN_ACTIVE = 'e2000000-0000-4000-8000-000000000002'
const ENTRY_START_TERMINAL = 'e1000000-0000-4000-8000-000000000001'
const ENTRY_END_TERMINAL = 'e1000000-0000-4000-8000-000000000002'
const ENTRY_START_ACTIVE = 'e1000000-0000-4000-8000-000000000003'
/** 活动时间线的类型顺序：夹具按此顺序给出，界面按同一顺序渲染。 */
const ACTIVITY_CODES = [
  'RUN', 'STATE_CHANGE', 'SPEC_CHANGE', 'CONTROL', 'INSTRUCTION', 'COMMENT',
] as const

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
    recover: '恢复',
    // 徽标按事实命名：徽标已经表达等待/失败时，同一张卡的 Run 摘要不再重复。
    badges: {
      paused: '已暂停',
      pausedOnError: '出错已暂停',
      waiting: '等待中',
      failed: '已失败',
    },
    badgeTitles: {
      paused: '已人工暂停',
      pausedOnError: '发生错误已暂停',
      waiting: '等待处理',
      failed: '执行失败',
    },
    running: '执行中',
    reason: '原因: Waiting for the settlement decision',
    boardSearchPlaceholder: '搜索 Issue 标题或编号',
    listTitle: '项目管理',
    listSearchPlaceholder: '搜索项目名称或描述',
    createHintStage: '创建后将自动进入「待开始」阶段。',
    specTab: '需求事实',
    activitiesTab: '活动时间线',
    runTab: 'Run 报告',
    editSpec: '编辑需求',
    expectedVersionLabel: '期望版本号',
    runElapsed: '耗时 1 小时 2 分钟',
    runRemaining: '剩余执行预算 1 小时 30 分钟',
    elapsedWord: '耗时',
    remainingWord: '剩余执行预算',
    activityKinds: ['运行', '阶段变更', '需求变更', '控制', '指令', '评论'],
    recoverTitle: '恢复至 处理中',
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
    recover: 'Recover',
    badges: {
      paused: 'Paused',
      pausedOnError: 'Paused on error',
      waiting: 'Waiting',
      failed: 'Failed',
    },
    badgeTitles: {
      paused: 'Paused manually',
      pausedOnError: 'Paused after an error',
      waiting: 'Waiting to be processed',
      failed: 'Execution failed',
    },
    running: 'Running',
    reason: `Reason: ${BLOCK_REASON}`,
    boardSearchPlaceholder: 'Search issue title or number',
    listTitle: 'Projects',
    listSearchPlaceholder: 'Search name or description',
    createHintStage: 'The issue starts in the "Not started" stage after creation.',
    specTab: 'Requirements',
    activitiesTab: 'Activity timeline',
    runTab: 'Run reports',
    editSpec: 'Edit requirements',
    expectedVersionLabel: 'Expected version',
    runElapsed: 'Elapsed 1h 2m',
    runRemaining: 'Remaining execution budget 1h 30m',
    elapsedWord: 'Elapsed',
    remainingWord: 'Remaining execution budget',
    activityKinds: ['Run', 'Stage change', 'Requirement change', 'Control', 'Instruction', 'Comment'],
    recoverTitle: 'Recover to In progress',
    evidenceTab: 'Public evidence',
    evidenceTabEmpty: 'Public evidence (0)',
    evidenceFailed: 'Failed to load public evidence',
    evidenceEmpty: 'No public evidence yet',
    retry: 'Retry',
  },
} as const

/** 卡片与详情都不能把内部状态码当正文；列头/徽标只保留可读名称。 */
const RAW_STATE_CODES = ['INIT', 'WORK', 'BLOCKED', 'DONE', 'PAUSED', 'ERROR', 'WAITING', 'FAILED']

type Locale = keyof typeof copy

function issue(
  id: string,
  number: string,
  title: string,
  state: string,
  extra: { blockedFromState?: string | null; blockReason?: string | null
    pauseReason?: PauseReason | null; pauseDetail?: string | null } = {},
): IssueDTO {
  return {
    id, projectId: PROJECT, number, title, description: '', state,
    blockedFromState: extra.blockedFromState ?? null,
    blockReason: extra.blockReason ?? null,
    pauseReason: extra.pauseReason ?? null,
    pauseDetail: extra.pauseDetail ?? null,
    version: '1', createdAt: STAMP, updatedAt: STAMP,
  }
}

function run(
  ordinal: string,
  state: string,
  status: 'RUNNING' | 'WAITING' | 'FAILED',
  agentName: string | null,
): IssueRunSummaryDTO {
  return {
    id: `c0000000-0000-0000-0000-00000000000${ordinal}`, issueId: ISSUE_WORK, ordinal, state,
    agentName, status, startedAt: STAMP, endedAt: status === 'RUNNING' ? null : STAMP,
  }
}

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
      nextIssueNumber: '9',
      version: '1',
      archivedAt: null,
      createdAt: STAMP,
      updatedAt: STAMP,
    },
    // 看板一次覆盖全部可见事实：无标记、执行中、人工暂停、错误暂停、等待、失败、阻塞与完成。
    issues: [
      { issue: issue(ISSUE_INIT, '1', 'Split refund callbacks', 'INIT'), currentOrLatestRun: null },
      {
        issue: issue(ISSUE_WORK, '2', 'Verify settlement ledger', 'WORK'),
        // 当前阶段的 Run：卡片不应再重复一次当前阶段。
        currentOrLatestRun: run('1', 'WORK', 'RUNNING', 'backend-dev'),
      },
      {
        issue: issue(ISSUE_PAUSED, '3', 'Reconcile payout batches', 'WORK', { pauseReason: 'USER' }),
        currentOrLatestRun: null,
      },
      {
        issue: issue(ISSUE_ERROR, '4', 'Retry ledger snapshots', 'WORK', {
          pauseReason: 'ERROR', pauseDetail: 'Ledger snapshot request timed out',
        }),
        currentOrLatestRun: null,
      },
      {
        issue: issue(ISSUE_WAITING, '5', 'Await settlement approval', 'WORK'),
        currentOrLatestRun: run('2', 'WORK', 'WAITING', 'reviewer'),
      },
      {
        issue: issue(ISSUE_FAILED, '6', 'Replay gateway timeouts', 'WORK'),
        currentOrLatestRun: run('3', 'WORK', 'FAILED', 'reviewer'),
      },
      {
        issue: issue(ISSUE_BLOCKED, '7', 'Escalate settlement decision', 'BLOCKED', {
          blockedFromState: 'WORK', blockReason: BLOCK_REASON,
        }),
        currentOrLatestRun: null,
      },
      { issue: issue(ISSUE_DONE, '8', 'Ship refund gateway', 'DONE'), currentOrLatestRun: null },
    ],
    referencedStateCodes: ['INIT', 'WORK', 'BLOCKED', 'DONE'],
  }
}

function issueDetail(): IssueDetailDTO {
  return {
    issue: {
      id: ISSUE_DETAIL, projectId: PROJECT, number: '12', title: DETAIL_TITLE,
      description: 'Evidence vertical', state: 'WORK', blockedFromState: null,
      blockReason: null, pauseReason: null, pauseDetail: null,
      version: '1', createdAt: STAMP, updatedAt: STAMP,
    },
    // 终态 Run 与活动 Run 并排：前者已冻结结束时戳，后者只持有服务端剩余预算。
    runs: [
      {
        id: RUN_TERMINAL, issueId: ISSUE_DETAIL, ordinal: '1', state: 'WORK',
        agentName: 'backend-dev', sessionId: RUN_TERMINAL, threadId: RUN_TERMINAL,
        status: 'COMPLETED', startEntryId: ENTRY_START_TERMINAL, endEntryId: ENTRY_END_TERMINAL,
        finalAnswerEntryId: null, nextState: 'DONE', observedActivitySequence: '2',
        remainingExecutionMs: '0', error: null, version: '1',
        startedAt: STAMP, endedAt: STAMP + ELAPSED_MS,
      },
      {
        id: RUN_ACTIVE, issueId: ISSUE_DETAIL, ordinal: '2', state: 'WORK',
        agentName: null, sessionId: RUN_ACTIVE, threadId: RUN_ACTIVE,
        status: 'RUNNING', startEntryId: ENTRY_START_ACTIVE, endEntryId: null,
        finalAnswerEntryId: null, nextState: null, observedActivitySequence: '6',
        remainingExecutionMs: REMAINING_MS, error: null, version: '1',
        startedAt: STAMP, endedAt: null,
      },
    ],
    // 时间线一次覆盖六种活动类型与三种行为者，供本地化文案断言。
    activities: [
      {
        issueId: ISSUE_DETAIL, sequence: '1', kind: 'RUN', actorType: 'AGENT',
        actorAgentName: 'backend-dev', runId: RUN_TERMINAL, body: 'Run started', createdAt: STAMP,
      },
      {
        issueId: ISSUE_DETAIL, sequence: '2', kind: 'STATE_CHANGE', actorType: 'SYSTEM',
        actorAgentName: null, runId: null, body: null, createdAt: STAMP + 1000,
      },
      {
        issueId: ISSUE_DETAIL, sequence: '3', kind: 'SPEC_CHANGE', actorType: 'HUMAN',
        actorAgentName: null, runId: null, body: 'Narrowed the acceptance criteria', createdAt: STAMP + 2000,
      },
      {
        issueId: ISSUE_DETAIL, sequence: '4', kind: 'CONTROL', actorType: 'SYSTEM',
        actorAgentName: null, runId: RUN_ACTIVE, body: null, createdAt: STAMP + 3000,
      },
      {
        issueId: ISSUE_DETAIL, sequence: '5', kind: 'INSTRUCTION', actorType: 'HUMAN',
        actorAgentName: null, runId: RUN_ACTIVE, body: 'Keep the ledger snapshots', createdAt: STAMP + 4000,
      },
      {
        issueId: ISSUE_DETAIL, sequence: '6', kind: 'COMMENT', actorType: 'AGENT',
        actorAgentName: 'reviewer', runId: null, body: 'Payout batches look consistent', createdAt: STAMP + 5000,
      },
    ],
    nextActivityCursor: null,
    stageBudgets: [
      { state: 'WORK', maxRuns: 5, budgetAfterOrdinal: '0', usedRuns: '2', remainingRuns: '3' },
    ],
    agentThreads: [],
  }
}

/**
 * 占位符是搜索入口里唯一的可见提示：按输入框自身的字体量出文本宽度，确认文案没被末尾裁掉。
 */
async function placeholderFit(input: Locator): Promise<{ placeholder: string
  textWidth: number; boxWidth: number }> {
  return input.evaluate((element) => {
    const style = getComputedStyle(element)
    const context = document.createElement('canvas').getContext('2d')!
    context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    const placeholder = (element as HTMLInputElement).placeholder
    return {
      placeholder,
      textWidth: context.measureText(placeholder).width,
      boxWidth: element.clientWidth,
    }
  })
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
    if (path === '/api/project/projects') {
      // 项目列表：页头搜索占位符是列表里唯一的可见提示文案。
      await route.fulfill({ json: [snapshotFor(locale).project] })
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
    test(`board keeps stage names primary and names every issue fact in ${locale} at ${viewport.width}px`, async ({
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
      // 状态码既不进正文，也不进徽标：它们只作为列头与卡片的 title 提示保留。
      for (const code of RAW_STATE_CODES) {
        await expect(page.getByText(code, { exact: true })).toHaveCount(0)
      }

      const card = (id: string) => page.getByTestId(`issue-card-${id}`)
      const cardBadges = (id: string) => card(id).locator('.issue-badges-row .ui-badge')

      // 卡片所属列已表达当前阶段：卡头只留编号，进度动作用目标阶段名称且是次级按钮。
      const initCard = card(ISSUE_INIT)
      await expect(initCard.locator('.issue-number')).toHaveText('#1')
      await expect(initCard.locator('.ui-badge')).toHaveCount(0)
      const transition = initCard.getByRole('button', { name: copy[locale].transitionToWork })
      await expect(transition).toHaveClass(/ghost-btn/)
      await expect(transition).not.toHaveClass(/btn-primary/)
      await expect(initCard.getByRole('button', { name: copy[locale].block })).toHaveClass(/ghost-btn/)

      // 当前阶段的 Run 不重复阶段名；没有徽标时执行状态仍用可读文字。
      const workCard = card(ISSUE_WORK)
      await expect(workCard.locator('.issue-card-meta')).toContainText('#1')
      await expect(workCard.locator('.issue-card-meta')).not.toContainText(copy[locale].columns.WORK)
      await expect(workCard.locator('.issue-card-meta')).toContainText(copy[locale].running)
      await expect(workCard.locator('.ui-badge')).toHaveCount(0)

      // 暂停：人工与错误是两种事实，徽标文案与提示都本地化。
      await expect(cardBadges(ISSUE_PAUSED)).toHaveCount(1)
      await expect(cardBadges(ISSUE_PAUSED)).toHaveText(copy[locale].badges.paused)
      await expect(cardBadges(ISSUE_PAUSED)).toHaveAttribute('title', copy[locale].badgeTitles.paused)
      await expect(cardBadges(ISSUE_ERROR)).toHaveCount(1)
      await expect(cardBadges(ISSUE_ERROR)).toHaveText(copy[locale].badges.pausedOnError)
      await expect(cardBadges(ISSUE_ERROR)).toHaveAttribute('title', copy[locale].badgeTitles.pausedOnError)

      // 等待与失败：徽标表达该事实后，Run 摘要只留序号与执行者。
      for (const [id, badge] of [
        [ISSUE_WAITING, 'waiting'],
        [ISSUE_FAILED, 'failed'],
      ] as const) {
        await expect(cardBadges(id)).toHaveCount(1)
        await expect(cardBadges(id)).toHaveText(copy[locale].badges[badge])
        await expect(cardBadges(id)).toHaveAttribute('title', copy[locale].badgeTitles[badge])
        const meta = card(id).locator('.issue-card-meta')
        await expect(meta).toContainText('#')
        await expect(meta).not.toContainText(copy[locale].badges[badge])
      }

      // 阻塞：所在列已表达业务阻塞，卡片不重复阶段徽标；原因与恢复目标在正文/提示里说明。
      const blockedCard = card(ISSUE_BLOCKED)
      await expect(page.locator('.project-board-column[data-state="BLOCKED"]')).toContainText(copy[locale].columns.BLOCKED)
      await expect(blockedCard.locator('.ui-badge')).toHaveCount(0)
      await expect(blockedCard.locator('.issue-block-reason')).toHaveText(copy[locale].reason)
      await expect(blockedCard.getByRole('button', { name: copy[locale].recover }))
        .toHaveAttribute('title', copy[locale].recoverTitle)

      // 页头「新建」是看板区域唯一主动作，卡片区没有主按钮。
      await expect(page.locator('header.project-detail-header .btn-primary')).toHaveCount(1)
      await expect(page.getByRole('button', { name: copy[locale].createIssue })).toHaveClass(/btn-primary/)
      await expect(page.locator('.project-board-section .btn-primary')).toHaveCount(0)

      // 看板自己的搜索占位符同样整段可读：英文文案不被输入框末尾裁字。
      const boardSearchInput = page.locator('.project-detail-toolbar .searchbox input')
      await expect(boardSearchInput).toBeVisible()
      const boardSearch = await placeholderFit(boardSearchInput)
      expect(boardSearch.placeholder).toBe(copy[locale].boardSearchPlaceholder)
      expect(boardSearch.textWidth).toBeLessThanOrEqual(boardSearch.boxWidth)

      if (viewport.width < 900) {
        // 窄屏：列横向滚动，卡片操作仍完整可见、不被挤出卡片。
        const scroller = page.locator('.project-board-columns')
        await expect(page.locator('.issue-card')).toHaveCount(8)
        const scroll = await scroller.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }))
        expect(scroll.scroll).toBeGreaterThan(scroll.client)
        for (const visible of await page.locator('.issue-card').all()) {
          const box = await visible.boundingBox()
          for (const action of await visible.getByRole('button').all()) {
            const actionBox = await action.boundingBox()
            await expect(action).toBeVisible()
            expect(actionBox!.x).toBeGreaterThanOrEqual(box!.x - 1)
            expect(actionBox!.x + actionBox!.width).toBeLessThanOrEqual(box!.x + box!.width + 1)
          }
        }
        await scroller.evaluate((el) => el.scrollTo({ left: el.scrollWidth }))
        await expect(card(ISSUE_DONE)).toBeInViewport()
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
      // 失败说明与重试同处一块（与环境诊断一致），重试就在失败块里。
      const failure = dialog.locator('.evidence-feedback')
      await expect(failure.getByText(copy[locale].evidenceFailed)).toBeVisible()
      await expect(failure.getByRole('button', { name: copy[locale].retry })).toBeEnabled()
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

    test(`issue detail wraps the spec heading and localizes activity kinds in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const dialog = page.getByRole('dialog')
      // 需求事实是默认分区：打开详情先看到标题、描述与阶段元信息。
      await expect(dialog.getByRole('tab', { name: copy[locale].specTab })).toHaveAttribute('aria-selected', 'true')
      const specHead = dialog.locator('.spec-head')
      await expect(specHead.locator('.spec-title')).toHaveText(DETAIL_TITLE)
      // 标题让出宽度换行，编辑动作整词单行并留在标题行内。
      const editSpec = specHead.getByRole('button', { name: copy[locale].editSpec })
      await expect(editSpec).toHaveCSS('white-space', 'nowrap')
      const headBox = (await specHead.boundingBox())!
      const editBox = (await editSpec.boundingBox())!
      expect(editBox.x + editBox.width).toBeLessThanOrEqual(headBox.x + headBox.width + 1)
      // 阶段按工作流名称展示；并发控制用的版本号不是可见字段。
      const specMeta = dialog.locator('.spec-meta-grid')
      await expect(specMeta).toContainText(copy[locale].columns.WORK)
      await expect(specMeta).not.toContainText(copy[locale].expectedVersionLabel)
      await sliceShot(page, `project-issue-spec-${locale}-${viewport.width}`)

      // 活动时间线：类型用本地化名称承载，原始枚举只留在提示里且整词不折。
      await dialog.getByRole('tab', { name: new RegExp(copy[locale].activitiesTab) }).click()
      const kinds = dialog.locator('.activity-badge-kind')
      await expect(kinds).toHaveText([...copy[locale].activityKinds])
      expect(await kinds.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('title'))))
        .toEqual([...ACTIVITY_CODES])
      expect(await kinds.evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).whiteSpace)))
        .toEqual(ACTIVITY_CODES.map(() => 'nowrap'))
      for (const code of ACTIVITY_CODES) {
        await expect(dialog.locator('.activity-timeline').getByText(code, { exact: true })).toHaveCount(0)
      }
      await sliceShot(page, `project-issue-timeline-${locale}-${viewport.width}`)
      if (sliceShotsDir) {
        await kinds.last().scrollIntoViewIfNeeded()
        await sliceShot(page, `project-issue-timeline-scrolled-${locale}-${viewport.width}`)
      }
    })

    test(`issue run cards keep elapsed and remaining budget apart in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const dialog = page.getByRole('dialog')
      await dialog.getByRole('tab', { name: new RegExp(copy[locale].runTab) }).click()
      const cards = dialog.locator('.run-card')
      await expect(cards).toHaveCount(2)

      // 终态 Run 报耗时（只由起止时戳得出），活动 Run 报服务端剩余预算：两者不互相冒充。
      const durations = cards.locator('.run-card-duration')
      await expect(durations).toHaveText([copy[locale].runElapsed, copy[locale].runRemaining])
      await expect(cards.nth(0)).not.toContainText(copy[locale].remainingWord)
      await expect(cards.nth(1)).not.toContainText(copy[locale].elapsedWord)

      // 活动 Run 的终点尚未冻结：报告只列已冻结的起点，终态 Run 给出完整区间。
      await expect(cards.nth(0).locator('.run-detail-ids code')).toHaveCount(2)
      await expect(cards.nth(1).locator('.run-detail-ids code')).toHaveCount(1)

      // 条目 UUID 是弱化诊断信息：与时长同一档小字与次级色。
      const muted = await durations.nth(0).evaluate((element) => {
        const style = getComputedStyle(element)
        return { color: style.color, fontSize: style.fontSize }
      })
      const ids = cards.nth(0).locator('.run-detail-ids')
      await expect(ids).toHaveCSS('color', muted.color)
      await expect(ids).toHaveCSS('font-size', muted.fontSize)

      // 字段名不折字：标签保持单行完整，窄屏优先让值换行。
      const labels = cards.nth(0).locator('.run-detail-label')
      await expect(labels).toHaveCount(2)
      expect(await labels.evaluateAll((nodes) => nodes.every((node) => (
        getComputedStyle(node).whiteSpace === 'nowrap' && node.scrollWidth <= node.clientWidth + 1
      )))).toBe(true)
      await sliceShot(page, `project-issue-run-${locale}-${viewport.width}`)
    })

    test(`create issue hint names the workflow initial stage in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?locale=${locale}`)
      await expect(page.getByRole('heading', { name: copy[locale].project, exact: true })).toBeVisible()

      await page.getByRole('button', { name: copy[locale].createIssue }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      // 提示直接写工作流声明的初始阶段名称，不露 INIT 编码。
      await expect(dialog.getByText(copy[locale].createHintStage, { exact: true })).toBeVisible()
      await expect(dialog).not.toContainText('INIT')
      await sliceShot(page, `project-issue-create-${locale}-${viewport.width}`)
    })

    test(`projects list shows its placeholders in ${locale} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?list&locale=${locale}`)

      await expect(page.getByRole('heading', { name: copy[locale].listTitle, exact: true })).toBeVisible()
      await expect(page.getByText(copy[locale].project, { exact: true }).first()).toBeVisible()
      // 搜索占位符按输入框字体完整显示：英文文案不被末尾裁字。
      const searchInput = page.locator('.list-page-header .searchbox input')
      await expect(searchInput).toBeVisible()
      const search = await placeholderFit(searchInput)
      expect(search.placeholder).toBe(copy[locale].listSearchPlaceholder)
      expect(search.textWidth).toBeLessThanOrEqual(search.boxWidth)
      await sliceShot(page, `projects-list-${locale}-${viewport.width}`)
    })
  }
}
