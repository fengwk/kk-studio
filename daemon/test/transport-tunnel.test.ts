import http, { type IncomingHttpHeaders } from 'node:http'
import net from 'node:net'
import crypto from 'node:crypto'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { constants as h2constants, type ServerHttp2Stream } from 'node:http2'
import { WebSocketServer } from 'ws'
import { describe, expect, it, vi } from 'vitest'

import {
  handleIdeConnectRequest,
  handleIdeHttpRequest,
  parseIdePath,
  validateUpstream,
} from '../src/transport/ide-tunnel.js'
import { IdeError } from '../src/ide/errors.js'
import type { IdeService, IdeStreamUpstream } from '../src/contracts.js'

const {
  HTTP2_HEADER_STATUS,
  NGHTTP2_INTERNAL_ERROR,
} = h2constants

class MockServerHttp2Stream extends PassThrough {
  headersSent = false
  closed = false
  destroyed = false
  respondedHeaders: Record<string, unknown> | null = null
  closeCode: number | null = null
  shouldThrowOnRespond = false
  shouldThrowOnWrite = false

  constructor() {
    super({ autoDestroy: false, emitClose: false })
  }

  override write(chunk: any, ...args: any[]): boolean {
    if (this.shouldThrowOnWrite) {
      throw new Error('write error')
    }
    return super.write(chunk, ...args)
  }

  respond(headers: Record<string, unknown>, _options?: { endStream?: boolean }): void {
    if (this.shouldThrowOnRespond) {
      throw new Error('respond error')
    }
    this.headersSent = true
    this.respondedHeaders = headers
  }

  close(code: number): void {
    this.closed = true
    this.closeCode = code
    this.emit('close')
  }
}

function makeValidUpstream(overrides: Partial<IdeStreamUpstream> = {}): IdeStreamUpstream {
  return {
    host: '127.0.0.1',
    port: 8080,
    path: '/view',
    headers: [['x-test', 'value']],
    signal: new AbortController().signal,
    release: () => {},
    ...overrides,
  }
}

function makeRawWebSocketServer(extraHead = ''): Promise<{ port: number; close: () => Promise<void> }> {
  const server = net.createServer((socket) => {
    socket.once('data', (chunk) => {
      const text = chunk.toString('utf8')
      const match = text.match(/sec-websocket-key:\s*([^\r\n]+)/i)
      const key = match ? match[1].trim() : 'dGhlIHNhbXBsZSBub25jZQ=='
      const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${accept}\r\n\r\n` +
          extraHead,
      )
    })
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const port = (server.address() as net.AddressInfo).port
      resolve({
        port,
        close: () => new Promise<void>((r) => server.close(() => r())),
      })
    })
  })
}

describe('ide-tunnel unit tests', () => {
  describe('validateUpstream', () => {
    it('rejects non-object or null upstream', () => {
      expect(() => validateUpstream(null as any)).toThrow(/resolved upstream is required/)
      expect(() => validateUpstream(undefined as any)).toThrow(/resolved upstream is required/)
      expect(() => validateUpstream('bad' as any)).toThrow(/resolved upstream is required/)
    })

    it('validates host numeric loopback', () => {
      expect(() => validateUpstream(makeValidUpstream({ host: 123 as any }))).toThrow(/numeric loopback host/)
      expect(() => validateUpstream(makeValidUpstream({ host: '192.168.1.1' }))).toThrow(/numeric loopback host/)
      expect(() => validateUpstream(makeValidUpstream({ host: 'example.com' }))).toThrow(/numeric loopback host/)
      expect(() => validateUpstream(makeValidUpstream({ host: '10.0.0.1' }))).toThrow(/numeric loopback host/)

      // 有效的 loopback
      expect(validateUpstream(makeValidUpstream({ host: '127.0.0.1' })).host).toBe('127.0.0.1')
      expect(validateUpstream(makeValidUpstream({ host: '127.1.2.3' })).host).toBe('127.1.2.3')
      expect(validateUpstream(makeValidUpstream({ host: '::1' })).host).toBe('::1')
      expect(validateUpstream(makeValidUpstream({ host: '[::1]' })).host).toBe('[::1]')
    })

    it('validates port range', () => {
      expect(() => validateUpstream(makeValidUpstream({ port: 0 }))).toThrow(/port is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ port: 65536 }))).toThrow(/port is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ port: -1 }))).toThrow(/port is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ port: 1.5 }))).toThrow(/port is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ port: NaN }))).toThrow(/port is invalid/)
      expect(validateUpstream(makeValidUpstream({ port: 80 })).port).toBe(80)
    })

    it('validates path format', () => {
      expect(() => validateUpstream(makeValidUpstream({ path: 'no-leading-slash' }))).toThrow(/path is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ path: '//double-slash' }))).toThrow(/path is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ path: '/bad\x00char' }))).toThrow(/path is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ path: 123 as any }))).toThrow(/path is invalid/)
      expect(validateUpstream(makeValidUpstream({ path: '/ok/path' })).path).toBe('/ok/path')
    })

    it('validates headers array format', () => {
      expect(() => validateUpstream(makeValidUpstream({ headers: 'not-array' as any }))).toThrow(/headers are invalid/)
      expect(() => validateUpstream(makeValidUpstream({ headers: ['not-pair' as any] }))).toThrow(/headers are invalid/)
      expect(() => validateUpstream(makeValidUpstream({ headers: [['bad header name!', 'val']] }))).toThrow(/headers are invalid/)
      expect(() => validateUpstream(makeValidUpstream({ headers: [['valid-name', 'bad\x00val']] }))).toThrow(/headers are invalid/)
      expect(validateUpstream(makeValidUpstream({ headers: [['valid-name', 'valid-val']] }))).toBeDefined()
    })

    it('validates cookie format', () => {
      expect(() => validateUpstream(makeValidUpstream({ cookie: 123 as any }))).toThrow(/cookie is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ cookie: 'bad\x00cookie' }))).toThrow(/cookie is invalid/)
      expect(validateUpstream(makeValidUpstream({ cookie: 'valid=cookie' })).cookie).toBe('valid=cookie')
    })

    it('validates protocols format', () => {
      expect(() => validateUpstream(makeValidUpstream({ protocols: 'not-array' as any }))).toThrow(/protocols are invalid/)
      expect(() => validateUpstream(makeValidUpstream({ protocols: ['invalid protocol!'] }))).toThrow(/protocols are invalid/)
      expect(validateUpstream(makeValidUpstream({ protocols: ['valid-protocol'] })).protocols).toEqual(['valid-protocol'])
    })

    it('validates signal and release lifecycle', () => {
      expect(() => validateUpstream(makeValidUpstream({ signal: null as any }))).toThrow(/lifecycle is invalid/)
      expect(() => validateUpstream(makeValidUpstream({ release: null as any }))).toThrow(/lifecycle is invalid/)
    })
  })

  describe('parseIdePath', () => {
    it('returns null on invalid inputs', () => {
      expect(parseIdePath(undefined)).toBeNull()
      expect(parseIdePath(null as any)).toBeNull()
      expect(parseIdePath(123 as any)).toBeNull()
      expect(parseIdePath('/not-ide/path')).toBeNull()
      expect(parseIdePath('/ide/not-a-uuid/1/path')).toBeNull()
      expect(parseIdePath('/ide/11111111-1111-4111-8111-111111111111/0/path')).toBeNull() // epoch 必须为非零正整数
      expect(parseIdePath('/ide/11111111-1111-4111-8111-111111111111/1')).toBeNull() // 缺少 viewPath 开头斜线
    })

    it('parses valid paths', () => {
      const parsed = parseIdePath('/ide/11111111-1111-4111-8111-111111111111/42/sub/path?query=1')
      expect(parsed).toEqual({
        viewId: '11111111-1111-4111-8111-111111111111',
        bindingEpoch: '42',
        viewPath: '/sub/path?query=1',
      })
    })
  })

  describe('handleIdeHttpRequest', () => {
    it('responds with 400 when path is invalid', async () => {
      const stream = new MockServerHttp2Stream()
      const ide = {} as IdeService
      await handleIdeHttpRequest(stream as any, { ':path': '/invalid' }, ide)
      expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(400)
    })

    it('responds with mapped error status on resolveView failure', async () => {
      const stream404 = new MockServerHttp2Stream()
      const ide404 = {
        resolveView: async () => {
          throw new IdeError('gone', 'VIEW_GONE')
        },
      } as unknown as IdeService
      await handleIdeHttpRequest(stream404 as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide404)
      expect(stream404.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(404)

      const stream503 = new MockServerHttp2Stream()
      const ide503 = {
        resolveView: async () => {
          throw new IdeError('busy', 'UNAVAILABLE')
        },
      } as unknown as IdeService
      await handleIdeHttpRequest(stream503 as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide503)
      expect(stream503.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(503)

      const stream502 = new MockServerHttp2Stream()
      const ide502 = {
        resolveView: async () => {
          throw new Error('generic')
        },
      } as unknown as IdeService
      await handleIdeHttpRequest(stream502 as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide502)
      expect(stream502.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(502)
    })

    it('releases upstream immediately if stream is already closed or destroyed', async () => {
      const stream = new MockServerHttp2Stream()
      stream.closed = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ release: () => { released = true } }),
      } as unknown as IdeService
      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      expect(released).toBe(true)
    })

    it('responds 502 on upstream network error and cleans up', async () => {
      const stream = new MockServerHttp2Stream()
      let released = false
      // 端口 1 通常不可连接，会触发 ECONNREFUSED
      const ide = {
        resolveView: async () => makeValidUpstream({ port: 1, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'x-arr': ['a', 'b'],
          'x-undef': undefined,
        },
        ide,
      )

      await vi.waitFor(() => {
        expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(502)
        expect(released).toBe(true)
      })
    })

    it('closes stream on upstreamReq error when headers are already sent', async () => {
      const stream = new MockServerHttp2Stream()
      stream.headersSent = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port: 1, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(stream.closed).toBe(true)
        expect(stream.closeCode).toBe(NGHTTP2_INTERNAL_ERROR)
        expect(released).toBe(true)
      })
    })

    it('handles successful upstream response and end event', async () => {
      const server = http.createServer((_req, res) => {
        res.writeHead(200, { 'content-type': 'text/plain' })
        res.end('done')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      stream.end()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('destroys upstreamRes and releases upstream if stream is closed before response', async () => {
      let resolveUpstreamRes: () => void = () => {}
      let serverReceived = false
      const server = http.createServer((_req, res) => {
        serverReceived = true
        new Promise<void>((r) => { resolveUpstreamRes = r }).then(() => {
          res.writeHead(200)
          res.end('ok')
        })
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))
      const stream = new MockServerHttp2Stream()
      stream.end()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      const p = handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(serverReceived).toBe(true)
      })
      stream.closed = true
      resolveUpstreamRes()
      await p
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles stream.respond throw in handleIdeHttpRequest', async () => {
      const server = http.createServer((_req, res) => {
        res.writeHead(200)
        res.end('ok')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))
      const stream = new MockServerHttp2Stream()
      stream.end()
      stream.shouldThrowOnRespond = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles upstreamRes error during HTTP proxy', async () => {
      const server = http.createServer((req, res) => {
        res.writeHead(200, { 'transfer-encoding': 'chunked' })
        res.write('part')
        req.socket.destroy()
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))
      const stream = new MockServerHttp2Stream()
      stream.end()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('closes stream on service abort signal', async () => {
      const abortCtrl = new AbortController()
      let released = false

      // 启动一个挂起的 http server
      let connected = false
      const server = http.createServer(() => {})
      server.on('connection', () => {
        connected = true
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      const ide = {
        resolveView: async () => makeValidUpstream({
          port,
          signal: abortCtrl.signal,
          cookie: 'secret-cookie',
          release: () => { released = true },
        }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(connected).toBe(true)
      })

      abortCtrl.abort()
      await vi.waitFor(() => {
        expect(stream.closed).toBe(true)
        expect(stream.closeCode).toBe(NGHTTP2_INTERNAL_ERROR)
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('releases upstream immediately if stream is closed before http request is dispatched', async () => {
      const stream = new MockServerHttp2Stream()
      stream.closed = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeHttpRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      expect(released).toBe(true)
    })

    it('passes array headers and upstream cookie to upstream in handleIdeHttpRequest', async () => {
      let receivedHeaders: http.IncomingHttpHeaders | null = null
      const server = http.createServer((req, res) => {
        receivedHeaders = req.headers
        res.writeHead(200)
        res.end('ok')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      stream.end()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({
          port,
          cookie: 'session=abc',
          release: () => { released = true },
        }),
      } as unknown as IdeService

      await handleIdeHttpRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          accept: ['text/html', 'application/json'],
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(released).toBe(true)
        expect(receivedHeaders).not.toBeNull()
      })
      expect(receivedHeaders!['cookie']).toBe('session=abc')
      await new Promise<void>((r) => server.close(() => r()))
    })
  })

  describe('handleIdeConnectRequest', () => {
    it('responds with 400 when path is invalid', async () => {
      const stream = new MockServerHttp2Stream()
      const ide = {} as IdeService
      await handleIdeConnectRequest(stream as any, { ':path': '/bad' }, ide)
      expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(400)
    })

    it('responds with mapped error status on resolveView failure', async () => {
      const stream = new MockServerHttp2Stream()
      const ide = {
        resolveView: async () => {
          throw new IdeError('unavail', 'UNAVAILABLE')
        },
      } as unknown as IdeService
      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(503)
    })

    it('releases upstream immediately if stream is already closed', async () => {
      const stream = new MockServerHttp2Stream()
      stream.destroyed = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ release: () => { released = true } }),
      } as unknown as IdeService
      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      expect(released).toBe(true)
    })

    it('responds 502 when upstream is unreachable', async () => {
      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port: 1, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'key',
        },
        ide,
      )

      await vi.waitFor(() => {
        expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(502)
        expect(released).toBe(true)
      })
    })

    it('closes stream on upgradeReq error when headers are already sent', async () => {
      const stream = new MockServerHttp2Stream()
      stream.headersSent = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port: 1, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(stream.closed).toBe(true)
        expect(stream.closeCode).toBe(NGHTTP2_INTERNAL_ERROR)
        expect(released).toBe(true)
      })
    })

    it('handles non-101 ordinary response from upstream (e.g. 403 Forbidden)', async () => {
      const server = http.createServer((_req, res) => {
        res.writeHead(403, { 'x-err': 'forbidden' })
        res.end('no upgrade')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(403)
        expect(stream.respondedHeaders?.['x-err']).toBe('forbidden')
        expect(released).toBe(true)
      })

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles non-upgrade response during connect request (e.g. 400)', async () => {
      const server = http.createServer((_req, res) => {
        res.writeHead(400, { 'x-err': 'bad' })
        res.end('bad')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(400)
        expect(released).toBe(true)
      })

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('releases upstream immediately if stream is closed before connect request is dispatched', async () => {
      const stream = new MockServerHttp2Stream()
      stream.closed = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      expect(released).toBe(true)
    })

    it('handles stream.respond throw and upstream.cookie on non-upgrade response', async () => {
      let receivedCookie: string | undefined
      const server = http.createServer((req, res) => {
        receivedCookie = req.headers['cookie']
        res.writeHead(403)
        res.end('forbidden')
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      stream.shouldThrowOnRespond = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({
          port,
          cookie: 'token=xyz',
          release: () => { released = true },
        }),
      } as unknown as IdeService

      await handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(released).toBe(true)
        expect(receivedCookie).toBe('token=xyz')
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('destroys upstreamRes and releases upstream if stream is closed before non-upgrade response', async () => {
      let resolveRes: () => void = () => {}
      let serverReceived = false
      const server = http.createServer((_req, res) => {
        serverReceived = true
        new Promise<void>((r) => { resolveRes = r }).then(() => {
          res.writeHead(403)
          res.end('forbidden')
        })
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      const p = handleIdeConnectRequest(stream as any, { ':path': '/ide/11111111-1111-4111-8111-111111111111/1/' }, ide)
      await vi.waitFor(() => {
        expect(serverReceived).toBe(true)
      })
      stream.closed = true
      resolveRes()
      await p
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('passes sec-websocket-extensions header back to stream on upgrade', async () => {
      const server = http.createServer()
      const wss = new WebSocketServer({ noServer: true, perMessageDeflate: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, () => {})
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
          'sec-websocket-extensions': 'permessage-deflate',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(stream.headersSent).toBe(true)
        expect(stream.respondedHeaders?.['sec-websocket-extensions']).toBeDefined()
      })
      stream.emit('close')
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles successful websocket upgrade with headers, head data, duplex piping, end and close', async () => {
      const server = http.createServer()
      const wss = new WebSocketServer({ noServer: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, (ws) => {
          ws.on('message', (data) => {
            ws.send(`echo:${data.toString()}`)
          })
        })
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
          'sec-websocket-protocol': 'chat',
        },
        ide,
      )

      await vi.waitFor(() => {
        expect(stream.respondedHeaders?.[HTTP2_HEADER_STATUS]).toBe(200)
      })

      // 测试半关闭
      stream.end()

      // 测试关闭
      stream.emit('close')
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles stream.respond throw and pipe errors during websocket upgrade', async () => {
      const server = http.createServer()
      const wss = new WebSocketServer({ noServer: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, () => {})
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      stream.shouldThrowOnRespond = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles stream error in tunnel pipe', async () => {
      const server = http.createServer()
      const wss = new WebSocketServer({ noServer: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, () => {})
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(stream.headersSent).toBe(true)
      })

      // 触发 stream error
      stream.emit('error', new Error('stream error'))
      expect(stream.closeCode).toBe(NGHTTP2_INTERNAL_ERROR)
      expect(released).toBe(true)

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('destroys upgrade response if stream is closed before upgrade event', async () => {
      const server = http.createServer()
      const wss = new WebSocketServer({ noServer: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, () => {})
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      stream.closed = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await new Promise<void>((r) => server.close(() => r()))
    })

    it('handles upstream socket close event in tunnel pipe', async () => {
      const server = http.createServer()
      let wsClient: any = null
      const wss = new WebSocketServer({ noServer: true })
      server.on('upgrade', (req, socket, head) => {
        wss.handleUpgrade(req, socket, head, (ws) => {
          wsClient = ws
        })
      })
      const port = await new Promise<number>((r) => server.listen(0, '127.0.0.1', () => r((server.address() as net.AddressInfo).port)))

      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(wsClient).not.toBeNull()
      })

      // 服务端关闭 WebSocket
      wsClient.terminate()
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })

      await new Promise<void>((r) => server.close(() => r()))
    })

    it('writes initial head buffer to stream on upgrade', async () => {
      const rawServer = await makeRawWebSocketServer('initial-head-bytes')
      const stream = new MockServerHttp2Stream()
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port: rawServer.port, release: () => { released = true } }),
      } as unknown as IdeService

      let receivedHead = ''
      stream.on('data', (chunk) => {
        receivedHead += chunk.toString('utf8')
      })

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(stream.headersSent).toBe(true)
        expect(receivedHead).toContain('initial-head-bytes')
      })
      stream.emit('close')
      await rawServer.close()
    })

    it('handles stream.write error when writing initial head buffer', async () => {
      const rawServer = await makeRawWebSocketServer('initial-head-bytes')
      const stream = new MockServerHttp2Stream()
      stream.shouldThrowOnWrite = true
      let released = false
      const ide = {
        resolveView: async () => makeValidUpstream({ port: rawServer.port, release: () => { released = true } }),
      } as unknown as IdeService

      await handleIdeConnectRequest(
        stream as any,
        {
          ':path': '/ide/11111111-1111-4111-8111-111111111111/1/',
          'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
          'sec-websocket-version': '13',
        },
        ide,
      )
      await vi.waitFor(() => {
        expect(released).toBe(true)
      })
      await rawServer.close()
    })
  })
})
