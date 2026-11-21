import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  closeIdeView,
  openIdeAttachment,
} from '@/features/environment/ide/environment-ide-service'
import { request } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({ request: vi.fn() }))

const requestMock = vi.mocked(request)

beforeEach(() => {
  requestMock.mockReset()
  requestMock.mockResolvedValue(undefined)
})

describe('environmentIdeService', () => {
  /** 普通关闭等待 IDE 内用户确认，不施加客户端超时；只有 discard 关闭才走有界超时。 */
  it('waits without a client timeout unless the close discards', async () => {
    await closeIdeView('env-1', 'view-1', {
      browserSessionId: 'browser-1',
      bindingEpoch: '7',
      expectedDaemonInstanceId: 'daemon-1',
      discard: false,
    })
    expect(requestMock).toHaveBeenLastCalledWith(
      'POST /api/ide/environments/{environmentId}/views/{viewId}/close',
      { path: { environmentId: 'env-1', viewId: 'view-1' }, body: expect.objectContaining({ discard: false }) },
      { timeoutMs: 0 },
    )

    await closeIdeView('env-1', 'view-1', {
      browserSessionId: 'browser-1',
      bindingEpoch: '7',
      expectedDaemonInstanceId: 'daemon-1',
      discard: true,
    })
    expect(requestMock).toHaveBeenLastCalledWith(
      'POST /api/ide/environments/{environmentId}/views/{viewId}/close',
      { path: { environmentId: 'env-1', viewId: 'view-1' }, body: expect.objectContaining({ discard: true }) },
      undefined,
    )
  })

  it('posts only the existing view binding and resource identity to the attachment route', async () => {
    const binding = {
      browserSessionId: 'browser-session',
      bindingEpoch: '7',
      expectedDaemonInstanceId: 'daemon-instance',
    }
    await openIdeAttachment(
      'environment/id',
      'view id',
      'session-id',
      'resource-id',
      binding,
    )

    expect(requestMock).toHaveBeenCalledWith(
      'POST /api/ide/environments/{environmentId}/views/{viewId}/attachments/{sessionId}/{resourceId}',
      {
        path: { environmentId: 'environment/id', viewId: 'view id', sessionId: 'session-id', resourceId: 'resource-id' },
        body: binding,
      },
    )
  })
})
