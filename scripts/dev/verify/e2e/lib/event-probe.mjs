import { assert, assertDecimalVersion, assertExactFields, sleep } from './http.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export function eventUrl(baseUrl) {
  const url = new URL(baseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/api/events/v1'
  url.search = ''
  url.hash = ''
  return url.toString()
}

/** Strict subset used by distributed notification cases; unexpected frames fail, never disappear. */
export function validateFrame(frame) {
  assert(frame?.version === 1, 'event frame version must be 1')
  if (frame.type === 'heartbeat') {
    assertExactFields(frame, ['version', 'type'])
    return
  }
  assert(['subscribed', 'event', 'resync'].includes(frame.type), 'unexpected event frame type')
  const resource = frame.resource
  assert(['projects', 'environments', 'canvas'].includes(resource?.kind), 'unexpected resource kind')
  assertExactFields(resource, resource.kind === 'canvas' ? ['kind', 'id'] : ['kind'])
  if (resource.kind === 'canvas') assert(UUID.test(resource.id), 'invalid canvas UUID')
  if (frame.type === 'resync') {
    assertExactFields(frame, ['version', 'type', 'resource'])
  } else if (frame.type === 'subscribed') {
    assertExactFields(frame, ['version', 'type', 'resource', 'cursor'])
    assertDecimalVersion(frame.cursor)
    if (resource.kind !== 'canvas') assert(frame.cursor === '0', 'global cursor must be zero')
  } else {
    const canvas = resource.kind === 'canvas'
    assertExactFields(frame, ['version', 'type', 'resource', 'name', 'data', ...(canvas ? ['cursor'] : [])])
    assert(frame.name === (canvas ? 'revision' : 'changed'), 'incorrect event name')
    assertExactFields(frame.data, canvas ? ['revision'] : resource.kind === 'projects' ? ['projectId'] : [])
    if (canvas) {
      assertDecimalVersion(frame.cursor)
      assert(frame.data.revision === frame.cursor, 'revision/cursor mismatch')
    }
    if (resource.kind === 'projects') assert(UUID.test(frame.data.projectId), 'invalid project UUID')
  }
}

/** Append-only capture begins before open/HTTP. Hooks initiate authoritative reads inside message dispatch. */
export class EventProbe {
  constructor(baseUrl, Socket = WebSocket) {
    this.socket = new Socket(eventUrl(baseUrl))
    this.frames = []
    this.hooks = new Set()
    this.failure = null
    this.closed = false
    this.listeners = {
      message: (event) => {
        try {
          const frame = JSON.parse(String(event.data))
          validateFrame(frame)
          this.frames.push(frame)
          for (const hook of this.hooks) hook(frame)
        } catch {
          this.failure ||= new Error('invalid application event frame or observation hook')
        }
      },
      error: () => { this.failure ||= new Error('application event socket error') },
      close: () => { this.closed = true },
    }
    for (const [type, listener] of Object.entries(this.listeners)) {
      this.socket.addEventListener(type, listener)
    }
  }

  check() {
    if (this.failure) throw this.failure
  }

  async until(predicate, timeoutMs = 10_000, allowClosed = false) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      this.check()
      if (predicate()) return
      assert(allowClosed || !this.closed, 'application event socket closed unexpectedly')
      await sleep(20)
    }
    throw new Error(`event observation timed out after ${timeoutMs}ms`)
  }

  async subscribe(resource, cursor = '0') {
    await this.until(() => this.socket.readyState === 1)
    const start = this.frames.length
    this.socket.send(JSON.stringify({ version: 1, type: 'subscribe', resource }))
    await this.until(() => this.frames.slice(start).some((frame) =>
      frame.type === 'subscribed' && JSON.stringify(frame.resource) === JSON.stringify(resource)))
    const ack = this.frames.slice(start).find((frame) =>
      frame.type === 'subscribed' && JSON.stringify(frame.resource) === JSON.stringify(resource))
    assert(ack.cursor === cursor, 'subscription baseline cursor mismatch')
    return ack
  }

  unsubscribe(resource) {
    this.socket.send(JSON.stringify({ version: 1, type: 'unsubscribe', resource }))
  }

  count(predicate, start = 0) {
    this.check()
    return this.frames.slice(start).filter(predicate).length
  }

  async stable(ms = 400, allowClosed = false) {
    // A bounded negative/duplicate window, not a claim of infinite silence.
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      this.check()
      assert(allowClosed || !this.closed, 'socket closed during stable window')
      await sleep(Math.min(20, deadline - Date.now()))
    }
  }

  async close() {
    try {
      if (!this.closed) this.socket.close(1000, 'E2E complete')
      const deadline = Date.now() + 5_000
      while (!this.closed && Date.now() < deadline) await sleep(20)
      assert(this.closed, 'timed out closing application event socket')
      this.check()
    } finally {
      for (const [type, listener] of Object.entries(this.listeners)) {
        this.socket.removeEventListener(type, listener)
      }
      this.hooks.clear()
    }
  }
}

/** Cleanup failures remain visible without replacing the primary assertion failure. */
export async function withCleanup(body, cleanups) {
  let failure
  try {
    await body()
  } catch (error) {
    failure = error
  }
  const errors = []
  for (const cleanup of cleanups) {
    try { await cleanup() } catch (error) { errors.push(error) }
  }
  if (failure && errors.length) throw new AggregateError([failure, ...errors], failure.message, { cause: failure })
  if (failure) throw failure
  if (errors.length) throw new AggregateError(errors, 'E2E cleanup failed')
}
