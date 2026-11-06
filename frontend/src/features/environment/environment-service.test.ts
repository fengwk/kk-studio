import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/features/environment/environment-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'

afterEach(() => {
  vi.unstubAllGlobals()
})

/** IDE 函数仍在 environment-service 中，由调用方持有 claim 与 binding 身份；文件浏览与下载见各调用方测试。 */
describe('environmentService IDE', () => {
  it('opens or resumes an ide view with the full binding identity', async () => {
    const environmentId = 'a1000000-0000-4000-8000-000000000001'
    const viewId = 'c1000000-0000-4000-8000-000000000001'
    const request = {
      viewId,
      documentId: 'd1000000-0000-4000-8000-000000000001',
      eventNodeId: 'e1000000-0000-4000-8000-000000000001',
      connectionId: 'f1000000-0000-4000-8000-000000000001',
      claimId: '91000000-0000-4000-8000-000000000001',
      mode: 'resume',
    }
    const view = {
      ...request,
      environmentId,
      daemonInstanceId: '92000000-0000-4000-8000-000000000001',
      bindingEpoch: '7',
      directory: '/home/user/workspace',
      workspacePath: '/home/user/workspace',
      state: 'OPEN',
      entryUrl: `/environment-ide/${environmentId}/${viewId}/__ide/bootstrap?token=xyz`,
    }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(view)
    const service = createEnvironmentService(client)
    const result = await service.openIdeView(environmentId, request)
    expect(client.post).toHaveBeenCalledWith(`/environments/${environmentId}/ide/views`, request)
    expect(result).toEqual(view)
  })

  it('changes ide view directory with the current document and binding epoch', async () => {
    const updated = {
      viewId: 'c1000000-0000-4000-8000-000000000001',
      environmentId: 'a1000000-0000-4000-8000-000000000001',
      documentId: 'd1000000-0000-4000-8000-000000000001',
      eventNodeId: 'e1000000-0000-4000-8000-000000000001',
      connectionId: 'f1000000-0000-4000-8000-000000000001',
      claimId: '91000000-0000-4000-8000-000000000001',
      daemonInstanceId: '92000000-0000-4000-8000-000000000001',
      bindingEpoch: '8',
      directory: '/new/path',
      workspacePath: '/new/path',
      state: 'OPEN',
      entryUrl: null,
    }
    const binding = {
      documentId: updated.documentId,
      bindingEpoch: '7',
      directory: '/new/path',
    }
    const client = createMockHttpClient()
    client.put.mockResolvedValue(updated)
    const service = createEnvironmentService(client)
    const result = await service.changeIdeViewDirectory('env-1', updated.viewId, binding)
    expect(client.put).toHaveBeenCalledWith(
      `/environments/env-1/ide/views/${updated.viewId}/directory`,
      binding,
    )
    expect(result).toEqual(updated)
  })

  it('lists ide directories with the encoded environment id and the requested path', async () => {
    const client = createMockHttpClient()
    const listing = {
      path: '/home/dev/a b',
      parentPath: '/home',
      children: [{ name: 'project', path: '/home/dev/a b/project' }],
      truncated: false,
    }
    client.get.mockResolvedValue(listing)
    const service = createEnvironmentService(client)
    const signal = new AbortController().signal

    expect(await service.listIdeDirectories('env/1', '/home/dev/a b', signal)).toEqual(listing)
    expect(client.get).toHaveBeenCalledWith('/environments/env%2F1/ide/directories', {
      params: { path: '/home/dev/a b' },
      signal,
    })

    // 省略 path 时服务端返回宿主主目录，请求不携带空查询参数。
    await service.listIdeDirectories('env/1')
    expect(client.get).toHaveBeenLastCalledWith('/environments/env%2F1/ide/directories', {
      signal: undefined,
    })
  })

  it('opens an ide view terminal with the current binding identity', async () => {
    const binding = {
      documentId: 'd1000000-0000-4000-8000-000000000001',
      bindingEpoch: '7',
    }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(undefined)
    const service = createEnvironmentService(client)
    await service.openIdeViewTerminal('env-1', 'c1000000-0000-4000-8000-000000000001', binding)
    expect(client.post).toHaveBeenCalledWith(
      '/environments/env-1/ide/views/c1000000-0000-4000-8000-000000000001/terminal',
      binding,
    )
  })

  it('closes an ide view with an explicit discard flag in the DELETE body', async () => {
    const binding = {
      documentId: 'd1000000-0000-4000-8000-000000000001',
      bindingEpoch: '7',
      discard: true,
    }
    const client = createMockHttpClient()
    client.delete.mockResolvedValue(undefined)
    const service = createEnvironmentService(client)
    await service.closeIdeView('env/a', 'c1000000-0000-4000-8000-000000000001', binding)
    expect(client.delete).toHaveBeenCalledWith(
      '/environments/env%2Fa/ide/views/c1000000-0000-4000-8000-000000000001',
      { data: binding },
    )
    await service.closeIdeView('env/a', 'view/a', { ...binding, discard: false })
    expect(client.delete).toHaveBeenLastCalledWith(
      '/environments/env%2Fa/ide/views/view%2Fa',
      { data: { ...binding, discard: false }, timeout: 0 },
    )
  })

  it('sends the binding to the mobile layout endpoint', async () => {
    const client = createMockHttpClient()
    const binding = { documentId: 'doc', bindingEpoch: '7' }
    await createEnvironmentService(client).setIdeViewMobileLayout('env/a', 'view/a', binding)
    expect(client.post).toHaveBeenCalledWith('/environments/env%2Fa/ide/views/view%2Fa/mobile-layout', binding)
  })
})
