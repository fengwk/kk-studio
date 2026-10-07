import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createProjectsApi } from './projects-api'

describe('projectsApi', () => {
  const mockProject = {
    id: 'a0000000-0000-0000-0000-000000000001',
    title: 'P1',
    description: '',
    workflow: {
      states: [
        { state: 'INIT', name: '待开始', next: ['DONE'] },
        { state: 'DONE', name: '完成' },
      ],
    },
    yoloEnabled: true,
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
    state: 'INIT',
    blockedFromState: null,
    blockReason: null,
    pauseReason: null,
    pauseDetail: null,
    version: '0',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
  }

  const mockClient: HttpClient = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  }

  const api = createProjectsApi({ client: mockClient })

  it('listProjects should query /projects with includeArchived parameter', async () => {
    // 测试意图：验证 listProjects 发送 GET /projects 并正确传递 includeArchived 参数并由 decodeProjectList 解码
    vi.mocked(mockClient.get).mockResolvedValueOnce([mockProject])
    const res = await api.listProjects(true)
    expect(mockClient.get).toHaveBeenCalledWith('/projects', {
      params: { includeArchived: true },
    })
    expect(res).toHaveLength(1)
    expect(res[0].id).toBe(mockProject.id)
  })

  it('createProject should post to /projects with yoloEnabled', async () => {
    // 测试意图：验证 createProject 发送 POST /projects 并携带包含 yoloEnabled 的请求
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockProject)
    const req = { title: 'P1', description: 'desc', yoloEnabled: true }
    const res = await api.createProject(req)
    expect(mockClient.post).toHaveBeenCalledWith('/projects', req)
    expect(res.title).toBe('P1')
    expect(res.yoloEnabled).toBe(true)
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
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockProject, version: '1' })
    const req = { expectedVersion: '0', title: 'New Title' }
    const res = await api.updateProject(mockProject.id, req)
    expect(mockClient.put).toHaveBeenCalledWith(`/projects/${mockProject.id}`, req)
    expect(res.version).toBe('1')
  })

  it('updateWorkflow should put to /projects/:id/workflow', async () => {
    // 测试意图：验证 updateWorkflow 执行 PUT /projects/:id/workflow 严格更新工作流
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockProject, version: '2' })
    const workflow = {
      states: [
        { state: 'INIT', name: 'Start', next: ['DONE'] },
        { state: 'DONE', name: 'Finish' },
      ],
    }
    const res = await api.updateWorkflow(mockProject.id, {
      expectedVersion: '1',
      workflow,
    })
    expect(mockClient.put).toHaveBeenCalledWith(`/projects/${mockProject.id}/workflow`, {
      expectedVersion: '1',
      workflow,
    })
    expect(res.version).toBe('2')
  })

  it('updateYolo should put to /projects/:id/yolo', async () => {
    // 测试意图：验证 updateYolo 执行 PUT /projects/:id/yolo 切换执行策略
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockProject, yoloEnabled: false })
    const res = await api.updateYolo(mockProject.id, {
      expectedVersion: '2',
      yoloEnabled: false,
    })
    expect(mockClient.put).toHaveBeenCalledWith(`/projects/${mockProject.id}/yolo`, {
      expectedVersion: '2',
      yoloEnabled: false,
    })
    expect(res.yoloEnabled).toBe(false)
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

  it('getProjectSnapshot should query /projects/:id/snapshot', async () => {
    // 测试意图：验证聚合快照读取
    vi.mocked(mockClient.get).mockResolvedValueOnce({
      project: mockProject,
      issues: [{ issue: mockIssue, currentOrLatestRun: null }],
      referencedStateCodes: ['INIT'],
    })
    const snap = await api.getProjectSnapshot(mockProject.id)
    expect(mockClient.get).toHaveBeenCalledWith(`/projects/${mockProject.id}/snapshot`)
    expect(snap.issues).toHaveLength(1)
  })

  it('createIssue should post to /projects/:id/issues', async () => {
    // 测试意图：验证 createIssue 发送 POST /projects/:id/issues
    vi.mocked(mockClient.post).mockResolvedValueOnce(mockIssue)
    const req = { title: 'Issue 1', description: 'Desc' }
    const res = await api.createIssue(mockProject.id, req)
    expect(mockClient.post).toHaveBeenCalledWith(`/projects/${mockProject.id}/issues`, req)
    expect(res.id).toBe(mockIssue.id)
  })

  it('getIssue and listActivities should support pagination', async () => {
    // 测试意图：验证 Issue 详情和活动列表查询支持游标和分页
    vi.mocked(mockClient.get).mockResolvedValueOnce({
      issue: mockIssue,
      activities: [],
      nextActivityCursor: null,
      runs: [],
      currentRun: null,
      latestRun: null,
      stageBudgets: [],
      agentThreads: [],
    })
    await api.getIssue(mockIssue.id, '10', 20)
    expect(mockClient.get).toHaveBeenCalledWith(`/issues/${mockIssue.id}`, {
      params: { afterSequence: '10', limit: 20 },
    })

    vi.mocked(mockClient.get).mockResolvedValueOnce([])
    await api.listActivities(mockIssue.id, '5', 50)
    expect(mockClient.get).toHaveBeenCalledWith(`/issues/${mockIssue.id}/activities`, {
      params: { afterSequence: '5', limit: 50 },
    })
  })

  it('updateIssue should put to /issues/:id', async () => {
    // 测试意图：验证 updateIssue 发送 PUT
    vi.mocked(mockClient.put).mockResolvedValueOnce({ ...mockIssue, version: '1' })
    const res = await api.updateIssue(mockIssue.id, {
      expectedVersion: '0',
      title: 'Updated',
    })
    expect(mockClient.put).toHaveBeenCalledWith(`/issues/${mockIssue.id}`, {
      expectedVersion: '0',
      title: 'Updated',
    })
    expect(res.version).toBe('1')
  })

  it('transitionIssue should post to /issues/:id/transition', async () => {
    // 测试意图：验证 transitionIssue 状态流转携带 requestKey 和 toState
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, state: 'WORK' })
    const res = await api.transitionIssue(mockIssue.id, {
      expectedVersion: '1',
      requestKey: 'k-trans',
      toState: 'WORK',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/transition`, {
      expectedVersion: '1',
      requestKey: 'k-trans',
      toState: 'WORK',
    })
    expect(res.state).toBe('WORK')
  })

  it('blockIssue and recoverIssue should post to block/recover sub-resources', async () => {
    // 测试意图：验证阻塞与恢复接口调用
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, state: 'BLOCKED' })
    await api.blockIssue(mockIssue.id, {
      expectedVersion: '1',
      requestKey: 'k-block',
      reason: 'Wait on vendor',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/block`, {
      expectedVersion: '1',
      requestKey: 'k-block',
      reason: 'Wait on vendor',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, state: 'INIT' })
    await api.recoverIssue(mockIssue.id, {
      expectedVersion: '2',
      requestKey: 'k-rec',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/recover`, {
      expectedVersion: '2',
      requestKey: 'k-rec',
    })
  })

  it('pauseIssue and resumeIssue should control execution state', async () => {
    // 测试意图：验证人工暂停与继续执行操作
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, pauseReason: 'USER' })
    await api.pauseIssue(mockIssue.id, {
      expectedVersion: '2',
      requestKey: 'k-pause',
      reason: 'USER',
      detail: 'Pausing manually',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/pause`, {
      expectedVersion: '2',
      requestKey: 'k-pause',
      reason: 'USER',
      detail: 'Pausing manually',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, pauseReason: null })
    await api.resumeIssue(mockIssue.id, {
      expectedVersion: '3',
      requestKey: 'k-res',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/resume`, {
      expectedVersion: '3',
      requestKey: 'k-res',
    })
  })

  it('stopIssue should post to /issues/:id/stop', async () => {
    // 测试意图：验证 stopIssue 终止运行
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, pauseReason: 'USER' })
    await api.stopIssue(mockIssue.id, {
      expectedVersion: '3',
      requestKey: 'k-stop',
      detail: 'Stopped by user',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/stop`, {
      expectedVersion: '3',
      requestKey: 'k-stop',
      detail: 'Stopped by user',
    })
  })

  it('resolveUnknown should post to /issues/:id/resolve-unknown', async () => {
    // 测试意图：验证 resolveUnknown 解除 UNKNOWN 门禁
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, pauseReason: null })
    await api.resolveUnknown(mockIssue.id, {
      expectedVersion: '4',
      requestKey: 'k-unk',
      verification: 'Verified no external changes',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/resolve-unknown`, {
      expectedVersion: '4',
      requestKey: 'k-unk',
      verification: 'Verified no external changes',
    })
  })

  it('deleteIssue should delete with query param expectedVersion', async () => {
    // 测试意图：验证 deleteIssue 发送 DELETE
    vi.mocked(mockClient.delete).mockResolvedValueOnce(undefined)
    await api.deleteIssue(mockIssue.id, '4')
    expect(mockClient.delete).toHaveBeenCalledWith(`/issues/${mockIssue.id}`, {
      params: { expectedVersion: '4' },
    })
  })

  it('reopenIssue should post to /issues/:id/reopen', async () => {
    // 测试意图：验证 reopenIssue 重开
    vi.mocked(mockClient.post).mockResolvedValueOnce({ ...mockIssue, state: 'INIT' })
    await api.reopenIssue(mockIssue.id, {
      expectedVersion: '5',
      requestKey: 'k-reopen',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/reopen`, {
      expectedVersion: '5',
      requestKey: 'k-reopen',
    })
  })

  it('resetStageBudget should post to /issues/:id/budget-reset', async () => {
    // 测试意图：验证 resetStageBudget 重置阶段预算
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      state: 'WORK',
      maxRuns: 5,
      budgetAfterOrdinal: '2',
      usedRuns: '0',
      remainingRuns: '5',
    })
    const budget = await api.resetStageBudget(mockIssue.id, {
      expectedVersion: '6',
      requestKey: 'k-budget',
      state: 'WORK',
      maxRuns: 5,
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/budget-reset`, {
      expectedVersion: '6',
      requestKey: 'k-budget',
      state: 'WORK',
      maxRuns: 5,
    })
    expect(budget.maxRuns).toBe(5)
  })

  it('archiveIssue and unarchiveIssue should post to sub-resources', async () => {
    // 测试意图：验证 Issue 归档与取消归档
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      ...mockIssue,
      archivedAt: '2026-09-14T00:00:00Z',
    })
    await api.archiveIssue(mockIssue.id, { expectedVersion: '7' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/archive`, {
      expectedVersion: '7',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce(mockIssue)
    await api.unarchiveIssue(mockIssue.id, { expectedVersion: '8' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/unarchive`, {
      expectedVersion: '8',
    })
  })

  it('appendIssueActivity should post to /issues/:id/activities', async () => {
    // 测试意图：验证 appendIssueActivity 发送评论或指令活动
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      issueId: mockIssue.id,
      sequence: '2',
      kind: 'INSTRUCTION',
      actorType: 'HUMAN',
      actorAgentName: null,
      runId: null,
      body: 'Do test',
      data: null,
      createdAt: '2026-09-14T00:00:00Z',
    })
    const res = await api.appendIssueActivity(mockIssue.id, {
      expectedVersion: '9',
      requestKey: 'k-act',
      kind: 'INSTRUCTION',
      body: 'Do test',
    })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/activities`, {
      expectedVersion: '9',
      requestKey: 'k-act',
      kind: 'INSTRUCTION',
      body: 'Do test',
    })
    expect(res.kind).toBe('INSTRUCTION')
  })

  it('addIssueEvidence should post to /issues/:id/evidence', async () => {
    // 测试意图：验证 addIssueEvidence 发送公开证据上传
    vi.mocked(mockClient.post).mockResolvedValueOnce({
      issueId: mockIssue.id,
      blobId: 'blob-1',
      uri: 'kkstudio:/resources/blob-1',
      name: 'report.txt',
      actorAgentName: null,
      runId: null,
      createdAt: '2026-09-14T00:00:00Z',
    })
    const res = await api.addIssueEvidence(mockIssue.id, { uploadId: 'u-1' })
    expect(mockClient.post).toHaveBeenCalledWith(`/issues/${mockIssue.id}/evidence`, {
      uploadId: 'u-1',
    })
    expect(res.name).toBe('report.txt')
  })
})
