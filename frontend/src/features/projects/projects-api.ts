import { apiClient, type HttpClient } from '@/shared/api/client'
import {
  decodeIssue,
  decodeIssueActivity,
  decodeIssueActivityList,
  decodeIssueDependency,
  decodeIssueDetail,
  decodeIssueEvidence,
  decodeProject,
  decodeProjectList,
  decodeProjectSnapshot,
} from './codecs'
import type {
  AddIssueDependencyRequest,
  AddIssueEvidenceRequest,
  AppendIssueActivityRequest,
  ArchiveIssueRequest,
  BlockIssueRequest,
  CancelIssueRequest,
  ChangeIssueStatusRequest,
  CreateIssueRequest,
  CreateProjectRequest,
  IssueActivityDTO,
  IssueDTO,
  IssueDependencyDTO,
  IssueDetailDTO,
  IssueEvidenceDTO,
  ProjectArchiveRequest,
  ProjectDTO,
  ProjectSnapshotDTO,
  ProjectUnarchiveRequest,
  RecoverIssueRequest,
  RetryIssueRequest,
  ReviewIssueRequest,
  UnarchiveIssueRequest,
  UpdateIssueRequest,
  UpdateProjectRequest,
} from './types'

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

    createProject: async (request: CreateProjectRequest): Promise<ProjectDTO> => {
      const raw = await client.post<unknown>('/projects', request)
      return decodeProject(raw)
    },

    getProject: async (projectId: string): Promise<ProjectDTO> => {
      const raw = await client.get<unknown>(`/projects/${encodeURIComponent(projectId)}`)
      return decodeProject(raw)
    },

    updateProject: async (
      projectId: string,
      request: UpdateProjectRequest,
    ): Promise<ProjectDTO> => {
      const raw = await client.put<unknown>(`/projects/${encodeURIComponent(projectId)}`, request)
      return decodeProject(raw)
    },

    deleteProject: async (projectId: string, expectedVersion: string): Promise<void> => {
      await client.delete(`/projects/${encodeURIComponent(projectId)}`, {
        params: { expectedVersion },
      })
    },

    archiveProject: async (
      projectId: string,
      request: ProjectArchiveRequest,
    ): Promise<ProjectDTO> => {
      const raw = await client.post<unknown>(
        `/projects/${encodeURIComponent(projectId)}/archive`,
        request,
      )
      return decodeProject(raw)
    },

    unarchiveProject: async (
      projectId: string,
      request: ProjectUnarchiveRequest,
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

    createIssue: async (projectId: string, request: CreateIssueRequest): Promise<IssueDTO> => {
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

    updateIssue: async (issueId: string, request: UpdateIssueRequest): Promise<IssueDTO> => {
      const raw = await client.put<unknown>(`/issues/${encodeURIComponent(issueId)}`, request)
      return decodeIssue(raw)
    },

    changeIssueStatus: async (
      issueId: string,
      request: ChangeIssueStatusRequest,
    ): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/status`,
        request,
      )
      return decodeIssue(raw)
    },

    blockIssue: async (issueId: string, request: BlockIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/block`,
        request,
      )
      return decodeIssue(raw)
    },

    recoverIssue: async (issueId: string, request: RecoverIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/recover`,
        request,
      )
      return decodeIssue(raw)
    },

    addIssueDependency: async (
      issueId: string,
      request: AddIssueDependencyRequest,
    ): Promise<IssueDependencyDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/dependencies`,
        request,
      )
      return decodeIssueDependency(raw)
    },

    removeIssueDependency: async (
      issueId: string,
      dependsOnIssueId: string,
      expectedVersion: string,
    ): Promise<void> => {
      await client.delete(
        `/issues/${encodeURIComponent(issueId)}/dependencies/${encodeURIComponent(dependsOnIssueId)}`,
        {
          params: { expectedVersion },
        },
      )
    },

    appendIssueActivity: async (
      issueId: string,
      request: AppendIssueActivityRequest,
    ): Promise<IssueActivityDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/activities`,
        request,
      )
      return decodeIssueActivity(raw)
    },

    reviewIssue: async (
      issueId: string,
      request: ReviewIssueRequest,
    ): Promise<void> => {
      await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/review`,
        request,
      )
    },

    cancelIssue: async (issueId: string, request: CancelIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/cancel`,
        request,
      )
      return decodeIssue(raw)
    },

    retryIssue: async (issueId: string, request: RetryIssueRequest = {}): Promise<IssueActivityDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/retry`,
        request,
      )
      return decodeIssueActivity(raw)
    },

    archiveIssue: async (issueId: string, request: ArchiveIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/archive`,
        request,
      )
      return decodeIssue(raw)
    },

    unarchiveIssue: async (issueId: string, request: UnarchiveIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/unarchive`,
        request,
      )
      return decodeIssue(raw)
    },

    addIssueEvidence: async (
      issueId: string,
      request: AddIssueEvidenceRequest,
    ): Promise<IssueEvidenceDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/evidence`,
        request,
      )
      return decodeIssueEvidence(raw)
    },
  }
}

export type ProjectsApi = ReturnType<typeof createProjectsApi>
export const projectsApi = createProjectsApi()
