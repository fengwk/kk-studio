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
import { canonicalUuid, newSessionTarget, userMessageCommand } from '../lib/harness.mjs'
import { registerCase } from '../lib/registry.mjs'

/** ProjectDTO 精确字段集合：Project 只拥有设置（YOLO 启动策略 + 打回阈值），没有任何 Coordinator 身份字段。 */
const PROJECT_FIELDS = [
  'id',
  'title',
  'description',
  'yoloEnabled',
  'maxReviewRejections',
  'nextIssueNumber',
  'version',
  'archivedAt',
  'createdAt',
  'updatedAt',
]

/** IssueDTO 精确字段集合：当前要求即正文，执行/审查职责是两种 Agent 角色，没有 inputSequence 之类的旧字段。 */
const ISSUE_FIELDS = [
  'id',
  'projectId',
  'number',
  'title',
  'description',
  'status',
  'assigneeAgentName',
  'reviewerAgentName',
  'version',
  'archivedAt',
  'createdAt',
  'updatedAt',
]

/** IssueDetailDTO 精确字段集合：Activity 只以有界窗口暴露，Agent 归属由 sessions 按 (issueId, agentName) 表达。 */
const ISSUE_DETAIL_FIELDS = [
  'issue',
  'blocked',
  'dependencies',
  'sessions',
  'activities',
  'nextActivityCursor',
  'runs',
  'currentRun',
  'latestRun',
]

/** IssueActivityDTO 精确字段集合：Issue 唯一有序事实流上的一条记录。 */
const ACTIVITY_FIELDS = [
  'issueId',
  'sequence',
  'kind',
  'actorType',
  'actorAgentName',
  'targetRole',
  'runId',
  'submissionRunId',
  'decision',
  'body',
  'idempotencyKey',
  'createdAt',
]

/** Snapshot 精确字段集合：Project 聚合读取面不再暴露 Coordinator Session/Thread。 */
const SNAPSHOT_FIELDS = ['project', 'issues', 'dependencies']
const SNAPSHOT_ISSUE_FIELDS = ['issue', 'blocked', 'reviewRejectionCount', 'currentOrLatestRun']

registerCase({
  id: 'project.issue_lifecycle',
  level: 'L1',
  title: 'Project 设置、Issue 七态与 Activity 事实流',
  docs: '创建 Project 取 YOLO=true/阈值 3 默认设置，无活动 Run 时 CAS 更新设置；创建/更新 Issue，追加幂等 COMMENT/INSTRUCTION 并按游标分页读取 Activity；显式 BLOCKED 与人工恢复，BLOCKED 不可归档；依赖、snapshot、取消、归档与深度删除',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    let project
    try {
      const createdProject = await ctx.call('POST', '/api/projects', {
        title: `E2E Project ${suffix}`,
        description: 'Project REST lifecycle',
      })
      assert(createdProject.status === 201, `create Project status ${createdProject.status}`)
      project = envelopeData(createdProject.json)
      assertExactFields(project, PROJECT_FIELDS, 'project')
      canonicalUuid(project.id, 'project.id')
      assertDecimalVersion(project.version, 'project.version')
      assert(project.version === '0' && project.archivedAt === null, JSON.stringify(project))
      // 省略设置即为项目默认值：新 Issue Agent Branch 开启 YOLO，正式审查打回阈值 3。
      assert(
        project.yoloEnabled === true
          && project.maxReviewRejections === '3'
          && project.nextIssueNumber === '1',
        JSON.stringify(project),
      )

      const listed = pageResults((await ctx.call('GET', '/api/projects')).json)
      assert(listed.some((candidate) => candidate.id === project.id), 'created Project not listed')

      await expectHttpError(
        () =>
          ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: '9',
            title: 'stale update',
          }),
        { status: 409 },
      )
      const unchanged = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}`)).json,
      )
      assert(
        unchanged.title === project.title
          && unchanged.version === '0'
          && unchanged.yoloEnabled === true,
        JSON.stringify(unchanged),
      )

      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: project.version,
            title: `E2E Project Updated ${suffix}`,
            description: 'Updated through the public REST contract',
            yoloEnabled: false,
          })
        ).json,
      )
      assert(
        project.version === '1'
          && project.yoloEnabled === false
          && project.maxReviewRejections === '3',
        JSON.stringify(project),
      )

      // 没有活动 Issue Run 时允许改回项目设置；新值只对下一 Run 生效。
      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: project.version,
            yoloEnabled: true,
            maxReviewRejections: 7,
          })
        ).json,
      )
      assert(
        project.version === '2'
          && project.yoloEnabled === true
          && project.maxReviewRejections === '7'
          && project.title === `E2E Project Updated ${suffix}`,
        JSON.stringify(project),
      )

      // 不存在 Coordinator 命令入口：Project owner 的 command batch 被 owner 判别式拒绝，不产生任何 Session/Thread。
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/harness/command-batches', {
            owner: { type: 'PROJECT', id: project.id },
            target: newSessionTarget({
              sessionId: cid(),
              threadId: cid(),
              rootSettings: { agentName: 'unused-agent' },
              yoloEnabled: false,
            }),
            commands: [userMessageCommand('Plan this project.', cid())],
          }),
        { status: 400, messageIncludes: 'owner.type must be CHAT, CANVAS, or ISSUE_AGENT_SESSION' },
      )

      const dependency = await createIssue(ctx, project.id, {
        title: 'Dependency',
        description: 'Must complete first',
      })
      const issue = await createIssue(ctx, project.id, {
        title: 'Primary task',
        description: 'Original specification',
      })
      // 编号由 Project 单调分配；未指派的 Issue 没有 Agent 归属，也没有 Run。
      assert(
        issue.projectId === project.id
          && issue.status === 'BACKLOG'
          && issue.number === '2'
          && issue.version === '0'
          && issue.assigneeAgentName === null
          && issue.reviewerAgentName === null
          && issue.archivedAt === null
          && dependency.number === '1'
          && dependency.projectId === project.id,
        JSON.stringify({ issue, dependency }),
      )

      // 建单只推进编号，不改 Project CAS 版本。
      const numbered = envelopeData((await ctx.call('GET', `/api/projects/${project.id}`)).json)
      assert(
        numbered.nextIssueNumber === '3' && numbered.version === project.version,
        JSON.stringify(numbered),
      )

      let detail = await readIssueDetail(ctx, issue.id)
      assertExactFields(detail, ISSUE_DETAIL_FIELDS, 'issue detail')
      // BACKLOG 且未指派 Agent 的 Issue 不派发 Run：没有 Run，也没有 (issueId, agentName) 的稳定归属。
      assert(
        detail.blocked === false
          && detail.dependencies.length === 0
          && detail.runs.length === 0
          && detail.currentRun === null
          && detail.latestRun === null
          && detail.sessions.length === 0,
        JSON.stringify(detail),
      )
      // 建单写入初始要求，因此 Activity 首条就是 SPEC_CHANGE，游标只能由实际响应推导。
      assert(
        detail.activities.length === 1
          && detail.activities[0].sequence === '1'
          && detail.activities[0].kind === 'SPEC_CHANGE'
          && detail.activities[0].actorType === 'HUMAN'
          && detail.activities[0].body === 'Original specification'
          && detail.nextActivityCursor === null,
        JSON.stringify(detail.activities),
      )

      const updatedIssue = envelopeData(
        (
          await ctx.call('PUT', `/api/issues/${issue.id}`, {
            expectedVersion: issue.version,
            title: 'Primary task updated',
            description: 'Updated specification',
          })
        ).json,
      )
      assert(
        updatedIssue.version === '1'
          && updatedIssue.title === 'Primary task updated'
          && updatedIssue.status === 'BACKLOG',
        JSON.stringify(updatedIssue),
      )

      const commentKey = `e2e-comment-${cid()}`
      const commentRequest = {
        kind: 'COMMENT',
        body: 'Please preserve the public contract.',
        idempotencyKey: commentKey,
      }
      const appendedComment = await ctx.call(
        'POST',
        `/api/issues/${issue.id}/activities`,
        commentRequest,
      )
      assert(appendedComment.status === 201, `append comment status ${appendedComment.status}`)
      const comment = envelopeData(appendedComment.json)
      assertExactFields(comment, ACTIVITY_FIELDS, 'comment activity')
      assert(
        comment.issueId === issue.id
          && comment.kind === 'COMMENT'
          && comment.actorType === 'HUMAN'
          && comment.targetRole === null
          && comment.body === commentRequest.body
          && comment.idempotencyKey === commentKey,
        JSON.stringify(comment),
      )
      assertDecimalVersion(comment.sequence, 'comment.sequence')

      // 同一幂等键重放命中同一 Activity，不新增事实流记录。
      const replayedComment = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/activities`, commentRequest)).json,
      )
      assert(
        replayedComment.sequence === comment.sequence
          && replayedComment.idempotencyKey === commentKey
          && replayedComment.kind === 'COMMENT',
        JSON.stringify({ comment, replayedComment }),
      )
      const afterComment = envelopeData(
        (
          await ctx.call(
            'GET',
            `/api/issues/${issue.id}/activities?afterSequence=${encodeURIComponent(comment.sequence)}&limit=50`,
          )
        ).json,
      )
      assert(Array.isArray(afterComment) && afterComment.length === 0, JSON.stringify(afterComment))

      const instructionKey = `e2e-instruction-${cid()}`
      const instruction = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/activities`, {
            kind: 'INSTRUCTION',
            targetRole: 'EXECUTOR',
            body: 'Keep the migration reversible.',
            idempotencyKey: instructionKey,
          })
        ).json,
      )
      assert(
        instruction.kind === 'INSTRUCTION'
          && instruction.targetRole === 'EXECUTOR'
          && instruction.actorType === 'HUMAN'
          && instruction.idempotencyKey === instructionKey
          && Number(instruction.sequence) > Number(comment.sequence),
        JSON.stringify(instruction),
      )
      // 定向输入必须显式给出目标职责，且旧的 inputs 写入口已不存在。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/activities`, {
            kind: 'INSTRUCTION',
            body: 'missing target role',
            idempotencyKey: `e2e-invalid-${cid()}`,
          }),
        { status: 400 },
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/inputs`, {
            kind: 'HUMAN',
            body: 'legacy input',
          }),
        { status: 404 },
      )
      // 有界 Activity 窗口：limit 必须在 1..200。
      await expectHttpError(
        () => ctx.call('GET', `/api/issues/${issue.id}?limit=0`),
        { status: 400 },
      )

      detail = await readIssueDetail(ctx, issue.id)
      const addedDependency = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/dependencies`, {
            expectedVersion: detail.issue.version,
            dependsOnIssueId: dependency.id,
          })
        ).json,
      )
      assert(
        addedDependency.issueId === issue.id
          && addedDependency.dependsOnIssueId === dependency.id
          && addedDependency.projectId === project.id,
        JSON.stringify(addedDependency),
      )

      detail = await readIssueDetail(ctx, issue.id)
      // 依赖未满足的 blocked 标记不是 BLOCKED 业务状态：Issue 仍在 BACKLOG 等待人工标记就绪。
      assert(
        detail.blocked === true
          && detail.dependencies.length === 1
          && detail.issue.status === 'BACKLOG',
        JSON.stringify(detail),
      )

      const snapshot = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}/snapshot`)).json,
      )
      assertExactFields(snapshot, SNAPSHOT_FIELDS, 'project snapshot')
      const snapshotIssue = snapshot.issues.find((candidate) => candidate.issue.id === issue.id)
      assertExactFields(snapshotIssue, SNAPSHOT_ISSUE_FIELDS, 'project snapshot issue')
      assert(
        snapshot.project.id === project.id
          && snapshot.issues.length === 2
          && snapshot.dependencies.length === 1
          && snapshotIssue?.blocked === true
          && snapshotIssue?.reviewRejectionCount === '0'
          && snapshotIssue?.currentOrLatestRun === null,
        JSON.stringify(snapshot),
      )

      await expectHttpError(
        () =>
          ctx.call('PUT', `/api/issues/${issue.id}`, {
            expectedVersion: '0',
            title: 'stale issue update',
          }),
        { status: 409 },
      )

      await ctx.call(
        'DELETE',
        `/api/issues/${issue.id}/dependencies/${dependency.id}`
          + `?expectedVersion=${encodeURIComponent(detail.issue.version)}`,
      )
      detail = await readIssueDetail(ctx, issue.id)
      assert(detail.blocked === false && detail.dependencies.length === 0, JSON.stringify(detail))

      // 人工显式阻塞：进入 BLOCKED 等待处理，不允许归档，也不能用旧版本 CAS 恢复。
      const blockReason = 'Waiting for the business decision on scope'
      const blocked = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/block`, {
            expectedVersion: detail.issue.version,
            reason: blockReason,
          })
        ).json,
      )
      assert(
        blocked.status === 'BLOCKED'
          && BigInt(blocked.version) === BigInt(detail.issue.version) + 1n,
        JSON.stringify(blocked),
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/archive`, {
            expectedVersion: blocked.version,
          }),
        { status: 400 },
      )
      const stillBlocked = await readIssueDetail(ctx, issue.id)
      assert(
        stillBlocked.issue.status === 'BLOCKED'
          && stillBlocked.issue.archivedAt === null
          && stillBlocked.issue.version === blocked.version,
        JSON.stringify(stillBlocked),
      )
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/recover`, {
            expectedVersion: detail.issue.version,
            toBacklog: true,
            comment: 'stale recovery',
          }),
        { status: 409 },
      )

      // 人查看打回理由后显式恢复：需先改要求则回 BACKLOG，并记录 RECOVERY 事实。
      const recoverComment = 'Scope must be re-cut before retrying'
      const recovered = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/recover`, {
            expectedVersion: blocked.version,
            toBacklog: true,
            comment: recoverComment,
          })
        ).json,
      )
      assert(
        recovered.status === 'BACKLOG'
          && BigInt(recovered.version) === BigInt(blocked.version) + 1n,
        JSON.stringify(recovered),
      )

      const rewritten = envelopeData(
        (
          await ctx.call('PUT', `/api/issues/${issue.id}`, {
            expectedVersion: recovered.version,
            title: 'Primary task rescoped',
            description: 'Rescoped specification',
          })
        ).json,
      )
      assert(rewritten.status === 'BACKLOG', JSON.stringify(rewritten))

      // 七态是唯一事实源：BACKLOG 不能直接标 DONE。
      await expectHttpError(
        () =>
          ctx.call('POST', `/api/issues/${issue.id}/status`, {
            expectedVersion: rewritten.version,
            status: 'DONE',
          }),
        { status: 400 },
      )

      const facts = await readActivityPages(ctx, issue.id)
      assert(
        facts.map((activity) => activity.kind).join(',')
          === [
            'SPEC_CHANGE',
            'SPEC_CHANGE',
            'COMMENT',
            'INSTRUCTION',
            'SPEC_CHANGE',
            'SPEC_CHANGE',
            'COMMENT',
            'RECOVERY',
            'SPEC_CHANGE',
          ].join(','),
        JSON.stringify(facts),
      )
      assert(
        facts[0].body === 'Original specification'
          && facts[2].body === commentRequest.body
          && facts[3].targetRole === 'EXECUTOR'
          && facts[7].body === recoverComment
          && facts[8].body === 'Rescoped specification',
        JSON.stringify(facts),
      )

      const canceledIssue = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/cancel`, {
            expectedVersion: rewritten.version,
            reason: 'E2E lifecycle cleanup',
          })
        ).json,
      )
      assert(canceledIssue.status === 'CANCELED', JSON.stringify(canceledIssue))

      let archivedIssue = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/archive`, {
            expectedVersion: canceledIssue.version,
          })
        ).json,
      )
      assert(archivedIssue.archivedAt != null, JSON.stringify(archivedIssue))
      archivedIssue = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/unarchive`, {
            expectedVersion: archivedIssue.version,
          })
        ).json,
      )
      assert(archivedIssue.archivedAt == null, JSON.stringify(archivedIssue))

      project = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/archive`, {
            expectedVersion: project.version,
          })
        ).json,
      )
      assert(project.archivedAt != null, JSON.stringify(project))
      const archivedProjects = pageResults(
        (await ctx.call('GET', '/api/projects?includeArchived=true')).json,
      )
      assert(
        archivedProjects.some((candidate) => candidate.id === project.id),
        'archived Project not listed when includeArchived=true',
      )

      project = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/unarchive`, {
            expectedVersion: project.version,
          })
        ).json,
      )
      assert(project.archivedAt == null, JSON.stringify(project))

      await ctx.call(
        'DELETE',
        `/api/projects/${project.id}?expectedVersion=${encodeURIComponent(project.version)}`,
      )
      await expectHttpError(
        () => ctx.call('GET', `/api/projects/${project.id}`),
        { status: 404 },
      )
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
    initialStatus: 'BACKLOG',
  })
  assert(created.status === 201, `create Issue status ${created.status}`)
  const issue = envelopeData(created.json)
  assertExactFields(issue, ISSUE_FIELDS, 'issue')
  canonicalUuid(issue.id, 'issue.id')
  assertDecimalVersion(issue.version, 'issue.version')
  return issue
}

async function readIssueDetail(ctx, issueId) {
  return envelopeData((await ctx.call('GET', `/api/issues/${issueId}`)).json)
}

/**
 * 以有界窗口（{@code afterSequence}/{@code limit}）遍历 Issue Activity 并返回完整事实流。
 *
 * 断言窗口契约：非末页必须取满、`nextActivityCursor` 精确等于当页最后一条 sequence、游标严格前进；
 * 最后与 `/api/issues/{id}/activities` 平铺读取的 sequence 对齐，证明分页不重不漏。
 */
async function readActivityPages(ctx, issueId, pageSize = 2) {
  const paged = []
  let cursor = '0'
  for (let guard = 0; ; guard++) {
    assert(guard < 50, `activity paging did not terminate: ${JSON.stringify(paged)}`)
    const window = envelopeData(
      (
        await ctx.call(
          'GET',
          `/api/issues/${issueId}?afterSequence=${encodeURIComponent(cursor)}&limit=${pageSize}`,
        )
      ).json,
    )
    assert(
      Array.isArray(window.activities) && window.activities.length <= pageSize,
      JSON.stringify(window),
    )
    for (const activity of window.activities) {
      assertExactFields(activity, ACTIVITY_FIELDS, 'issue activity')
      assert(
        Number(activity.sequence) > Number(cursor),
        `activity ${activity.sequence} not after cursor ${cursor}`,
      )
      paged.push(activity)
    }
    if (window.nextActivityCursor === null) {
      break
    }
    assert(
      window.activities.length === pageSize
        && window.nextActivityCursor === window.activities[pageSize - 1].sequence,
      JSON.stringify(window),
    )
    cursor = window.nextActivityCursor
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
