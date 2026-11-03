import { apiClient, type HttpClient } from '@/shared/api/client'
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
  ResolveUnknownIssueRequestDTO,
  StopIssueRequestDTO,
  TransitionIssueRequestDTO,
  UpdateIssueRequestDTO,
  UpdateProjectRequestDTO,
  UpdateProjectWorkflowRequestDTO,
  UpdateProjectYoloRequestDTO,
} from '@/shared/api/generated/contracts'

export interface ProjectsApiOptions {
  client?: HttpClient
}

/** 产品 HTTP 数据直接使用生成的传输契约；HTTP 信封由共享客户端处理。 */
export function createProjectsApi(options: ProjectsApiOptions = {}) {
  const client = options.client ?? apiClient
  return {
    listProjects: (includeArchived = false): Promise<ProjectDTO[]> =>
      client.get('/projects', { params: { includeArchived } }),
    createProject: (request: CreateProjectRequestDTO): Promise<ProjectDTO> =>
      client.post('/projects', request),
    getProject: (projectId: string): Promise<ProjectDTO> =>
      client.get(`/projects/${encodeURIComponent(projectId)}`),
    updateProject: (projectId: string, request: UpdateProjectRequestDTO): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}`, request),
    updateWorkflow: (projectId: string, request: UpdateProjectWorkflowRequestDTO): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}/workflow`, request),
    updateYolo: (projectId: string, request: UpdateProjectYoloRequestDTO): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}/yolo`, request),
    deleteProject: (projectId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/projects/${encodeURIComponent(projectId)}`, { params: { expectedVersion } }),
    archiveProject: (projectId: string, request: ProjectVersionRequestDTO): Promise<ProjectDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/archive`, request),
    unarchiveProject: (projectId: string, request: ProjectVersionRequestDTO): Promise<ProjectDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/unarchive`, request),
    getProjectSnapshot: (projectId: string): Promise<ProjectSnapshotDTO> =>
      client.get(`/projects/${encodeURIComponent(projectId)}/snapshot`),
    createIssue: (projectId: string, request: CreateIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/issues`, request),
    getIssue: (issueId: string, afterSequence?: string, limit?: number): Promise<IssueDetailDTO> =>
      client.get(`/issues/${encodeURIComponent(issueId)}`, {
        params: {
          ...(afterSequence ? { afterSequence } : {}),
          ...(typeof limit === 'number' ? { limit } : {}),
        },
      }),
    updateIssue: (issueId: string, request: UpdateIssueRequestDTO): Promise<IssueDTO> =>
      client.put(`/issues/${encodeURIComponent(issueId)}`, request),
    transitionIssue: (issueId: string, request: TransitionIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/transition`, request),
    blockIssue: (issueId: string, request: BlockIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/block`, request),
    recoverIssue: (issueId: string, request: RecoverIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/recover`, request),
    stopIssue: (issueId: string, request: StopIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/stop`, request),
    resolveUnknown: (issueId: string, request: ResolveUnknownIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/resolve-unknown`, request),
    deleteIssue: (issueId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/issues/${encodeURIComponent(issueId)}`, { params: { expectedVersion } }),
    reopenIssue: (issueId: string, request: ReopenIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/reopen`, request),
    resetStageBudget: (issueId: string, request: ResetStageBudgetRequestDTO): Promise<IssueStageBudgetDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/budget-reset`, request),
    appendIssueActivity: (issueId: string, request: AppendIssueActivityRequestDTO): Promise<IssueActivityDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/activities`, request),
    addIssueEvidence: (issueId: string, request: AddIssueEvidenceRequestDTO): Promise<IssueEvidenceDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/evidence`, request),
    listIssueEvidence: (issueId: string): Promise<IssueEvidenceDTO[]> =>
      client.get(`/issues/${encodeURIComponent(issueId)}/evidence`),
  }
}

export type ProjectsApi = ReturnType<typeof createProjectsApi>
export const projectsApi = createProjectsApi()
