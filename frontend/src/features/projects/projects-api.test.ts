import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createProjectsApi } from './projects-api'

describe('projectsApi', () => {
  const mockProject = {
    id: 'a0000000-0000-0000-0000-000000000001',
    title: 'P1',
    description: '',
    coordinatorAgentName: 'coordinator-agent',
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
    status: 'TODO',
    assigneeAgentName: null,
    reviewerAgentName: null,
    version: '0',
    specRevision: '0',
    inputSequence: '0',
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

  it('createProject should post to /projects', async () => {
    // 测试意图：验证 createProject 发送 POST /projects 并携带创建请求负载
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockProject)
    const req = { title: 'P1', description: 'desc', coordinatorAgentName: 'coord' }
    const res = await api.createProject(req)
    expect(mockClient.post).toHaveBeenCalledWith('/projects', req)
    expect(res.title).toBe('P1')
  })

  it('getProject should query /projects/:id', async () => {
    // 测试意图：验证 getProject 请求路径包含编码后的 projectId
    vi.mocked(mockClient.get).mockResolvedValueOnce(mockProject)
    const res = await api.getProject(mockProject.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/projects/${mockProject.id}`)
    expect(res.id).toBe(mockProject.id)
  })

  it('updateProject should put to /projects/:id with expectedVersion', async () => {
    // 测试意图：验证 updateProject 执行 PUT 并传递 expectedVersion
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockProject, version: '1' })
    const req = { expectedVersion: '0', title: 'New Title' }
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

  it('sendProjectCommand should post commands to /projects/:id/commands', async () => {
    // 测试意图：验证向 Coordinator Session 发送命令接口的正确路径与 body
    const acceptedMock = {
      session: { sessionId: 'f0000000-0000-0000-0000-000000000001' },
      thread: { threadId: 'f0000000-0000-0000-0000-000000000002' },
      acceptedCommands: [],
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(acceptedMock)
    const cmdReq = {
      idempotencyKey: 'c0000000-0000-0000-0000-000000000001',
      message: 'Create plan',
    }
    const res = await api.sendProjectCommand(mockProject.id, cmdReq)
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/commands`, cmdReq)
    expect(res).toEqual(acceptedMock)
  })

  it('getProjectSnapshot should query /projects/:id/snapshot and decode', async () => {
    // 测试意图：验证读取权威 Snapshot 面
    const snapshotRaw = {
      project: mockProject,
      issues: [],
      dependencies: [],
      coordinatorSessionId: null,
      coordinatorSession: null,
      coordinatorThread: null,
    }
    vi.mocked(mockClient.get).mockResolvedValueOnce(snapshotRaw)
    const res = await api.getProjectSnapshot(mockProject.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/projects/${mockProject.id}/snapshot`)
    expect(res.project.id).toBe(mockProject.id)
    expect(res.issues).toHaveLength(0)
  })

  it('createIssue should post to /projects/:id/issues', async () => {
    // 测试意图：验证创建 Issue 请求构造
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockIssue)
    const issueReq = { title: 'New Issue', initialStatus: 'BACKLOG' as const }
    const res = await api.createIssue(mockProject.id, issueReq)
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/issues`, issueReq)
    expect(res.id).toBe(mockIssue.id)
  })

  it('getIssue should query /issues/:id', async () => {
    // 测试意图：验证获取单个 Issue 详情
    const issueDetailRaw = {
      issue: mockIssue,
      blocked: false,
      dependencies: [],
      inputs: [],
      runs: [],
      currentRun: null,
      latestRun: null,
    }
    vi.mocked(mockClient.get).mockResolvedValueOnce(issueDetailRaw)
    const res = await api.getIssue(mockIssue.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/issues/${mockIssue.id}`)
    expect(res.issue.id).toBe(mockIssue.id)
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

  it('appendIssueInput should post to /issues/:id/inputs', async () => {
    // 测试意图：验证人类向 Issue 追加输入
    const inputMock = {
      issueId: mockIssue.id,
      sequence: '1',
      kind: 'HUMAN',
      body: 'Answer',
      idempotencyKey: null,
      createdAt: '2026-09-14T00:00:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(inputMock)
    const res = await api.appendIssueInput(mockIssue.id, { body: 'Answer' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/inputs`, {
      body: 'Answer',
    })
    expect(res.body).toBe('Answer')
  })

  it('reviewIssue should post review decision', async () => {
    // 测试意图：验证人工 Review 接口调用与返回值解析
    const runSummaryMock = {
      id: 'd0000000-0000-0000-0000-000000000001',
      issueId: mockIssue.id,
      ordinal: '2',
      role: 'REVIEWER',
      actorType: 'HUMAN',
      agentName: null,
      submissionRunId: null,
      status: 'COMPLETED',
      outcome: 'APPROVED',
      waitingReason: null,
      createdAt: '2026-09-14T00:00:00Z',
      completedAt: '2026-09-14T00:01:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(runSummaryMock)
    const reviewReq = { decision: 'APPROVE' as const, summary: 'LGTM' }
    const res = await api.reviewIssue(mockIssue.id, reviewReq)
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/review`, reviewReq)
    expect(res.outcome).toBe('APPROVED')
  })

  it('cancelIssue and retryIssue should call appropriate endpoints', async () => {
    // 测试意图：验证取消 Issue 与重试 Run 请求
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, status: 'CANCELED' })
    await api.cancelIssue(mockIssue.id, { expectedVersion: '1', reason: 'No longer needed' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/cancel`, {
      expectedVersion: '1',
      reason: 'No longer needed',
    })

    const retryInputMock = {
      issueId: mockIssue.id,
      sequence: '2',
      kind: 'SYSTEM',
      body: 'RETRY',
      idempotencyKey: 'c0000000-0000-0000-0000-000000000002',
      createdAt: '2026-09-14T00:02:00Z',
    }
    vi.mocked(mockClient.post).mockResolvedValueOnce(retryInputMock)
    await api.retryIssue(mockIssue.id, { idempotencyKey: retryInputMock.idempotencyKey })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/retry`, {
      idempotencyKey: retryInputMock.idempotencyKey,
    })
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
})
