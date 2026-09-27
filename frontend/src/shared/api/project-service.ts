import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AddIssueEvidenceRequestDTO,
  AppendIssueActivityRequestDTO,
  ArchiveIssueRequestDTO,
  BlockIssueRequestDTO,
  CreateIssueRequestDTO,
  CreateProjectRequestDTO,
  IssueActivityDTO,
  IssueDTO,
  IssueDetailDTO,
  IssueEvidenceDTO,
  IssueStageBudgetDTO,
  PauseIssueRequestDTO,
  ProjectDTO,
  ProjectSnapshotDTO,
  ProjectVersionRequestDTO,
  RecoverIssueRequestDTO,
  ReopenIssueRequestDTO,
  ResetStageBudgetRequestDTO,
  ResolveUnknownIssueRequestDTO,
  ResumeIssueRequestDTO,
  StopIssueRequestDTO,
  TransitionIssueRequestDTO,
  UnarchiveIssueRequestDTO,
  UpdateIssueRequestDTO,
  UpdateProjectRequestDTO,
  UpdateProjectWorkflowRequestDTO,
  UpdateProjectYoloRequestDTO,
} from './contracts/project'

export interface ProjectService {
  listProjects(includeArchived?: boolean): Promise<ProjectDTO[]>
  createProject(request: CreateProjectRequestDTO): Promise<ProjectDTO>
  getProject(projectId: string): Promise<ProjectDTO>
  updateProject(projectId: string, request: UpdateProjectRequestDTO): Promise<ProjectDTO>
  updateWorkflow(projectId: string, request: UpdateProjectWorkflowRequestDTO): Promise<ProjectDTO>
  updateYolo(projectId: string, request: UpdateProjectYoloRequestDTO): Promise<ProjectDTO>
  deleteProject(projectId: string, expectedVersion: string): Promise<void>
  archiveProject(projectId: string, request: ProjectVersionRequestDTO): Promise<ProjectDTO>
  unarchiveProject(projectId: string, request: ProjectVersionRequestDTO): Promise<ProjectDTO>
  getProjectSnapshot(projectId: string): Promise<ProjectSnapshotDTO>

  createIssue(projectId: string, request: CreateIssueRequestDTO): Promise<IssueDTO>
  getIssue(issueId: string, afterSequence?: string, limit?: number): Promise<IssueDetailDTO>
  listActivities(issueId: string, afterSequence?: string, limit?: number): Promise<IssueActivityDTO[]>
  updateIssue(issueId: string, request: UpdateIssueRequestDTO): Promise<IssueDTO>
  transitionIssue(issueId: string, request: TransitionIssueRequestDTO): Promise<IssueDTO>
  blockIssue(issueId: string, request: BlockIssueRequestDTO): Promise<IssueDTO>
  recoverIssue(issueId: string, request: RecoverIssueRequestDTO): Promise<IssueDTO>
  pauseIssue(issueId: string, request: PauseIssueRequestDTO): Promise<IssueDTO>
  resumeIssue(issueId: string, request: ResumeIssueRequestDTO): Promise<IssueDTO>
  stopIssue(issueId: string, request: StopIssueRequestDTO): Promise<IssueDTO>
  resolveUnknown(issueId: string, request: ResolveUnknownIssueRequestDTO): Promise<IssueDTO>
  deleteIssue(issueId: string, expectedVersion: string): Promise<void>
  reopenIssue(issueId: string, request: ReopenIssueRequestDTO): Promise<IssueDTO>
  resetStageBudget(issueId: string, request: ResetStageBudgetRequestDTO): Promise<IssueStageBudgetDTO>
  archiveIssue(issueId: string, request: ArchiveIssueRequestDTO): Promise<IssueDTO>
  unarchiveIssue(issueId: string, request: UnarchiveIssueRequestDTO): Promise<IssueDTO>
  appendActivity(issueId: string, request: AppendIssueActivityRequestDTO): Promise<IssueActivityDTO>
  addEvidence(issueId: string, request: AddIssueEvidenceRequestDTO): Promise<IssueEvidenceDTO>
  listEvidence(issueId: string): Promise<IssueEvidenceDTO[]>
}

export function createProjectService(client: HttpClient = apiClient): ProjectService {
  return {
    listProjects: async (includeArchived = false): Promise<ProjectDTO[]> =>
      client.get('/projects', { params: { includeArchived } }),

    createProject: async (request: CreateProjectRequestDTO): Promise<ProjectDTO> =>
      client.post('/projects', request),

    getProject: async (projectId: string): Promise<ProjectDTO> =>
      client.get(`/projects/${encodeURIComponent(projectId)}`),

    updateProject: async (
      projectId: string,
      request: UpdateProjectRequestDTO,
    ): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}`, request),

    updateWorkflow: async (
      projectId: string,
      request: UpdateProjectWorkflowRequestDTO,
    ): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}/workflow`, request),

    updateYolo: async (
      projectId: string,
      request: UpdateProjectYoloRequestDTO,
    ): Promise<ProjectDTO> =>
      client.put(`/projects/${encodeURIComponent(projectId)}/yolo`, request),

    deleteProject: async (projectId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/projects/${encodeURIComponent(projectId)}`, {
        params: { expectedVersion },
      }),

    archiveProject: async (
      projectId: string,
      request: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/archive`, request),

    unarchiveProject: async (
      projectId: string,
      request: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/unarchive`, request),

    getProjectSnapshot: async (projectId: string): Promise<ProjectSnapshotDTO> =>
      client.get(`/projects/${encodeURIComponent(projectId)}/snapshot`),

    createIssue: async (projectId: string, request: CreateIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/projects/${encodeURIComponent(projectId)}/issues`, request),

    getIssue: async (
      issueId: string,
      afterSequence?: string,
      limit?: number,
    ): Promise<IssueDetailDTO> =>
      client.get(`/issues/${encodeURIComponent(issueId)}`, {
        params: {
          ...(afterSequence ? { afterSequence } : {}),
          ...(typeof limit === 'number' ? { limit } : {}),
        },
      }),

    listActivities: async (
      issueId: string,
      afterSequence?: string,
      limit?: number,
    ): Promise<IssueActivityDTO[]> =>
      client.get(`/issues/${encodeURIComponent(issueId)}/activities`, {
        params: {
          ...(afterSequence ? { afterSequence } : {}),
          ...(typeof limit === 'number' ? { limit } : {}),
        },
      }),

    updateIssue: async (issueId: string, request: UpdateIssueRequestDTO): Promise<IssueDTO> =>
      client.put(`/issues/${encodeURIComponent(issueId)}`, request),

    transitionIssue: async (
      issueId: string,
      request: TransitionIssueRequestDTO,
    ): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/transition`, request),

    blockIssue: async (issueId: string, request: BlockIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/block`, request),

    recoverIssue: async (issueId: string, request: RecoverIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/recover`, request),

    pauseIssue: async (issueId: string, request: PauseIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/pause`, request),

    resumeIssue: async (issueId: string, request: ResumeIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/resume`, request),

    stopIssue: async (issueId: string, request: StopIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/stop`, request),

    resolveUnknown: async (
      issueId: string,
      request: ResolveUnknownIssueRequestDTO,
    ): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/resolve-unknown`, request),

    deleteIssue: async (issueId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/issues/${encodeURIComponent(issueId)}`, {
        params: { expectedVersion },
      }),

    reopenIssue: async (issueId: string, request: ReopenIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/reopen`, request),

    resetStageBudget: async (
      issueId: string,
      request: ResetStageBudgetRequestDTO,
    ): Promise<IssueStageBudgetDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/budget-reset`, request),

    archiveIssue: async (issueId: string, request: ArchiveIssueRequestDTO): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/archive`, request),

    unarchiveIssue: async (
      issueId: string,
      request: UnarchiveIssueRequestDTO,
    ): Promise<IssueDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/unarchive`, request),

    appendActivity: async (
      issueId: string,
      request: AppendIssueActivityRequestDTO,
    ): Promise<IssueActivityDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/activities`, request),

    addEvidence: async (
      issueId: string,
      request: AddIssueEvidenceRequestDTO,
    ): Promise<IssueEvidenceDTO> =>
      client.post(`/issues/${encodeURIComponent(issueId)}/evidence`, request),

    listEvidence: async (issueId: string): Promise<IssueEvidenceDTO[]> =>
      client.get(`/issues/${encodeURIComponent(issueId)}/evidence`),
  }
}

export const projectService = createProjectService()
