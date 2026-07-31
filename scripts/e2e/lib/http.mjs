/** 共享 HTTP / 断言工具（Node 原生 fetch，无第三方依赖）。 */

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
    return { status: res.status, json }
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
