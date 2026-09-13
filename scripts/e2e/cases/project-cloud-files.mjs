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

registerCase({
  id: 'cloud_files.text_lifecycle',
  level: 'L1',
  title: 'Cloud Files 目录与文本写读改移、CAS、find 和 grep',
  docs: '在 /knowledge 下创建临时目录和 UTF-8 文本；校验 revision CAS、窗口读取、RE2/J find/grep、节点移动和版本化删除',
  async run(ctx) {
    const rootPath = `/knowledge/e2e-${cid()}`
    const sourcePath = `${rootPath}/notes.md`
    const destinationPath = `${rootPath}/renamed.md`
    try {
      const directory = envelopeData(
        (
          await ctx.call('POST', '/api/cloud/directories', {
            path: rootPath,
            recursive: true,
          })
        ).json,
      )
      assert(
        directory.path === rootPath
          && directory.kind === 'DIRECTORY'
          && directory.version === '0',
        JSON.stringify(directory),
      )

      const created = envelopeData(
        (
          await ctx.call('PUT', '/api/cloud/text', {
            path: sourcePath,
            content: 'alpha\nneedle β\nomega\n',
            expectedRevision: '0',
          })
        ).json,
      )
      assert(created.path === sourcePath && created.revision === '1', JSON.stringify(created))

      await expectHttpError(
        () =>
          ctx.call('PUT', '/api/cloud/text', {
            path: sourcePath,
            content: 'stale write',
            expectedRevision: '0',
          }),
        { status: 409 },
      )

      const edited = envelopeData(
        (
          await ctx.call('PATCH', '/api/cloud/text', {
            path: sourcePath,
            oldString: 'needle β',
            newString: 'needle γ',
            replaceAll: false,
            expectedRevision: created.revision,
          })
        ).json,
      )
      assert(edited.revision === '2', JSON.stringify(edited))

      let snapshot = envelopeData(
        (
          await ctx.call(
            'GET',
            `/api/cloud/files?path=${encodeURIComponent(sourcePath)}&offset=1&limit=2`,
          )
        ).json,
      )
      assert(
        snapshot.node.path === sourcePath
          && snapshot.node.kind === 'TEXT'
          && snapshot.text.revision === '2'
          && snapshot.text.totalLines === 3
          && snapshot.text.nextOffset === 3
          && snapshot.text.lines[1]?.content === 'needle γ',
        JSON.stringify(snapshot),
      )

      const found = envelopeData(
        (
          await ctx.call('POST', '/api/cloud/find', {
            path: rootPath,
            pattern: '*.md',
            limit: 10,
            timeoutSeconds: 5,
          })
        ).json,
      )
      assert(
        found.limited === false
          && found.paths.length === 1
          && found.paths[0] === sourcePath,
        JSON.stringify(found),
      )

      const grepped = envelopeData(
        (
          await ctx.call('POST', '/api/cloud/grep', {
            path: rootPath,
            pattern: 'needle γ',
            literal: true,
            limit: 10,
            timeoutSeconds: 5,
          })
        ).json,
      )
      assert(
        grepped.limited === false
          && grepped.matches.length === 1
          && grepped.matches[0].path === sourcePath
          && grepped.matches[0].lineNumber === 2,
        JSON.stringify(grepped),
      )

      const moved = envelopeData(
        (
          await ctx.call('POST', '/api/cloud/nodes/move', {
            sourcePath,
            destinationPath,
            expectedVersion: snapshot.node.version,
          })
        ).json,
      )
      assert(
        moved.path === destinationPath && moved.version !== snapshot.node.version,
        JSON.stringify(moved),
      )

      snapshot = envelopeData(
        (
          await ctx.call('GET', `/api/cloud/files?path=${encodeURIComponent(destinationPath)}`)
        ).json,
      )
      assert(
        snapshot.text.revision === '2'
          && snapshot.text.lines.some((line) => line.content === 'needle γ'),
        JSON.stringify(snapshot),
      )

      const directorySnapshot = envelopeData(
        (await ctx.call('GET', `/api/cloud/files?path=${encodeURIComponent(rootPath)}`)).json,
      )
      assert(
        directorySnapshot.children.length === 1
          && directorySnapshot.children[0].path === destinationPath,
        JSON.stringify(directorySnapshot),
      )

      await ctx.call(
        'DELETE',
        `/api/cloud/nodes?path=${encodeURIComponent(destinationPath)}`
          + `&expectedVersion=${encodeURIComponent(snapshot.node.version)}`,
      )
      await ctx.call(
        'DELETE',
        `/api/cloud/nodes?path=${encodeURIComponent(rootPath)}`
          + `&expectedVersion=${encodeURIComponent(directorySnapshot.node.version)}`,
      )
    } finally {
      await deleteCloudTreeIfPresent(ctx, rootPath, [sourcePath, destinationPath])
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

async function deleteCloudTreeIfPresent(ctx, rootPath, candidateChildren) {
  for (const path of candidateChildren) {
    await deleteCloudNodeIfPresent(ctx, path)
  }
  await deleteCloudNodeIfPresent(ctx, rootPath)
}

async function deleteCloudNodeIfPresent(ctx, path) {
  try {
    const snapshot = envelopeData(
      (await ctx.call('GET', `/api/cloud/files?path=${encodeURIComponent(path)}`)).json,
    )
    await ctx.call(
      'DELETE',
      `/api/cloud/nodes?path=${encodeURIComponent(path)}`
        + `&expectedVersion=${encodeURIComponent(snapshot.node.version)}`,
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
