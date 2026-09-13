import { apiClient, type HttpClient } from '@/shared/api/client'
import {
  decodeIssue,
  decodeIssueDependency,
  decodeIssueDetail,
  decodeIssueInput,
  decodeIssueRunSummary,
  decodeProject,
  decodeProjectList,
  decodeProjectSnapshot,
} from './codecs'
import type {
  AddIssueDependencyRequest,
  AppendIssueInputRequest,
  ArchiveIssueRequest,
  CancelIssueRequest,
  ChangeIssueStatusRequest,
  CreateIssueRequest,
  CreateProjectRequest,
  HarnessAcceptedCommandsDTO,
  IssueDTO,
  IssueDependencyDTO,
  IssueDetailDTO,
  IssueInputDTO,
  IssueRunSummaryDTO,
  ProjectArchiveRequest,
  ProjectCommandRequest,
  ProjectDTO,
  ProjectSnapshotDTO,
  ProjectUnarchiveRequest,
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

    sendProjectCommand: async (
      projectId: string,
      request: ProjectCommandRequest,
    ): Promise<HarnessAcceptedCommandsDTO> => {
      return client.post<HarnessAcceptedCommandsDTO>(
        `/projects/${encodeURIComponent(projectId)}/commands`,
        request,
      )
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

    getIssue: async (issueId: string): Promise<IssueDetailDTO> => {
      const raw = await client.get<unknown>(`/issues/${encodeURIComponent(issueId)}`)
      return decodeIssueDetail(raw)
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

    appendIssueInput: async (
      issueId: string,
      request: AppendIssueInputRequest,
    ): Promise<IssueInputDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/inputs`,
        request,
      )
      return decodeIssueInput(raw)
    },

    reviewIssue: async (
      issueId: string,
      request: ReviewIssueRequest,
    ): Promise<IssueRunSummaryDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/review`,
        request,
      )
      return decodeIssueRunSummary(raw)
    },

    cancelIssue: async (issueId: string, request: CancelIssueRequest): Promise<IssueDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/cancel`,
        request,
      )
      return decodeIssue(raw)
    },

    retryIssue: async (issueId: string, request: RetryIssueRequest): Promise<IssueInputDTO> => {
      const raw = await client.post<unknown>(
        `/issues/${encodeURIComponent(issueId)}/retry`,
        request,
      )
      return decodeIssueInput(raw)
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
  }
}

export type ProjectsApi = ReturnType<typeof createProjectsApi>
export const projectsApi = createProjectsApi()
