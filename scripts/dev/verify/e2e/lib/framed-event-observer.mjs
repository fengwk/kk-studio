/**
 * Passive framed app-events observer for a Playwright page WebSocket.
 *
 * It never sends and never re-implements framing. From the browser's own `framesent` carrier
 * fragments it recovers the real per-connection publisher UUID, then routes every `framereceived`
 * fragment through an independent shared {@link FramedEventLink} whose `self` is that browser
 * publisher. Topic/target/peer validation, the `count=1` case and multi-fragment reassembly all
 * come from the shared link (which itself reuses `frontend/src/shared/notification/*.mjs`); the
 * observer only parses the delivered UTF-8 logical body as JSON.
 *
 * A malformed logical body is a fatal observation error. A reassembly recovery is forwarded with
 * `recover=true` so the caller can let the browser reconnect and keep observing.
 */

import {
  APP_EVENTS_TOPIC,
  FramedEventLink,
} from '../../../../../frontend/src/shared/app-events/framed-link.mjs'
import {
  decodeNotificationCarrier,
  defaultNotificationLimits,
} from '../../../../../frontend/src/shared/notification/notification.mjs'

const LIMITS = defaultNotificationLimits()

export class FramedEventObserver {
  #limits
  #onFrame
  #onFailure
  #socket = null
  #link = null
  #listeners = null

  /**
   * @param {object} [options]
   * @param {object} [options.limits] shared notification limits; defaults to `defaultNotificationLimits()`
   * @param {((frame: unknown) => void) | null} [options.onFrame] decoded logical server frame
   * @param {((recover: boolean) => void) | null} [options.onFailure] framing/recovery failure
   */
  constructor({ limits = LIMITS, onFrame = null, onFailure = null } = {}) {
    this.#limits = limits
    this.#onFrame = onFrame
    this.#onFailure = onFailure
  }

  /** Attaches to a Playwright WebSocket; called once, before the first frame. */
  observe(socket) {
    if (this.#socket != null) {
      throw new Error('framed event observer is already attached')
    }
    this.#socket = socket
    this.#listeners = {
      framesent: (event) => this.#onSent(event.payload),
      framereceived: (event) => this.#onReceived(event.payload),
      close: () => this.close(),
    }
    for (const [type, listener] of Object.entries(this.#listeners)) {
      socket.on(type, listener)
    }
  }

  /** Detaches every listener and releases the shared link; idempotent. */
  close() {
    const socket = this.#socket
    if (socket != null) {
      for (const [type, listener] of Object.entries(this.#listeners)) {
        socket.off(type, listener)
      }
    }
    this.#socket = null
    this.#listeners = null
    this.#link?.close()
    this.#link = null
  }

  #onSent(payload) {
    if (this.#link != null || typeof payload !== 'string') {
      return
    }
    let carrier
    try {
      carrier = decodeNotificationCarrier(payload, this.#limits, null)
    } catch {
      // 非 carrier 的 framesent 不是本 observer 的帧；浏览器连接自身负责拒绝它。
      return
    }
    if (carrier == null || carrier.topic() !== APP_EVENTS_TOPIC) {
      return
    }
    this.#link = new FramedEventLink({
      self: carrier.publisher(),
      limits: this.#limits,
      deliver: (body) => this.#deliver(body),
      onFailure: (recover) => this.#onFailure?.(recover),
    })
  }

  #onReceived(payload) {
    this.#link?.accept(payload)
  }

  #deliver(body) {
    let frame
    try {
      frame = JSON.parse(body)
    } catch {
      this.#link?.close()
      this.#link = null
      this.#onFailure?.(false)
      return
    }
    this.#onFrame?.(frame)
  }
}
