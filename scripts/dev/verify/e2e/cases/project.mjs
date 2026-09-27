import {
  HttpError,
  assert,
  assertDecimalVersion,
  assertExactFields,
  cid,
  envelopeData,
  expectHttpError,
  pageResults,
} from '../lib/http.mjs'
import { canonicalUuid } from '../lib/harness.mjs'
import { registerCase } from '../lib/registry.mjs'

/** ProjectDTO 精确字段集合：Project 只拥有展示事实、workflow 与 YOLO 启动策略，没有 Coordinator 身份字段。 */
const PROJECT_FIELDS = [
  'id',
  'title',
  'description',
  'workflow',
  'yoloEnabled',
  'nextIssueNumber',
  'version',
  'archivedAt',
  'createdAt',
  'updatedAt',
]

/** ProjectWorkflowDTO / ProjectWorkflowStateDTO 精确字段集合：JSON 是 workflow 的唯一事实源。 */
const WORKFLOW_FIELDS = ['states']
const WORKFLOW_STATE_FIELDS = [
  'state',
  'name',
  'agent',
  'environment',
  'instructions',
  'maxRuns',
  'enabled',
  'next',
]

/** IssueDTO 精确字段集合：当前要求即正文，状态由明确操作流转，没有 assignee/reviewer 之类角色字段。 */
const ISSUE_FIELDS = [
  'id',
  'projectId',
  'number',
  'title',
  'description',
  'state',
  'blockedFromState',
  'blockReason',
  'pauseReason',
  'pauseDetail',
  'version',
  'archivedAt',
  'createdAt',
  'updatedAt',
]

/** IssueDetailDTO 精确字段集合：Activity 只以有界窗口暴露，Agent 归属由 agentThreads 表达。 */
const ISSUE_DETAIL_FIELDS = [
  'issue',
  'activities',
  'nextActivityCursor',
  'runs',
  'currentRun',
  'latestRun',
  'stageBudgets',
  'agentThreads',
]

/** IssueActivityDTO 精确字段集合：Issue 唯一有序事实流上的一条记录（不暴露内部幂等键）。 */
const ACTIVITY_FIELDS = [
  'issueId',
  'sequence',
  'kind',
  'actorType',
  'actorAgentName',
  'runId',
  'body',
  'data',
  'createdAt',
]

/** ProjectSnapshotDTO / ProjectIssueSnapshotDTO 精确字段集合：快照不再聚合依赖图。 */
const SNAPSHOT_FIELDS = ['project', 'issues']
const SNAPSHOT_ISSUE_FIELDS = ['issue', 'currentOrLatestRun']

const RESERVED_STATES = ['INIT', 'BLOCKED', 'DONE']

function workflowStateBody(state, name, next, extra = {}) {
  return { state, name, next, ...extra }
}

/** 默认 workflow 的线上投影：INIT → WORK（人工阶段）→ DONE，加保留的 BLOCKED。 */
const DEFAULT_WORKFLOW_STATES = [
  [RESERVED_STATES[0], '待开始', ['WORK']],
  ['WORK', '处理中', ['DONE']],
  ['BLOCKED', '业务阻塞', []],
  ['DONE', '完成', []],
]

function assertWorkflow(workflow, expectedStages) {
  assertExactFields(workflow, WORKFLOW_FIELDS, 'project workflow')
  assert(Array.isArray(workflow.states), JSON.stringify(workflow))
  for (const state of workflow.states) {
    assertExactFields(state, WORKFLOW_STATE_FIELDS, 'workflow state')
    assert(typeof state.state === 'string' && state.state.trim(), JSON.stringify(state))
    assert(typeof state.name === 'string' && state.name.trim(), JSON.stringify(state))
    assert(state.enabled === true, JSON.stringify(state))
    assert(Array.isArray(state.next), JSON.stringify(state))
  }
  for (const [state, name, next] of expectedStages) {
    const actual = workflow.states.find((candidate) => candidate.state === state)
    assert(
      actual && actual.name === name && actual.next.join(',') === next.join(','),
      `workflow stage ${state}: ${JSON.stringify(workflow.states)}`,
    )
  }
  const codes = workflow.states.map((state) => state.state)
  for (const reserved of RESERVED_STATES) {
    assert(codes.includes(reserved), `workflow must reserve ${reserved}: ${JSON.stringify(codes)}`)
  }
}

/** 断言一条 append 成功的人工 Activity：身份由服务端产生，正文与类型由请求决定。 */
function assertHumanActivity(activity, { issueId, kind, body }) {
  assertExactFields(activity, ACTIVITY_FIELDS, 'issue activity')
  assert(canonicalUuid(activity.issueId, 'activity.issueId') === issueId, JSON.stringify(activity))
  assertDecimalVersion(activity.sequence, 'activity.sequence')
  assert(
    activity.kind === kind
      && activity.actorType === 'HUMAN'
      && activity.actorAgentName === null
      && activity.runId === null
      && activity.body === body
      && typeof activity.createdAt === 'string',
    JSON.stringify(activity),
  )
  return activity
}

registerCase({
  id: 'project.issue_lifecycle',
  level: 'L1',
  title: 'Project workflow/Issue 状态机与 Activity 事实流',
  docs:
    '免费 L1：Project 默认 workflow（INIT→WORK 人工阶段→DONE，含保留 BLOCKED）、设置 CAS（标题/描述与 YOLO 各自独立更新）、workflow JSON 整体替换与非法配置拒绝；Issue 从 INIT 起按 workflow next 白名单 transition（工作流外目标、保留状态与未声明状态一律拒绝）、BLOCKED 由专用 block/recover 进出并记录原阶段与原因、pause(UNKNOWN)/resolve-unknown/resume 门禁与 400 约定、无活动 Run 时 INSTRUCTION 明确拒绝、COMMENT 幂等重放；Activity 有界窗口分页（nextActivityCursor 语义）与 snapshot 投影；旧七态/依赖/Run 控制端点（/status、/cancel、/inputs、/dependencies）已不存在',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    let project = null
    try {
      const createdProject = await ctx.call('POST', '/api/projects', {
        title: `E2E Project ${suffix}`,
        description: 'Project workflow lifecycle',
      })
      assert(createdProject.status === 201, `create Project status ${createdProject.status}`)
      project = envelopeData(createdProject.json)
      assertExactFields(project, PROJECT_FIELDS, 'project')
      canonicalUuid(project.id, 'project.id')
      assertDecimalVersion(project.version, 'project.version')
      assert(
        project.version === '0'
          && project.archivedAt === null
          && project.yoloEnabled === true
          && project.nextIssueNumber === '1',
        JSON.stringify(project),
      )
      assertWorkflow(project.workflow, DEFAULT_WORKFLOW_STATES)
      // 默认 workflow 只有人工阶段：没有 Agent、Environment、instructions 或 Run 额度。
      for (const state of project.workflow.states) {
        assert(
          state.agent === null && state.environment === null && state.maxRuns === null,
          JSON.stringify(state),
        )
      }
      assert(
        project.workflow.states.find((state) => state.state === 'WORK').instructions === null,
        JSON.stringify(project.workflow.states),
      )

      const listed = pageResults((await ctx.call('GET', '/api/projects')).json)
      assert(listed.some((candidate) => candidate.id === project.id), 'created Project not listed')

      // 公共 batch 端点只服务直接持有 Session 的 Chat owner：Project 不是 owner 形态，
      // Issue+Agent 命令由 Issue 业务工作流拥有，都不能借该端点伪造命令。
      const projectOwnerBatch = await expectHttpError(
        () =>
          ctx.call('POST', '/api/harness/command-batches', {
            owner: { type: 'PROJECT', id: project.id },
            target: {
              type: 'NEW_SESSION',
              sessionId: cid(),
              threadId: cid(),
              rootSettings: { agentName: 'unused-agent' },
              yoloEnabled: false,
            },
            commands: [{ type: 'USER_MESSAGE', idempotencyKey: cid(), contents: [{ type: 'TEXT', text: 'x' }] }],
          }),
        { status: 400 },
      )
      assert(
        /owner\.type must be CHAT or ISSUE_AGENT/.test(JSON.parse(projectOwnerBatch.body).errors?.detail),
        projectOwnerBatch.body,
      )
      const issueOwnerBatch = await expectHttpError(
        () =>
          ctx.call('POST', '/api/harness/command-batches', {
            owner: { type: 'ISSUE_AGENT', issueId: cid(), agentName: 'e2e-agent-owner' },
            target: {
              type: 'NEW_SESSION',
              sessionId: cid(),
              threadId: cid(),
              rootSettings: { agentName: 'e2e-agent-owner' },
              yoloEnabled: false,
            },
            commands: [{ type: 'USER_MESSAGE', idempotencyKey: cid(), contents: [{ type: 'TEXT', text: 'x' }] }],
          }),
        { status: 409 },
      )
      assert(
        /Issue agent commands must use the Issue business workflow/.test(
          JSON.parse(issueOwnerBatch.body).errors?.detail,
        ),
        issueOwnerBatch.body,
      )

      await expectHttpError(
        () =>
          ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: '9',
            title: 'stale update',
          }),
        { status: 409 },
      )
      const unchanged = envelopeData((await ctx.call('GET', `/api/projects/${project.id}`)).json)
      assert(
        unchanged.title === project.title && unchanged.version === '0' && unchanged.yoloEnabled === true,
        JSON.stringify(unchanged),
      )

      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: project.version,
            title: `E2E Project Updated ${suffix}`,
            description: 'Updated through the public REST contract',
          })
        ).json,
      )
      assert(project.version === '1' && project.yoloEnabled === true, JSON.stringify(project))

      // YOLO 启动策略是独立 CAS 面：配置更新不触碰它，它也不触碰配置版本以外的字段。
      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}/yolo`, {
            expectedVersion: project.version,
            yoloEnabled: false,
          })
        ).json,
      )
      assert(
        project.version === '2' && project.yoloEnabled === false && project.title === `E2E Project Updated ${suffix}`,
        JSON.stringify(project),
      )

      // workflow 整体替换：JSON 是唯一事实源，合法配置立即生效。
      const renamedStages = [
        [RESERVED_STATES[0], '待开始', ['WORK']],
        ['WORK', `人工处理 ${suffix}`, ['REVIEW', 'DONE']],
        ['REVIEW', '人工复核', ['DONE']],
        ['BLOCKED', '业务阻塞', []],
        ['DONE', '完成', []],
      ]
      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}/workflow`, {
            expectedVersion: project.version,
            workflow: {
              states: renamedStages.map(([state, name, next]) =>
                workflowStateBody(state, name, next),
              ),
            },
          })
        ).json,
      )
      assert(project.version === '3', JSON.stringify(project))
      assertWorkflow(project.workflow, renamedStages)

      // 非法 workflow：缺少保留状态 / 启用阶段从 INIT 不可达 / 工作阶段缺少 maxRuns（有 Agent）。
      for (const states of [
        [workflowStateBody('INIT', '待开始', ['WORK']), workflowStateBody('WORK', '处理中', ['DONE'])],
        [
          workflowStateBody('INIT', '待开始', ['DONE']),
          workflowStateBody('ORPHAN', '孤岛阶段', ['DONE']),
          workflowStateBody('BLOCKED', '业务阻塞', []),
          workflowStateBody('DONE', '完成', []),
        ],
        [
          workflowStateBody('INIT', '待开始', ['BUILD']),
          workflowStateBody('BUILD', '构建', ['DONE'], { agent: `e2e-agent-${suffix}` }),
          workflowStateBody('BLOCKED', '业务阻塞', []),
          workflowStateBody('DONE', '完成', []),
        ],
        [
          workflowStateBody('INIT', '待开始', ['WORK']),
          workflowStateBody('WORK', '处理中', ['BLOCKED']),
          workflowStateBody('BLOCKED', '业务阻塞', []),
          workflowStateBody('DONE', '完成', []),
        ],
      ]) {
        await expectHttpError(
          () =>
            ctx.call('PUT', `/api/projects/${project.id}/workflow`, {
              expectedVersion: project.version,
              workflow: { states },
            }),
          { status: 400 },
        )
      }
      const workflowUnchanged = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}`)).json,
      )
      assertWorkflow(workflowUnchanged.workflow, renamedStages)
      assert(workflowUnchanged.version === '3', JSON.stringify(workflowUnchanged))

      const dependency = await createIssue(ctx, project.id, {
        title: 'Stale residue',
        description: 'Kept as legacy issue fact',
      })
      const issue = await createIssue(ctx, project.id, {
        title: 'Primary task',
        description: 'Initial requirement',
      })
      let current = issue
      assert(
        issue.projectId === project.id
          && issue.number === '2'
          && issue.state === 'INIT'
          && issue.version === '0'
          && issue.blockedFromState === null
          && issue.blockReason === null
          && issue.pauseReason === null
          && issue.pauseDetail === null
          && issue.archivedAt === null
          && dependency.number === '1',
        JSON.stringify({ issue, dependency }),
      )

      // 编号由 Project 单调分配且不改 Project CAS 版本。
      const numbered = envelopeData((await ctx.call('GET', `/api/projects/${project.id}`)).json)
      assert(
        numbered.nextIssueNumber === '3' && numbered.version === '3',
        JSON.stringify(numbered),
      )

      // 旧七态/依赖/Run 控制端点已从公开面移除。
      for (const [method, requestPath, body] of [
        ['POST', `/api/issues/${issue.id}/status`, { expectedVersion: '0', status: 'DONE' }],
        ['POST', `/api/issues/${issue.id}/inputs`, { kind: 'HUMAN', body: 'legacy' }],
        ['POST', `/api/issues/${issue.id}/cancel`, { expectedVersion: '0' }],
        ['POST', `/api/issues/${issue.id}/dependencies`, { dependsOnIssueId: dependency.id }],
      ]) {
        await expectHttpError(() => ctx.call(method, requestPath, body), { status: 404 })
      }
      await expectHttpError(
        () => ctx.call('DELETE', `/api/issues/${issue.id}/dependencies/${dependency.id}`),
        { status: 404 },
      )

      let detail = await readIssueDetail(ctx, issue.id)
      assertExactFields(detail, ISSUE_DETAIL_FIELDS, 'issue detail')
      assertExactFields(detail.issue, ISSUE_FIELDS, 'detail issue')
      // 建单只产生 Issue 事实：没有初始 Activity、没有 Run、没有额度、没有 Agent 归属。
      assert(
        detail.activities.length === 0
          && detail.nextActivityCursor === null
          && detail.runs.length === 0
          && detail.currentRun === null
          && detail.latestRun === null
          && detail.stageBudgets.length === 0
          && detail.agentThreads.length === 0,
        JSON.stringify(detail),
      )

      current = envelopeData(
        (
          await ctx.call('PUT', `/api/issues/${issue.id}`, {
            expectedVersion: current.version,
            title: 'Primary task updated',
            description: 'Updated requirement',
          })
        ).json,
      )
      assert(current.version === '1' && current.title === 'Primary task updated', JSON.stringify(current))

      // COMMENT：只留痕且不唤醒 Agent，幂等键重放命中原事实。
      const commentKey = `e2e-comment-${cid()}`
      const commentRequest = {
        expectedVersion: current.version,
        requestKey: commentKey,
        kind: 'COMMENT',
        body: 'Please preserve the public contract.',
      }
      const appendedComment = await ctx.call('POST', `/api/issues/${issue.id}/activities`, commentRequest)
      assert(appendedComment.status === 201, `append comment status ${appendedComment.status}`)
      const comment = assertHumanActivity(envelopeData(appendedComment.json), {
        issueId: issue.id,
        kind: 'COMMENT',
        body: commentRequest.body,
      })
      assert(comment.sequence === '1', JSON.stringify(comment))

      const replayedComment = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/activities`, commentRequest)).json,
      )
      assert(replayedComment.sequence === comment.sequence, JSON.stringify(replayedComment))
      const afterComment = envelopeData(
        (
          await ctx.call(
            'GET',
            `/api/issues/${issue.id}/activities?afterSequence=${encodeURIComponent(comment.sequence)}&limit=50`,
          )
        ).json,
      )
      assert(Array.isArray(afterComment) && afterComment.length === 0, JSON.stringify(afterComment))

      // 定向指示必须投递给明确的当前 Run：没有活动 Run 时明确拒绝，绝不构造未来阶段队列。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/activities`, {
            expectedVersion: commentRequest.expectedVersion,
            requestKey: `e2e-instruction-${cid()}`,
            kind: 'INSTRUCTION',
            body: 'Keep the migration reversible.',
          }),
        { status: 400 },
      )
      // 形状非法与空白正文在触达领域前被拒绝。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/activities`, {
            requestKey: `e2e-missing-version-${cid()}`,
            kind: 'COMMENT',
            body: 'missing expectedVersion',
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/activities`, {
            expectedVersion: commentRequest.expectedVersion,
            requestKey: `e2e-blank-body-${cid()}`,
            kind: 'COMMENT',
            body: '   ',
          }),
        { status: 400 },
      )

      // 正常边：INIT → WORK → DONE，每一步是显式转移并记录 STATE_CHANGE 事实。
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/transition`, {
            expectedVersion: current.version,
            requestKey: `e2e-transition-work-${cid()}`,
            toState: 'WORK',
          })
        ).json,
      )
      assert(current.state === 'WORK' && current.version === '2', JSON.stringify(current))

      // 非法转移：未声明状态、非白名单目标、保留状态都不能通过正常边进入。
      for (const toState of ['NOPE', 'BLOCKED', 'INIT', 'DONE_EXTRA']) {
        await expectHttpError(
          () =>
            ctx.call('POST', `/api/issues/${issue.id}/transition`, {
              expectedVersion: current.version,
              requestKey: `e2e-transition-invalid-${toState}-${cid()}`,
              toState,
            }),
          { status: 400 },
        )
      }
      const afterInvalid = await readIssueDetail(ctx, issue.id)
      assert(
        afterInvalid.issue.state === 'WORK' && afterInvalid.issue.version === current.version,
        JSON.stringify(afterInvalid.issue),
      )

      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/transition`, {
            expectedVersion: current.version,
            requestKey: `e2e-transition-done-${cid()}`,
            toState: 'DONE',
          })
        ).json,
      )
      assert(current.state === 'DONE', JSON.stringify(current))

      // DONE 不能被阻塞，只能由显式 reopen 回到 INIT。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/block`, {
            expectedVersion: current.version,
            requestKey: `e2e-block-done-${cid()}`,
            reason: 'cannot block a done issue',
          }),
        { status: 400 },
      )
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/reopen`, {
            expectedVersion: current.version,
            requestKey: `e2e-reopen-${cid()}`,
          })
        ).json,
      )
      assert(current.state === 'INIT' && current.version === '4', JSON.stringify(current))

      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/transition`, {
            expectedVersion: current.version,
            requestKey: `e2e-transition-work-2-${cid()}`,
            toState: 'WORK',
          })
        ).json,
      )
      assert(current.state === 'WORK' && current.version === '5', JSON.stringify(current))

      // 业务阻塞：记录原阶段与原因，已在 BLOCKED 时再次阻塞被拒绝。
      const blockReason = 'Waiting for the business decision on scope'
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/block`, {
            expectedVersion: current.version,
            requestKey: `e2e-block-${cid()}`,
            reason: blockReason,
          })
        ).json,
      )
      assert(
        current.state === 'BLOCKED'
          && current.blockedFromState === 'WORK'
          && current.blockReason === blockReason
          && current.version === '6',
        JSON.stringify(current),
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/block`, {
            expectedVersion: current.version,
            requestKey: `e2e-block-again-${cid()}`,
            reason: 'double block',
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/recover`, {
            expectedVersion: String(BigInt(current.version) - 1n),
            requestKey: `e2e-recover-stale-${cid()}`,
          }),
        { status: 409 },
      )
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/recover`, {
            expectedVersion: current.version,
            requestKey: `e2e-recover-${cid()}`,
          })
        ).json,
      )
      assert(
        current.state === 'WORK' && current.blockedFromState === null && current.blockReason === null,
        JSON.stringify(current),
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/recover`, {
            expectedVersion: current.version,
            requestKey: `e2e-recover-unblocked-${cid()}`,
          }),
        { status: 400 },
      )

      // 控制暂停与业务阻塞分离：UNKNOWN 门禁只能由人工核查事实解除，解除后转 USER 门禁。
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/pause`, {
            expectedVersion: current.version,
            requestKey: `e2e-pause-unknown-${cid()}`,
            reason: 'UNKNOWN',
            detail: 'in-flight side effect needs manual verification',
          })
        ).json,
      )
      assert(current.pauseReason === 'UNKNOWN' && current.state === 'WORK', JSON.stringify(current))
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/resume`, {
            expectedVersion: current.version,
            requestKey: `e2e-resume-unknown-${cid()}`,
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/transition`, {
            expectedVersion: current.version,
            requestKey: `e2e-transition-paused-${cid()}`,
            toState: 'WORK',
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/archive`, { expectedVersion: current.version }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/resolve-unknown`, {
            expectedVersion: current.version,
            requestKey: `e2e-resolve-invalid-${cid()}`,
            verification: '   ',
          }),
        { status: 400 },
      )
      const verification = 'checked the provider console: nothing was dispatched'
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/resolve-unknown`, {
            expectedVersion: current.version,
            requestKey: `e2e-resolve-${cid()}`,
            verification,
          })
        ).json,
      )
      assert(
        current.pauseReason === 'USER' && current.pauseDetail === verification,
        JSON.stringify(current),
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/resolve-unknown`, {
            expectedVersion: current.version,
            requestKey: `e2e-resolve-again-${cid()}`,
            verification: 'second verification',
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('DELETE', `/api/issues/${issue.id}?expectedVersion=${current.version}`),
        { status: 400 },
      )
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/resume`, {
            expectedVersion: current.version,
            requestKey: `e2e-resume-${cid()}`,
          })
        ).json,
      )
      assert(current.pauseReason === null && current.pauseDetail === null, JSON.stringify(current))

      // 人工终止：没有活动 Run 时只保留 USER 门禁，不伪造 Run 事实。
      const stopDetail = 'stopped by the operator'
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/stop`, {
            expectedVersion: current.version,
            requestKey: `e2e-stop-${cid()}`,
            detail: stopDetail,
          })
        ).json,
      )
      assert(
        current.pauseReason === 'USER'
          && current.pauseDetail === stopDetail
          && current.state === 'WORK',
        JSON.stringify(current),
      )
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/resume`, {
            expectedVersion: current.version,
            requestKey: `e2e-resume-after-stop-${cid()}`,
          })
        ).json,
      )
      assert(current.pauseReason === null, JSON.stringify(current))

      // Activity 事实流：STATE_CHANGE 由服务端生成，窗口分页不重不漏。
      const facts = await readActivityPages(ctx, issue.id, 2)
      assert(
        facts.map((activity) => activity.kind).join(',')
          === [
            'COMMENT',
            'STATE_CHANGE',
            'STATE_CHANGE',
            'STATE_CHANGE',
            'STATE_CHANGE',
            'CONTROL',
            'CONTROL',
            'CONTROL',
            'CONTROL',
            'CONTROL',
            'CONTROL',
            'CONTROL',
          ].join(','),
        JSON.stringify(facts.map((activity) => [activity.kind, activity.sequence])),
      )
      const transitions = facts.filter((activity) => activity.kind === 'STATE_CHANGE')
      assert(
        transitions[0].actorType === 'SYSTEM'
          && transitions[0].actorAgentName === null
          && transitions[0].data?.action === 'TRANSITION'
          && transitions[0].data?.from === 'INIT'
          && transitions[0].data?.to === 'WORK',
        JSON.stringify(transitions[0]),
      )
      assert(
        transitions.map((activity) => `${activity.data.from}->${activity.data.to}`).join(',')
          === 'INIT->WORK,WORK->DONE,DONE->INIT,INIT->WORK',
        JSON.stringify(transitions.map((activity) => activity.data)),
      )
      const resolveFact = facts.find((activity) => activity.data?.action === 'RESOLVE_UNKNOWN')
      assert(
        resolveFact?.data?.verification === verification && resolveFact.actorType === 'HUMAN',
        JSON.stringify(resolveFact),
      )
      await expectHttpError(() => ctx.call('GET', `/api/issues/${issue.id}?limit=0`), { status: 400 })
      await expectHttpError(() => ctx.call('GET', `/api/issues/${issue.id}?afterSequence=-1`), {
        status: 400,
      })

      // Snapshot：只聚合 Project 真相与每个 Issue 的当前/最近 Run，不再携带依赖图。
      const snapshot = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}/snapshot`)).json,
      )
      assertExactFields(snapshot, SNAPSHOT_FIELDS, 'project snapshot')
      assert(!('dependencies' in snapshot), JSON.stringify(snapshot))
      const snapshotIssue = snapshot.issues.find((candidate) => candidate.issue.id === issue.id)
      assertExactFields(snapshotIssue, SNAPSHOT_ISSUE_FIELDS, 'project snapshot issue')
      assertExactFields(snapshotIssue.issue, ISSUE_FIELDS, 'snapshot issue')
      assert(
        snapshot.project.id === project.id
          && snapshot.issues.length === 2
          && snapshotIssue.issue.state === 'WORK'
          && snapshotIssue.currentOrLatestRun === null,
        JSON.stringify(snapshot),
      )

      // 归档/恢复归档只切换可编辑性，不删除历史；删除后不存在。
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/archive`, {
            expectedVersion: current.version,
          })
        ).json,
      )
      assert(current.archivedAt != null, JSON.stringify(current))
      current = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/unarchive`, {
            expectedVersion: current.version,
          })
        ).json,
      )
      assert(current.archivedAt === null, JSON.stringify(current))

      await ctx.call('DELETE', `/api/issues/${issue.id}?expectedVersion=${current.version}`)
      await expectHttpError(() => ctx.call('GET', `/api/issues/${issue.id}`), { status: 404 })
      const remaining = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}/snapshot`)).json,
      )
      assert(
        remaining.issues.length === 1 && remaining.issues[0].issue.id === dependency.id,
        JSON.stringify(remaining.issues),
      )

      project = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/archive`, {
            expectedVersion: project.version,
          })
        ).json,
      )
      assert(project.archivedAt != null, JSON.stringify(project))
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/projects/${project.id}/issues`, {
            title: 'cannot be created',
            description: 'archived project rejects new issues',
          }),
        { status: 400 },
      )
      const activeProjects = pageResults((await ctx.call('GET', '/api/projects')).json)
      assert(
        !activeProjects.some((candidate) => candidate.id === project.id),
        'archived Project must not appear in the default list',
      )
      const archivedProjects = pageResults(
        (await ctx.call('GET', '/api/projects?includeArchived=true')).json,
      )
      assert(
        archivedProjects.some((candidate) => candidate.id === project.id),
        'archived Project must appear with includeArchived=true',
      )
      project = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/unarchive`, {
            expectedVersion: project.version,
          })
        ).json,
      )
      assert(project.archivedAt === null, JSON.stringify(project))

      await ctx.call('DELETE', `/api/projects/${project.id}?expectedVersion=${project.version}`)
      await expectHttpError(() => ctx.call('GET', `/api/projects/${project.id}`), { status: 404 })
      project = null
    } finally {
      if (project?.id) {
        await deleteProjectIfPresent(ctx, project.id)
      }
    }
  },
})

async function createIssue(ctx, projectId, { title, description }) {
  const created = await ctx.call('POST', `/api/projects/${projectId}/issues`, {
    title,
    description,
  })
  assert(created.status === 201, `create Issue status ${created.status}`)
  const issue = envelopeData(created.json)
  assertExactFields(issue, ISSUE_FIELDS, 'issue')
  canonicalUuid(issue.id, 'issue.id')
  assertDecimalVersion(issue.version, 'issue.version')
  assert(issue.state === 'INIT', JSON.stringify(issue))
  return issue
}

async function readIssueDetail(ctx, issueId) {
  return envelopeData((await ctx.call('GET', `/api/issues/${issueId}`)).json)
}

/**
 * 以有界窗口（{@code afterSequence}/{@code limit}）遍历 Issue Activity 并返回完整事实流。
 *
 * 契约：窗口每次返回不超过 limit 条按 sequence 升序的事实；`nextActivityCursor` 仅在窗口取满时给出当页最后一条
 * sequence（因此最后一页取满时需要再取一次空页才能确认结束），游标严格前进；结果与平铺读取逐条一致。
 */
async function readActivityPages(ctx, issueId, pageSize = 2) {
  const paged = []
  let cursor = '0'
  let pages = 0
  for (;;) {
    assert(pages < 50, `activity paging did not terminate: ${JSON.stringify(paged)}`)
    const window = envelopeData(
      (
        await ctx.call(
          'GET',
          `/api/issues/${issueId}?afterSequence=${encodeURIComponent(cursor)}&limit=${pageSize}`,
        )
      ).json,
    )
    assertExactFields(window, ISSUE_DETAIL_FIELDS, 'issue detail window')
    assert(Array.isArray(window.activities), JSON.stringify(window))
    if (window.activities.length === 0) {
      assert(window.nextActivityCursor === null, JSON.stringify(window))
      break
    }
    for (const activity of window.activities) {
      assertExactFields(activity, ACTIVITY_FIELDS, 'issue activity')
      assert(
        Number(activity.sequence) > Number(cursor),
        `activity ${activity.sequence} not after cursor ${cursor}`,
      )
      paged.push(activity)
    }
    if (window.activities.length < pageSize) {
      assert(window.nextActivityCursor === null, JSON.stringify(window))
      break
    }
    assert(
      window.nextActivityCursor === window.activities[window.activities.length - 1].sequence,
      JSON.stringify(window),
    )
    cursor = window.nextActivityCursor
    pages += 1
  }

  const flat = envelopeData(
    (await ctx.call('GET', `/api/issues/${issueId}/activities?limit=200`)).json,
  )
  assert(Array.isArray(flat), JSON.stringify(flat))
  assert(
    flat.map((activity) => activity.sequence).join(',')
      === paged.map((activity) => activity.sequence).join(','),
    JSON.stringify({ flat, paged }),
  )
  return paged
}

async function deleteProjectIfPresent(ctx, projectId) {
  try {
    const project = envelopeData((await ctx.call('GET', `/api/projects/${projectId}`)).json)
    await ctx.call(
      'DELETE',
      `/api/projects/${projectId}?expectedVersion=${encodeURIComponent(project.version)}`,
    )
  } catch (error) {
    if (!(error instanceof HttpError && error.status === 404)) {
      throw error
    }
  }
}

registerCase({
  id: 'project.issue_stage_budget',
  level: 'L1',
  title: 'Issue 阶段额度：仅启用 Agent 阶段可授权与重置',
  docs:
    '免费 L1：阶段额度只属于 workflow 中「启用且有 Agent」的工作阶段，人工阶段与保留状态（INIT/BLOCKED/DONE）明确拒绝（400）、maxRuns 必须 > 0、CAS 过期 409；首次 budget-reset 即授权（高水位 0）并写入 CONTROL 事实，同 requestKey 精确重放返回同一额度；Issue 详情的 stageBudgets 只列出已授权阶段且携带 state/maxRuns/budgetAfterOrdinal/usedRuns/remainingRuns；提高 maxRuns 的重新授权不回退高水位',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const agentName = `e2e-agent-budget-${suffix}`
    let project = null
    try {
      project = envelopeData(
        (
          await ctx.call('POST', '/api/projects', {
            title: `E2E Budget Project ${suffix}`,
            description: 'Stage budget contract',
          })
        ).json,
      )
      // 用显式 workflow 引入一个启用且声明 Agent 的工作阶段：WORK 仍是人工阶段，BUILD 才是可授权阶段。
      const stages = [
        [RESERVED_STATES[0], '待开始', ['WORK']],
        ['WORK', '人工处理', ['BUILD']],
        ['BUILD', '自动构建', ['DONE']],
        ['BLOCKED', '业务阻塞', []],
        ['DONE', '完成', []],
      ]
      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}/workflow`, {
            expectedVersion: project.version,
            workflow: {
              states: stages.map(([state, name, next]) =>
                state === 'BUILD'
                  ? workflowStateBody(state, name, next, { agent: agentName, maxRuns: '3' })
                  : workflowStateBody(state, name, next),
              ),
            },
          })
        ).json,
      )
      assert(project.version === '1', JSON.stringify(project))
      const buildStage = project.workflow.states.find((state) => state.state === 'BUILD')
      assert(
        buildStage.agent === agentName && buildStage.maxRuns === '3' && buildStage.enabled === true,
        JSON.stringify(buildStage),
      )

      const issue = await createIssue(ctx, project.id, {
        title: 'Budgeted task',
        description: 'Stage budget contract',
      })
      let current = issue
      const detail = await readIssueDetail(ctx, issue.id)
      assert(
        detail.issue.state === 'INIT' && detail.stageBudgets.length === 0,
        JSON.stringify(detail.stageBudgets),
      )

      // 人工阶段、保留状态与非法 maxRuns 都不能授权额度。
      for (const [state, maxRuns] of [
        ['WORK', 3],
        ['INIT', 3],
        ['BLOCKED', 3],
        ['NOPE', 3],
        ['BUILD', 0],
      ]) {
        await expectHttpError(
          () =>
            ctx.call('POST', `/api/issues/${issue.id}/budget-reset`, {
              expectedVersion: current.version,
              requestKey: `e2e-budget-invalid-${state}-${maxRuns}-${cid()}`,
              state,
              maxRuns,
            }),
          { status: 400 },
        )
      }

      // 首次重置即授权：没有历史 Run，高水位为 0，剩余额度等于 maxRuns。
      const resetKey = `e2e-budget-${cid()}`
      const resetRequest = {
        expectedVersion: current.version,
        requestKey: resetKey,
        state: 'BUILD',
        maxRuns: 3,
      }
      const authorized = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/budget-reset`, resetRequest)).json,
      )
      assertExactFields(
        authorized,
        ['state', 'maxRuns', 'budgetAfterOrdinal', 'usedRuns', 'remainingRuns'],
        'stage budget',
      )
      assert(
        authorized.state === 'BUILD'
          && authorized.maxRuns === 3
          && authorized.budgetAfterOrdinal === '0'
          && authorized.usedRuns === '0'
          && authorized.remainingRuns === '3',
        JSON.stringify(authorized),
      )
      const replayed = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/budget-reset`, resetRequest)).json,
      )
      assert(JSON.stringify(replayed) === JSON.stringify(authorized), JSON.stringify(replayed))
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/budget-reset`, {
            expectedVersion: String(BigInt(resetRequest.expectedVersion) + 1n),
            requestKey: `e2e-budget-stale-${cid()}`,
            state: 'BUILD',
            maxRuns: 4,
          }),
        { status: 409 },
      )

      current = envelopeData((await ctx.call('GET', `/api/issues/${issue.id}`)).json).issue
      const withBudget = await readIssueDetail(ctx, issue.id)
      assert(
        withBudget.stageBudgets.length === 1
          && JSON.stringify(withBudget.stageBudgets[0]) === JSON.stringify(authorized),
        JSON.stringify(withBudget.stageBudgets),
      )
      assert(withBudget.issue.version === current.version, JSON.stringify(withBudget.issue))

      // 提高额度：高水位取服务端当前 nextRunOrdinal - 1，绝不回退重新授权历史 Run。
      const raised = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/budget-reset`, {
            expectedVersion: current.version,
            requestKey: `e2e-budget-raised-${cid()}`,
            state: 'BUILD',
            maxRuns: 7,
          })
        ).json,
      )
      assert(
        raised.maxRuns === 7 && raised.budgetAfterOrdinal === '0' && raised.remainingRuns === '7',
        JSON.stringify(raised),
      )

      const facts = await readActivityPages(ctx, issue.id, 5)
      const budgetFacts = facts.filter((activity) => activity.data?.action === 'RESET')
      assert(
        budgetFacts.length === 2
          && budgetFacts[0].data.state === 'BUILD'
          && budgetFacts[0].data.maxRuns === 3
          && budgetFacts[0].data.previousMaxRuns === 0
          && budgetFacts[1].data.previousMaxRuns === 3
          && budgetFacts[1].data.maxRuns === 7
          && budgetFacts.every(
            (activity) => activity.kind === 'CONTROL' && activity.actorType === 'HUMAN',
          ),
        JSON.stringify(budgetFacts.map((activity) => activity.data)),
      )

      await ctx.call('DELETE', `/api/issues/${issue.id}?expectedVersion=${current.version}`)
      await ctx.call('DELETE', `/api/projects/${project.id}?expectedVersion=${project.version}`)
      await expectHttpError(() => ctx.call('GET', `/api/projects/${project.id}`), { status: 404 })
      project = null
    } finally {
      if (project?.id) {
        await deleteProjectIfPresent(ctx, project.id)
      }
    }
  },
})
