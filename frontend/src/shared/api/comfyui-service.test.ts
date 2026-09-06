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
  it('maps workflow CRUD and run APIs with encoded path/query values', async () => {
    const client = createClient()
    const service = createComfyuiService(client)
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
    await service.runWorkflow('37d4fa8b-00cf-4dbd-a44e-53934d6a7567', {
      parameters: { scale: 2 },
      files: {
        image: {
          blobId: 'd1405f21-1801-4619-93ec-880b37dd1c3d',
          filename: 'input.png',
        },
      },
    })
    await service.getRun('run/1', '$.outputs[?(@.type=="image")]')
    await service.getRun('run-2')
    await service.cancelRun('run/1')

    expect(client.get).toHaveBeenNthCalledWith(1, '/comfyui/workflows', { params: { pageNumber: 2, pageSize: 25 } })
    expect(client.post).toHaveBeenNthCalledWith(1, '/comfyui/workflows', body)
    expect(client.put).toHaveBeenCalledWith('/comfyui/workflows/workflow%2F1', body)
    expect(client.delete).toHaveBeenCalledWith('/comfyui/workflows/workflow%2F1')
    expect(client.post).toHaveBeenNthCalledWith(
      2,
      '/comfyui/workflows/37d4fa8b-00cf-4dbd-a44e-53934d6a7567/runs',
      {
        parameters: { scale: 2 },
        files: {
          image: {
            blobId: 'd1405f21-1801-4619-93ec-880b37dd1c3d',
            filename: 'input.png',
          },
        },
      },
    )
    expect(client.get).toHaveBeenNthCalledWith(2, '/comfyui/runs/run%2F1', {
      params: { select: '$.outputs[?(@.type=="image")]' },
    })
    expect(client.get).toHaveBeenNthCalledWith(3, '/comfyui/runs/run-2', { params: undefined })
    expect(client.post).toHaveBeenNthCalledWith(3, '/comfyui/runs/run%2F1/cancel')
  })
})
