import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import { createSystemSettingsService } from '@/features/settings/system-settings-service'
import {
  DEFAULT_MAX_RESOURCE_BYTES,
  makeSettingsDto,
} from '@/test-support/settings-test-fixtures'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)

describe('systemSettingsService', () => {
  afterEach(() => {
    apiRequest.mockReset()
    vi.unstubAllGlobals()
  })

  it('maps GET to the platform settings resource and passes the body through', async () => {
    const dto = makeSettingsDto()
    apiRequest.mockResolvedValue(dto)
    const service = createSystemSettingsService()

    await expect(service.get()).resolves.toBe(dto)
    expect(apiRequest).toHaveBeenCalledWith('GET /api/platform/settings', {})
    // Long / Integer / version wire 形态在契约层原样保留。
    expect(dto.environment.maxResourceBytes).toBe(DEFAULT_MAX_RESOURCE_BYTES)
    expect(dto.aiRuntime.retryMaxRetries).toBe(3)
    expect(dto.version).toBe('0')
  })

  it('maps PUT to the platform settings resource with the full aggregate and expectedVersion', async () => {
    const base = makeSettingsDto()
    const update = {
      tool: base.tool,
      aiRuntime: base.aiRuntime,
      environment: base.environment,
      backendNetwork: base.backendNetwork,
      storageMedia: base.storageMedia,
      advanced: base.advanced,
      expectedVersion: '0',
    }
    const updated = makeSettingsDto({ version: '1' })
    updated.tool.modelGatewayBusyRetryMillis = '6000'
    apiRequest.mockResolvedValue(updated)
    const service = createSystemSettingsService()

    await expect(service.update(update)).resolves.toBe(updated)
    expect(apiRequest).toHaveBeenCalledWith('PUT /api/platform/settings', { body: update })
  })

  it('keeps Long fields as decimal strings and Integer fields as numbers on the wire', async () => {
    const service = createSystemSettingsService()
    const wire = makeSettingsDto({ version: '1' })
    apiRequest.mockResolvedValue(wire)

    const payload = {
      tool: wire.tool,
      aiRuntime: wire.aiRuntime,
      environment: wire.environment,
      backendNetwork: wire.backendNetwork,
      storageMedia: wire.storageMedia,
      advanced: wire.advanced,
      expectedVersion: wire.version,
    }
    await service.update(payload)
    const sent = (apiRequest.mock.calls[0]?.[1] as { body: typeof payload }).body
    expect(sent.expectedVersion).toBe('1')
    expect(sent.advanced.processorLeaseDurationMillis).toBe('30000')
    expect(sent.advanced.applicationEventQueueCapacity).toBe(512)
    expect(sent.aiRuntime.retryMaxRetries).toBe(3)
    expect(sent.tool.modelGatewayBusyRetryMillis).toBe('5000')
  })
})
