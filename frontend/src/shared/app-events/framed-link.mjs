/**
 * Shared framed app-events link for the browser connection and the Node E2E probe.
 *
 * This is the single JavaScript implementation of the per-connection framing policy that the Java
 * `fun.fengwk.kkstudio.share.notification.NotificationPeerLink` applies on the server side. It is
 * I/O-free: it validates one physical carrier frame at a time, freezes the peer, reassembles the
 * logical body through the shared {@link NotificationReassembler} and hands complete UTF-8 bodies
 * to the owner; the owner drives the native socket and {@link FramedEventLink#pollBatch} /
 * {@link FramedEventLink#complete} / {@link FramedEventLink#expire}.
 *
 * Every logical message (including `count=1`) is carried as {@link NotificationCarrier} fragments
 * with the fixed `app.events.v2` topic. A connection has no out-of-band handshake metadata, so the
 * first valid fragment with the fixed topic and a `self`/`*` target freezes the peer publisher
 * UUID; a later fragment from a different publisher, a wrong topic or a foreign target is a fatal
 * violation. Outbound messages broadcast (`*`) until the peer is frozen, then target the peer. Own
 * echoes are dropped by the shared decoder before Base64 decoding.
 *
 * A reassembly loss (missing/expired/over-budget) requests recovery through the shared resync
 * callback; an invalid carrier, peer/topic/target violation or invalid UTF-8 is a terminal
 * violation. Both funnel into {@link FramedEventLink#onFailure} exactly once, after the link is
 * torn down. {@link FramedEventLink#close} releases every reserved byte and never retries.
 */

import {
  NotificationOutbox,
  NotificationPacket,
  NotificationReassembler,
  decodeNotificationCarrier,
} from '../notification/notification.mjs'

/** The single logical topic of the app-events WebSocket wire. */
export const APP_EVENTS_TOPIC = 'app.events.v2'

const textEncoder = new TextEncoder()
const textDecoder = new TextDecoder('utf-8', { fatal: true })

export class FramedEventLink {
  #self
  #topic
  #limits
  #deliver
  #onFailure
  #reassembler
  #outbox
  #peer = null
  #closed = false
  #packet = null
  #resync = false

  /**
   * @param {object} options
   * @param {string} options.self per-physical-connection publisher UUID
   * @param {import('../notification/notification.mjs').NotificationLimits} options.limits
   * @param {(body: string) => void} options.deliver complete UTF-8 logical body
   * @param {(recover: boolean) => void} options.onFailure called once per fatal/recovery condition
   * @param {string} [options.topic]
   * @param {(() => number) | null} [options.clock]
   */
  constructor({ self, limits, deliver, onFailure, topic = APP_EVENTS_TOPIC, clock = null }) {
    if (typeof self !== 'string') {
      throw new TypeError('framed link self is required')
    }
    if (typeof deliver !== 'function') {
      throw new TypeError('framed link deliver callback is required')
    }
    if (typeof onFailure !== 'function') {
      throw new TypeError('framed link failure callback is required')
    }
    this.#self = self
    this.#topic = topic
    this.#limits = limits
    this.#deliver = deliver
    this.#onFailure = onFailure
    this.#reassembler = new NotificationReassembler(
      self,
      limits,
      clock,
      (packet) => {
        this.#packet = packet
      },
      () => {
        this.#resync = true
      },
    )
    this.#outbox = new NotificationOutbox(limits)
  }

  /** Frozen peer publisher UUID; null until the first valid inbound fragment. */
  peer() {
    return this.#peer
  }

  isClosed() {
    return this.#closed
  }

  /**
   * Validates one physical frame, freezes the peer, reassembles and delivers a complete logical
   * body. Own echoes and incomplete fragments are ignored; any fatal condition tears the link down
   * and requests recovery/terminal handling exactly once.
   */
  accept(rawFrame) {
    if (this.#closed) {
      return
    }
    if (typeof rawFrame !== 'string') {
      this.#fail(false)
      return
    }
    let frame
    try {
      frame = decodeNotificationCarrier(rawFrame, this.#limits, this.#self)
    } catch {
      this.#fail(false)
      return
    }
    if (frame == null) {
      // Own echo: dropped by the shared decoder before any reassembly allocation.
      return
    }
    if (frame.topic() !== this.#topic) {
      this.#fail(false)
      return
    }
    const target = frame.target()
    if (target !== null && target !== this.#self) {
      this.#fail(false)
      return
    }
    if (this.#peer === null) {
      this.#peer = frame.publisher()
    } else if (this.#peer !== frame.publisher()) {
      this.#fail(false)
      return
    }

    this.#packet = null
    this.#resync = false
    this.#reassembler.accept(frame)
    if (this.#resync) {
      // Missing/expired/over-budget reassembly: recover the connection instead of terminating.
      this.#fail(true)
      return
    }
    const packet = this.#packet
    this.#packet = null
    if (packet == null) {
      return
    }
    let body
    try {
      body = textDecoder.decode(packet.bytes())
    } catch {
      this.#fail(false)
      return
    }
    this.#deliver(body)
  }

  /** Sweeps expired reassembly; an expired packet requests connection recovery. */
  expire() {
    if (this.#closed) {
      return
    }
    this.#resync = false
    this.#reassembler.expire()
    if (this.#resync) {
      this.#fail(true)
    }
  }

  /**
   * Reserves one complete logical packet against the shared outbox. Returns false for a closed
   * link, an oversized body or an exhausted queue/byte budget; the caller retries nothing.
   */
  offer(messageId, body) {
    if (typeof body !== 'string') {
      throw new TypeError('framed link body is required')
    }
    if (this.#closed || body.length > this.#limits.maxMessageBytes) {
      return false
    }
    let packet
    try {
      packet = new NotificationPacket(
        this.#self,
        this.#peer,
        this.#topic,
        messageId,
        textEncoder.encode(body),
      )
    } catch {
      return false
    }
    return this.#outbox.offer(packet)
  }

  /** Next bounded batch of encoded fragments, or null while closed, drained or already active. */
  pollBatch() {
    if (this.#closed) {
      return null
    }
    return this.#outbox.pollBatch()
  }

  /**
   * Completes the active batch by identity: success advances the cursor and only the final batch
   * releases the logical packet; failure drops it with no retry.
   */
  complete(batch, success) {
    if (this.#closed) {
      return false
    }
    return this.#outbox.complete(batch, success)
  }

  /** Whether the outbox still holds a queued or in-flight packet for {@link pollBatch}. */
  hasPending() {
    return !this.#closed && this.#outbox.pendingMessages() > 0
  }

  /** Measured queued plus in-flight logical bytes; returns to zero after a full release. */
  pendingBytes() {
    return this.#outbox.pendingBytes()
  }

  /** Releases every queued and in-flight packet plus every reassembly byte; idempotent. */
  close() {
    if (this.#closed) {
      return
    }
    this.#tearDown()
  }

  #fail(recover) {
    if (this.#closed) {
      return
    }
    this.#tearDown()
    this.#onFailure(recover)
  }

  #tearDown() {
    this.#closed = true
    this.#peer = null
    this.#outbox.close()
    this.#reassembler.clear()
  }
}
