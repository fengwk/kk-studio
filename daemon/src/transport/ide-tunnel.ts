import http, { type IncomingHttpHeaders } from 'node:http'
import { constants as h2constants, type ServerHttp2Stream } from 'node:http2'
import net from 'node:net'

import type { IdeService, IdeStreamRequest, IdeStreamUpstream } from '../contracts.js'
import { IdeError } from '../ide/errors.js'

const {
  HTTP2_HEADER_STATUS,
  NGHTTP2_INTERNAL_ERROR,
} = h2constants

const IDE_PATH_PATTERN =
  /^\/ide\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/([1-9][0-9]*)(\/.*)?$/

const DROP_RESPONSE_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

const PROTOCOL_TOKEN = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/

function isLoopbackHost(host: string): boolean {
  const value = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host
  if (value === '::1') {
    return true
  }
  return net.isIPv4(value) && value.split('.')[0] === '127'
}

function hasHeaderUnsafeCharacter(value: string): boolean {
  return /[\u0000-\u001f\u007f]/.test(value)
}

export function validateUpstream(target: IdeStreamUpstream): IdeStreamUpstream {
  if (typeof target !== 'object' || target === null) {
    throw new IdeError('resolved upstream is required')
  }
  if (typeof target.host !== 'string' || !isLoopbackHost(target.host)) {
    throw new IdeError('resolved upstream must be a numeric loopback host')
  }
  if (!Number.isSafeInteger(target.port) || target.port < 1 || target.port > 65_535) {
    throw new IdeError('resolved upstream port is invalid')
  }
  if (
    typeof target.path !== 'string' ||
    !target.path.startsWith('/') ||
    target.path.startsWith('//') ||
    hasHeaderUnsafeCharacter(target.path)
  ) {
    throw new IdeError('resolved upstream path is invalid')
  }
  if (
    !Array.isArray(target.headers) ||
    target.headers.some(
      (header) =>
        !Array.isArray(header) ||
        header.length !== 2 ||
        typeof header[0] !== 'string' ||
        typeof header[1] !== 'string' ||
        !PROTOCOL_TOKEN.test(header[0]) ||
        hasHeaderUnsafeCharacter(header[1]),
    )
  ) {
    throw new IdeError('resolved upstream headers are invalid')
  }
  if (target.cookie !== undefined && (typeof target.cookie !== 'string' || hasHeaderUnsafeCharacter(target.cookie))) {
    throw new IdeError('resolved upstream cookie is invalid')
  }
  if (target.protocols !== undefined) {
    if (
      !Array.isArray(target.protocols) ||
      target.protocols.some((value) => typeof value !== 'string' || !PROTOCOL_TOKEN.test(value))
    ) {
      throw new IdeError('resolved upstream protocols are invalid')
    }
  }
  if (!(target.signal instanceof AbortSignal) || typeof target.release !== 'function') {
    throw new IdeError('resolved upstream lifecycle is invalid')
  }
  return target
}

function upstreamAuthority(target: IdeStreamUpstream): string {
  const host = target.host.startsWith('[') ? target.host : target.host.includes(':') ? `[${target.host}]` : target.host
  return `${host}:${target.port}`
}

function filterResponseHeaders(rawHeaders: NodeJS.Dict<string | string[]>): Record<string, string | string[]> {
  const filtered: Record<string, string | string[]> = {}
  for (const [key, value] of Object.entries(rawHeaders)) {
    if (value === undefined) {
      continue
    }
    const lowerKey = key.toLowerCase()
    if (lowerKey.startsWith(':') || DROP_RESPONSE_HEADERS.has(lowerKey)) {
      continue
    }
    filtered[lowerKey] = value
  }
  return filtered
}

function mapResolveErrorToStatus(error: unknown): number {
  if (error instanceof IdeError) {
    if (error.code === 'VIEW_GONE') {
      return 404
    }
    if (error.code === 'UNAVAILABLE') {
      return 503
    }
  }
  return 502
}

interface ParsedIdePath {
  viewId: string
  bindingEpoch: string
  viewPath: string
}

function parseIdePath(path: string | undefined): ParsedIdePath | null {
  if (typeof path !== 'string') {
    return null
  }
  const match = IDE_PATH_PATTERN.exec(path)
  if (match === null) {
    return null
  }
  const viewId = match[1]!
  const bindingEpoch = match[2]!
  const viewPath = match[3] ?? ''
  if (!viewPath.startsWith('/')) {
    return null
  }
  return { viewId, bindingEpoch, viewPath }
}

function extractRequestHeaders(headers: IncomingHttpHeaders): [string, string][] {
  const result: [string, string][] = []
  for (const [key, value] of Object.entries(headers)) {
    if (key.startsWith(':') || value === undefined) {
      continue
    }
    if (Array.isArray(value)) {
      for (const item of value) {
        result.push([key, item])
      }
    } else {
      result.push([key, value])
    }
  }
  return result
}

/** 处理 IDE HTTP 流式请求。 */
export async function handleIdeHttpRequest(
  stream: ServerHttp2Stream,
  headers: IncomingHttpHeaders,
  ide: IdeService,
): Promise<void> {
  return handleIdeRequest(stream, headers, ide, 'HTTP')
}

/** 处理 IDE extended CONNECT 请求（RFC 8441）。 */
export async function handleIdeConnectRequest(
  stream: ServerHttp2Stream,
  headers: IncomingHttpHeaders,
  ide: IdeService,
): Promise<void> {
  return handleIdeRequest(stream, headers, ide, 'WS')
}

async function handleIdeRequest(
  stream: ServerHttp2Stream,
  headers: IncomingHttpHeaders,
  ide: IdeService,
  mode: 'HTTP' | 'WS',
): Promise<void> {
  const parsed = parseIdePath(headers[':path'] as string | undefined)
  if (parsed === null) {
    if (!stream.closed && !stream.destroyed) {
      stream.respond({ [HTTP2_HEADER_STATUS]: 400 }, { endStream: true })
    }
    return
  }

  const method = mode === 'WS' ? 'GET' : (headers[':method'] as string) || 'GET'
  const streamHeaders = extractRequestHeaders(headers)
  const ideRequest: IdeStreamRequest = {
    mode,
    method,
    path: parsed.viewPath,
    headers: streamHeaders,
  }

  let upstream: IdeStreamUpstream
  try {
    upstream = validateUpstream(await ide.resolveView(parsed.viewId, parsed.bindingEpoch, ideRequest))
  } catch (error) {
    const status = mapResolveErrorToStatus(error)
    if (!stream.closed && !stream.destroyed) {
      stream.respond({ [HTTP2_HEADER_STATUS]: status }, { endStream: true })
    }
    return
  }

  let released = false
  const releaseUpstream = (): void => {
    if (!released) {
      released = true
      try {
        upstream.release()
      } catch {}
    }
  }

  if (stream.closed || stream.destroyed) {
    releaseUpstream()
    return
  }

  const reqHeaders: Record<string, string | string[]> = {}
  for (const [name, val] of upstream.headers) {
    reqHeaders[name.toLowerCase()] = val
  }
  reqHeaders['host'] = upstreamAuthority(upstream)
  if (mode === 'WS') {
    reqHeaders['connection'] = 'Upgrade'
    reqHeaders['upgrade'] = 'websocket'
  }
  if (upstream.cookie) {
    reqHeaders['cookie'] = upstream.cookie
  }
  if (mode === 'WS') {
    for (const key of ['sec-websocket-key', 'sec-websocket-version', 'sec-websocket-protocol', 'sec-websocket-extensions']) {
      const val = headers[key]
      if (val !== undefined) {
        reqHeaders[key] = val
      }
    }
  }

  const upstreamReq = http.request({
    host: upstream.host,
    port: upstream.port,
    path: upstream.path,
    method,
    headers: reqHeaders,
  })

  const onStreamAbort = (): void => {
    upstreamReq.destroy()
    releaseUpstream()
  }

  const onServiceAbort = (): void => {
    upstreamReq.destroy()
    if (!stream.closed && !stream.destroyed) {
      try {
        stream.close(NGHTTP2_INTERNAL_ERROR)
      } catch {}
    }
    releaseUpstream()
  }

  stream.on('error', onStreamAbort)
  stream.on('close', onStreamAbort)
  upstream.signal.addEventListener('abort', onServiceAbort, { once: true })

  upstreamReq.on('error', () => {
    if (!stream.headersSent && !stream.closed && !stream.destroyed) {
      try {
        stream.respond({ [HTTP2_HEADER_STATUS]: 502 }, { endStream: true })
      } catch {}
    } else if (!stream.closed && !stream.destroyed) {
      try {
        stream.close(NGHTTP2_INTERNAL_ERROR)
      } catch {}
    }
    releaseUpstream()
  })

  upstreamReq.on('response', (upstreamRes) => {
    if (stream.closed || stream.destroyed) {
      upstreamRes.destroy()
      releaseUpstream()
      return
    }

    const filtered = filterResponseHeaders(upstreamRes.headers)
    try {
      stream.respond({
        [HTTP2_HEADER_STATUS]: upstreamRes.statusCode ?? (mode === 'HTTP' ? 200 : 502),
        ...filtered,
      })
    } catch {
      upstreamRes.destroy()
      releaseUpstream()
      return
    }

    upstreamRes.pipe(stream)
    upstreamRes.on('end', () => {
      releaseUpstream()
    })
    upstreamRes.on('error', () => {
      if (!stream.closed && !stream.destroyed) {
        try {
          stream.close(NGHTTP2_INTERNAL_ERROR)
        } catch {}
      }
      releaseUpstream()
    })
  })

  if (mode === 'HTTP') {
    stream.pipe(upstreamReq)
    return
  }

  upstreamReq.on('upgrade', (upstreamRes, upstreamSocket, head) => {
    if (stream.closed || stream.destroyed) {
      upstreamSocket.destroy()
      releaseUpstream()
      return
    }

    const resHeaders: Record<string, string | string[] | number> = {
      [HTTP2_HEADER_STATUS]: 200,
    }
    if (upstreamRes.headers['sec-websocket-accept']) {
      resHeaders['sec-websocket-accept'] = upstreamRes.headers['sec-websocket-accept']
    }
    if (upstreamRes.headers['sec-websocket-protocol']) {
      resHeaders['sec-websocket-protocol'] = upstreamRes.headers['sec-websocket-protocol']
    }
    if (upstreamRes.headers['sec-websocket-extensions']) {
      resHeaders['sec-websocket-extensions'] = upstreamRes.headers['sec-websocket-extensions']
    }

    try {
      stream.respond(resHeaders)
    } catch {
      upstreamSocket.destroy()
      releaseUpstream()
      return
    }

    if (head && head.length > 0) {
      try {
        stream.write(head)
      } catch {
        upstreamSocket.destroy()
        releaseUpstream()
        return
      }
    }

    stream.pipe(upstreamSocket)
    upstreamSocket.pipe(stream)

    stream.on('end', () => {
      upstreamSocket.end()
    })
    upstreamSocket.on('end', () => {
      if (!stream.closed && !stream.destroyed) {
        stream.end()
      }
    })

    const onPipeError = (): void => {
      upstreamSocket.destroy()
      if (!stream.closed && !stream.destroyed) {
        try {
          stream.close(NGHTTP2_INTERNAL_ERROR)
        } catch {}
      }
      releaseUpstream()
    }

    stream.on('error', onPipeError)
    upstreamSocket.on('error', onPipeError)
    stream.on('close', () => {
      upstreamSocket.destroy()
      releaseUpstream()
    })
    upstreamSocket.on('close', () => {
      if (!stream.closed && !stream.destroyed) {
        try {
          stream.end()
        } catch {}
      }
      releaseUpstream()
    })
  })

  upstreamReq.end()
}
