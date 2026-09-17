import { afterEach, describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createSystemSettingsService } from '@/shared/api/system-settings-service'
import {
  DEFAULT_MAX_RESOURCE_BYTES,
  makeSettingsDto,
} from '@/test-support/settings-test-fixtures'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('systemSettingsService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('maps GET to /settings and passes the envelope data through', async () => {
    const client = createClient()
    const dto = makeSettingsDto()
    vi.mocked(client.get).mockResolvedValue(dto)
    const service = createSystemSettingsService(client)

    await expect(service.get()).resolves.toBe(dto)
    expect(client.get).toHaveBeenCalledWith('/settings')
    // Long / Integer / version wire 形态在契约层原样保留。
    expect(dto.environment.maxResourceBytes).toBe(DEFAULT_MAX_RESOURCE_BYTES)
    expect(dto.aiRuntime.retryMaxRetries).toBe(3)
    expect(dto.version).toBe('0')
  })

  it('maps PUT to /settings with the full aggregate and expectedVersion', async () => {
    const client = createClient()
    const base = makeSettingsDto()
    const update = {
      tool: base.tool,
      aiRuntime: base.aiRuntime,
      environment: base.environment,
      integrations: base.integrations,
      storageMedia: base.storageMedia,
      advanced: base.advanced,
      expectedVersion: '0',
    }
    const updated = makeSettingsDto({ version: '1' })
    updated.tool.defaultYolo = true
    vi.mocked(client.put).mockResolvedValue(updated)
    const service = createSystemSettingsService(client)

    await expect(service.update(update)).resolves.toBe(updated)
    expect(client.put).toHaveBeenCalledWith('/settings', update)
  })

  it('keeps Long fields as decimal strings and Integer fields as numbers on the wire', async () => {
    const client = createClient()
    const service = createSystemSettingsService(client)
    const wire = makeSettingsDto({ version: '1' })
    vi.mocked(client.put).mockResolvedValue(wire)

    const payload = {
      tool: wire.tool,
      aiRuntime: wire.aiRuntime,
      environment: wire.environment,
      integrations: wire.integrations,
      storageMedia: wire.storageMedia,
      advanced: wire.advanced,
      expectedVersion: wire.version,
    }
    await service.update(payload)
    const sent = vi.mocked(client.put).mock.calls[0][1] as typeof payload
    expect(sent.expectedVersion).toBe('1')
    expect(sent.advanced.processorLeaseDurationMillis).toBe('30000')
    expect(sent.advanced.applicationEventQueueCapacity).toBe(512)
    expect(sent.aiRuntime.retryMaxRetries).toBe(3)
    expect(sent.tool.defaultYolo).toBe(false)
  })
})
