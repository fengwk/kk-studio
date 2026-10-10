import nodeHttp from 'isomorphic-git/http/node'
import http from 'node:http'
import https from 'node:https'
import type { Duplex } from 'node:stream'
import type { Socket } from 'node:net'
import type { HttpClient, GitHttpRequest, GitHttpResponse } from 'isomorphic-git'

export const DEFAULT_CONNECT_MILLIS = 60_000
export const DEFAULT_READ_MILLIS = 180_000

export class TimeoutSocketError extends Error {
  readonly code: string

  constructor(code: 'GIT_CONNECT_TIMEOUT' | 'GIT_READ_TIMEOUT', message: string) {
    super(message)
    this.name = 'TimeoutSocketError'
    this.code = code
  }
}

class TimeoutHttpAgent extends http.Agent {
  private readonly connectMillis: number
  private readonly readMillis: number
  private readonly signal?: AbortSignal

  constructor(connectMillis: number, readMillis: number, signal?: AbortSignal) {
    super({ keepAlive: false })
    this.connectMillis = connectMillis
    this.readMillis = readMillis
    this.signal = signal
  }

  override createConnection(options: http.ClientRequestArgs, callback?: (err: Error | null, stream: Duplex) => void): Duplex | null | undefined {
    const socket = super.createConnection(options, callback) as Socket | null | undefined
    if (socket) {
      setupSocketTimeouts(socket, this.connectMillis, this.readMillis, this.signal)
    }
    return socket
  }
}

class TimeoutHttpsAgent extends https.Agent {
  private readonly connectMillis: number
  private readonly readMillis: number
  private readonly signal?: AbortSignal

  constructor(connectMillis: number, readMillis: number, signal?: AbortSignal) {
    super({ keepAlive: false })
    this.connectMillis = connectMillis
    this.readMillis = readMillis
    this.signal = signal
  }

  override createConnection(options: https.RequestOptions, callback?: (err: Error | null, stream: Duplex) => void): Duplex | null | undefined {
    const socket = super.createConnection(options, callback) as Socket | null | undefined
    if (socket) {
      setupSocketTimeouts(socket, this.connectMillis, this.readMillis, this.signal)
    }
    return socket
  }
}

function setupSocketTimeouts(
  socket: Socket,
  connectMillis: number,
  readMillis: number,
  signal?: AbortSignal,
): void {
  let connectTimer: NodeJS.Timeout | null = setTimeout(() => {
    socket.destroy(new TimeoutSocketError('GIT_CONNECT_TIMEOUT', 'Git connect timeout'))
  }, connectMillis)

  let readTimer: NodeJS.Timeout | null = null

  const resetReadTimer = (): void => {
    if (readTimer) {
      clearTimeout(readTimer)
    }
    readTimer = setTimeout(() => {
      socket.destroy(new TimeoutSocketError('GIT_READ_TIMEOUT', 'Git read timeout'))
    }, readMillis)
  }

  const cleanup = (): void => {
    if (connectTimer) {
      clearTimeout(connectTimer)
      connectTimer = null
    }
    if (readTimer) {
      clearTimeout(readTimer)
      readTimer = null
    }
  }

  socket.once('connect', () => {
    if (connectTimer) {
      clearTimeout(connectTimer)
      connectTimer = null
    }
    resetReadTimer()
  })

  socket.on('data', () => {
    resetReadTimer()
  })

  socket.once('close', cleanup)
  socket.once('error', cleanup)

  if (signal) {
    if (signal.aborted) {
      cleanup()
      const abortErr = new Error('Operation cancelled')
      abortErr.name = 'AbortError'
      socket.destroy(abortErr)
    } else {
      const onAbort = (): void => {
        cleanup()
        const abortErr = new Error('Operation cancelled')
        abortErr.name = 'AbortError'
        socket.destroy(abortErr)
      }
      signal.addEventListener('abort', onAbort, { once: true })
      socket.once('close', () => {
        signal.removeEventListener('abort', onAbort)
      })
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
  const signal = options?.signal

  const httpAgent = new TimeoutHttpAgent(connectMillis, readMillis, signal)
  const httpsAgent = new TimeoutHttpsAgent(connectMillis, readMillis, signal)

  return {
    async request(req: GitHttpRequest): Promise<GitHttpResponse> {
      if (signal?.aborted) {
        const err = new Error('Operation cancelled')
        err.name = 'AbortError'
        throw err
      }

      let combinedSignal = signal
      if (req.signal && signal) {
        combinedSignal = AbortSignal.any([signal, req.signal])
      } else if (req.signal) {
        combinedSignal = req.signal
      }

      const urlObj = new URL(req.url)
      const agent = urlObj.protocol === 'https:' ? httpsAgent : httpAgent

      const res = await nodeHttp.request({
        ...req,
        agent,
        signal: combinedSignal,
      })

      return res
    },
  }
}

export function isAbortError(error: unknown): boolean {
  if (!error) return false
  if (typeof error === 'object') {
    const err = error as { name?: string; message?: string }
    if (err.name === 'AbortError') return true
    if (err.message === 'Operation cancelled' || err.message?.includes('The operation was aborted')) return true
  }
  return false
}

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
