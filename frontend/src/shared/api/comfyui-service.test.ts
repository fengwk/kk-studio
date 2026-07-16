import { describe, expect, it, vi } from 'vitest'
import { createComfyuiService } from '@/shared/api/comfyui-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('comfyuiService', () => {
  it('maps workflow CRUD, S3 and run APIs with encoded path/query values', async () => {
    const client = createClient()
    const service = createComfyuiService(client, vi.fn())
    const body = {
      apiName: 'image-upscale',
      name: 'Image Upscale',
      workflowJson: '{}',
      inputBindingsJson: '[]',
      enabled: true,
    }

    await service.listWorkflows(2, 25)
    await service.createWorkflow(body)
    await service.updateWorkflow('workflow/1', body)
    await service.deleteWorkflow('workflow/1')
    await service.createPresignedUpload({ key: 'inputs/a.png', contentType: 'image/png' })
    await service.createPresignedDownload({ key: 'outputs/a.png' })
    await service.runWorkflow('image/upscale', { parameters: { scale: 2 }, files: {} })
    await service.getRun('run/1', '$.outputs[?(@.type=="image")]')
    await service.getRun('run-2')
    await service.cancelRun('run/1')

    expect(client.get).toHaveBeenNthCalledWith(1, '/comfyui/workflows', { params: { pageNumber: 2, pageSize: 25 } })
    expect(client.post).toHaveBeenNthCalledWith(1, '/comfyui/workflows', body)
    expect(client.put).toHaveBeenCalledWith('/comfyui/workflows/workflow%2F1', body)
    expect(client.delete).toHaveBeenCalledWith('/comfyui/workflows/workflow%2F1')
    expect(client.post).toHaveBeenNthCalledWith(2, '/s3/presigned-uploads', { key: 'inputs/a.png', contentType: 'image/png' })
    expect(client.post).toHaveBeenNthCalledWith(3, '/s3/presigned-downloads', { key: 'outputs/a.png' })
    expect(client.post).toHaveBeenNthCalledWith(4, '/comfyui/workflows/image%2Fupscale/runs', {
      parameters: { scale: 2 },
      files: {},
    })
    expect(client.get).toHaveBeenNthCalledWith(2, '/comfyui/runs/run%2F1', {
      params: { select: '$.outputs[?(@.type=="image")]' },
    })
    expect(client.get).toHaveBeenNthCalledWith(3, '/comfyui/runs/run-2', { params: undefined })
    expect(client.post).toHaveBeenNthCalledWith(5, '/comfyui/runs/run%2F1/cancel')
  })

  it('requests a presigned upload, PUTs the File directly, and returns only the key reference', async () => {
    const client = createClient()
    vi.mocked(client.post).mockImplementation(async (url) => {
      if (url === '/s3/presigned-uploads') {
        return {
          bucket: 'studio',
          key: 'comfyui-inputs/image-upscale/upload/input_file.png',
          method: 'PUT',
          url: 'https://s3.example/upload-token',
          headers: { 'Content-Type': 'image/png', Host: 'forbidden.example', 'X-Amz-Meta-Test': 'yes' },
          expiresAt: '2026-07-14T00:00:00Z',
        }
      }
      return { runId: 'run-1', status: 'pending', defaultSelector: '$.outputs' }
    })
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200 }) as Response)
    const service = createComfyuiService(client, fetchMock as typeof fetch)
    const file = new File(['pixels'], 'input file.png', { type: 'image/png' })

    const reference = await service.uploadFile('image-upscale', file)
    await service.runWorkflow('image-upscale', { parameters: {}, files: { image: reference } })

    const presignedRequest = vi.mocked(client.post).mock.calls[0]
    expect(presignedRequest[0]).toBe('/s3/presigned-uploads')
    expect(presignedRequest[1]).toEqual({
      key: expect.stringMatching(/^comfyui-inputs\/image-upscale\/[^/]+\/input_file\.png$/),
      contentType: 'image/png',
    })
    expect(fetchMock).toHaveBeenCalledWith('https://s3.example/upload-token', {
      method: 'PUT',
      headers: { 'Content-Type': 'image/png', 'X-Amz-Meta-Test': 'yes' },
      body: file,
    })
    expect(client.post).toHaveBeenLastCalledWith('/comfyui/workflows/image-upscale/runs', {
      parameters: {},
      files: {
        image: {
          key: 'comfyui-inputs/image-upscale/upload/input_file.png',
          filename: 'input file.png',
          contentType: 'image/png',
        },
      },
    })
  })

  it('uses a binary content type fallback and rejects invalid or failed direct PUTs', async () => {
    const client = createClient()
    vi.mocked(client.post).mockResolvedValue({
      bucket: 'studio',
      key: 'input.bin',
      method: 'POST',
      url: 'https://s3.example/upload-token',
      headers: {},
      expiresAt: null,
    })
    const service = createComfyuiService(client, vi.fn())

    await expect(service.uploadFile('workflow', new File(['x'], '...'))).rejects.toThrow('预签名上传方法无效')
    expect(client.post).toHaveBeenCalledWith('/s3/presigned-uploads', {
      key: expect.stringMatching(/\/input\.bin$/),
      contentType: 'application/octet-stream',
    })

    vi.mocked(client.post).mockResolvedValueOnce({
      bucket: 'studio',
      key: 'input.bin',
      method: 'PUT',
      url: 'https://s3.example/upload-token',
      headers: {},
      expiresAt: null,
    })
    const failedService = createComfyuiService(client, vi.fn(async () => ({ ok: false, status: 403 }) as Response) as typeof fetch)
    await expect(failedService.uploadFile('workflow', new File(['x'], 'input.bin'))).rejects.toThrow('HTTP 403')
  })
})
