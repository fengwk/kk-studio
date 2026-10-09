/**
 * Canonical notification transport carrier / reassembler / fair outbox shared by the browser client
 * and the Node E2E probe. This is the single JavaScript implementation of the Java
 * `fun.fengwk.kkstudio.share.notification` framing: probes and browser senders must import it
 * instead of re-implementing chunking, reassembly or scheduling.
 *
 * One ASCII carrier per fragment (including `count=1`):
 *
 *   1|publisher|target-or-*|topic|messageId|index|count|totalBytes|base64
 *
 * Fragments are never decoded on their own, so a chunk boundary may fall inside a multi-byte UTF-8
 * sequence; only the fully reassembled bytes are interpreted. All scalar fields, the topic and the
 * Base64 are validated canonically, and every error message is fixed and never echoes the raw input.
 *
 * Only native browser/Node APIs are used (TextEncoder, atob, btoa, performance.now); there is no
 * Buffer or Node-only import, so the exact same module runs in the browser and in Node.
 */

/** Maximum bytes per fragment; the last fragment may be shorter. */
export const CHUNK_BYTES = 5400

/** Hard logical message limit shared with the Java carrier/packet value objects. */
export const DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024 * 1024

const PAYLOAD_LIMIT = 7900
const INT_MAX = 2147483647
const TOPIC_PATTERN = /^[a-z][a-z0-9_.-]{0,95}$/
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const CANONICAL_INT = /^(0|[1-9][0-9]{0,9})$/

const textEncoder = new TextEncoder()

/**
 * Module-private ownership tables. The logical bytes are never reachable from outside the module:
 * a private field cannot be read cross-class, so the framing/reassembly helpers use these tables
 * instead of an exposed symbol-keyed method.
 */
const PACKET_BYTES = new WeakMap()
const CARRIER_BYTES = new WeakMap()
/** Module-private polled batch cursor; never an own property of the (frozen) batch. */
const BATCH_START = new WeakMap()

function utf8Length(value) {
  return textEncoder.encode(value).length
}

/**
 * Requires a genuine `Uint8Array` (a subclass is fine) so a foreign iterable/array-like can never
 * drive an unbounded allocation. `ArrayBuffer.isView` plus the proxy-proof `Uint8Array` tag is
 * realm-independent, unlike `instanceof`, and still rejects every non-Uint8Array view. The caller
 * must check the length before copying.
 */
const UINT8_TAG = '[object Uint8Array]'

function requireBytes(value) {
  if (!ArrayBuffer.isView(value) || Object.prototype.toString.call(value) !== UINT8_TAG) {
    throw new TypeError('notification bytes must be a Uint8Array')
  }
  return value
}

/** Bounded native copy of a verified typed array; does not consult the iterator protocol. */
function copyBytes(value) {
  return new Uint8Array(value)
}

function encodeBase64(bytes) {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index])
  }
  return btoa(binary)
}

function decodeBase64(value) {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

function requireTopic(topic) {
  if (typeof topic !== 'string') {
    throw new TypeError('notification topic is required')
  }
  if (!TOPIC_PATTERN.test(topic)) {
    throw new RangeError('invalid notification topic')
  }
  return topic
}

function canonicalUuid(field) {
  if (!CANONICAL_UUID.test(field)) {
    throw new RangeError('invalid notification carrier identity')
  }
  return field
}

/**
 * Validates one owned value object's identity field: only a canonical lowercase UUID string is
 * accepted, so an encoded header can never carry arbitrary metadata. Errors are fixed and never
 * echo the rejected value.
 */
function requireUuid(value, message) {
  if (typeof value !== 'string') {
    throw new TypeError(message)
  }
  if (!CANONICAL_UUID.test(value)) {
    throw new RangeError(message)
  }
  return value
}

/** Canonical non-negative decimal: no sign, no leading zeros and no integer overflow. */
function canonicalInt(field) {
  if (!CANONICAL_INT.test(field)) {
    throw new RangeError('invalid notification carrier dimension')
  }
  const value = Number(field)
  if (value > INT_MAX) {
    throw new RangeError('invalid notification carrier dimension')
  }
  return value
}

function sameHeader(left, right) {
  return (
    left.target() === right.target() &&
    left.topic() === right.topic() &&
    left.count() === right.count() &&
    left.totalBytes() === right.totalBytes()
  )
}

function bytesEqual(buffer, offset, other) {
  for (let index = 0; index < other.length; index += 1) {
    if (buffer[offset + index] !== other[index]) {
      return false
    }
  }
  return true
}

/** Number of fragments a logical payload of {@code totalBytes} bytes occupies; empty still means one. */
export function carrierCount(totalBytes) {
  if (!Number.isInteger(totalBytes) || totalBytes < 0) {
    throw new RangeError('invalid notification payload size')
  }
  return Math.max(1, Math.ceil(totalBytes / CHUNK_BYTES))
}

/** One complete logical notification body; the body is copied in and never handed out by reference. */
export class NotificationPacket {
  #publisher
  #target
  #topic
  #messageId

  constructor(publisher, target, topic, messageId, bytes) {
    const owner = requireUuid(publisher, 'invalid notification packet publisher')
    const recipient =
      target == null ? null : requireUuid(target, 'invalid notification packet target')
    const id = requireUuid(messageId, 'invalid notification packet messageId')
    const source = requireBytes(bytes)
    // Reject the logical size before copying, so an oversized body is never cloned into the packet.
    if (source.byteLength > DEFAULT_MAX_MESSAGE_BYTES) {
      throw new RangeError('notification packet exceeds logical message limit')
    }
    this.#publisher = owner
    this.#target = recipient
    this.#topic = requireTopic(topic)
    this.#messageId = id
    PACKET_BYTES.set(this, copyBytes(source))
  }

  publisher() {
    return this.#publisher
  }

  /** Null broadcasts to every receiver; a non-null value selects one receiver identity. */
  target() {
    return this.#target
  }

  topic() {
    return this.#topic
  }

  messageId() {
    return this.#messageId
  }

  bytes() {
    return PACKET_BYTES.get(this).slice()
  }

  byteLength() {
    return PACKET_BYTES.get(this).length
  }

  toString() {
    return `NotificationPacket[publisher=${this.#publisher}, target=${this.#target}, topic=${this.#topic}, messageId=${this.#messageId}, byteLength=${PACKET_BYTES.get(this).length}]`
  }
}

/**
 * One ASCII carrier fragment. The value is deeply immutable: identity, topic, numeric fields and
 * Base64 are validated in the constructor, the byte array is copied in and on access, and
 * {@code toString} never renders the body.
 */
export class NotificationCarrier {
  #publisher
  #target
  #topic
  #messageId
  #index
  #count
  #totalBytes

  constructor(publisher, target, topic, messageId, index, count, totalBytes, bytes) {
    const owner = requireUuid(publisher, 'invalid notification carrier publisher')
    const recipient =
      target == null ? null : requireUuid(target, 'invalid notification carrier target')
    const id = requireUuid(messageId, 'invalid notification carrier messageId')
    if (
      !Number.isInteger(totalBytes) ||
      totalBytes < 0 ||
      totalBytes > DEFAULT_MAX_MESSAGE_BYTES ||
      !Number.isInteger(count) ||
      count !== carrierCount(totalBytes) ||
      !Number.isInteger(index) ||
      index < 0 ||
      index >= count
    ) {
      throw new RangeError('invalid notification carrier dimensions')
    }
    const source = requireBytes(bytes)
    // Validate the expected fragment length before copying, so a hand-built value cannot make the
    // carrier copy an arbitrary array.
    const expected = Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES)
    if (source.byteLength !== expected) {
      throw new RangeError('invalid notification carrier chunk length')
    }
    this.#publisher = owner
    this.#target = recipient
    this.#topic = requireTopic(topic)
    this.#messageId = id
    this.#index = index
    this.#count = count
    this.#totalBytes = totalBytes
    CARRIER_BYTES.set(this, copyBytes(source))
  }

  publisher() {
    return this.#publisher
  }

  /** Null broadcasts to every receiver; a non-null value selects one receiver identity. */
  target() {
    return this.#target
  }

  topic() {
    return this.#topic
  }

  messageId() {
    return this.#messageId
  }

  index() {
    return this.#index
  }

  count() {
    return this.#count
  }

  totalBytes() {
    return this.#totalBytes
  }

  bytes() {
    return CARRIER_BYTES.get(this).slice()
  }

  encode() {
    const encoded =
      `1|${this.#publisher}|${this.#target == null ? '*' : this.#target}|${this.#topic}|` +
      `${this.#messageId}|${this.#index}|${this.#count}|${this.#totalBytes}|${encodeBase64(CARRIER_BYTES.get(this))}`
    if (utf8Length(encoded) >= PAYLOAD_LIMIT) {
      throw new RangeError('notification carrier exceeds payload limit')
    }
    return encoded
  }

  toString() {
    return `NotificationCarrier[version=1, publisher=${this.#publisher}, target=${this.#target}, topic=${this.#topic}, messageId=${this.#messageId}, index=${this.#index}, count=${this.#count}, totalBytes=${this.#totalBytes}]`
  }
}

/**
 * Copies only the requested slice out of the packet's owned body; it never clones the whole message,
 * so framing a large payload stays proportional to the fragment size.
 */
export function carrierChunk(message, index) {
  if (!(message instanceof NotificationPacket)) {
    throw new TypeError('notification packet is required')
  }
  const payload = PACKET_BYTES.get(message)
  const total = carrierCount(payload.length)
  if (!Number.isInteger(index) || index < 0 || index >= total) {
    throw new RangeError('invalid notification carrier index')
  }
  const start = index * CHUNK_BYTES
  const end = Math.min(payload.length, start + CHUNK_BYTES)
  return new NotificationCarrier(
    message.publisher(),
    message.target(),
    message.topic(),
    message.messageId(),
    index,
    total,
    payload.length,
    payload.subarray(start, end),
  )
}

/**
 * Parses one carrier. The publisher is read from the fixed header first: an own echo returns
 * {@code null} before Base64 decoding or any reassembly allocation. Any malformed input throws a
 * fixed, payload-free error.
 */
export function decodeNotificationCarrier(value, limits, self = null) {
  if (typeof value !== 'string') {
    throw new TypeError('notification carrier is required')
  }
  if (limits == null) {
    throw new TypeError('notification limits are required')
  }
  // Never let a NaN/undefined/mutated budget silently bypass the dimension comparison.
  if (!isPositiveInteger(limits.maxMessageBytes) || limits.maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES) {
    throw new RangeError('invalid notification limits')
  }
  if (value.length >= PAYLOAD_LIMIT || utf8Length(value) >= PAYLOAD_LIMIT) {
    throw new RangeError('oversized notification carrier')
  }
  const fields = value.split('|')
  if (fields.length !== 9 || fields[0] !== '1' || !TOPIC_PATTERN.test(fields[3])) {
    throw new RangeError('invalid notification carrier header')
  }
  const publisher = canonicalUuid(fields[1])
  if (publisher === self) {
    return null
  }
  const target = fields[2] === '*' ? null : canonicalUuid(fields[2])
  const messageId = canonicalUuid(fields[4])
  const index = canonicalInt(fields[5])
  const count = canonicalInt(fields[6])
  const totalBytes = canonicalInt(fields[7])
  if (totalBytes > limits.maxMessageBytes || count !== carrierCount(totalBytes) || index >= count) {
    throw new RangeError('invalid notification carrier dimensions')
  }
  let bytes
  try {
    bytes = decodeBase64(fields[8])
  } catch {
    throw new RangeError('invalid notification carrier chunk')
  }
  const expected = Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES)
  if (bytes.length !== expected || encodeBase64(bytes) !== fields[8]) {
    throw new RangeError('invalid notification carrier chunk')
  }
  return new NotificationCarrier(
    publisher,
    target,
    fields[3],
    messageId,
    index,
    count,
    totalBytes,
    bytes,
  )
}

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0 && value <= INT_MAX
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0 && value <= INT_MAX
}

/** Positive finite millisecond budget within the safe integer range. */
function isValidTimeout(value) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= Number.MAX_SAFE_INTEGER
}

/** Canonical per-instance budgets; a smaller `maxMessageBytes` also bounds its packet/carrier values. */
export function createNotificationLimits(limits) {
  if (limits == null) {
    throw new TypeError('notification limits are required')
  }
  const {
    maxMessageBytes,
    pendingBytes,
    queueCapacity,
    reassemblyBytes,
    reassemblyMessages,
    reassemblyTimeoutMs,
    sendBatchFrames,
  } = limits
  if (
    !isPositiveInteger(maxMessageBytes) ||
    maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES ||
    !isNonNegativeInteger(pendingBytes) ||
    pendingBytes < maxMessageBytes ||
    !isPositiveInteger(queueCapacity) ||
    !isNonNegativeInteger(reassemblyBytes) ||
    reassemblyBytes < maxMessageBytes ||
    !isPositiveInteger(reassemblyMessages) ||
    !isValidTimeout(reassemblyTimeoutMs) ||
    !isPositiveInteger(sendBatchFrames)
  ) {
    throw new RangeError('invalid notification limits')
  }
  return Object.freeze({
    maxMessageBytes,
    pendingBytes,
    queueCapacity,
    reassemblyBytes,
    reassemblyMessages,
    reassemblyTimeoutMs,
    sendBatchFrames,
  })
}

export function defaultNotificationLimits() {
  return createNotificationLimits({
    maxMessageBytes: DEFAULT_MAX_MESSAGE_BYTES,
    pendingBytes: 32 * 1024 * 1024,
    queueCapacity: 256,
    reassemblyBytes: 32 * 1024 * 1024,
    reassemblyMessages: 8,
    reassemblyTimeoutMs: 5000,
    sendBatchFrames: 32,
  })
}

/**
 * Bounded reassembler shared by every transfer. It reserves the entire logical size before
 * accepting a fragment, keeps only bounded completion tombstones, and delivers a complete
 * {@link NotificationPacket} exactly once while its tombstone is retained.
 *
 * Contradictory headers, conflicting duplicate fragments, expired or over-budget messages release
 * the whole packet and request a resync of the owning topic; a byte-identical duplicate is ignored
 * instead of being delivered again. {@code count=1} and empty payloads take the same path.
 *
 * Callbacks run synchronously inside {@code accept}/{@code expire}: they must not block and must
 * never call back into another reassembler operation.
 */
export class NotificationReassembler {
  #self
  #limits
  #clock
  #delivery
  #resync
  #pending = new Map()
  #finished = new Map()
  #reservedBytes = 0

  constructor(self, limits, clock, delivery, resync) {
    if (typeof delivery !== 'function') {
      throw new TypeError('notification delivery callback is required')
    }
    if (typeof resync !== 'function') {
      throw new TypeError('notification resync callback is required')
    }
    this.#self = self == null ? null : self
    // Own an immutable copy so later caller mutation cannot change the bounded budgets.
    this.#limits = createNotificationLimits(limits)
    this.#clock = clock == null ? () => performance.now() : clock
    this.#delivery = delivery
    this.#resync = resync
  }

  accept(frame) {
    if (!(frame instanceof NotificationCarrier)) {
      throw new TypeError('notification carrier is required')
    }
    // Own echoes are discarded before expiry, lookup, or any allocation.
    if (this.#self === frame.publisher()) {
      return
    }
    const target = frame.target()
    if (target !== null && target !== this.#self) {
      return
    }
    this.expire()
    const key = `${frame.publisher()}\u0000${frame.messageId()}`
    if (this.#finished.has(key)) {
      return
    }
    let message = this.#pending.get(key)
    if (message === undefined) {
      const totalBytes = frame.totalBytes()
      if (
        totalBytes > this.#limits.maxMessageBytes ||
        this.#pending.size >= this.#limits.reassemblyMessages ||
        totalBytes > this.#limits.reassemblyBytes - this.#reservedBytes
      ) {
        this.#remember(key)
        this.#resync(frame.topic())
        return
      }
      message = {
        first: frame,
        bytes: new Uint8Array(totalBytes),
        received: new Uint8Array(frame.count()),
        remaining: frame.count(),
        deadline: this.#clock() + this.#limits.reassemblyTimeoutMs,
      }
      this.#pending.set(key, message)
      this.#reservedBytes += totalBytes
    } else if (!sameHeader(message.first, frame)) {
      this.#reject(key, message)
      this.#resync(frame.topic())
      return
    }
    const index = frame.index()
    const offset = index * CHUNK_BYTES
    const frameBytes = CARRIER_BYTES.get(frame)
    if (message.received[index] === 1) {
      if (!bytesEqual(message.bytes, offset, frameBytes)) {
        this.#reject(key, message)
      }
      return
    }
    message.bytes.set(frameBytes, offset)
    message.received[index] = 1
    message.remaining -= 1
    if (message.remaining === 0) {
      this.#remove(key, message)
      this.#remember(key)
      this.#delivery(
        new NotificationPacket(
          frame.publisher(),
          frame.target(),
          frame.topic(),
          frame.messageId(),
          message.bytes,
        ),
      )
    }
  }

  expire() {
    const now = this.#clock()
    for (const [key, deadline] of this.#finished) {
      if (deadline <= now) {
        this.#finished.delete(key)
      }
    }
    for (const [key, message] of this.#pending) {
      if (message.deadline <= now) {
        this.#pending.delete(key)
        this.#reservedBytes -= message.bytes.length
        this.#remember(key)
        this.#resync(message.first.topic())
      }
    }
  }

  clear() {
    this.#pending.clear()
    this.#finished.clear()
    this.#reservedBytes = 0
  }

  reservedBytes() {
    return this.#reservedBytes
  }

  #reject(key, message) {
    this.#remove(key, message)
    this.#remember(key)
    this.#resync(message.first.topic())
  }

  #remove(key, message) {
    this.#pending.delete(key)
    this.#reservedBytes -= message.bytes.length
  }

  #remember(key) {
    if (this.#finished.size === this.#limits.queueCapacity) {
      this.#finished.delete(this.#finished.keys().next().value)
    }
    this.#finished.set(key, this.#clock() + this.#limits.reassemblyTimeoutMs)
  }
}

/**
 * Bounded fair cursor queue over complete logical packets. It owns only scheduling: it reserves the
 * whole logical budget from {@link NotificationOutbox#offer} until the final native batch completes,
 * hands out at most one in-flight {@link NotificationBatch} of at most `sendBatchFrames` encoded
 * fragments, and rotates an unfinished packet to the tail so a large body cannot monopolize the
 * queue.
 *
 * It never performs I/O, retries or payload logging, and reuses the shared carrier framing instead
 * of defining a second wire format. A failed batch drops its logical packet and releases the
 * reservation; only the final fragment releases a successful packet.
 */
export class NotificationBatch {
  #publisher
  #target
  #topic
  #messageId
  #totalBytes
  #frames

  constructor(packet, startIndex, frames) {
    this.#publisher = packet.publisher()
    this.#target = packet.target()
    this.#topic = packet.topic()
    this.#messageId = packet.messageId()
    this.#totalBytes = packet.byteLength()
    this.#frames = Object.freeze(frames.slice())
    BATCH_START.set(this, startIndex)
    Object.freeze(this)
  }

  publisher() {
    return this.#publisher
  }

  target() {
    return this.#target
  }

  topic() {
    return this.#topic
  }

  messageId() {
    return this.#messageId
  }

  totalBytes() {
    return this.#totalBytes
  }

  frameCount() {
    return this.#frames.length
  }

  /** Immutable encoded fragments; never exposes the logical body. */
  frames() {
    return this.#frames
  }

  toString() {
    return `NotificationOutbox.Batch[topic=${this.#topic}, target=${this.#target}, messageId=${this.#messageId}, frames=${this.#frames.length}, totalBytes=${this.#totalBytes}]`
  }
}

export class NotificationOutbox {
  static Batch = NotificationBatch

  #limits
  #queued = []
  #pendingBytes = 0
  #pendingMessages = 0
  #active = null
  #activeBatch = null
  #closed = false

  constructor(limits) {
    // Own an immutable copy so later caller mutation cannot change the bounded budgets.
    this.#limits = createNotificationLimits(limits)
  }

  /**
   * Reserves the whole logical packet against the queue and byte budgets. Returns false for an
   * oversized packet, a full queue, an exhausted byte budget or a closed outbox; a null packet is a
   * fixed validation error. It does not retry, log payload or invoke callbacks.
   */
  offer(message) {
    if (!(message instanceof NotificationPacket)) {
      throw new TypeError('notification packet is required')
    }
    if (
      this.#closed ||
      message.byteLength() > this.#limits.maxMessageBytes ||
      this.#pendingMessages >= this.#limits.queueCapacity ||
      message.byteLength() > this.#limits.pendingBytes - this.#pendingBytes
    ) {
      return false
    }
    this.#queued.push({ packet: message, nextIndex: 0 })
    this.#pendingBytes += message.byteLength()
    this.#pendingMessages += 1
    return true
  }

  /**
   * Returns the next bounded send batch, or null while closed, empty or already serving an active
   * batch. It encodes at most `sendBatchFrames` fragments of the head packet.
   */
  pollBatch() {
    if (this.#closed || this.#active !== null || this.#queued.length === 0) {
      return null
    }
    const cursor = this.#queued.shift()
    this.#active = cursor
    const total = carrierCount(cursor.packet.byteLength())
    const end = Math.min(total, cursor.nextIndex + this.#limits.sendBatchFrames)
    const frames = []
    for (let index = cursor.nextIndex; index < end; index += 1) {
      frames.push(carrierChunk(cursor.packet, index).encode())
    }
    this.#activeBatch = new NotificationBatch(cursor.packet, cursor.nextIndex, frames)
    return this.#activeBatch
  }

  /**
   * Completes the active batch by identity. Wrong, stale, foreign or duplicate batches are rejected
   * without releasing another packet's reservation. Success advances the cursor and rotates an
   * unfinished packet to the tail, releasing the reservation only on the final fragment; failure
   * drops the logical packet and releases it with no retry.
   */
  complete(batch, success) {
    if (batch == null || batch !== this.#activeBatch) {
      return false
    }
    const cursor = this.#active
    this.#active = null
    this.#activeBatch = null
    if (!success) {
      this.#release(cursor.packet)
      return true
    }
    const end = BATCH_START.get(batch) + batch.frameCount()
    if (end >= carrierCount(cursor.packet.byteLength())) {
      this.#release(cursor.packet)
    } else {
      cursor.nextIndex = end
      this.#queued.push(cursor)
    }
    return true
  }

  /** Measured queued plus in-flight logical packets. */
  pendingMessages() {
    return this.#pendingMessages
  }

  /** Measured queued plus in-flight logical bytes. */
  pendingBytes() {
    return this.#pendingBytes
  }

  /** Releases every queued and in-flight packet; the outbox still accepts further offers. */
  clear() {
    this.#queued.length = 0
    this.#active = null
    this.#activeBatch = null
    this.#pendingBytes = 0
    this.#pendingMessages = 0
  }

  /** Permanently refuses new offers and releases every queued and in-flight packet. */
  close() {
    this.#closed = true
    this.clear()
  }

  #release(packet) {
    this.#pendingBytes -= packet.byteLength()
    this.#pendingMessages -= 1
  }
}
