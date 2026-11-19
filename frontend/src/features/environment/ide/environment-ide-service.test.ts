import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  closeIdeView,
  listIdeDirectories,
  openIdeAttachment,
} from '@/features/environment/ide/environment-ide-service'
import { executeFetch, request } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({ executeFetch: vi.fn(), request: vi.fn() }))

const requestMock = vi.mocked(request)
const executeFetchMock = vi.mocked(executeFetch)

beforeEach(() => {
  requestMock.mockReset()
  executeFetchMock.mockReset()
  requestMock.mockResolvedValue(undefined)
  executeFetchMock.mockResolvedValue(undefined)
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

    expect(executeFetchMock).toHaveBeenCalledWith(
      '/api/ide/environments/environment%2Fid/views/view%20id/attachments/session-id/resource-id',
      { method: 'POST', body: binding },
    )
  })

  /** 省略目录时不能发送空 path 查询，否则服务端会把空串当成显式目标目录。 */
  it('omits the directory query when no path is requested', async () => {
    await listIdeDirectories('env-1', null)
    expect(requestMock).toHaveBeenLastCalledWith(
      'GET /api/ide/environments/{environmentId}/directories',
      { path: { environmentId: 'env-1' } },
      { signal: undefined },
    )

    const controller = new AbortController()
    await listIdeDirectories('env-1', '/home/dev', controller.signal)
    expect(requestMock).toHaveBeenLastCalledWith(
      'GET /api/ide/environments/{environmentId}/directories',
      { path: { environmentId: 'env-1' }, query: { path: '/home/dev' } },
      { signal: controller.signal },
    )
  })
})
