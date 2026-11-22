import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, tokenColor, type Locator, type Page } from './fixture'
import type {
  IssueActivityDTO, IssueDTO, IssueDetailDTO, IssueEvidenceDTO, IssueRunSummaryDTO, PauseReason,
  ProjectDTO, ProjectSnapshotDTO,
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
/** 单阶段项目：英文阶段数必须用单数，与多阶段项目并排对照。 */
const SINGLE_STAGE_PROJECT = 'a0000000-0000-0000-0000-000000000010'
const EVIDENCE_BLOB = 'f0000000-0000-4000-8000-000000000001'
/** 详情首窗口之后的活动游标与其中的唯一一条事实。 */
const ACTIVITY_CURSOR = '6'
const LATEST_ACTIVITY_SEQUENCE = '7'
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
    boardSearchPlaceholder: '搜索标题、编号或阶段',
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
    stagesLabel: '工作流阶段',
    stagesOne: '1 个阶段',
    stagesMany: '4 个阶段',
    singleStageProject: 'Solo Flow',
    loadMore: '加载更多',
    activitiesLoadMoreFailed: '活动读取失败',
    activityLatest: 'Latest note',
    evidenceOpenFailed: '证据打开失败，请重试',
    downloadOrView: '下载/查看',
    threadsTab: 'Agent Thread',
    openThread: '打开',
    blockDialogTitle: '标记业务阻塞',
    blockReasonField: '阻塞原因',
    // 工作流给 BLOCKED 声明了自定义显示名：详情头部必须跟随声明名，而不是固定通用名。
    customBlockedStage: '等待外部依赖',
    genericBlockedStage: '业务阻塞',
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
    boardSearchPlaceholder: 'Search title, number or stage',
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
    stagesLabel: 'Workflow stages',
    stagesOne: '1 stage',
    stagesMany: '4 stages',
    singleStageProject: 'Solo Flow',
    loadMore: 'Load more',
    activitiesLoadMoreFailed: 'Failed to load activities',
    activityLatest: 'Latest note',
    evidenceOpenFailed: 'Failed to open the evidence. Try again.',
    downloadOrView: 'Download / view',
    threadsTab: 'Agent Threads',
    openThread: 'Open',
    blockDialogTitle: 'Mark as blocked',
    blockReasonField: 'Blocked reason',
    customBlockedStage: 'Waiting on external',
    genericBlockedStage: 'Blocked',
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

/** 单阶段项目卡：与多阶段项目同屏对照，阶段数在英文下按单复数分别表述。 */
function singleStageProject(locale: Locale): ProjectDTO {
  const project = snapshotFor(locale).project
  return {
    ...project,
    id: SINGLE_STAGE_PROJECT,
    title: copy[locale].singleStageProject,
    workflow: { states: project.workflow.states.slice(0, 1) },
  }
}

/** 详情首窗口还有后续活动：页签只说明已加载多少，时间线末尾给出下一窗口入口。 */
function paginatedDetail(): IssueDetailDTO {
  return { ...issueDetail(), nextActivityCursor: ACTIVITY_CURSOR }
}

/** 后续窗口里的唯一一条事实：接在首窗口末尾，来源是人而不是系统。 */
function latestActivity(): IssueActivityDTO {
  return {
    issueId: ISSUE_DETAIL, sequence: LATEST_ACTIVITY_SEQUENCE, kind: 'COMMENT', actorType: 'HUMAN',
    actorAgentName: null, runId: null, body: copy['zh-CN'].activityLatest, createdAt: STAMP + 6000,
  }
}

/** 阻塞态详情：工作流为 BLOCKED 声明了自定义名称，头部必须跟随声明名。 */
function blockedDetail(): IssueDetailDTO {
  const base = issueDetail()
  return {
    ...base,
    issue: { ...base.issue, state: 'BLOCKED', blockedFromState: 'WORK', blockReason: BLOCK_REASON },
  }
}

/** 公开证据行：签名地址不可用时整行就地失败，并保留重试入口。 */
function evidenceFixture(): IssueEvidenceDTO {
  return {
    issueId: ISSUE_DETAIL, blobId: EVIDENCE_BLOB, uri: `blob://${EVIDENCE_BLOB}`,
    name: 'settlement.log', actorAgentName: 'backend-dev', runId: RUN_TERMINAL, createdAt: STAMP,
  }
}

/** Agent Thread 行：完整 UUID 在 390 下保持单行省略，完整值只放在 title 提示里。 */
const THREAD_ID = 'd8c9f9a4-37b7-4b0e-9f2e-6f3a6d7c1b42'
const THREAD_AGENT = 'backend-settlement-reconciler'
const REVIEW_THREAD_ID = 'e1a2b3c4-0000-4000-8000-000000000009'

function threadsDetail(): IssueDetailDTO {
  return {
    ...issueDetail(),
    agentThreads: [
      { issueId: ISSUE_DETAIL, agentName: THREAD_AGENT, threadId: THREAD_ID },
      { issueId: ISSUE_DETAIL, agentName: 'reviewer', threadId: REVIEW_THREAD_ID },
    ],
  }
}

/** 只放目标 Issue 的看板快照：点卡打开的是带「打开」动作的真实详情，线程行才拿到真实宽度。 */
function threadsSnapshot(locale: Locale): ProjectSnapshotDTO {
  return {
    ...snapshotFor(locale),
    issues: [{ issue: threadsDetail().issue, currentOrLatestRun: null }],
  }
}

/** 看板卡片直接打开详情时，服务端返回同一 Issue 的快照；活动与证据另行请求。 */
function boardIssueDetail(locale: Locale, issueId: string): IssueDetailDTO {
  const entry = snapshotFor(locale).issues.find((item) => item.issue.id === issueId)
  if (!entry) {
    throw new Error(`Unknown board issue ${issueId}`)
  }
  return {
    issue: entry.issue,
    activities: [],
    nextActivityCursor: null,
    runs: [],
    stageBudgets: [],
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

interface TransportOptions {
  /** 详情快照；默认是活动/证据垂直所用的 ISSUE_DETAIL。 */
  detail?: IssueDetailDTO
  /** 看板快照；默认是八张卡的看板夹具，Agent Thread 用例只放目标 Issue。 */
  snapshot?: ProjectSnapshotDTO
  /** 后续活动窗口：按 afterSequence 返回一页，'error' 表示该窗口读取失败。 */
  activityPage?: (afterSequence: string) => { activities: IssueActivityDTO[]
    nextActivityCursor: string | null } | 'error'
  /** 公开证据列表。 */
  evidences?: IssueEvidenceDTO[]
  /** 签名地址不可用：证据行点击后就地失败。 */
  failSignedUrl?: boolean
  /** 详情读取挂起点：兑现前详情请求保持未完成，用于观察加载态。 */
  holdDetail?: Promise<void>
}

interface TransportState {
  failEvidence: boolean
  evidenceReads: number
  /** 后续活动窗口每次请求的 afterSequence，按请求顺序记录。 */
  activityReads: string[]
  failActivityPage: boolean
  failSignedUrl: boolean
}

async function transport(
  page: Page,
  locale: Locale,
  options: TransportOptions = {},
): Promise<TransportState> {
  const state: TransportState = {
    failEvidence: false,
    evidenceReads: 0,
    activityReads: [],
    failActivityPage: false,
    failSignedUrl: options.failSignedUrl ?? false,
  }
  const detail = options.detail ?? issueDetail()
  const snapshot = options.snapshot ?? snapshotFor(locale)
  const evidences = options.evidences ?? []
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
  const unavailable = (title: string) => ({ status: 500, contentType: 'application/problem+json',
    json: { type: 'about:blank', status: 500, code: 'internal', title } })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (path === `/api/project/projects/${PROJECT}/snapshot`) {
      await route.fulfill({ json: snapshot })
      return
    }
    if (path === '/api/project/projects') {
      // 项目列表：页头搜索占位符是列表里唯一的可见提示文案；两张卡对照单/复数阶段数。
      await route.fulfill({ json: [snapshotFor(locale).project, singleStageProject(locale)] })
      return
    }
    if (/^\/api\/project\/issues\/[^/]+\/evidence$/.test(path)) {
      state.evidenceReads++
      if (state.failEvidence) {
        await route.fulfill(unavailable('Evidence read failed'))
        return
      }
      await route.fulfill({ json: evidences })
      return
    }
    if (/^\/api\/platform\/storage\/blobs\/[^/]+\/(download|preview)-url$/.test(path)) {
      if (state.failSignedUrl) {
        await route.fulfill(unavailable('Storage unavailable'))
        return
      }
      await route.fulfill({ json: { url: 'https://storage.invalid/evidence', expiresAt: STAMP + 60_000 } })
      return
    }
    const issueMatch = /^\/api\/project\/issues\/([^/]+)$/.exec(path)
    if (issueMatch && request.method() === 'GET') {
      const afterSequence = url.searchParams.get('afterSequence')
      if (afterSequence != null) {
        state.activityReads.push(afterSequence)
        const pageWindow = options.activityPage?.(afterSequence)
        if (state.failActivityPage || !pageWindow || pageWindow === 'error') {
          await route.fulfill(unavailable('Activity window failed'))
          return
        }
        await route.fulfill({ json: pageWindow })
        return
      }
      if (issueMatch[1] !== ISSUE_DETAIL) {
        await route.fulfill({ json: boardIssueDetail(locale, issueMatch[1]) })
        return
      }
      if (options.holdDetail) {
        await options.holdDetail
      }
      await route.fulfill({ json: detail })
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

      // 整块看板先成图：占位符溢出不阻止截图产出，失败仍由下一处断言给出。
      await sliceShot(page, `project-board-${locale}-${viewport.width}`)

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
      // 整载失败用统一的危险态失败块（与诊断事件读取失败一致），不是中性灰说明行。
      const failure = dialog.locator('.evidence-failure')
      // 危险态失败块自带 alert，外层不重复播报。
      await expect(failure.getByRole('alert')).toHaveCount(1)
      await expect(failure).not.toHaveAttribute('role', 'alert')
      const failureBlock = failure.locator('.state-block.danger')
      await expect(failureBlock).toHaveAttribute('role', 'alert')
      await expect(failureBlock).toHaveText(copy[locale].evidenceFailed)
      await expect(failureBlock).toHaveCSS('color', await tokenColor(page, '--danger'))
      await expect(failureBlock).toHaveCSS('border-top-color', await tokenColor(page, '--danger-border'))
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

      // 阶段数按卡片自己的工作流计算：单阶段项目在英文下必须是单数，多阶段项目仍是复数。
      const stagesValue = (title: string) => page.locator('.resource-card')
        .filter({ hasText: title })
        .locator('.resource-card-meta-row')
        .filter({ hasText: copy[locale].stagesLabel })
        .locator('.resource-card-meta-value')
      await expect(stagesValue(copy[locale].project)).toHaveText(copy[locale].stagesMany)
      await expect(stagesValue(copy[locale].singleStageProject)).toHaveText(copy[locale].stagesOne)
      await sliceShot(page, `projects-list-${locale}-${viewport.width}`)
    })

    test(`activity tab counts loaded facts and fetches the next window in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      const state = await transport(page, locale, {
        detail: paginatedDetail(),
        activityPage: () => ({ activities: [latestActivity()], nextActivityCursor: null }),
      })
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const dialog = page.getByRole('dialog')
      // 还有未加载窗口：页签只声明已加载多少，末尾给出下一窗口入口。
      const moreTab = dialog.getByRole('tab', {
        name: new RegExp(`${copy[locale].activitiesTab} \\(6\\+\\)`),
      })
      await expect(moreTab).toBeVisible()
      await moreTab.click()
      const items = dialog.locator('.activity-item')
      await expect(items).toHaveCount(6)
      const loadMore = dialog.getByRole('button', { name: copy[locale].loadMore })
      await expect(loadMore).toBeVisible()
      if (sliceShotsDir) await loadMore.scrollIntoViewIfNeeded()
      await sliceShot(page, `project-issue-activity-more-${locale}-${viewport.width}`)

      // 后续窗口失败：已加载的事实不变，时间线末尾就地说明并可重试。
      state.failActivityPage = true
      await loadMore.click()
      const failed = dialog.locator('.activity-more [role="alert"]')
      await expect(failed.getByText(copy[locale].activitiesLoadMoreFailed)).toBeVisible()
      await expect(failed.getByRole('button', { name: copy[locale].retry })).toBeEnabled()
      await expect(items).toHaveCount(6)
      await expect(moreTab).toBeVisible()
      if (sliceShotsDir) await failed.scrollIntoViewIfNeeded()
      await sliceShot(page, `project-issue-activity-more-failed-${locale}-${viewport.width}`)

      // 重试成功：追加窗口接在末尾，游标耗尽后页签才给出确定的计数。
      state.failActivityPage = false
      await failed.getByRole('button', { name: copy[locale].retry }).click()
      await expect(items).toHaveCount(7)
      await expect(dialog.locator('.activity-timeline')).toContainText(copy[locale].activityLatest)
      await expect(dialog.getByRole('tab', {
        name: new RegExp(`${copy[locale].activitiesTab} \\(7\\)`),
      })).toBeVisible()
      await expect(dialog.getByRole('button', { name: copy[locale].loadMore })).toHaveCount(0)
      // 两次读取只请求同一个游标：失败重试不换页，也不重复追加。
      expect(state.activityReads).toEqual([ACTIVITY_CURSOR, ACTIVITY_CURSOR])
    })

    test(`detail header names the blocked stage from the workflow in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale, { detail: blockedDetail() })
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const header = page.getByRole('dialog').locator('.issue-detail-header-left')
      // 阻塞徽标使用工作流声明的阶段名，与看板列头一致，而不是固定通用名。
      const badge = header.locator('.ui-badge')
      await expect(badge).toHaveCount(1)
      await expect(badge).toHaveText(copy[locale].customBlockedStage)
      await expect(badge).not.toHaveText(copy[locale].genericBlockedStage)
      await sliceShot(page, `project-issue-header-blocked-${locale}-${viewport.width}`)
    })

    test(`detail header holds the stage badge until the snapshot arrives in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      let release: () => void = () => {}
      const holdDetail = new Promise<void>((resolve) => {
        release = resolve
      })
      await transport(page, locale, { holdDetail })
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const header = page.getByRole('dialog').locator('.issue-detail-header-left')
      // 快照未到达：没有阶段事实，也没有内部编码冒充阶段。
      await expect(header.locator('.issue-number')).toHaveText('#...')
      await expect(header.locator('.ui-badge')).toHaveCount(0)
      await expect(page.getByRole('dialog')).not.toContainText('INIT')
      await sliceShot(page, `project-issue-loading-${locale}-${viewport.width}`)

      // 快照到达后徽标才出现，且用工作流名称。
      release()
      await expect(header.locator('.ui-badge')).toHaveText(copy[locale].columns.WORK)
    })

    test(`evidence row reports an unavailable signed url inline in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale, { evidences: [evidenceFixture()], failSignedUrl: true })
      await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

      const dialog = page.getByRole('dialog')
      await dialog.getByRole('tab', {
        name: new RegExp(`${copy[locale].evidenceTab} \\(1\\)`),
      }).click()
      const row = dialog.locator('.evidence-card')
      await expect(row).toHaveCount(1)
      await expect(row).toContainText('settlement.log')

      // 下载/预览地址都不可用：行内说明失败并给出重试，而不是点击后没有任何反馈。
      await row.getByRole('link', { name: copy[locale].downloadOrView }).click()
      const failure = row.getByRole('alert')
      await expect(failure.getByText(copy[locale].evidenceOpenFailed)).toBeVisible()
      await expect(failure.getByRole('button', { name: copy[locale].retry })).toBeEnabled()
      await sliceShot(page, `project-evidence-open-failed-${locale}-${viewport.width}`)

      // 重试仍失败：提示留在行内，链接动作与重试入口继续可用。
      await failure.getByRole('button', { name: copy[locale].retry }).click()
      await expect(row.getByRole('alert')).toBeVisible()
      await expect(failure.getByRole('button', { name: copy[locale].retry })).toBeEnabled()
    })

    test(`issue agent threads keep the full id on one ellipsized line in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      // 从看板卡片打开真实接线：详情带「打开」动作，线程行在 390 下才拿到真实剩余宽度。
      await transport(page, locale, { detail: threadsDetail(), snapshot: threadsSnapshot(locale) })
      await page.goto(`/browser-tests/project-workflow-harness.html?board&locale=${locale}`)
      await page.getByTestId(`issue-card-${ISSUE_DETAIL}`)
        .getByRole('button', { name: new RegExp(`#12\\s+${DETAIL_TITLE}`) }).click()

      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await dialog.getByRole('tab', { name: new RegExp(`${copy[locale].threadsTab} \\(2\\)`) }).click()
      const rows = dialog.locator('.thread-item')
      await expect(rows).toHaveCount(2)
      await expect(rows.first()).toContainText(THREAD_AGENT)

      // Agent 名称与完整 UUID 是两个事实：UUID 单行省略，不从中间折断，也不与标题抢主视觉。
      const threadId = rows.first().locator('.thread-item-id')
      await expect(threadId).toHaveText(THREAD_ID)
      await expect(threadId).toHaveAttribute('title', THREAD_ID)
      await expect(threadId).toHaveCSS('white-space', 'nowrap')
      await expect(threadId).toHaveCSS('text-overflow', 'ellipsis')
      const geometry = await threadId.evaluate((element) => {
        const style = getComputedStyle(element)
        return {
          lines: Math.round(element.getBoundingClientRect().height / Number.parseFloat(style.lineHeight)),
          clipped: element.scrollWidth > element.clientWidth + 1,
        }
      })
      expect(geometry.lines).toBe(1)
      // 宽屏放得下完整 UUID；窄屏只省略尾部，完整值仍在 title 里。
      expect(geometry.clipped).toBe(viewport.width < 900)
      // 「打开」动作仍在行内，没有被长 ID 挤出卡片。
      await expect(rows.first().getByRole('button', { name: copy[locale].openThread, exact: true })).toBeVisible()
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await sliceShot(page, `project-issue-threads-${locale}-${viewport.width}`)
    })

    test(`card block action opens the detail with the reason form ready in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await transport(page, locale)
      await page.goto(`/browser-tests/project-workflow-harness.html?board&locale=${locale}`)
      await expect(page.getByRole('heading', { name: copy[locale].project, exact: true })).toBeVisible()

      await page.getByTestId(`issue-card-${ISSUE_INIT}`)
        .getByRole('button', { name: copy[locale].block }).click()

      // 卡片动作承诺填写原因：详情打开后原因表单已经展开，不需要再点一次。
      await expect(page.getByRole('dialog')).toHaveCount(2)
      const blockDialog = page.getByRole('dialog', { name: copy[locale].blockDialogTitle })
      await expect(blockDialog).toBeVisible()
      const reason = blockDialog.getByRole('textbox', { name: copy[locale].blockReasonField })
      await expect(reason).toBeVisible()
      await expect(reason).toBeFocused()
      await sliceShot(page, `project-issue-block-intent-${locale}-${viewport.width}`)
    })
  }
}
