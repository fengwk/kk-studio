/**
 * Node E2E framed app-events socket helper.
 *
 * It reuses the exact browser/shared framing implementation: every logical app-events message is
 * carried as `app.events.v2` NotificationCarrier fragments through
 * `frontend/src/shared/app-events/framed-link.mjs`, which in turn reuses the canonical
 * `frontend/src/shared/notification/notification.mjs` packet/reassembler/outbox. Probes never
 * re-implement chunking or reassembly.
 *
 * The helper performs no reconnect or status policy: it exposes the decoded logical frames, the
 * underlying native socket and a bounded synchronous drain, and records the first framing failure.
 */

import { randomUUID } from 'node:crypto'
import { FramedEventLink } from '../../../../../frontend/src/shared/app-events/framed-link.mjs'
import { defaultNotificationLimits } from '../../../../../frontend/src/shared/notification/notification.mjs'

const LIMITS = defaultNotificationLimits()

/** Builds the sole app-events ws(s) URL for a base HTTP(S) URL, clearing search/hash. */
export function eventUrl(baseUrl) {
  const url = new URL(baseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/api/events/v1'
  url.search = ''
  url.hash = ''
  return url.toString()
}

export class FramedEventSocket {
  #listeners

  /**
   * @param {string} url full ws(s) app-events URL
   * @param {{ Socket?: typeof WebSocket, self?: string, limits?: object }} [options]
   */
  constructor(url, { Socket = globalThis.WebSocket, self = randomUUID(), limits = LIMITS } = {}) {
    /** Decoded logical server frames (v2), in arrival order. */
    this.frames = []
    /** Observation hooks invoked synchronously per decoded frame. */
    this.hooks = new Set()
    /** First framing/transport failure, never overwritten by a later one. */
    this.failure = null
    this.closed = false
    this.socket = new Socket(url)
    this.link = new FramedEventLink({
      self,
      limits,
      deliver: (body) => this.#deliver(body),
      onFailure: (recover) => {
        this.failure ||= new Error(
          recover
            ? 'application event framing requested recovery'
            : 'invalid application event frame',
        )
      },
    })
    this.#listeners = {
      message: (event) => {
        if (typeof event.data !== 'string') {
          this.failure ||= new Error('application event binary frame')
          return
        }
        this.link.accept(event.data)
      },
      error: () => {
        this.failure ||= new Error('application event socket error')
      },
      close: () => {
        this.closed = true
      },
    }
    for (const [type, listener] of Object.entries(this.#listeners)) {
      this.socket.addEventListener(type, listener)
    }
  }

  #deliver(body) {
    let frame
    try {
      frame = JSON.parse(body)
    } catch {
      this.failure ||= new Error('invalid application event JSON frame')
      return
    }
    this.frames.push(frame)
    for (const hook of this.hooks) {
      hook(frame)
    }
  }

  /** Sends one logical v2 client message as real carrier fragments; returns whether it was queued. */
  send(message) {
    const body = JSON.stringify({ version: 2, ...message })
    if (!this.link.offer(randomUUID(), body)) {
      return false
    }
    this.drain()
    return true
  }

  subscribe(resource) {
    return this.send({ type: 'subscribe', resource })
  }

  unsubscribe(resource) {
    return this.send({ type: 'unsubscribe', resource })
  }

  /** Bounded synchronous drain over the shared outbox; probe messages are small. */
  drain() {
    for (;;) {
      const batch = this.link.pollBatch()
      if (batch == null) {
        return
      }
      try {
        for (const frame of batch.frames()) {
          this.socket.send(frame)
        }
        this.link.complete(batch, true)
      } catch (error) {
        this.link.complete(batch, false)
        this.failure ||= error
        return
      }
    }
  }

  check() {
    if (this.failure) {
      throw this.failure
    }
  }

  close() {
    this.link.close()
    for (const [type, listener] of Object.entries(this.#listeners)) {
      this.socket.removeEventListener(type, listener)
    }
    this.hooks.clear()
  }
}
