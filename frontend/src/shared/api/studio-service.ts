import { apiClient } from '@/shared/api/client'

/**
 * Studio HTTP contract.
 *
 * Write paths currently return 501 until core adapters are implemented.
 * Always use DEFAULT_WORKSPACE_ID until multi-workspace exists.
 */

export const DEFAULT_WORKSPACE_ID = '1'

export interface FunctionDefinitionDTO {
  functionId: string
  version: string
  scope: string
  workspaceId?: string | null
  displayName: string
  description: string
  inputKeys: string[]
  outputChannelKeys: string[]
  configSchemaJson: string
  cacheable: boolean
}

export interface CanvasDocumentDTO {
  id: string
  workspaceId: string
  title: string
  schemaVersion: number
  revision: string
  lifecycle: string
  homeViewportJson: string
}

export async function listFunctions(
  workspaceId: string = DEFAULT_WORKSPACE_ID,
): Promise<FunctionDefinitionDTO[]> {
  return apiClient.get<FunctionDefinitionDTO[]>('/functions', {
    params: { workspaceId },
  })
}

export async function listCanvases(
  workspaceId: string = DEFAULT_WORKSPACE_ID,
): Promise<CanvasDocumentDTO[]> {
  return apiClient.get<CanvasDocumentDTO[]>('/canvases', {
    params: { workspaceId },
  })
}
