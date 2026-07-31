import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  ComfyuiWorkflowApiCreateDTO,
  ComfyuiWorkflowApiDTO,
  ComfyuiWorkflowApiUpdateDTO,
  ComfyuiWorkflowCancelDTO,
  ComfyuiWorkflowId,
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
  ComfyuiWorkflowRunFileDTO,
  ComfyuiWorkflowRunRequestDTO,
  PageResult,
  S3PresignedRequestDTO,
  S3PresignedResponseDTO,
} from '@/shared/api/contracts'

const forbiddenHeaderNames = new Set([
  'connection',
  'content-length',
  'cookie',
  'date',
  'expect',
  'host',
  'keep-alive',
  'origin',
  'referer',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'via',
])

function safeUploadHeaders(headers: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(headers).filter(([name]) => {
      const normalized = name.toLowerCase()
      return !forbiddenHeaderNames.has(normalized) && !normalized.startsWith('proxy-') && !normalized.startsWith('sec-')
    }),
  )
}

function safePathSegment(value: string, fallback: string): string {
  const sanitized = value
    .normalize('NFKC')
    .replace(/[^a-zA-Z0-9._-]+/g, '_')
    .replace(/^[_.]+|[_.]+$/g, '')
    .slice(0, 120)
  return sanitized || fallback
}

function randomUploadSegment(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

export function createComfyuiUploadKey(apiName: string, filename: string): string {
  return `comfyui-inputs/${safePathSegment(apiName, 'workflow')}/${randomUploadSegment()}/${safePathSegment(filename, 'input.bin')}`
}

export function createComfyuiService(client: HttpClient = apiClient, fetchImpl: typeof fetch = fetch) {
  return {
    listWorkflows: (pageNumber = 1, pageSize = 100): Promise<PageResult<ComfyuiWorkflowApiDTO>> =>
      client.get('/comfyui/workflows', { params: { pageNumber, pageSize } }),

    createWorkflow: (data: ComfyuiWorkflowApiCreateDTO): Promise<ComfyuiWorkflowApiDTO> =>
      client.post('/comfyui/workflows', data),

    updateWorkflow: (id: ComfyuiWorkflowId, data: ComfyuiWorkflowApiUpdateDTO): Promise<ComfyuiWorkflowApiDTO> =>
      client.put(`/comfyui/workflows/${encodeURIComponent(String(id))}`, data),

    deleteWorkflow: (id: ComfyuiWorkflowId): Promise<void> =>
      client.delete(`/comfyui/workflows/${encodeURIComponent(String(id))}`),

    createPresignedUpload: (data: S3PresignedRequestDTO): Promise<S3PresignedResponseDTO> =>
      client.post('/s3/presigned-uploads', data),

    runWorkflow: (apiName: string, data: ComfyuiWorkflowRunRequestDTO): Promise<ComfyuiWorkflowRunDTO> =>
      client.post(`/comfyui/workflows/${encodeURIComponent(apiName)}/runs`, data),

    getRun: (runId: string, selector?: string): Promise<ComfyuiWorkflowJobDTO> =>
      client.get(`/comfyui/runs/${encodeURIComponent(runId)}`, {
        params: selector ? { select: selector } : undefined,
      }),

    cancelRun: (runId: string): Promise<ComfyuiWorkflowCancelDTO> =>
      client.post(`/comfyui/runs/${encodeURIComponent(runId)}/cancel`),

    uploadFile: async (apiName: string, file: File): Promise<ComfyuiWorkflowRunFileDTO> => {
      const contentType = file.type || 'application/octet-stream'
      const requestedKey = createComfyuiUploadKey(apiName, file.name)
      const presigned = await client.post<S3PresignedResponseDTO>('/s3/presigned-uploads', {
        key: requestedKey,
        contentType,
      })
      const method = presigned.method.toUpperCase()
      if (method !== 'PUT') {
        throw new Error(`预签名上传方法无效：${presigned.method}`)
      }
      const response = await fetchImpl(presigned.url, {
        method,
        headers: safeUploadHeaders(presigned.headers ?? {}),
        body: file,
      })
      if (!response.ok) {
        throw new Error(`文件直传失败（HTTP ${response.status}）`)
      }
      return {
        key: presigned.key || requestedKey,
        filename: file.name,
        contentType,
      }
    },
  }
}

export const comfyuiService = createComfyuiService()
