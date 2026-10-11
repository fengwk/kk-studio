import { describe, it, expect } from 'vitest'
import {
  canFallback,
  classifyGitError,
  isAbortError,
  createGitHttpClient,
  TimeoutSocketError,
  TimeoutHttpAgent,
  TimeoutHttpsAgent,
} from '../../src/skill/http.js'

describe('http helper tests', () => {
  it('TimeoutSocketError has correct code and message', () => {
    const err = new TimeoutSocketError('GIT_CONNECT_TIMEOUT', 'connect timed out')
    expect(err.code).toBe('GIT_CONNECT_TIMEOUT')
    expect(err.message).toBe('connect timed out')
  })

  it('isAbortError detects abort errors correctly', () => {
    expect(isAbortError(null)).toBe(false)
    expect(isAbortError(undefined)).toBe(false)
    expect(isAbortError('not an object')).toBe(false)
    expect(isAbortError(new Error('something else'))).toBe(false)

    const abortErr = new Error('aborted')
    abortErr.name = 'AbortError'
    expect(isAbortError(abortErr)).toBe(true)

    const cancelErr = new Error('Operation cancelled')
    expect(isAbortError(cancelErr)).toBe(true)

    const abortMsgErr = new Error('The operation was aborted by user')
    expect(isAbortError(abortMsgErr)).toBe(true)
  })

  it('classifyGitError handles different error cases and nested causes', () => {
    expect(classifyGitError(null)).toBe('GIT_FETCH_FAILED')
    expect(classifyGitError('string error')).toBe('GIT_FETCH_FAILED')

    const abortErr = new Error('Operation cancelled')
    expect(classifyGitError(abortErr)).toBe('OPERATION_CANCELLED')

    expect(classifyGitError({ code: 'GIT_CONNECT_TIMEOUT' })).toBe('GIT_CONNECT_TIMEOUT')
    expect(classifyGitError({ code: 'GIT_READ_TIMEOUT' })).toBe('GIT_READ_TIMEOUT')
    expect(classifyGitError({ code: 'UnknownTransportError' })).toBe('UNSUPPORTED_REPOSITORY_SCHEME')

    expect(classifyGitError({ statusCode: 401 })).toBe('GIT_AUTHENTICATION_FAILED')
    expect(classifyGitError({ statusCode: 403 })).toBe('GIT_AUTHENTICATION_FAILED')
    expect(classifyGitError({ data: { statusCode: 401 } })).toBe('GIT_AUTHENTICATION_FAILED')
    expect(classifyGitError({ message: 'User not authorized' })).toBe('GIT_AUTHENTICATION_FAILED')
    expect(classifyGitError({ message: 'authentication is required' })).toBe('GIT_AUTHENTICATION_FAILED')
    expect(classifyGitError({ message: 'action not permitted' })).toBe('GIT_AUTHENTICATION_FAILED')

    expect(classifyGitError({ message: 'unrecognized transport protocol file' })).toBe(
      'UNSUPPORTED_REPOSITORY_SCHEME',
    )
    expect(classifyGitError({ message: 'unsupported git transport scheme ssh' })).toBe(
      'UNSUPPORTED_REPOSITORY_SCHEME',
    )

    // Nested cause
    const nested = {
      message: 'top level error',
      cause: {
        message: 'sub error',
        cause: {
          code: 'GIT_CONNECT_TIMEOUT',
        },
      },
    }
    expect(classifyGitError(nested)).toBe('GIT_CONNECT_TIMEOUT')
  })

  it('canFallback returns true for unadvertised objects and false otherwise', () => {
    expect(canFallback(null)).toBe(false)
    expect(canFallback(undefined)).toBe(false)

    // Not GIT_FETCH_FAILED
    expect(canFallback({ code: 'GIT_CONNECT_TIMEOUT' })).toBe(false)
    expect(canFallback({ statusCode: 401 })).toBe(false)

    // Aborted
    const abortErr = new Error('Operation cancelled')
    expect(canFallback(abortErr)).toBe(false)

    // Fallback matches
    expect(canFallback(new Error('Server reported: not our ref'))).toBe(true)
    expect(canFallback(new Error('remote: unadvertised object 1234567890'))).toBe(true)
    expect(canFallback(new Error('want 0123456789012345678901234567890123456789 not valid'))).toBe(true)

    // Nested cause fallback
    const nested = new Error('top level error')
    // @ts-expect-error cause
    nested.cause = new Error('not our ref')
    expect(canFallback(nested)).toBe(true)

    // Generic error
    expect(canFallback(new Error('network unreachable'))).toBe(false)
  })

  it('createGitHttpClient throws immediately if signal is already aborted', async () => {
    const ac = new AbortController()
    ac.abort()
    const client = createGitHttpClient({ signal: ac.signal })

    await expect(
      client.request({
        url: 'https://example.com/repo.git',
      }),
    ).rejects.toMatchObject({
      name: 'AbortError',
      message: 'Operation cancelled',
    })
  })

  it('createGitHttpClient combines request signal and client signal', async () => {
    const acClient = new AbortController()
    const client = createGitHttpClient({ signal: acClient.signal })

    const acReq = new AbortController()
    acReq.abort()

    await expect(
      client.request({
        url: 'http://example.com/repo.git',
        signal: acReq.signal,
      }),
    ).rejects.toThrow()

    // Client without option signal but with req.signal
    const clientNoSignal = createGitHttpClient()
    await expect(
      clientNoSignal.request({
        url: 'http://example.com/repo.git',
        signal: acReq.signal,
      }),
    ).rejects.toThrow()
  })

  it('canFallback returns false when error has nested AbortError', () => {
    const nestedAbort = {
      message: 'fetch failed',
      cause: {
        name: 'AbortError',
        message: 'aborted',
      },
    }
    expect(canFallback(nestedAbort)).toBe(false)
  })

  it('TimeoutHttpsAgent and TimeoutHttpAgent configure sockets with timeouts and respect pre-aborted signal', () => {
    // Normal connection setup
    const httpsAgent = new TimeoutHttpsAgent(500, 500)
    const httpsSocket = httpsAgent.createConnection({ host: '127.0.0.1', port: 443 })
    expect(httpsSocket).toBeDefined()
    if (httpsSocket) {
      httpsSocket.destroy()
    }

    // Pre-aborted signal immediately destroys socket
    const ac = new AbortController()
    ac.abort()
    const httpAgent = new TimeoutHttpAgent(500, 500, ac.signal)
    const httpSocket = httpAgent.createConnection({ host: '127.0.0.1', port: 80 })
    expect(httpSocket).toBeDefined()
    if (httpSocket) {
      expect(httpSocket.destroyed).toBe(true)
    }
  })
})
