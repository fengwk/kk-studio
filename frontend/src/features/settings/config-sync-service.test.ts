import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import { createConfigSyncService } from '@/features/settings/config-sync-service'
import type {
  ConfigSyncImportCheckDTO,
  ConfigSyncImportResultDTO,
  ConfigSyncInventoryDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)

describe('configSyncService', () => {
  afterEach(() => apiRequest.mockReset())

  it('maps inventory to the platform config-sync resource and passes the payload through', async () => {
    const inventory: ConfigSyncInventoryDTO = {
      items: [
        {
          kind: 'agents',
          name: 'reviewer',
          dependencies: [{ kind: 'providers', name: 'openai' }],
        },
      ],
    }
    apiRequest.mockResolvedValue(inventory)
    const service = createConfigSyncService()

    await expect(service.getInventory()).resolves.toBe(inventory)
    expect(apiRequest).toHaveBeenCalledWith('GET /api/platform/config-sync', {})
  })

  it('maps export to the export operation with the {items} body', async () => {
    const items = [
      { kind: 'providers' as const, name: 'openai' },
      { kind: 'settings' as const, name: 'settings' },
    ]
    apiRequest.mockResolvedValue({ yaml: 'providers: []' })
    const service = createConfigSyncService()

    await expect(service.exportConfig(items)).resolves.toEqual({ yaml: 'providers: []' })
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/config-sync/export', {
      body: { items },
    })
  })

  it('maps import with {yaml, allowPartial}', async () => {
    const response: ConfigSyncImportResultDTO = {
      imported: [{ kind: 'providers', name: 'openai' }],
      skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
    }
    apiRequest.mockResolvedValue(response)
    const service = createConfigSyncService()

    // 缺省不授权部分导入。
    await expect(service.importConfig('providers: []')).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/config-sync/import', {
      body: { yaml: 'providers: []', allowPartial: false },
    })

    // 显式授权部分导入。
    apiRequest.mockClear()
    await expect(service.importConfig('providers: []', true)).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/config-sync/import', {
      body: { yaml: 'providers: []', allowPartial: true },
    })
  })

  it('maps precheck to the check-import operation with the {yaml} body', async () => {
    const response: ConfigSyncImportCheckDTO = {
      created: [{ kind: 'providers', name: 'openai' }],
      updated: [{ kind: 'models', name: 'openai/gpt' }],
      skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
    }
    apiRequest.mockResolvedValue(response)
    const service = createConfigSyncService()

    await expect(service.checkImport('providers: []')).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/config-sync/check-import', {
      body: { yaml: 'providers: []' },
    })
  })
})
