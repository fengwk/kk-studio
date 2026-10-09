/**
 * Type declarations for the canonical notification transport module
 * (`notification.mjs`). The declarations describe the public runtime shape only; the
 * implementation stays the single source of truth.
 */

/** Maximum bytes per fragment; the last fragment may be shorter. */
export declare const CHUNK_BYTES: number

/** Hard logical message limit shared with the Java carrier/packet value objects. */
export declare const DEFAULT_MAX_MESSAGE_BYTES: number

export interface NotificationLimits {
  readonly maxMessageBytes: number
  readonly pendingBytes: number
  readonly queueCapacity: number
  readonly reassemblyBytes: number
  readonly reassemblyMessages: number
  readonly reassemblyTimeoutMs: number
  readonly sendBatchFrames: number
}

/** Number of fragments a logical payload of `totalBytes` bytes occupies; empty still means one. */
export declare function carrierCount(totalBytes: number): number

/** Canonical per-instance budgets; a smaller `maxMessageBytes` also bounds its packet/carrier values. */
export declare function createNotificationLimits(limits: NotificationLimits): Readonly<NotificationLimits>

export declare function defaultNotificationLimits(): Readonly<NotificationLimits>

/** One complete logical notification body; the body is copied in and never handed out by reference. */
export declare class NotificationPacket {
  constructor(
    publisher: string,
    target: string | null,
    topic: string,
    messageId: string,
    bytes: Uint8Array,
  )

  publisher(): string
  /** Null broadcasts to every receiver; a non-null value selects one receiver identity. */
  target(): string | null
  topic(): string
  messageId(): string
  bytes(): Uint8Array
  byteLength(): number
  toString(): string
}

/**
 * One ASCII carrier fragment; identity, topic, numeric fields and Base64 are validated on
 * construction and the byte body is copied in and on access.
 */
export declare class NotificationCarrier {
  constructor(
    publisher: string,
    target: string | null,
    topic: string,
    messageId: string,
    index: number,
    count: number,
    totalBytes: number,
    bytes: Uint8Array,
  )

  publisher(): string
  target(): string | null
  topic(): string
  messageId(): string
  index(): number
  count(): number
  totalBytes(): number
  bytes(): Uint8Array
  /** Encodes this fragment; throws a fixed error when it exceeds the wire budget. */
  encode(): string
  toString(): string
}

/** Copies only the requested slice out of the packet's owned body. */
export declare function carrierChunk(message: NotificationPacket, index: number): NotificationCarrier

/**
 * Parses one carrier. Returns `null` for an own echo (before Base64 decoding or reassembly
 * allocation) and throws a fixed, payload-free error for any malformed input.
 */
export declare function decodeNotificationCarrier(
  value: string,
  limits: NotificationLimits,
  self?: string | null,
): NotificationCarrier | null

export type NotificationDelivery = (packet: NotificationPacket) => void
export type NotificationResync = (topic: string) => void

/**
 * Bounded reassembler; callbacks run synchronously inside `accept`/`expire` and must not block or
 * call back into another reassembler operation.
 */
export declare class NotificationReassembler {
  constructor(
    self: string | null,
    limits: NotificationLimits,
    clock: (() => number) | null | undefined,
    delivery: NotificationDelivery,
    resync: NotificationResync,
  )

  accept(frame: NotificationCarrier): void
  expire(): void
  clear(): void
  reservedBytes(): number
}

/** Read-only view of one polled send batch. */
export declare class NotificationBatch {
  publisher(): string
  target(): string | null
  topic(): string
  messageId(): string
  totalBytes(): number
  frameCount(): number
  /** Immutable encoded fragments; never exposes the logical body. */
  frames(): readonly string[]
  toString(): string
}

/** Bounded fair cursor queue over complete logical packets. */
export declare class NotificationOutbox {
  static readonly Batch: typeof NotificationBatch

  constructor(limits: NotificationLimits)

  offer(message: NotificationPacket): boolean
  pollBatch(): NotificationBatch | null
  complete(batch: NotificationBatch | null, success: boolean): boolean
  pendingMessages(): number
  pendingBytes(): number
  clear(): void
  close(): void
}
