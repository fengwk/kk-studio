import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, afterEach, describe, expect, it } from 'vitest'
import { WebSocketServer } from 'ws'

import type { SystemProxyResolver } from '../src/contracts.js'
import {
  getOutboundNetwork,
  installOutboundNetwork,
  withLoopbackResponse,
  type OutboundNetwork,
} from '../src/network.js'
import { createHttpPutObject } from '../src/transport/resources.js'
import { createWebSocketTransport } from '../src/transport/control.js'

/** 测试用系统回退：明确直连。 */
const systemDirect: SystemProxyResolver = {
  async resolve(): Promise<string | null> {
    return null
  },
  async close(): Promise<void> {},
}

interface ForwardProxy {
  url: string
  requests: string[]
  connects: string[]
  upgrades: string[]
  close(): Promise<void>
}

/** 真实本地转发代理：支持 absolute-form、CONNECT 隧道与 Upgrade 透传。 */
async function startProxy(): Promise<ForwardProxy> {
  const requests: string[] = []
  const connects: string[] = []
  const upgrades: string[] = []
  const server = http.createServer((request, response) => {
    requests.push(request.url ?? '')
    const target = new URL(request.url ?? '/')
    const upstream = http.request(
      {
        host: target.hostname,
        port: target.port === '' ? 80 : Number(target.port),
        method: request.method,
        path: `${target.pathname}${target.search}`,
        headers: request.headers,
      },
      (up) => {
        response.writeHead(up.statusCode ?? 502, up.headers)
        up.pipe(response)
      },
    )
    upstream.on('error', () => {
      response.writeHead(502)
      response.end()
    })
    request.pipe(upstream)
  })
  server.on('connect', (request, clientSocket, head) => {
    connects.push(request.url ?? '')
    const [host, port] = (request.url ?? '').split(':')
    const upstream = net.connect(Number(port ?? 443), host, () => {
      clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
      if (head.length > 0) {
        upstream.write(head)
      }
      upstream.pipe(clientSocket)
      clientSocket.pipe(upstream)
    })
    upstream.on('error', () => clientSocket.destroy())
    clientSocket.on('error', () => upstream.destroy())
  })
  server.on('upgrade', (request, clientSocket, head) => {
    upgrades.push(request.url ?? '')
    const target = new URL(request.url ?? '/')
    const upstream = http.request({
      host: target.hostname,
      port: target.port === '' ? 80 : Number(target.port),
      method: request.method,
      path: `${target.pathname}${target.search}`,
      headers: { ...request.headers, host: target.host },
    })
    upstream.on('upgrade', (upResponse, upSocket, upHead) => {
      let raw = `HTTP/1.1 ${upResponse.statusCode} ${upResponse.statusMessage}\r\n`
      for (let index = 0; index < upResponse.rawHeaders.length; index += 2) {
        raw += `${upResponse.rawHeaders[index]}: ${upResponse.rawHeaders[index + 1]}\r\n`
      }
      raw += '\r\n'
      clientSocket.write(raw)
      if (head.length > 0) {
        upSocket.write(head)
      }
      if (upHead.length > 0) {
        upSocket.unshift(upHead)
      }
      upSocket.pipe(clientSocket)
      clientSocket.pipe(upSocket)
    })
    upstream.on('error', () => clientSocket.destroy())
    upstream.end()
    clientSocket.on('error', () => upstream.destroy())
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as net.AddressInfo).port
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    connects,
    upgrades,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.()
        server.close(() => resolve())
      }),
  }
}

interface Origin {
  url: string
  hits: string[]
  connections: number
  close(): Promise<void>
}

async function startOrigin(body = 'origin-body'): Promise<Origin> {
  const hits: string[] = []
  let connections = 0
  const server = http.createServer((request, response) => {
    hits.push(request.url ?? '')
    response.writeHead(200, { 'content-type': 'text/plain' })
    response.end(body)
  })
  server.on('connection', () => {
    connections += 1
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as net.AddressInfo).port
  return {
    url: `http://127.0.0.1:${port}`,
    hits,
    get connections() {
      return connections
    },
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.()
        server.close(() => resolve())
      }),
  }
}

async function readText(network: OutboundNetwork, url: string): Promise<string> {
  return network.withResponse(url, {}, new AbortController().signal, async (response) => {
    const chunks: Buffer[] = []
    for await (const chunk of response.body) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array))
    }
    expect(response.statusCode).toBe(200)
    return Buffer.concat(chunks).toString('utf8')
  })
}

async function withNetwork<T>(
  environment: Record<string, string | undefined>,
  run: (network: OutboundNetwork) => Promise<T>,
): Promise<T> {
  const installed = await installOutboundNetwork(environment, systemDirect)
  try {
    return await run(installed.network)
  } finally {
    await installed.close()
  }
}

const cleanups: Array<() => Promise<void>> = []
const probeChildren = new Set<ChildProcess>()
afterEach(async () => {
  for (const child of probeChildren) {
    child.kill('SIGKILL')
  }
  probeChildren.clear()
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

/** 运行独立探针子进程：动态 URL/代理地址仅经环境变量传入，返回退出码与输出（不读取源码）。 */
async function runProbe(
  environment: NodeJS.ProcessEnv,
  url: string,
  proxyUrl: string,
): Promise<{ code: number | null; stdout: string; stderr: string }> {
  const probePath = fileURLToPath(new URL('./resources/network/connect-probe.mjs', import.meta.url))
  const child = spawn(process.execPath, [probePath], {
    env: { ...environment, KK_PROBE_URL: url, KK_PROBE_PROXY: proxyUrl },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  probeChildren.add(child)
  const stdout: Buffer[] = []
  const stderr: Buffer[] = []
  child.stdout?.on('data', (chunk: Buffer) => stdout.push(chunk))
  child.stderr?.on('data', (chunk: Buffer) => stderr.push(chunk))
  const code = await new Promise<number | null>((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', (exitCode) => resolve(exitCode))
  })
  probeChildren.delete(child)
  return { code, stdout: Buffer.concat(stdout).toString('utf8'), stderr: Buffer.concat(stderr).toString('utf8') }
}

describe('outbound network integration', () => {
  it('routes HTTP GET through the chosen proxy and honours no_proxy', async () => {
    const proxy = await startProxy()
    const origin = await startOrigin()
    const bypass = await startOrigin('bypass-body')
    cleanups.push(proxy.close, origin.close, bypass.close)

    await withNetwork(
      { http_proxy: proxy.url, no_proxy: `127.0.0.1:${new URL(bypass.url).port}` },
      async (network) => {
        expect(await readText(network, origin.url)).toBe('origin-body')
        expect(proxy.requests).toHaveLength(1)
        expect(proxy.requests[0]!.startsWith(origin.url)).toBe(true)

        expect(await readText(network, bypass.url)).toBe('bypass-body')
        // no_proxy 命中直连：代理不再看到 bypass，origin 自己收到。
        expect(proxy.requests).toHaveLength(1)
        expect(bypass.hits).toHaveLength(1)
      },
    )
  })

  it('tunnels HTTPS through CONNECT under default TLS verification', async () => {
    const proxy = await startProxy()
    cleanups.push(proxy.close)
    const { key, cert, certPath } = await ensureCertificate()

    const secure = https.createServer({ key, cert }, (_request, response) => {
      response.writeHead(200)
      response.end('secure-body')
    })
    await new Promise<void>((resolve) => secure.listen(0, '127.0.0.1', () => resolve()))
    cleanups.push(
      () =>
        new Promise<void>((resolve) => {
          secure.closeAllConnections?.()
          secure.close(() => resolve())
        }),
    )
    const securePort = (secure.address() as net.AddressInfo).port

    // 独占子进程：默认 TLS 验证开启，仅信任运行时生成的测试 CA；probe 经环境变量接收目标与代理地址。
    const result = await runProbe(
      { ...process.env, NODE_EXTRA_CA_CERTS: certPath, NODE_TLS_REJECT_UNAUTHORIZED: '1' },
      `https://127.0.0.1:${securePort}/`,
      proxy.url,
    )
    expect(result.stderr).toBe('')
    expect(result.code).toBe(0)
    expect(result.stdout).toBe('secure-body')
    expect(proxy.connects).toEqual([`127.0.0.1:${securePort}`])
  })

  it('refuses to fall back to a dead proxy', async () => {
    const origin = await startOrigin()
    const dead = await startProxy()
    cleanups.push(origin.close, dead.close)
    const deadUrl = dead.url
    await dead.close()

    await withNetwork({ http_proxy: deadUrl }, async (network) => {
      await expect(readText(network, origin.url)).rejects.toThrow()
    })
    expect(origin.hits).toHaveLength(0)
  })

  it('upgrades a WebSocket through the proxy', async () => {
    const proxy = await startProxy()
    const server = new WebSocketServer({ port: 0, perMessageDeflate: true })
    await new Promise<void>((resolve) => server.once('listening', () => resolve()))
    cleanups.push(
      proxy.close,
      () =>
        new Promise<void>((resolve) => {
          for (const client of server.clients) {
            client.terminate()
          }
          server.close(() => resolve())
        }),
    )
    const port = (server.address() as net.AddressInfo).port
    server.on('connection', (socket) => {
      socket.on('message', (data) => socket.send(data.toString()))
    })

    await withNetwork({ http_proxy: proxy.url }, async () => {
      const received: string[] = []
      const connection = await createWebSocketTransport(`ws://127.0.0.1:${port}`).connect({
        onMessage(message) {
          received.push(message)
        },
        onDisconnected() {},
      })
      expect(connection.isOpen()).toBe(true)
      await connection.sendText('proxied')
      await waitFor(() => received.length === 1)
      expect(received[0]).toBe('proxied')
      connection.close()
    })
    expect(proxy.upgrades).toHaveLength(1)
    expect(proxy.upgrades[0]!.startsWith(`http://127.0.0.1:${port}`)).toBe(true)
  })

  it('never follows redirects for a signed PUT', async () => {
    const target = await startOrigin('target-body')
    cleanups.push(target.close)
    let redirected = 0
    const upload = http.createServer((_request, response) => {
      redirected += 1
      response.writeHead(307, { location: target.url })
      response.end()
    })
    await new Promise<void>((resolve) => upload.listen(0, '127.0.0.1', () => resolve()))
    cleanups.push(
      () =>
        new Promise<void>((resolve) => {
          upload.closeAllConnections?.()
          upload.close(() => resolve())
        }),
    )
    const port = (upload.address() as net.AddressInfo).port

    const put = createHttpPutObject()
    await expect(
      put(
        {
          method: 'PUT',
          url: `http://127.0.0.1:${port}/signed?X-Amz-Signature=SECRETQUERY`,
          headers: { 'content-type': 'application/octet-stream' },
          expiresAt: '2030-01-01T00:00:00.000Z',
        },
        new Uint8Array([1, 2, 3]),
        new AbortController().signal,
      ),
    ).rejects.toThrow(/redirect/)
    expect(redirected).toBe(1)
    expect(target.hits).toHaveLength(0)
  })

  it('keeps private loopback requests off the proxy under NODE_USE_ENV_PROXY', async () => {
    const proxy = await startProxy()
    const origin = await startOrigin('secret-body')
    cleanups.push(proxy.close, origin.close)

    const saved = {
      nodeUseEnvProxy: process.env.NODE_USE_ENV_PROXY,
      httpProxy: process.env.http_proxy,
      httpsProxy: process.env.https_proxy,
    }
    process.env.NODE_USE_ENV_PROXY = '1'
    process.env.http_proxy = proxy.url
    process.env.https_proxy = proxy.url
    try {
      const body = await withLoopbackResponse(origin.url, {}, new AbortController().signal, async (response) => {
        const chunks: Buffer[] = []
        for await (const chunk of response.body) {
          chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array))
        }
        return Buffer.concat(chunks).toString('utf8')
      })
      expect(body).toBe('secret-body')
      expect(proxy.requests).toHaveLength(0)

      // 扩展的私有 bridge 用同款显式 direct Agent，也必须绕过环境代理。
      const direct = new http.Agent({ keepAlive: false })
      try {
        const text = await new Promise<string>((resolve, reject) => {
          const request = http.get(origin.url, { agent: direct }, (response) => {
            const chunks: Buffer[] = []
            response.on('data', (chunk: Buffer) => chunks.push(chunk))
            response.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
          })
          request.on('error', reject)
        })
        expect(text).toBe('secret-body')
      } finally {
        direct.destroy()
      }
      expect(proxy.requests).toHaveLength(0)
    } finally {
      if (saved.nodeUseEnvProxy === undefined) {
        delete process.env.NODE_USE_ENV_PROXY
      } else {
        process.env.NODE_USE_ENV_PROXY = saved.nodeUseEnvProxy
      }
      if (saved.httpProxy === undefined) {
        delete process.env.http_proxy
      } else {
        process.env.http_proxy = saved.httpProxy
      }
      if (saved.httpsProxy === undefined) {
        delete process.env.https_proxy
      } else {
        process.env.https_proxy = saved.httpsProxy
      }
    }
  })

  it('owns one dispatcher per exchange and releases it', async () => {
    const origin = await startOrigin()
    cleanups.push(origin.close)

    await withNetwork({}, async (network) => {
      for (let index = 0; index < 3; index++) {
        expect(await readText(network, origin.url)).toBe('origin-body')
      }
    })
    // 每次交换独占且关闭 dispatcher，绝不跨请求复用 keep-alive 连接。
    expect(origin.connections).toBe(3)
  })

  it('restores the previous network when an install closes', async () => {
    const baseline = await installOutboundNetwork({}, systemDirect)
    try {
      const installed = await installOutboundNetwork({ http_proxy: 'http://127.0.0.1:9' }, systemDirect)
      expect(getOutboundNetwork()).toBe(installed.network)
      await installed.close()
      expect(getOutboundNetwork()).toBe(baseline.network)
    } finally {
      await baseline.close()
    }
  })

  it('leaves the current network untouched when the environment is invalid', async () => {
    const baseline = getOutboundNetwork()
    await expect(
      installOutboundNetwork({ https_proxy: 'invalid-secret' }, systemDirect),
    ).rejects.toThrow(/Invalid HTTP proxy or bypass configuration/)
    expect(getOutboundNetwork()).toBe(baseline)
  })

  it('does not leak the query URL into a failed request error', async () => {
    const marker = 'SECRETMARKER_QUERY'
    await withNetwork({}, async (network) => {
      let message = ''
      try {
        await network.withResponse(
          `http://127.0.0.1:1/path?token=${marker}`,
          {},
          new AbortController().signal,
          async () => undefined,
        )
      } catch (error) {
        message = (error as Error).message
      }
      expect(message.length).toBeGreaterThan(0)
      expect(message).not.toContain(marker)
    })
  })
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

let certificate: { key: string; cert: string; certPath: string } | null = null
let certificateDir: string | null = null

/**
 * 生成一次性自签 CA/服务器证书（SAN=IP:127.0.0.1）。
 *
 * <p>用跨平台 `-config` 而非 `-addext`：CI 的 ubuntu/macos/windows runner 都提供 `openssl`，缺少时测试显式失败而不是跳过。
 */
async function ensureCertificate(): Promise<{ key: string; cert: string; certPath: string }> {
  if (certificate !== null) {
    return certificate
  }
  certificateDir = mkdtempSync(join(tmpdir(), 'kk-network-cert-'))
  const keyPath = join(certificateDir, 'key.pem')
  const certPath = join(certificateDir, 'cert.pem')
  const configPath = join(certificateDir, 'req.cnf')
  writeFileSync(
    configPath,
    [
      '[req]',
      'distinguished_name = dn',
      'x509_extensions = v3_req',
      'prompt = no',
      '[dn]',
      'CN = 127.0.0.1',
      '[v3_req]',
      'subjectAltName = IP:127.0.0.1',
      'basicConstraints = critical, CA:TRUE',
      'keyUsage = critical, digitalSignature, keyCertSign',
      '',
    ].join('\n'),
  )
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      keyPath,
      '-out',
      certPath,
      '-days',
      '1',
      '-config',
      configPath,
    ],
    { stdio: ['ignore', 'ignore', 'ignore'] },
  )
  certificate = { key: readFileSync(keyPath, 'utf8'), cert: readFileSync(certPath, 'utf8'), certPath }
  return certificate
}

afterAll(() => {
  if (certificateDir !== null) {
    rmSync(certificateDir, { recursive: true, force: true })
  }
})
