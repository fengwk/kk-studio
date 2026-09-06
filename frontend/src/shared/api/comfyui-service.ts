import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  ComfyuiWorkflowApiCreateDTO,
  ComfyuiWorkflowApiDTO,
  ComfyuiWorkflowApiUpdateDTO,
  ComfyuiWorkflowCancelDTO,
  ComfyuiWorkflowId,
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
  ComfyuiWorkflowRunRequestDTO,
} from '@/shared/api/contracts/comfyui'
import type { PageResult } from '@/shared/api/contracts/base'

export function createComfyuiService(client: HttpClient = apiClient) {
  return {
    listWorkflows: (pageNumber = 1, pageSize = 100): Promise<PageResult<ComfyuiWorkflowApiDTO>> =>
      client.get('/comfyui/workflows', { params: { pageNumber, pageSize } }),

    createWorkflow: (data: ComfyuiWorkflowApiCreateDTO): Promise<ComfyuiWorkflowApiDTO> =>
      client.post('/comfyui/workflows', data),

    updateWorkflow: (id: ComfyuiWorkflowId, data: ComfyuiWorkflowApiUpdateDTO): Promise<ComfyuiWorkflowApiDTO> =>
      client.put(`/comfyui/workflows/${encodeURIComponent(String(id))}`, data),

    deleteWorkflow: (id: ComfyuiWorkflowId): Promise<void> =>
      client.delete(`/comfyui/workflows/${encodeURIComponent(String(id))}`),

    runWorkflow: (
      workflowId: ComfyuiWorkflowId,
      data: ComfyuiWorkflowRunRequestDTO,
    ): Promise<ComfyuiWorkflowRunDTO> =>
      client.post(`/comfyui/workflows/${encodeURIComponent(String(workflowId))}/runs`, data),

    getRun: (runId: string, selector?: string): Promise<ComfyuiWorkflowJobDTO> =>
      client.get(`/comfyui/runs/${encodeURIComponent(runId)}`, {
        params: selector ? { select: selector } : undefined,
      }),

    cancelRun: (runId: string): Promise<ComfyuiWorkflowCancelDTO> =>
      client.post(`/comfyui/runs/${encodeURIComponent(runId)}/cancel`),
  }
}

export const comfyuiService = createComfyuiService()
