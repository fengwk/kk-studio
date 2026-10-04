import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createConfigSyncService } from '@/shared/api/config-sync-service'
import type {
  ConfigSyncImportResponseDTO,
  ConfigSyncInventoryDTO,
} from '@/shared/api/contracts/config-sync'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('configSyncService', () => {
  it('maps inventory to GET /settings/sync and passes the payload through', async () => {
    const client = createClient()
    const inventory: ConfigSyncInventoryDTO = {
      items: [
        {
          kind: 'agents',
          name: 'reviewer',
          dependencies: [{ kind: 'providers', name: 'openai' }],
        },
      ],
    }
    vi.mocked(client.get).mockResolvedValue(inventory)
    const service = createConfigSyncService(client)

    await expect(service.getInventory()).resolves.toBe(inventory)
    expect(client.get).toHaveBeenCalledWith('/settings/sync')
  })

  it('maps export to POST /settings/sync/export with the {items} body', async () => {
    const client = createClient()
    const items = [
      { kind: 'providers' as const, name: 'openai' },
      { kind: 'settings' as const, name: 'settings' },
    ]
    vi.mocked(client.post).mockResolvedValue({ yaml: 'providers: []' })
    const service = createConfigSyncService(client)

    await expect(service.exportConfig(items)).resolves.toEqual({ yaml: 'providers: []' })
    expect(client.post).toHaveBeenCalledWith('/settings/sync/export', { items })
  })

  it('maps import to POST /settings/sync/import with the {yaml} body', async () => {
    const client = createClient()
    const response: ConfigSyncImportResponseDTO = {
      imported: [{ kind: 'providers', name: 'openai' }],
      skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
    }
    vi.mocked(client.post).mockResolvedValue(response)
    const service = createConfigSyncService(client)

    await expect(service.importConfig('providers: []')).resolves.toBe(response)
    expect(client.post).toHaveBeenCalledWith('/settings/sync/import', { yaml: 'providers: []' })
  })
})
