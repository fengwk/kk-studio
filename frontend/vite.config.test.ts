// @vitest-environment node
import crypto from 'node:crypto'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import type net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import type stream from 'node:stream'
import { createServer, loadConfigFromFile } from 'vite'
import { describe, expect, it } from 'vitest'

describe('vite dev proxy', () => {
  it('binds to loopback and keeps Vite host allowlisting enabled by default', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const server = source.match(/server: \{([\s\S]*?)\n\s{2}\},/)

    expect(server?.[1]).toContain("host: '127.0.0.1'")
    expect(server?.[1]).not.toContain('allowedHosts')
  })

  it('rewrites the application event WebSocket origin to the backend target', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const apiProxy = source.match(/'\/api': \{([\s\S]*?)\n\s{6}\},/)

    expect(apiProxy?.[1]).toContain("target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'")
    expect(apiProxy?.[1]).toContain('changeOrigin: true')
    expect(apiProxy?.[1]).toContain('rewriteWsOrigin: true')
    expect(apiProxy?.[1]).toContain('ws: true')
  })

  it('preserves browser Host and Origin for same-origin IDE HTTP and WebSocket proxy', () => {
    const source = readFileSync(path.resolve(process.cwd(), 'vite.config.ts'), 'utf8')
    const ideProxy = source.match(/'\/environment-ide': \{([\s\S]*?)\n\s{6}\},/)

    expect(ideProxy?.[1]).toContain("target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'")
    expect(ideProxy?.[1]).toContain('changeOrigin: false')
    expect(ideProxy?.[1]).toContain('ws: true')
    expect(ideProxy?.[1]).not.toContain('rewriteWsOrigin')
  })

  it('verifies loopback Vite dev server correctly forwards IDE bootstrap, preserves HTTP POST Host/Origin pair, and upgrades WebSocket without rewriting Origin', async () => {
    const recordedRequests: Array<{
      method?: string
      url?: string
      headers: http.IncomingHttpHeaders
    }> = []

    const recordedUpgrades: Array<{
      url?: string
      headers: http.IncomingHttpHeaders
    }> = []

    const activeSockets = new Set<stream.Duplex>()

    const stubBackend = http.createServer((req, res) => {
      recordedRequests.push({
        method: req.method,
        url: req.url,
        headers: req.headers,
      })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ status: 200, data: 'ok' }))
    })

    stubBackend.on('connection', (socket) => {
      activeSockets.add(socket)
      socket.on('close', () => activeSockets.delete(socket))
    })

    stubBackend.on('upgrade', (req, socket) => {
      activeSockets.add(socket)
      socket.on('close', () => activeSockets.delete(socket))
      recordedUpgrades.push({
        url: req.url,
        headers: req.headers,
      })
      const key = req.headers['sec-websocket-key']
      if (key) {
        const accept = crypto
          .createHash('sha1')
          .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
          .digest('base64')
        socket.write(
          'HTTP/1.1 101 Switching Protocols\r\n' +
            'Upgrade: websocket\r\n' +
            'Connection: Upgrade\r\n' +
            `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
        )
      } else {
        socket.destroy()
      }
    })

    let tempDir: string | null = null
    const originalApiTarget = process.env.API_PROXY_TARGET
    let viteServer: Awaited<ReturnType<typeof createServer>> | null = null

    try {
      await new Promise<void>((resolve) => stubBackend.listen(0, '127.0.0.1', resolve))
      const backendPort = (stubBackend.address() as net.AddressInfo).port

      tempDir = mkdtempSync(path.join(os.tmpdir(), 'vite-ide-proxy-test-'))
      writeFileSync(path.join(tempDir, 'index.html'), '<!doctype html><html><body>ok</body></html>')

      process.env.API_PROXY_TARGET = `http://127.0.0.1:${backendPort}`

      const loaded = await loadConfigFromFile(
        { command: 'serve', mode: 'development' },
        path.resolve(process.cwd(), 'vite.config.ts'),
      )
      expect(loaded).not.toBeNull()
      const baseConfig = loaded!.config

      viteServer = await createServer({
        ...baseConfig,
        configFile: false,
        root: tempDir,
        plugins: [],
        optimizeDeps: {
          noDiscovery: true,
          include: [],
        },
        server: {
          ...baseConfig.server,
          port: 0,
          strictPort: false,
        },
      })
      await viteServer.listen()
      const vitePort = (viteServer.httpServer?.address() as net.AddressInfo).port

      const sendRequest = (options: {
        path: string
        method?: string
        headers?: http.OutgoingHttpHeaders
        body?: string
      }): Promise<{ status?: number; data: string }> =>
        new Promise((resolve, reject) => {
          const req = http.request(
            {
              hostname: '127.0.0.1',
              port: vitePort,
              path: options.path,
              method: options.method ?? 'GET',
              headers: options.headers,
            },
            (res) => {
              let data = ''
              res.on('data', (chunk) => {
                data += chunk
              })
              res.on('end', () => resolve({ status: res.statusCode, data }))
            },
          )
          req.on('error', reject)
          if (options.body) {
            req.write(options.body)
          }
          req.end()
        })

      // 1. IDE Bootstrap GET: path & query must NOT be rewritten
      const bootstrapPath =
        '/environment-ide/e1000000-0000-4000-8000-000000000001/__ide/bootstrap?token=test-token-123'
      const getRes = await sendRequest({
        path: bootstrapPath,
        headers: { Host: `127.0.0.1:${vitePort}` },
      })

      expect(getRes.status).toBe(200)
      const lastGet = recordedRequests.find((r) => r.method === 'GET' && r.url === bootstrapPath)
      expect(lastGet).toBeDefined()
      expect(lastGet?.url).toBe(bootstrapPath)

      // 2. Unsafe HTTP POST: Host & Origin paired together without rewrite (changeOrigin: false)
      const postPath =
        '/environment-ide/e1000000-0000-4000-8000-000000000001/api/terminal?rows=24&cols=80'
      const expectedHost = `127.0.0.1:${vitePort}`
      const expectedOrigin = `http://127.0.0.1:${vitePort}`

      const postRes = await sendRequest({
        path: postPath,
        method: 'POST',
        headers: {
          Host: expectedHost,
          Origin: expectedOrigin,
          'Sec-Fetch-Site': 'same-origin',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ command: 'echo hello' }),
      })

      expect(postRes.status).toBe(200)
      const lastPost = recordedRequests.find((r) => r.method === 'POST' && r.url === postPath)
      expect(lastPost).toBeDefined()
      expect(lastPost?.headers.host).toBe(expectedHost)
      expect(lastPost?.headers.origin).toBe(expectedOrigin)

      // 3. WebSocket Upgrade: upgrade reaches stub and preserves same-origin without rewrite
      const wsPath =
        '/environment-ide/e1000000-0000-4000-8000-000000000001/vscode-stream?token=test-token-123'
      await new Promise<void>((resolve, reject) => {
        const req = http.request({
          hostname: '127.0.0.1',
          port: vitePort,
          path: wsPath,
          method: 'GET',
          headers: {
            Host: expectedHost,
            Origin: expectedOrigin,
            Connection: 'Upgrade',
            Upgrade: 'websocket',
            'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==',
            'Sec-WebSocket-Version': '13',
          },
        })
        req.on('response', (res) => {
          res.resume()
          reject(new Error(`WebSocket upgrade failed with HTTP status ${res.statusCode}`))
        })
        req.on('upgrade', (_res, socket) => {
          socket.destroy()
          resolve()
        })
        req.on('error', reject)
        req.end()
      })

      const lastUpgrade = recordedUpgrades.find((u) => u.url === wsPath)
      expect(lastUpgrade).toBeDefined()
      expect(lastUpgrade?.headers.host).toBe(expectedHost)
      expect(lastUpgrade?.headers.origin).toBe(expectedOrigin)
    } finally {
      if (viteServer) {
        await viteServer.close()
      }
      for (const s of activeSockets) {
        s.destroy()
      }
      activeSockets.clear()
      if (stubBackend.listening) {
        stubBackend.closeAllConnections?.()
        await new Promise<void>((resolve) => stubBackend.close(() => resolve()))
      }
      if (tempDir) {
        rmSync(tempDir, { recursive: true, force: true })
      }
      if (originalApiTarget !== undefined) {
        process.env.API_PROXY_TARGET = originalApiTarget
      } else {
        delete process.env.API_PROXY_TARGET
      }
    }
  })
})

describe('install verification targets', () => {
  it('keeps UI tests and coverage pointed at existing files', () => {
    // 迁移目录后，Vitest 不应悄悄遗漏安装器依赖的 UI 测试或覆盖目标。
    const source = readFileSync(path.resolve(process.cwd(), 'vite.install.config.ts'), 'utf8')
    const targets = [...source.matchAll(/'(src\/[^']+)'/gu)].map(match => match[1])
    expect(targets).toEqual(expect.arrayContaining([
      'src/shared/ui/feedback/Toast.test.tsx',
      'src/shared/ui/controls/Select.test.tsx',
      'src/shared/ui/feedback/Toast.tsx',
    ]))
    for (const target of targets) {
      expect(existsSync(path.resolve(process.cwd(), target)), target).toBe(true)
    }
  })
})
