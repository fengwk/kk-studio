import { request } from '@/shared/api/client'
import type {
  AddIssueEvidenceRequestDTO,
  AppendIssueActivityRequestDTO,
  BlockIssueRequestDTO,
  CreateIssueRequestDTO,
  CreateProjectRequestDTO,
  IssueActivityDTO,
  IssueDetailDTO,
  IssueDTO,
  IssueEvidenceDTO,
  IssueStageBudgetDTO,
  ProjectDTO,
  ProjectSnapshotDTO,
  ProjectVersionRequestDTO,
  RecoverIssueRequestDTO,
  ReopenIssueRequestDTO,
  ResetStageBudgetRequestDTO,
  ResumeIssueRequestDTO,
  StopIssueRequestDTO,
  TransitionIssueRequestDTO,
  UpdateIssueRequestDTO,
  UpdateProjectRequestDTO,
  UpdateProjectWorkflowRequestDTO,
  UpdateProjectYoloRequestDTO,
} from '@/shared/api/generated/contracts'

/**
 * Project 与 Issue 产品 HTTP 客户端：按生成的路由键委托 shared/api/client 的 request()。
 *
 * <p>Issue 没有归档状态；Project 的归档是项目生命周期的一部分。
 */
export function createProjectsApi() {
  return {
    listProjects: (includeArchived = false): Promise<ProjectDTO[]> =>
      request('GET /api/project/projects', { query: { includeArchived } }),
    createProject: (body: CreateProjectRequestDTO): Promise<ProjectDTO> =>
      request('POST /api/project/projects', { body }),
    getProject: (projectId: string): Promise<ProjectDTO> =>
      request('GET /api/project/projects/{projectId}', { path: { projectId } }),
    updateProject: (projectId: string, body: UpdateProjectRequestDTO): Promise<ProjectDTO> =>
      request('PUT /api/project/projects/{projectId}', { path: { projectId }, body }),
    updateWorkflow: (
      projectId: string,
      body: UpdateProjectWorkflowRequestDTO,
    ): Promise<ProjectDTO> =>
      request('PUT /api/project/projects/{projectId}/workflow', { path: { projectId }, body }),
    updateYolo: (projectId: string, body: UpdateProjectYoloRequestDTO): Promise<ProjectDTO> =>
      request('PUT /api/project/projects/{projectId}/yolo', { path: { projectId }, body }),
    deleteProject: (projectId: string, expectedVersion: string): Promise<void> =>
      request('DELETE /api/project/projects/{projectId}', {
        path: { projectId },
        query: { expectedVersion: Number(expectedVersion) },
      }),
    archiveProject: (
      projectId: string,
      body: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> =>
      request('POST /api/project/projects/{projectId}/archive', { path: { projectId }, body }),
    unarchiveProject: (
      projectId: string,
      body: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> =>
      request('POST /api/project/projects/{projectId}/unarchive', { path: { projectId }, body }),
    getProjectSnapshot: (projectId: string): Promise<ProjectSnapshotDTO> =>
      request('GET /api/project/projects/{projectId}/snapshot', { path: { projectId } }),
    createIssue: (projectId: string, body: CreateIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/projects/{projectId}/issues', { path: { projectId }, body }),
    getIssue: (issueId: string, afterSequence?: string, limit?: number): Promise<IssueDetailDTO> =>
      request('GET /api/project/issues/{issueId}', {
        path: { issueId },
        query: {
          ...(afterSequence ? { afterSequence: Number(afterSequence) } : {}),
          ...(typeof limit === 'number' ? { limit } : {}),
        },
      }),
    updateIssue: (issueId: string, body: UpdateIssueRequestDTO): Promise<IssueDTO> =>
      request('PUT /api/project/issues/{issueId}', { path: { issueId }, body }),
    transitionIssue: (issueId: string, body: TransitionIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/transition', { path: { issueId }, body }),
    blockIssue: (issueId: string, body: BlockIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/block', { path: { issueId }, body }),
    recoverIssue: (issueId: string, body: RecoverIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/recover', { path: { issueId }, body }),
    resumeIssue: (issueId: string, body: ResumeIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/resume', { path: { issueId }, body }),
    stopIssue: (issueId: string, body: StopIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/stop', { path: { issueId }, body }),
    deleteIssue: (issueId: string, expectedVersion: string): Promise<void> =>
      request('DELETE /api/project/issues/{issueId}', {
        path: { issueId },
        query: { expectedVersion: Number(expectedVersion) },
      }),
    reopenIssue: (issueId: string, body: ReopenIssueRequestDTO): Promise<IssueDTO> =>
      request('POST /api/project/issues/{issueId}/reopen', { path: { issueId }, body }),
    resetStageBudget: (
      issueId: string,
      body: ResetStageBudgetRequestDTO,
    ): Promise<IssueStageBudgetDTO> =>
      request('POST /api/project/issues/{issueId}/reset-budget', { path: { issueId }, body }),
    appendIssueActivity: (
      issueId: string,
      body: AppendIssueActivityRequestDTO,
    ): Promise<IssueActivityDTO> =>
      request('POST /api/project/issues/{issueId}/activities', { path: { issueId }, body }),
    addIssueEvidence: (
      issueId: string,
      body: AddIssueEvidenceRequestDTO,
    ): Promise<IssueEvidenceDTO> =>
      request('POST /api/project/issues/{issueId}/evidence', { path: { issueId }, body }),
    listIssueEvidence: (issueId: string): Promise<IssueEvidenceDTO[]> =>
      request('GET /api/project/issues/{issueId}/evidence', { path: { issueId } }),
  }
}

export type ProjectsApi = ReturnType<typeof createProjectsApi>
export const projectsApi = createProjectsApi()
