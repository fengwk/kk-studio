import http from 'node:http'
import type { AddressInfo } from 'node:net'
import type { Socket } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'

import { createGitHttpClient, redirectHeaders, TimeoutSocketError } from '../../src/skill/http.js'
import { installOutboundNetwork } from '../../src/network.js'
import type { SystemProxyResolver } from '../../src/contracts.js'

/** 直连系统回退：本地测试服务器不需要 OS 代理。 */
const directSystem: SystemProxyResolver = {
  async resolve(): Promise<string | null> {
    return null
  },
  async close(): Promise<void> {},
}

interface TrackedServer {
  readonly url: string
  readonly connections: number
  readonly closes: number
  close(): Promise<void>
}

/** 本地 HTTP 服务器：记录 socket 关闭次数，便于断言连接被真正释放。 */
async function startServer(handler: http.RequestListener): Promise<{ server: TrackedServer; sockets: Socket[] }> {
  const sockets: Socket[] = []
  let closes = 0
  const server = http.createServer(handler)
  server.on('connection', (socket) => {
    sockets.push(socket)
    socket.on('close', () => {
      closes += 1
    })
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as AddressInfo).port
  return {
    sockets,
    server: {
      url: `http://127.0.0.1:${port}`,
      get connections() {
        return sockets.length
      },
      get closes() {
        return closes
      },
      close: () =>
        new Promise<void>((resolve) => {
          server.closeAllConnections?.()
          server.close(() => resolve())
        }),
    },
  }
}

async function drain(body: AsyncIterableIterator<Uint8Array>): Promise<number> {
  let total = 0
  for await (const chunk of body) {
    total += chunk.length
  }
  return total
}

const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

async function waitFor(predicate: () => boolean, timeoutMillis = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('waitFor timed out')
    }
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

describe('git http client against a live server', () => {
  it('returns an empty body and never reads the error body on non-2xx', async () => {
    const { server } = await startServer((_request, response) => {
      response.writeHead(404, { 'content-type': 'application/octet-stream' })
      response.end('SECRET_ERROR_BODY')
    })
    cleanups.push(server.close)

    const client = createGitHttpClient()
    const response = await client.request({ url: `${server.url}/missing` })
    expect(response.statusCode).toBe(404)
    expect(await drain(response.body)).toBe(0)
  })

  it('strips authorization and cookie on a cross-origin redirect but keeps safe headers', async () => {
    const received: http.IncomingHttpHeaders[] = []
    const { server: target } = await startServer((request, response) => {
      received.push(request.headers)
      response.writeHead(200)
      response.end('ok')
    })
    const { server: source } = await startServer((_request, response) => {
      response.writeHead(302, { location: `${target.url}/next` })
      response.end()
    })
    cleanups.push(source.close, target.close)

    const client = createGitHttpClient()
    const response = await client.request({
      url: `${source.url}/repo.git`,
      headers: { authorization: 'Basic SECRET', cookie: 'session=SECRET', 'git-protocol': 'version=2' },
    })
    expect(response.statusCode).toBe(200)
    expect(await drain(response.body)).toBe(2)
    expect(received).toHaveLength(1)
    expect(received[0]!.authorization).toBeUndefined()
    expect(received[0]!.cookie).toBeUndefined()
    expect(received[0]!['git-protocol']).toBe('version=2')
  })

  it('refuses a cross-origin redirect carrying a sensitive custom header', async () => {
    const { server: target } = await startServer((_request, response) => {
      response.writeHead(200)
      response.end('ok')
    })
    const { server: source } = await startServer((_request, response) => {
      response.writeHead(302, { location: `${target.url}/next` })
      response.end()
    })
    cleanups.push(source.close, target.close)

    const client = createGitHttpClient()
    await expect(
      client.request({ url: `${source.url}/repo.git`, headers: { 'x-api-key': 'SECRET' } }),
    ).rejects.toThrow(/redirect/)
  })

  it('refuses a method with a body that would be replayed across a redirect', async () => {
    const { server: target } = await startServer((_request, response) => {
      response.writeHead(200)
      response.end('ok')
    })
    const { server: source } = await startServer((_request, response) => {
      response.writeHead(307, { location: `${target.url}/next` })
      response.end()
    })
    cleanups.push(source.close, target.close)

    const client = createGitHttpClient()
    await expect(
      client.request({
        url: `${source.url}/repo.git`,
        method: 'POST',
        body: (async function* () {
          yield Buffer.from('payload')
        })(),
      }),
    ).rejects.toThrow(/redirect/)
  })

  it('releases the connection when the consumer stops early', async () => {
    const { server } = await startServer((_request, response) => {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      const timer = setInterval(() => response.write('chunk'), 20)
      response.on('close', () => clearInterval(timer))
    })
    cleanups.push(server.close)

    const client = createGitHttpClient()
    const response = await client.request({ url: `${server.url}/stream` })
    expect(response.statusCode).toBe(200)
    for await (const _chunk of response.body) {
      break
    }
    await waitFor(() => server.closes >= 1)
  })

  it('reclaims an unconsumed response when the owning network closes', async () => {
    const { server } = await startServer((_request, response) => {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      response.write('partial')
      // 永不结束：只有 owner 关闭时才会释放。
    })
    cleanups.push(server.close)

    const installed = await installOutboundNetwork({}, directSystem)
    const client = createGitHttpClient()
    const response = await client.request({ url: `${server.url}/never-consumed` })
    expect(response.statusCode).toBe(200)
    await installed.close()
    await waitFor(() => server.closes >= 1)
  })

  it('maps a stalled body read to GIT_READ_TIMEOUT', async () => {
    const { server } = await startServer((_request, response) => {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      response.write('partial')
      // 保持开启，触发 body 读超时。
    })
    cleanups.push(server.close)

    const client = createGitHttpClient({ readMillis: 150 })
    const response = await client.request({ url: `${server.url}/stall` })
    let error: unknown
    try {
      await drain(response.body)
    } catch (caught) {
      error = caught
    }
    expect(error).toBeInstanceOf(TimeoutSocketError)
    expect((error as TimeoutSocketError).code).toBe('GIT_READ_TIMEOUT')
  })
})

describe('redirect header safety', () => {
  it('keeps headers unchanged for a same-origin redirect', () => {
    expect(
      redirectHeaders({ authorization: 'Bearer token' }, new URL('https://a.example/x'), new URL('https://a.example/y')),
    ).toEqual({ authorization: 'Bearer token' })
  })

  it('refuses an HTTPS to HTTP downgrade', () => {
    expect(() =>
      redirectHeaders({ authorization: 'Bearer token' }, new URL('https://a.example/x'), new URL('http://a.example/y')),
    ).toThrow(/redirect/)
  })

  it('refuses a sensitive custom header it cannot safely keep cross-origin', () => {
    expect(() =>
      redirectHeaders({ 'x-api-key': 'SECRET' }, new URL('https://a.example/x'), new URL('https://b.example/y')),
    ).toThrow(/redirect/)
  })
})
