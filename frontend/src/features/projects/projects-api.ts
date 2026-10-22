import { apiClient, type HttpClient } from '@/shared/api/client'
import {
  decodeIssue,
  decodeIssueActivity,
  decodeIssueActivityList,
  decodeIssueDetail,
  decodeIssueEvidence,
  decodeIssueEvidenceList,
  decodeIssueStageBudget,
  decodeProject,
  decodeProjectList,
  decodeProjectSnapshot,
} from './codecs'
import type {
  AddIssueEvidenceRequestDTO,
  AppendIssueActivityRequestDTO,
  ArchiveIssueRequestDTO,
  BlockIssueRequestDTO,
  CreateIssueRequestDTO,
  CreateProjectRequestDTO,
  IssueActivityDTO,
  IssueDetailDTO,
  IssueDTO,
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
} from '@/shared/api/generated/contracts'

export interface ProjectsApiOptions {
  client?: HttpClient
}

export function createProjectsApi(options: ProjectsApiOptions = {}) {
  const client = options.client ?? apiClient

  return {
    listProjects: async (includeArchived = false): Promise<ProjectDTO[]> => {
      const raw = await client.get<unknown>('/projects', {
        params: { includeArchived },
      })
      return decodeProjectList(raw)
    },

    createProject: async (request: CreateProjectRequestDTO): Promise<ProjectDTO> => {
      const raw = await client.post<unknown>('/projects', request)
      return decodeProject(raw)
    },

    getProject: async (projectId: string): Promise<ProjectDTO> => {
      const raw = await client.get<unknown>(`/projects/${encodeURIComponent(projectId)}`)
      return decodeProject(raw)
    },

    updateProject: async (
      projectId: string,
      request: UpdateProjectRequestDTO,
    ): Promise<ProjectDTO> => {
      const raw = await client.put<unknown>(`/projects/${encodeURIComponent(projectId)}`, request)
      return decodeProject(raw)
    },

    updateWorkflow: async (
      projectId: string,
      request: UpdateProjectWorkflowRequestDTO,
    ): Promise<ProjectDTO> => {
      const raw = await client.put<unknown>(
        `/projects/${encodeURIComponent(projectId)}/workflow`,
        request,
      )
      return decodeProject(raw)
    },

    updateYolo: async (
      projectId: string,
      request: UpdateProjectYoloRequestDTO,
    ): Promise<ProjectDTO> => {
      const raw = await client.put<unknown>(
        `/projects/${encodeURIComponent(projectId)}/yolo`,
        request,
      )
      return decodeProject(raw)
    },

    deleteProject: async (projectId: string, expectedVersion: string): Promise<void> => {
      await client.delete(`/projects/${encodeURIComponent(projectId)}`, {
        params: { expectedVersion },
      })
    },

    archiveProject: async (
      projectId: string,
      request: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> => {
      const raw = await client.post<unknown>(
        `/projects/${encodeURIComponent(projectId)}/archive`,
        request,
      )
      return decodeProject(raw)
    },

    unarchiveProject: async (
      projectId: string,
      request: ProjectVersionRequestDTO,
    ): Promise<ProjectDTO> => {
      const raw = await client.post<unknown>(
        `/projects/${encodeURIComponent(projectId)}/unarchive`,
        request,
      )
      return decodeProject(raw)
    },

    getProjectSnapshot: async (projectId: string): Promise<ProjectSnapshotDTO> => {
      const raw = await client.get<unknown>(
        `/projects/${encodeURIComponent(projectId)}/snapshot`,
      )
      return decodeProjectSnapshot(raw)
    },

    createIssue: async (projectId: string, request: CreateIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/projects/${encodeURIComponent(projectId)}/issues`,
        request,
      )
      return decodeIssue(raw)
    },

    getIssue: async (
      issueId: string,
      afterSequence?: string,
      limit?: number,
    ): Promise<IssueDetailDTO> => {
      const raw = await client.get<unknown>(`/issues/${encodeURIComponent(issueId)}`, {
        params: {
          ...(afterSequence ? { afterSequence } : {}),
          ...(typeof limit === 'number' ? { limit } : {}),
        },
      })
      return decodeIssueDetail(raw)
    },

    listActivities: async (
      issueId: string,
      afterSequence?: string,
      limit?: number,
    ): Promise<IssueActivityDTO[]> => {
      const raw = await client.get<unknown>(
        `/issues/${encodeURIComponent(issueId)}/activities`,
        {
          params: {
            ...(afterSequence ? { afterSequence } : {}),
            ...(typeof limit === 'number' ? { limit } : {}),
          },
        },
      )
      return decodeIssueActivityList(raw)
    },

    updateIssue: async (issueId: string, request: UpdateIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.put<unknown>(`/issues/${encodeURIComponent(issueId)}`, request)
      return decodeIssue(raw)
    },

    transitionIssue: async (
      issueId: string,
      request: TransitionIssueRequestDTO,
    ): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/transition`,
        request,
      )
      return decodeIssue(raw)
    },

    blockIssue: async (issueId: string, request: BlockIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/block`,
        request,
      )
      return decodeIssue(raw)
    },

    recoverIssue: async (issueId: string, request: RecoverIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/recover`,
        request,
      )
      return decodeIssue(raw)
    },

    pauseIssue: async (issueId: string, request: PauseIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/pause`,
        request,
      )
      return decodeIssue(raw)
    },

    resumeIssue: async (issueId: string, request: ResumeIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/resume`,
        request,
      )
      return decodeIssue(raw)
    },

    stopIssue: async (issueId: string, request: StopIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/stop`,
        request,
      )
      return decodeIssue(raw)
    },

    resolveUnknown: async (
      issueId: string,
      request: ResolveUnknownIssueRequestDTO,
    ): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/resolve-unknown`,
        request,
      )
      return decodeIssue(raw)
    },

    deleteIssue: async (issueId: string, expectedVersion: string): Promise<void> => {
      await client.delete(`/issues/${encodeURIComponent(issueId)}`, {
        params: { expectedVersion },
      })
    },

    reopenIssue: async (issueId: string, request: ReopenIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/reopen`,
        request,
      )
      return decodeIssue(raw)
    },

    resetStageBudget: async (
      issueId: string,
      request: ResetStageBudgetRequestDTO,
    ): Promise<IssueStageBudgetDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/budget-reset`,
        request,
      )
      return decodeIssueStageBudget(raw)
    },

    archiveIssue: async (issueId: string, request: ArchiveIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/archive`,
        request,
      )
      return decodeIssue(raw)
    },

    unarchiveIssue: async (issueId: string, request: UnarchiveIssueRequestDTO): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/unarchive`,
        request,
      )
      return decodeIssue(raw)
    },

    appendIssueActivity: async (
      issueId: string,
      request: AppendIssueActivityRequestDTO,
    ): Promise<IssueActivityDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/activities`,
        request,
      )
      return decodeIssueActivity(raw)
    },

    addIssueEvidence: async (
      issueId: string,
      request: AddIssueEvidenceRequestDTO,
    ): Promise<IssueEvidenceDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/evidence`,
        request,
      )
      return decodeIssueEvidence(raw)
    },

    listIssueEvidence: async (issueId: string): Promise<IssueEvidenceDTO[]> => {
      const raw = await client.get<unknown>(
        `/issues/${encodeURIComponent(issueId)}/evidence`,
      )
      return decodeIssueEvidenceList(raw)
    },
  }
}

export type ProjectsApi = ReturnType<typeof createProjectsApi>
export const projectsApi = createProjectsApi()
