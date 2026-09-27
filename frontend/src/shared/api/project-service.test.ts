import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createProjectService } from './project-service'

/**
 * ProjectService 单元测试。
 *
 * <p>测试意图：
 * 验证 ProjectService 的各个方法是否按照后端 REST API 契约正确派发 HTTP 动词、URL、参数与 Payload，
 * 包含工作流配置、YOLO 开关、Stop 终止、UNKNOWN 人工核查、预算重置、活动追加、证据上传以及删除等全部生命周期端点。
 */
describe('projectService', () => {
  const mockClient: HttpClient = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  }

  const service = createProjectService(mockClient)

  it('listProjects: 正确传参 includeArchived 请求 /projects', async () => {
    vi.mocked(mockClient.get).mockResolvedValueOnce([{ id: 'p1' }])
    const result = await service.listProjects(true)
    expect(mockClient.get).toHaveBeenCalledWith('/projects', { params: { includeArchived: true } })
    expect(result).toEqual([{ id: 'p1' }])
  })

  it('createProject: 发起 POST /projects 携带标题与描述', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'p1', title: 'New' })
    const result = await service.createProject({ title: 'New', yoloEnabled: true })
    expect(mockClient.post).toHaveBeenCalledWith('/projects', { title: 'New', yoloEnabled: true })
    expect(result).toEqual({ id: 'p1', title: 'New' })
  })

  it('getProject & snapshot: 对 projectId 编码并请求对应资源', async () => {
    vi.mocked(mockClient.get).mockResolvedValue({ id: 'p/1' })
    await service.getProject('p/1')
    expect(mockClient.get).toHaveBeenCalledWith('/projects/p%2F1')

    await service.getProjectSnapshot('p/1')
    expect(mockClient.get).toHaveBeenCalledWith('/projects/p%2F1/snapshot')
  })

  it('updateWorkflow: PUT /projects/:id/workflow 严格提交工作流', async () => {
    vi.mocked(mockClient.put).mockResolvedValueOnce({ id: 'p1' })
    const workflow = { states: [{ state: 'INIT', name: 'Start' }] }
    await service.updateWorkflow('p1', { expectedVersion: '3', workflow })
    expect(mockClient.put).toHaveBeenCalledWith('/projects/p1/workflow', {
      expectedVersion: '3',
      workflow,
    })
  })

  it('updateYolo: PUT /projects/:id/yolo 提交 yoloEnabled 设置', async () => {
    vi.mocked(mockClient.put).mockResolvedValueOnce({ id: 'p1', yoloEnabled: false })
    await service.updateYolo('p1', { expectedVersion: '4', yoloEnabled: false })
    expect(mockClient.put).toHaveBeenCalledWith('/projects/p1/yolo', {
      expectedVersion: '4',
      yoloEnabled: false,
    })
  })

  it('deleteProject: DELETE /projects/:id 带 expectedVersion 参数', async () => {
    vi.mocked(mockClient.delete).mockResolvedValueOnce(undefined)
    await service.deleteProject('p1', '2')
    expect(mockClient.delete).toHaveBeenCalledWith('/projects/p1', {
      params: { expectedVersion: '2' },
    })
  })

  it('archive/unarchiveProject: POST /projects/:id/archive 与 unarchive', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'p1' })
    await service.archiveProject('p1', { expectedVersion: '1' })
    expect(mockClient.post).toHaveBeenCalledWith('/projects/p1/archive', { expectedVersion: '1' })

    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'p1' })
    await service.unarchiveProject('p1', { expectedVersion: '2' })
    expect(mockClient.post).toHaveBeenCalledWith('/projects/p1/unarchive', { expectedVersion: '2' })
  })

  it('createIssue: POST /projects/:id/issues', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1' })
    await service.createIssue('p1', { title: 'Issue 1', description: 'Desc' })
    expect(mockClient.post).toHaveBeenCalledWith('/projects/p1/issues', {
      title: 'Issue 1',
      description: 'Desc',
    })
  })

  it('getIssue & listActivities: GET /issues/:id 携带游标与分页', async () => {
    vi.mocked(mockClient.get).mockResolvedValueOnce({ issue: { id: 'i1' } })
    await service.getIssue('i1', '10', 20)
    expect(mockClient.get).toHaveBeenCalledWith('/issues/i1', {
      params: { afterSequence: '10', limit: 20 },
    })

    vi.mocked(mockClient.get).mockResolvedValueOnce([])
    await service.listActivities('i1', '5', 10)
    expect(mockClient.get).toHaveBeenCalledWith('/issues/i1/activities', {
      params: { afterSequence: '5', limit: 10 },
    })
  })

  it('transitionIssue: POST /issues/:id/transition 携带 requestKey 和 toState', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', state: 'DESIGN' })
    await service.transitionIssue('i1', {
      expectedVersion: '1',
      requestKey: 'k-1',
      toState: 'DESIGN',
    })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/transition', {
      expectedVersion: '1',
      requestKey: 'k-1',
      toState: 'DESIGN',
    })
  })

  it('blockIssue & recoverIssue: 阻塞与恢复请求', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', state: 'BLOCKED' })
    await service.blockIssue('i1', { expectedVersion: '1', requestKey: 'k-block', reason: 'Blocked by external' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/block', {
      expectedVersion: '1',
      requestKey: 'k-block',
      reason: 'Blocked by external',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', state: 'DESIGN' })
    await service.recoverIssue('i1', { expectedVersion: '2', requestKey: 'k-rec' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/recover', {
      expectedVersion: '2',
      requestKey: 'k-rec',
    })
  })

  it('pauseIssue & resumeIssue: 暂停与继续请求', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', pauseReason: 'USER' })
    await service.pauseIssue('i1', { expectedVersion: '1', requestKey: 'k-p', reason: 'USER', detail: 'Hold' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/pause', {
      expectedVersion: '1',
      requestKey: 'k-p',
      reason: 'USER',
      detail: 'Hold',
    })

    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', pauseReason: null })
    await service.resumeIssue('i1', { expectedVersion: '2', requestKey: 'k-res' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/resume', {
      expectedVersion: '2',
      requestKey: 'k-res',
    })
  })

  it('stopIssue: POST /issues/:id/stop 终止运行', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1' })
    await service.stopIssue('i1', { expectedVersion: '3', requestKey: 'k-stop', detail: 'User stop' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/stop', {
      expectedVersion: '3',
      requestKey: 'k-stop',
      detail: 'User stop',
    })
  })

  it('resolveUnknown: POST /issues/:id/resolve-unknown 人工解除 UNKNOWN 门禁', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', pauseReason: null })
    await service.resolveUnknown('i1', {
      expectedVersion: '4',
      requestKey: 'k-unk',
      verification: 'Checked no side effects',
    })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/resolve-unknown', {
      expectedVersion: '4',
      requestKey: 'k-unk',
      verification: 'Checked no side effects',
    })
  })

  it('deleteIssue: DELETE /issues/:id 带 expectedVersion 参数', async () => {
    vi.mocked(mockClient.delete).mockResolvedValueOnce(undefined)
    await service.deleteIssue('i1', '5')
    expect(mockClient.delete).toHaveBeenCalledWith('/issues/i1', {
      params: { expectedVersion: '5' },
    })
  })

  it('reopenIssue: POST /issues/:id/reopen 重开已完成 Issue', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ id: 'i1', state: 'INIT' })
    await service.reopenIssue('i1', { expectedVersion: '6', requestKey: 'k-reopen' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/reopen', {
      expectedVersion: '6',
      requestKey: 'k-reopen',
    })
  })

  it('resetStageBudget: POST /issues/:id/budget-reset 重置阶段预算', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ state: 'DESIGN', maxRuns: 5 })
    await service.resetStageBudget('i1', {
      expectedVersion: '7',
      requestKey: 'k-budget',
      state: 'DESIGN',
      maxRuns: 5,
    })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/budget-reset', {
      expectedVersion: '7',
      requestKey: 'k-budget',
      state: 'DESIGN',
      maxRuns: 5,
    })
  })

  it('appendActivity: POST /issues/:id/activities 区分 COMMENT / INSTRUCTION', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ issueId: 'i1', sequence: '1' })
    await service.appendActivity('i1', {
      expectedVersion: '8',
      requestKey: 'k-act',
      kind: 'INSTRUCTION',
      body: 'Do something',
    })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/activities', {
      expectedVersion: '8',
      requestKey: 'k-act',
      kind: 'INSTRUCTION',
      body: 'Do something',
    })
  })

  it('addEvidence: POST /issues/:id/evidence 提交 uploadId', async () => {
    vi.mocked(mockClient.post).mockResolvedValueOnce({ issueId: 'i1', blobId: 'b-1' })
    await service.addEvidence('i1', { uploadId: 'u-1' })
    expect(mockClient.post).toHaveBeenCalledWith('/issues/i1/evidence', { uploadId: 'u-1' })
  })
})
