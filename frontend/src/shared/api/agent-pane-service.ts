import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentCommandBatchRequestDTO,
  AgentCommandBatchResponseDTO,
  HarnessSessionEntryDTO,
  HarnessThreadSnapshotDTO,
  ManualCompactionRequestDTO,
  ManualCompactionResponseDTO,
  RuntimeSessionSummaryDTO,
  RuntimeThreadSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'

export function createAgentPaneService(client: HttpClient = apiClient) {
  return {
    acceptCommandBatch: (
      data: AgentCommandBatchRequestDTO,
    ): Promise<AgentCommandBatchResponseDTO> =>
      client.post('/ai/runtime/command-batches', data),

    listChatSessions: (chatId: string): Promise<RuntimeSessionSummaryDTO[]> =>
      client.get(`/ai/chat/${encodeURIComponent(chatId)}/sessions`),

    listCanvasSessions: (canvasId: string): Promise<RuntimeSessionSummaryDTO[]> =>
      client.get(`/canvases/${encodeURIComponent(canvasId)}/sessions`),

    listSessionThreads: (sessionId: string): Promise<RuntimeThreadSummaryDTO[]> =>
      client.get(`/ai/runtime/sessions/${encodeURIComponent(sessionId)}/threads`),

    listSessionEntries: (sessionId: string): Promise<HarnessSessionEntryDTO[]> =>
      client.get(`/ai/runtime/sessions/${encodeURIComponent(sessionId)}/entries`),

    getThreadSnapshot: (threadId: string): Promise<HarnessThreadSnapshotDTO> =>
      client.get(`/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`),

    compactThread: (
      threadId: string,
      data: ManualCompactionRequestDTO,
    ): Promise<ManualCompactionResponseDTO> =>
      client.post(`/ai/runtime/threads/${encodeURIComponent(threadId)}/compact`, data),
  }
}

export const agentPaneService = createAgentPaneService()
