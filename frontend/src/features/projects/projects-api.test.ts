import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createProjectsApi } from './projects-api'

describe('projectsApi', () => {
  const mockProject = {
    id: 'a0000000-0000-0000-0000-000000000001',
    title: 'P1',
    description: '',
    yoloEnabled: true,
    maxReviewRejections: '3',
    nextIssueNumber: '1',
    version: '0',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
  }

  const mockIssue = {
    id: 'b0000000-0000-0000-0000-000000000001',
    projectId: 'a0000000-0000-0000-0000-000000000001',
    number: '1',
    title: 'I1',
    description: '',
    status: 'TODO' as const,
    assigneeAgentName: null,
    reviewerAgentName: null,
    version: '0',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
  }

  const mockClient: HttpClient = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  }

  const api = createProjectsApi({ client: mockClient })

  it('listProjects should query /projects with includeArchived parameter', async () => {
    // 测试意图：验证 listProjects 发送 GET /projects 并正确传递 includeArchived 参数
    vi.mocked(mockClient.get).mockResolvedValueOnce([mockProject])
    const res = await api.listProjects(true)
    expect(mockClient.get).toHaveBeenCalledWith('/projects', {
      params: { includeArchived: true },
    })
    expect(res).toHaveLength(1)
    expect(res[0].id).toBe(mockProject.id)
  })

  it('createProject should post to /projects with yoloEnabled and maxReviewRejections', async () => {
    // 测试意图：验证 createProject 发送 POST /projects 并携带包含 yoloEnabled 与 maxReviewRejections 的请求
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockProject)
    const req = { title: 'P1', description: 'desc', yoloEnabled: true, maxReviewRejections: 3 }
    const res = await api.createProject(req)
    expect(mockClient.post).toHaveBeenCalledWith('/projects', req)
    expect(res.title).toBe('P1')
    expect(res.yoloEnabled).toBe(true)
    expect(res.maxReviewRejections).toBe('3')
  })

  it('getProject should query /projects/:id', async () => {
    // 测试意图：验证 getProject 请求路径包含编码后的 projectId
    vi.mocked(mockClient.get).mockResolvedValueOnce(mockProject)
    const res = await api.getProject(mockProject.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/projects/${mockProject.id}`)
    expect(res.id).toBe(mockProject.id)
  })

  it('updateProject should put to /projects/:id with expectedVersion', async () => {
    // 测试意图：验证 updateProject 执行 PUT 并传递 expectedVersion 及新配置项
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockProject, version: '1', maxReviewRejections: '5' })
    const req = { expectedVersion: '0', title: 'New Title', maxReviewRejections: 5 }
    const res = await api.updateProject(mockProject.id, req)
    expect(mockClient.put).toHaveBeenCalledWith(`/projects/${mockProject.id}`, req)
    expect(res.version).toBe('1')
  })

  it('deleteProject should delete with query param expectedVersion', async () => {
    // 测试意图：验证 deleteProject 发送 DELETE 并将 expectedVersion 作为 URL 参数
    vi.mocked(mockClient.delete).mockResolvedValueOnce(undefined)
    await api.deleteProject(mockProject.id, '2')
    expect(mockClient.delete).toHaveBeenCalledWith(`/projects/${mockProject.id}`, {
      params: { expectedVersion: '2' },
    })
  })

  it('archiveProject and unarchiveProject should post to respective sub-resources', async () => {
    // 测试意图：验证归档与解归档请求投递到正确的子资源路径
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      ...mockProject,
      archivedAt: '2026-09-14T00:00:00Z',
    })
    await api.archiveProject(mockProject.id, { expectedVersion: '1' })
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/archive`, {
      expectedVersion: '1',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce(mockProject)
    await api.unarchiveProject(mockProject.id, { expectedVersion: '2' })
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/unarchive`, {
      expectedVersion: '2',
    })
  })

  it('getProjectSnapshot should query /projects/:id/snapshot and decode', async () => {
    // 测试意图：验证读取权威 Snapshot 面，正确解码包含 reviewRejectionCount 的 Issue 快照
    const snapshotRaw = {
      project: mockProject,
      issues: [
        {
          issue: mockIssue,
          blocked: true,
          reviewRejectionCount: '3',
          currentOrLatestRun: null,
        },
      ],
      dependencies: [],
    }
    vi.mocked(mockClient.get).mockResolvedValueOnce(snapshotRaw)
    const res = await api.getProjectSnapshot(mockProject.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/projects/${mockProject.id}/snapshot`)
    expect(res.project.id).toBe(mockProject.id)
    expect(res.issues).toHaveLength(1)
    expect(res.issues[0].blocked).toBe(true)
    expect(res.issues[0].reviewRejectionCount).toBe('3')
  })

  it('createIssue should post to /projects/:id/issues', async () => {
    // 测试意图：验证创建 Issue 请求构造
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockIssue)
    const issueReq = { title: 'New Issue', initialStatus: 'BACKLOG' as const }
    const res = await api.createIssue(mockProject.id, issueReq)
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/issues`, issueReq)
    expect(res.id).toBe(mockIssue.id)
  })

  it('getIssue should query /issues/:id with paging params', async () => {
    // 测试意图：验证获取单个 Issue 详情包含 sessions 与 activities 并传递 afterSequence
    const issueDetailRaw = {
      issue: mockIssue,
      blocked: false,
      dependencies: [],
      sessions: [],
      activities: [],
      nextActivityCursor: null,
      runs: [],
      currentRun: null,
      latestRun: null,
    }
    vi.mocked(mockClient.get).mockResolvedValueOnce(issueDetailRaw)
    const res = await api.getIssue(mockIssue.id, '10', 20)
    expect(mockClient.get).toHaveBeenCalledWith(`/issues/${mockIssue.id}`, {
      params: { afterSequence: '10', limit: 20 },
    })
    expect(res.issue.id).toBe(mockIssue.id)
  })

  it('listActivities should query /issues/:id/activities', async () => {
    // 测试意图：验证分页查询 Issue 活动流
    const activityRaw = {
      issueId: mockIssue.id,
      sequence: '1',
      kind: 'INSTRUCTION',
      actorType: 'HUMAN',
      actorAgentName: null,
      targetRole: 'EXECUTOR',
      runId: null,
      submissionRunId: null,
      decision: null,
      body: 'Do X',
      idempotencyKey: null,
      createdAt: '2026-09-14T00:00:00Z',
    }
    vi.mocked(mockClient.get).mockResolvedValueOnce([activityRaw])
    const res = await api.listActivities(mockIssue.id, '0', 50)
    expect(mockClient.get).toHaveBeenCalledWith(`/issues/${mockIssue.id}/activities`, {
      params: { afterSequence: '0', limit: 50 },
    })
    expect(res).toHaveLength(1)
    expect(res[0].body).toBe('Do X')
  })

  it('updateIssue should put to /issues/:id', async () => {
    // 测试意图：验证修改 Issue 属性
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockIssue, title: 'Updated' })
    const res = await api.updateIssue(mockIssue.id, {
      expectedVersion: '0',
      title: 'Updated',
    })
    expect(mockClient.put).toHaveBeenCalledWith(`/issues/${mockIssue.id}`, {
      expectedVersion: '0',
      title: 'Updated',
    })
    expect(res.title).toBe('Updated')
  })

  it('changeIssueStatus should post to /issues/:id/status', async () => {
    // 测试意图：验证状态机流转请求
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, status: 'BACKLOG' })
    const res = await api.changeIssueStatus(mockIssue.id, {
      expectedVersion: '0',
      status: 'BACKLOG',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/status`, {
      expectedVersion: '0',
      status: 'BACKLOG',
    })
    expect(res.status).toBe('BACKLOG')
  })

  it('blockIssue should post to /issues/:id/block with reason', async () => {
    // 测试意图：验证人工阻塞 Issue 并发送必填 reason
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, status: 'BLOCKED' })
    const res = await api.blockIssue(mockIssue.id, {
      expectedVersion: '1',
      reason: 'Wait for customer clarification',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/block`, {
      expectedVersion: '1',
      reason: 'Wait for customer clarification',
    })
    expect(res.status).toBe('BLOCKED')
  })

  it('recoverIssue should post to /issues/:id/recover with toBacklog option', async () => {
    // 测试意图：验证人工恢复 Issue，可指定恢复到 TODO (toBacklog: false) 或 BACKLOG (toBacklog: true)
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, status: 'TODO' })
    const res = await api.recoverIssue(mockIssue.id, {
      expectedVersion: '2',
      toBacklog: false,
      comment: 'Resume working',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/recover`, {
      expectedVersion: '2',
      toBacklog: false,
      comment: 'Resume working',
    })
    expect(res.status).toBe('TODO')
  })

  it('addIssueDependency and removeIssueDependency should construct correct requests', async () => {
    // 测试意图：验证依赖增删操作与 expectedVersion 传递
    const depMock = {
      issueId: mockIssue.id,
      dependsOnIssueId: 'b0000000-0000-0000-0000-000000000002',
      projectId: mockProject.id,
      createdAt: '2026-09-14T00:00:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(depMock)
    await api.addIssueDependency(mockIssue.id, {
      expectedVersion: '0',
      dependsOnIssueId: depMock.dependsOnIssueId,
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/dependencies`, {
      expectedVersion: '0',
      dependsOnIssueId: depMock.dependsOnIssueId,
    })

    vi.mocked(mockClient.delete).mockResolvedValueOnce(undefined)
    await api.removeIssueDependency(mockIssue.id, depMock.dependsOnIssueId, '1')
    expect(mockClient.delete).toHaveBeenCalledWith(
      `/issues/${mockIssue.id}/dependencies/${depMock.dependsOnIssueId}`,
      { params: { expectedVersion: '1' } },
    )
  })

  it('appendIssueActivity should post to /issues/:id/activities with targetRole', async () => {
    // 测试意图：验证追加 Issue Activity（带 targetRole）
    const activityMock = {
      issueId: mockIssue.id,
      sequence: '1',
      kind: 'INSTRUCTION',
      actorType: 'HUMAN',
      actorAgentName: null,
      targetRole: 'EXECUTOR',
      runId: null,
      submissionRunId: null,
      decision: null,
      body: 'Focus on performance',
      idempotencyKey: null,
      createdAt: '2026-09-14T00:00:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(activityMock)
    const res = await api.appendIssueActivity(mockIssue.id, {
      body: 'Focus on performance',
      targetRole: 'EXECUTOR',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/activities`, {
      body: 'Focus on performance',
      targetRole: 'EXECUTOR',
    })
    expect(res.body).toBe('Focus on performance')
    expect(res.targetRole).toBe('EXECUTOR')
  })

  it('reviewIssue should post review decision', async () => {
    // 测试意图：验证人工 Review 接口调用（返回 void）
    vi.mocked(mockClient.post).mockResolvedValueOnce(undefined)
    const reviewReq = { decision: 'APPROVE' as const, reason: 'LGTM' }
    await api.reviewIssue(mockIssue.id, reviewReq)
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/review`, reviewReq)
  })

  it('cancelIssue and retryIssue should call appropriate endpoints', async () => {
    // 测试意图：验证取消 Issue 与重试 Run 请求
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, status: 'CANCELED' })
    await api.cancelIssue(mockIssue.id, { expectedVersion: '1', reason: 'No longer needed' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/cancel`, {
      expectedVersion: '1',
      reason: 'No longer needed',
    })

    const retryActivityMock = {
      issueId: mockIssue.id,
      sequence: '2',
      kind: 'RETRY',
      actorType: 'HUMAN',
      actorAgentName: null,
      targetRole: null,
      runId: null,
      submissionRunId: null,
      decision: null,
      body: 'RETRY',
      idempotencyKey: 'c0000000-0000-0000-0000-000000000002',
      createdAt: '2026-09-14T00:02:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(retryActivityMock)
    const res = await api.retryIssue(mockIssue.id, {
      idempotencyKey: retryActivityMock.idempotencyKey,
      verification: '已核对残留调用',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/retry`, {
      idempotencyKey: retryActivityMock.idempotencyKey,
      verification: '已核对残留调用',
    })
    expect(res.kind).toBe('RETRY')
  })

  it('archiveIssue and unarchiveIssue should call appropriate endpoints', async () => {
    // 测试意图：验证单个 Issue 归档与解归档接口
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      ...mockIssue,
      archivedAt: '2026-09-14T00:00:00Z',
    })
    await api.archiveIssue(mockIssue.id, { expectedVersion: '3' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/archive`, {
      expectedVersion: '3',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce(mockIssue)
    await api.unarchiveIssue(mockIssue.id, { expectedVersion: '4' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/unarchive`, {
      expectedVersion: '4',
    })
  })

  it('addIssueEvidence should post to /issues/:issueId/evidence and return decoded evidence', async () => {
    // 测试意图：验证 addIssueEvidence 向 /issues/:issueId/evidence 提交 uploadId 并正确解码 IssueEvidenceDTO
    const rawEvidence = {
      issueId: mockIssue.id,
      blobId: 'blob-00000000-0000-0000-0000-000000000001',
      uri: 'kkstudio:/resources/blob-00000000-0000-0000-0000-000000000001',
      origin: 'HUMAN',
      name: 'screenshot.png',
      runId: null,
      publishedAt: '2026-09-20T12:00:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(rawEvidence)
    const res = await api.addIssueEvidence(mockIssue.id, {
      uploadId: 'upload-00000000-0000-0000-0000-000000000001',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/evidence`, {
      uploadId: 'upload-00000000-0000-0000-0000-000000000001',
    })
    expect(res.blobId).toBe(rawEvidence.blobId)
    expect(res.origin).toBe('HUMAN')
    expect(res.name).toBe('screenshot.png')
  })
})
