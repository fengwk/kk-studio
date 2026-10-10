/**
 * Type declarations for the shared framed app-events link (`framed-link.mjs`). The declarations
 * describe the public runtime shape only; the implementation stays the single source of truth.
 */

import type {
  NotificationBatch,
  NotificationLimits,
} from '../notification/notification.mjs'

/** The single logical topic of the app-events WebSocket wire. */
export declare const APP_EVENTS_TOPIC: string

export interface FramedEventLinkOptions {
  /** Per-physical-connection publisher UUID. */
  self: string
  limits: NotificationLimits
  /** Complete UTF-8 logical body. */
  deliver: (body: string) => void
  /** Called exactly once per fatal (`false`) or recoverable (`true`) condition. */
  onFailure: (recover: boolean) => void
  topic?: string
  clock?: (() => number) | null
}

export declare class FramedEventLink {
  constructor(options: FramedEventLinkOptions)

  /** Frozen peer publisher UUID; null until the first valid inbound fragment. */
  peer(): string | null
  isClosed(): boolean

  accept(rawFrame: string): void
  expire(): void
  offer(messageId: string, body: string): boolean
  pollBatch(): NotificationBatch | null
  complete(batch: NotificationBatch | null, success: boolean): boolean
  hasPending(): boolean
  pendingBytes(): number
  close(): void
}
