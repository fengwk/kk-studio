import {
  HttpError,
  assert,
  assertDecimalVersion,
  cid,
  envelopeData,
  expectHttpError,
  pageResults,
} from '../lib/http.mjs'
import { waitForQuiescentThread } from '../lib/harness.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'project.issue_lifecycle',
  level: 'L1',
  title: 'Project、Coordinator 与 Issue REST 生命周期',
  docs: '创建/读取/更新/归档 Project，原子启动 Coordinator Session/Thread；创建/更新 Issue，追加幂等输入，增删依赖并读取 detail/snapshot；校验 stale CAS=409，最后深度删除 Project',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const coordinatorAgentName = await firstAgentName(ctx)
    let project
    try {
      project = envelopeData(
        (
          await ctx.call('POST', '/api/projects', {
            title: `E2E Project ${suffix}`,
            description: 'Project REST lifecycle',
            coordinatorAgentName,
          })
        ).json,
      )
      assertCanonicalUuid(project.id, 'project.id')
      assertDecimalVersion(project.version, 'project.version')
      assert(project.version === '0', JSON.stringify(project))

      const listed = pageResults((await ctx.call('GET', '/api/projects')).json)
      assert(listed.some((candidate) => candidate.id === project.id), 'created Project not listed')

      await expectHttpError(
        () =>
          ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: '9',
            title: 'stale update',
            description: project.description,
            coordinatorAgentName,
          }),
        { status: 409 },
      )
      const unchanged = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}`)).json,
      )
      assert(
        unchanged.title === project.title && unchanged.version === '0',
        JSON.stringify(unchanged),
      )

      project = envelopeData(
        (
          await ctx.call('PUT', `/api/projects/${project.id}`, {
            expectedVersion: project.version,
            title: `E2E Project Updated ${suffix}`,
            description: 'Updated through the public REST contract',
            coordinatorAgentName,
          })
        ).json,
      )
      assert(project.version === '1', JSON.stringify(project))

      const accepted = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/commands`, {
            idempotencyKey: cid(),
            message: 'Create an execution plan for this project.',
            threadId: null,
          })
        ).json,
      )
      assertCanonicalUuid(accepted.session.sessionId, 'coordinator session id')
      assertCanonicalUuid(accepted.thread.threadId, 'coordinator thread id')
      assert(accepted.replayed === false, JSON.stringify(accepted))
      await waitForQuiescentThread(ctx, accepted.thread.threadId, {
        timeoutMs: 60_000,
        intervalMs: 100,
      })

      const dependency = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/issues`, {
            title: 'Dependency',
            description: 'Must complete first',
            initialStatus: 'BACKLOG',
          })
        ).json,
      )
      const issue = envelopeData(
        (
          await ctx.call('POST', `/api/projects/${project.id}/issues`, {
            title: 'Primary task',
            description: 'Original specification',
            initialStatus: 'BACKLOG',
          })
        ).json,
      )
      assertCanonicalUuid(issue.id, 'issue.id')
      assert(issue.projectId === project.id && issue.status === 'BACKLOG', JSON.stringify(issue))

      const updatedIssue = envelopeData(
        (
          await ctx.call('PUT', `/api/issues/${issue.id}`, {
            expectedVersion: issue.version,
            title: 'Primary task updated',
            description: 'Updated specification',
          })
        ).json,
      )
      assert(updatedIssue.version === '1', JSON.stringify(updatedIssue))

      const inputKey = `e2e-input-${cid()}`
      const inputRequest = {
        kind: 'HUMAN',
        body: 'Please preserve the public contract.',
        idempotencyKey: inputKey,
      }
      const input = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/inputs`, inputRequest)).json,
      )
      const replayedInput = envelopeData(
        (await ctx.call('POST', `/api/issues/${issue.id}/inputs`, inputRequest)).json,
      )
      assert(
        input.sequence === '1'
          && replayedInput.sequence === input.sequence
          && replayedInput.idempotencyKey === inputKey,
        JSON.stringify({ input, replayedInput }),
      )

      let detail = envelopeData((await ctx.call('GET', `/api/issues/${issue.id}`)).json)
      assert(
        detail.issue.inputSequence === '1'
          && detail.inputs.length === 1
          && detail.inputs[0].body === inputRequest.body,
        JSON.stringify(detail),
      )

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
          && addedDependency.dependsOnIssueId === dependency.id,
        JSON.stringify(addedDependency),
      )

      detail = envelopeData((await ctx.call('GET', `/api/issues/${issue.id}`)).json)
      assert(detail.blocked === true && detail.dependencies.length === 1, JSON.stringify(detail))

      const snapshot = envelopeData(
        (await ctx.call('GET', `/api/projects/${project.id}/snapshot`)).json,
      )
      const snapshotIssue = snapshot.issues.find((candidate) => candidate.issue.id === issue.id)
      assert(
        snapshot.project.id === project.id
          && snapshot.issues.length === 2
          && snapshot.dependencies.length === 1
          && snapshotIssue?.blocked === true,
        JSON.stringify(snapshot),
      )
      assert(
        snapshot.coordinatorSessionId === accepted.session.sessionId
          && snapshot.coordinatorThread?.threadId === accepted.thread.threadId,
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
      detail = envelopeData((await ctx.call('GET', `/api/issues/${issue.id}`)).json)
      assert(detail.blocked === false && detail.dependencies.length === 0, JSON.stringify(detail))

      const canceledIssue = envelopeData(
        (
          await ctx.call('POST', `/api/issues/${issue.id}/cancel`, {
            expectedVersion: detail.issue.version,
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

function assertCanonicalUuid(value, label) {
  assert(
    typeof value === 'string'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value),
    `${label} must be a canonical UUID`,
  )
}

async function firstAgentName(ctx) {
  const agents = pageResults(
    (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')).json,
  )
  assert(agents.length > 0 && typeof agents[0].name === 'string', 'Agent seed required')
  return agents[0].name
}
