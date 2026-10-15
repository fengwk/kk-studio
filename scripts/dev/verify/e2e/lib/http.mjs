/** 共享 HTTP / 断言工具（Node 原生 fetch，无第三方依赖）。 */

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

export async function httpJson(
  baseUrl,
  method,
  requestPath,
  body,
  timeoutMs = 60_000,
  requestHeaders = {},
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
    if (!res.ok) throw new HttpError(res.status, text, requestPath)
    return { status: res.status, json, headers: res.headers }
  } catch (err) {
    if (err instanceof HttpError) throw err
    if (err?.name === 'AbortError') throw new Error(`timeout ${method} ${requestPath}`)
    throw err
  } finally {
    clearTimeout(timer)
  }
}

export function envelopeData(payload) {
  assert(payload && typeof payload === 'object', `expected envelope object, got ${typeof payload}`)
  return payload.data
}

export function pageResults(payload) {
  const data = envelopeData(payload)
  if (data && typeof data === 'object' && Array.isArray(data.results)) return data.results
  if (Array.isArray(data)) return data
  throw new Error(`expected page/list data, got ${JSON.stringify(data)?.slice(0, 200)}`)
}

export function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

export function cid() {
  return crypto.randomUUID()
}

export async function expectHttpError(fn, { status, messageIncludes } = {}) {
  try {
    await fn()
    throw new Error('expected HTTP error, but call succeeded')
  } catch (err) {
    assert(err instanceof HttpError, `expected HttpError, got ${err}`)
    if (status != null) assert(err.status === status, `expected status ${status}, got ${err.status}: ${err.body}`)
    if (messageIncludes) {
      const re = messageIncludes instanceof RegExp ? messageIncludes : new RegExp(messageIncludes)
      assert(re.test(String(err.body)), `body not match ${re}: ${err.body}`)
    }
    return err
  }
}
