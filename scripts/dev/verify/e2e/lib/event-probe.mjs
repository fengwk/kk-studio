import { assert, assertDecimalVersion, assertExactFields, sleep } from './http.mjs'
import { FramedEventSocket, eventUrl } from './framed-event-socket.mjs'

export { eventUrl }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** Validates the resource coordinate shared by every resource-scoped frame. */
function assertResource(resource) {
  assert(['projects', 'environments', 'canvas'].includes(resource?.kind), 'unexpected resource kind')
  assertExactFields(resource, resource.kind === 'canvas' ? ['kind', 'id'] : ['kind'])
  if (resource.kind === 'canvas') assert(UUID.test(resource.id), 'invalid canvas UUID')
}

/** Strict subset used by distributed notification cases; unexpected frames fail, never disappear. */
export function validateFrame(frame) {
  assert(frame?.version === 2, 'event frame version must be 2')
  if (frame.type === 'heartbeat') {
    assertExactFields(frame, ['version', 'type'])
    return
  }
  if (frame.type === 'error') {
    // A channel closing by protocol (for example SEND_FAILED followed by close 1012 while the bus
    // resyncs) still carries a typed error frame; it is part of the v2 wire set and must not be
    // mistaken for a malformed frame. Field rules mirror the browser decoder exactly.
    assertExactFields(
      frame,
      frame.resource === undefined
        ? ['version', 'type', 'code', 'message']
        : ['version', 'type', 'code', 'message', 'resource'],
    )
    assert(typeof frame.code === 'string' && frame.code.length > 0, 'error frame code must be a non-empty string')
    assert(typeof frame.message === 'string', 'error frame message must be a string')
    if (frame.resource !== undefined) assertResource(frame.resource)
    return
  }
  assert(['subscribed', 'event', 'resync'].includes(frame.type), 'unexpected event frame type')
  const resource = frame.resource
  assertResource(resource)
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

/**
 * Append-only capture over the shared framed transport. Every physical carrier fragment is
 * reassembled by `FramedEventSocket`; hooks initiate authoritative reads inside message dispatch.
 */
export class EventProbe {
  constructor(baseUrl, Socket = WebSocket) {
    this.framed = new FramedEventSocket(eventUrl(baseUrl), { Socket })
    this.socket = this.framed.socket
    this.hooks = this.framed.hooks
    const observe = (frame) => {
      try {
        validateFrame(frame)
      } catch {
        this.framed.failure ||= new Error('invalid application event frame')
      }
    }
    this.observe = observe
    this.framed.hooks.add(observe)
  }

  get frames() {
    return this.framed.frames
  }

  get failure() {
    return this.framed.failure
  }

  get closed() {
    return this.framed.closed
  }

  check() {
    this.framed.check()
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
    this.framed.subscribe(resource)
    await this.until(() => this.frames.slice(start).some((frame) =>
      frame.type === 'subscribed' && JSON.stringify(frame.resource) === JSON.stringify(resource)))
    const ack = this.frames.slice(start).find((frame) =>
      frame.type === 'subscribed' && JSON.stringify(frame.resource) === JSON.stringify(resource))
    assert(ack.cursor === cursor, 'subscription baseline cursor mismatch')
    return ack
  }

  unsubscribe(resource) {
    this.framed.unsubscribe(resource)
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
      this.framed.hooks.delete(this.observe)
      this.framed.close()
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
