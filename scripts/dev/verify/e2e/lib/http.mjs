/** 共享 HTTP / 断言工具（Node 原生 fetch，无第三方依赖）。 */

import { loginE2eStudio, sessionCookie } from '../../../../../deploy/reliability/e2e-studio-login.mjs'

/** Java long 上界：wire 上的 canonical 版本游标只能是这个范围内的非负十进制文本。 */
const LONG_MAX = 9223372036854775807n

export class HttpError extends Error {
  constructor(status, body, requestPath) {
    super(`HTTP ${status} ${requestPath}: ${String(body).slice(0, 500)}`)
    this.status = status
    this.body = body
    this.path = requestPath
  }
}

export function assert(cond, message) {
  if (!cond) throw new Error(message || 'assertion failed')
}

export function assertExactFields(value, expectedFields, label = 'object') {
  assert(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`)
  const actual = Object.keys(value).sort()
  const expected = [...expectedFields].sort()
  assert(
    actual.length === expected.length && actual.every((field, index) => field === expected[index]),
    `${label} fields must be ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
  )
}

/** canonical 非负十进制字符串（Java long wire）：'0' 或非零开头，拒绝前导零/负数/空白/数字，且不得越过 signed 64-bit 上界。 */
export function assertDecimalVersion(value, label = 'version') {
  assert(
    typeof value === 'string'
      && /^(0|[1-9][0-9]*)$/.test(value)
      && BigInt(value) <= LONG_MAX,
    `${label} must be a canonical non-negative decimal Java long, got ${JSON.stringify(value)}`,
  )
}

/**
 * 与给定 canonical 非负十进制 long 文本不同、且仍落在同一合法 long 范围内的版本。
 *
 * <p>用 BigInt 精确构造：超过 2^53 的 epoch 用 Number 加减一会丢精度（甚至造出越界值），
 * 因此 `MAX` 时只能减一，其余情况加一。
 */
export function differentDecimalLong(value, label = 'version') {
  assertDecimalVersion(value, label)
  const current = BigInt(value)
  return (current === LONG_MAX ? current - 1n : current + 1n).toString()
}

const studioCookies = new Map()

/** Authenticate the seeded E2E administrator and retain the server-issued session cookie. */
export async function loginStudio(baseUrl, timeoutMs = 60_000) {
  const cookie = await loginE2eStudio(baseUrl, timeoutMs)
  setStudioCookie(baseUrl, cookie)
  return cookie
}

export function setStudioCookie(baseUrl, cookie) {
  studioCookies.set(new URL(baseUrl).origin, cookie)
}

export function studioAuthHeaders(baseUrl) {
  const origin = new URL(baseUrl).origin
  const cookie = studioCookies.get(origin)
  assert(cookie, 'call loginStudio before making authenticated E2E requests')
  return { Cookie: cookie, Origin: origin }
}

export function openStudioWebSocket(baseUrl, requestPath = '/ws/events', headers = studioAuthHeaders(baseUrl)) {
  const url = new URL(requestPath, baseUrl)
  assert(url.origin === new URL(baseUrl).origin, 'WebSocket path must stay on Studio')
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return new WebSocket(url, { headers })
}

export function createHttpClient({ baseUrl, origin = new URL(baseUrl).origin }) {
  let cookie
  const authHeaders = () => cookie ? { Cookie: cookie, Origin: origin } : { Origin: origin }
  const request = (path, { method = 'GET', body, timeoutMs, headers, allowError } = {}) =>
    sendJson(baseUrl, method, path, body, timeoutMs, { ...headers, ...authHeaders() }, allowError)
  return {
    request,
    openWebSocket(path) {
      assert(cookie, 'call login before opening a Studio WebSocket')
      return openStudioWebSocket(baseUrl, path, authHeaders())
    },
    async login(username, password) {
      const response = await request('/api/auth/login', { method: 'POST', body: { username, password }, allowError: true })
      assert(response.status === 200, `Studio login failed (HTTP ${response.status})`)
      cookie = sessionCookie(response.headers)
      return response.json
    },
  }
}

export function httpJson(baseUrl, method, requestPath, body, timeoutMs, requestHeaders) {
  const headers = studioCookies.has(new URL(baseUrl).origin) ? studioAuthHeaders(baseUrl) : {}
  return sendJson(baseUrl, method, requestPath, body, timeoutMs, { ...requestHeaders, ...headers })
}

async function sendJson(
  baseUrl,
  method,
  requestPath,
  body,
  timeoutMs = 60_000,
  requestHeaders = {},
  allowError = false,
) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const headers = {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...requestHeaders,
    }
    const res = await fetch(`${baseUrl.replace(/\/$/, '')}${requestPath}`, {
      method,
      redirect: 'manual',
      headers: Object.keys(headers).length === 0 ? undefined : headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    })
    const text = await res.text()
    let json = null
    if (text) {
      try {
        json = JSON.parse(text)
      } catch {
        json = text
      }
    }
    if (!res.ok && !allowError) throw new HttpError(res.status, text, requestPath)
    return { status: res.status, json, text, headers: res.headers }
  } catch (err) {
    if (err instanceof HttpError) throw err
    if (err?.name === 'AbortError') throw new Error(`timeout ${method} ${requestPath}`)
    throw err
  } finally {
    clearTimeout(timer)
  }
}

/** 读取直接返回的列表；分页信封已随页码分页删除，任何对象形状都视为契约漂移。 */
export function listResults(payload) {
  if (Array.isArray(payload)) return payload
  throw new Error(`expected list data, got ${JSON.stringify(payload)?.slice(0, 200)}`)
}

export function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

export function cid() {
  return crypto.randomUUID()
}

/**
 * 断言调用失败并返回错误响应，但不断言状态。
 *
 * <p>只用于状态无法事先唯一确定的故障注入与清理时序检查；调用方必须立即锁定可接受的确切 status、code 与 reason，
 * 不能把“任意失败”当作通过。
 */
export async function captureHttpError(fn) {
  try {
    await fn()
  } catch (err) {
    assert(err instanceof HttpError, `expected HttpError, got ${err}`)
    return err
  }
  throw new Error('expected HTTP error, but call succeeded')
}

/**
 * 断言调用失败，并锁定 canonical problem 信封。
 *
 * <p>必须显式给出确切的 {@code status}；4xx 还必须给出确切的 problem {@code code}，{@code code=conflict}
 * 还必须给出确切的 {@code reason}。期望值来自产品抛出处的 {@code DomainException} 与 web `ProblemAdvice`
 * 的状态映射，不接受“code 非空即可”或“任意失败即可”。
 */
export async function expectHttpError(fn, { status, code, reason, messageIncludes } = {}) {
  assert(
    typeof status === 'number',
    'expectHttpError requires the exact expected HTTP status; use captureHttpError for env-dependent failures',
  )
  const err = await captureHttpError(fn)
  assert(err.status === status, `expected status ${status}, got ${err.status}: ${err.body}`)
  if (status >= 400 && status < 500) {
    assert(
      typeof code === 'string' && code.length > 0,
      `expectHttpError requires an explicit problem code for status ${status}`,
    )
    let problem = null
    try {
      problem = JSON.parse(err.body)
    } catch {
      problem = null
    }
    assert(problem && typeof problem === 'object', `response body must be a problem object: ${err.body}`)
    assert(problem.code === code, `expected code ${code}, got ${problem.code}: ${err.body}`)
    if (code === 'conflict') {
      assert(
        typeof reason === 'string' && reason.length > 0,
        'conflict assertions must pin the exact problem reason',
      )
    }
    if (reason != null) assert(problem.reason === reason, `expected reason ${reason}: ${err.body}`)
  }
  if (messageIncludes) {
    const re = messageIncludes instanceof RegExp ? messageIncludes : new RegExp(messageIncludes)
    assert(re.test(String(err.body)), `body not match ${re}: ${err.body}`)
  }
  return err
}
