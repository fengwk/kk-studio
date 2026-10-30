import { Readable } from 'node:stream'
import { errors as undiciErrors } from 'undici'
import type { HttpClient, GitHttpRequest, GitHttpResponse } from 'isomorphic-git'

import { getOutboundNetwork, type OutboundNetwork, type OutboundRequestInit, type OutboundResponse } from '../network.js'
import { isAbortError } from '../native/abort.js'

export const DEFAULT_CONNECT_MILLIS = 60_000
export const DEFAULT_READ_MILLIS = 180_000

/** git 感知的连接/读超时错误；分类逻辑只依赖稳定 code。 */
export class TimeoutSocketError extends Error {
  readonly code: string

  constructor(code: 'GIT_CONNECT_TIMEOUT' | 'GIT_READ_TIMEOUT', message: string) {
    super(message)
    this.name = 'TimeoutSocketError'
    this.code = code
  }
}

const REDIRECT_STATUS = new Set([301, 302, 303, 307, 308])
const MAX_REDIRECTS = 10

function abortError(): Error {
  const error = new Error('Operation cancelled')
  error.name = 'AbortError'
  return error
}

/** 把底层 undici 超时映射回 git 常量，其它错误原样保留（错误文本绝不携带 URL 或查询串）。 */
function translateGitError(error: unknown): unknown {
  if (error instanceof undiciErrors.ConnectTimeoutError) {
    return new TimeoutSocketError('GIT_CONNECT_TIMEOUT', 'Git connect timeout')
  }
  if (error instanceof undiciErrors.HeadersTimeoutError || error instanceof undiciErrors.BodyTimeoutError) {
    return new TimeoutSocketError('GIT_READ_TIMEOUT', 'Git read timeout')
  }
  return error
}

function combineSignals(client: AbortSignal | undefined, request: AbortSignal | undefined): AbortSignal | undefined {
  if (client && request) {
    return AbortSignal.any([client, request])
  }
  return request ?? client
}

const SENSITIVE_HEADERS = new Set([
  'authorization',
  'cookie',
  'proxy-authorization',
  'www-authenticate',
  'proxy-authenticate',
])
const SENSITIVE_HEADER_PATTERN = /(?:^|[-_])(?:authorization|cookie|token|secret|credential)s?(?:$|[-_])|api[-_]?key/i

/**
 * 逐跳处理重定向：只有无 body 的 GET/HEAD 跟随，且每跳按新 URL 重新选择代理。
 * 跨 origin 时剥离认证/cookie/代理认证，禁止 HTTPS->HTTP 降级携带凭据，无法安全保留的敏感自定义头则拒绝该次跟随。
 */
async function openWithRedirects(
  network: OutboundNetwork,
  url: string,
  method: string,
  init: OutboundRequestInit,
  signal: AbortSignal | undefined,
): Promise<{ response: OutboundResponse; finalUrl: string }> {
  let current = url
  let headers = init.headers
  for (let hop = 0; ; hop += 1) {
    const response = await network.openResponse(
      current,
      { ...init, headers, redirect: 'manual' },
      signal ?? new AbortController().signal,
    )
    if (!REDIRECT_STATUS.has(response.statusCode)) {
      return { response, finalUrl: current }
    }
    const location = response.headers.location
    await response.dispose()
    if (typeof location !== 'string' || hop >= MAX_REDIRECTS || (method !== 'GET' && method !== 'HEAD')) {
      throw new Error('git request refused an unexpected redirect')
    }
    if (init.body !== undefined && init.body !== null) {
      throw new Error('git request refused an unexpected redirect')
    }
    const from = new URL(current)
    const to = new URL(location, current)
    headers = redirectHeaders(headers, from, to)
    current = to.toString()
  }
}

/** 同 origin 保持原样；跨 origin 剥离标准 secret，降级或无法安全保留的敏感自定义头确定失败。 */
export function redirectHeaders(
  headers: Record<string, string> | undefined,
  from: URL,
  to: URL,
): Record<string, string> | undefined {
  if (headers === undefined || sameOrigin(from, to)) {
    return headers
  }
  if (from.protocol === 'https:' && to.protocol === 'http:') {
    throw new Error('git request refused a cross-scheme redirect')
  }
  const kept: Record<string, string> = {}
  for (const [name, value] of Object.entries(headers)) {
    const lower = name.toLowerCase()
    if (SENSITIVE_HEADERS.has(lower)) {
      continue
    }
    if (SENSITIVE_HEADER_PATTERN.test(lower)) {
      throw new Error('git request refused an unsafe cross-origin redirect')
    }
    kept[name] = value
  }
  return kept
}

function sameOrigin(a: URL, b: URL): boolean {
  return a.protocol === b.protocol && a.hostname === b.hostname && a.port === b.port
}

/** 非 2xx 不读取错误体：直接取消并返回可按消费契约迭代的空 body。 */
async function* emptyBody(): AsyncGenerator<Uint8Array> {
  return
}

/**
 * 真实 async generator：正常结束、读错误或调用方提前 return 都在 finally 中 await 释放独占 dispatcher。
 * 读阶段超时同样映射回 GIT_READ_TIMEOUT；读错误与清理错误同时发生时两者都保留。
 */
async function* streamingBody(response: OutboundResponse): AsyncGenerator<Uint8Array> {
  let primary: unknown
  try {
    for await (const chunk of response.body) {
      yield Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array)
    }
  } catch (error) {
    primary = translateGitError(error)
    throw primary
  } finally {
    try {
      await response.dispose()
    } catch (cleanupError) {
      if (primary === undefined) {
        throw cleanupError
      }
      throw new AggregateError([primary, cleanupError], 'git response body and cleanup both failed')
    }
  }
}

export function createGitHttpClient(options?: {
  connectMillis?: number
  readMillis?: number
  signal?: AbortSignal
}): HttpClient {
  const connectMillis = options?.connectMillis ?? DEFAULT_CONNECT_MILLIS
  const readMillis = options?.readMillis ?? DEFAULT_READ_MILLIS
  const clientSignal = options?.signal

  return {
    async request(req: GitHttpRequest): Promise<GitHttpResponse> {
      const signal = combineSignals(clientSignal, req.signal)
      if (signal?.aborted) {
        throw abortError()
      }
      const method = (req.method ?? 'GET').toUpperCase()
      const network = getOutboundNetwork()
      let opened: { response: OutboundResponse; finalUrl: string }
      try {
        opened = await openWithRedirects(
          network,
          req.url,
          method,
          {
            method,
            headers: req.headers,
            body: req.body === undefined ? null : Readable.from(req.body),
            headersTimeout: readMillis,
            bodyTimeout: readMillis,
            connectTimeout: connectMillis,
          },
          signal,
        )
      } catch (error) {
        throw translateGitError(error)
      }
      const { response, finalUrl } = opened
      // 非 2xx 不读取错误体（不可信远端可借此 OOM）：直接取消并释放；清理失败必须保留。
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.dispose()
        return {
          url: finalUrl,
          method,
          headers: flattenHeaders(response.headers),
          body: emptyBody(),
          statusCode: response.statusCode,
          statusMessage: response.statusMessage,
        }
      }
      return {
        url: finalUrl,
        method,
        headers: flattenHeaders(response.headers),
        body: streamingBody(response),
        statusCode: response.statusCode,
        statusMessage: response.statusMessage,
      }
    },
  }
}

function flattenHeaders(headers: Record<string, string | string[] | undefined>): Record<string, string> {
  const flat: Record<string, string> = {}
  for (const [name, value] of Object.entries(headers)) {
    if (value === undefined) {
      continue
    }
    flat[name] = Array.isArray(value) ? value.join(', ') : value
  }
  return flat
}

export { isAbortError }

export function classifyGitError(error: unknown): string {
  if (isAbortError(error)) {
    return 'OPERATION_CANCELLED'
  }
  if (!error || typeof error !== 'object') {
    return 'GIT_FETCH_FAILED'
  }

  const err = error as {
    code?: string
    statusCode?: number
    data?: { statusCode?: number; statusMessage?: string; response?: string }
    message?: string
    cause?: unknown
  }

  if (err.code === 'GIT_CONNECT_TIMEOUT') return 'GIT_CONNECT_TIMEOUT'
  if (err.code === 'GIT_READ_TIMEOUT') return 'GIT_READ_TIMEOUT'
  if (err.code === 'UnknownTransportError') return 'UNSUPPORTED_REPOSITORY_SCHEME'

  const message = (err.message ?? '').toLowerCase()
  const statusCode = err.statusCode ?? err.data?.statusCode

  if (
    statusCode === 401 ||
    statusCode === 403 ||
    message.includes('not authorized') ||
    message.includes('authentication is required') ||
    message.includes('not permitted') ||
    message.includes('401') ||
    message.includes('403')
  ) {
    return 'GIT_AUTHENTICATION_FAILED'
  }

  if (message.includes('unrecognized transport protocol') || message.includes('unsupported git transport scheme')) {
    return 'UNSUPPORTED_REPOSITORY_SCHEME'
  }

  if (err.cause) {
    return classifyGitError(err.cause)
  }

  return 'GIT_FETCH_FAILED'
}

export function canFallback(error: unknown): boolean {
  const code = classifyGitError(error)
  if (code !== 'GIT_FETCH_FAILED') {
    return false
  }

  const checkMessage = (err: unknown): boolean => {
    if (!err || typeof err !== 'object') return false
    const e = err as { name?: string; message?: string; cause?: unknown }
    if (e.name === 'AbortError' || e.message === 'Operation cancelled') return false
    const msg = (e.message ?? '').toLowerCase()
    if (
      msg.includes('not our ref') ||
      msg.includes('unadvertised object') ||
      /want [0-9a-f]{40,64} not valid/i.test(msg)
    ) {
      return true
    }
    if (e.cause) return checkMessage(e.cause)
    return false
  }

  return checkMessage(error)
}
