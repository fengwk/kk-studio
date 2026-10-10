import { randomUUID } from 'node:crypto'
import type { WebSocketRoute } from '@playwright/test'
import { FramedEventLink } from '../src/shared/app-events/framed-link.mjs'
import { defaultNotificationLimits } from '../src/shared/notification/notification.mjs'

export interface AppEventsSubscriptionResource {
  kind: string
  id?: string
}

export interface AppEventsAdapterOptions {
  onSubscribe?: (resource: AppEventsSubscriptionResource) => void
  autoAck?: boolean
}

export interface AppEventsAdapter {
  send: (message: Record<string, unknown>) => void
}

/**
 * Browser-test WebSocket adapter wrapping FramedEventLink for app-events v2 transport.
 *
 * Each physical socket receives a unique randomUUID self identity. Outbound frames
 * (both subscribe ACK and caller pushes) pass strictly through
 * link.offer / pollBatch / socket.send / complete without raw JSON bypasses.
 */
export function attachAppEventsAdapter(
  socket: WebSocketRoute,
  options: AppEventsAdapterOptions = {},
): AppEventsAdapter {
  const self = randomUUID()
  const limits = defaultNotificationLimits()
  const autoAck = options.autoAck ?? true

  function sendLogicalFrame(message: Record<string, unknown>): void {
    if (!message || typeof message !== 'object' || message.version !== 2) {
      throw new Error('Outbound frame must be strict v2 logical frame with version=2')
    }
    const body = JSON.stringify(message)
    const messageId = randomUUID()
    if (!link.offer(messageId, body)) {
      throw new Error('FramedEventLink.offer rejected message')
    }
    while (true) {
      const batch = link.pollBatch()
      if (batch == null) {
        break
      }
      try {
        for (const frame of batch.frames()) {
          socket.send(frame)
        }
        link.complete(batch, true)
      } catch (err) {
        link.complete(batch, false)
        throw err
      }
    }
  }

  const link = new FramedEventLink({
    self,
    limits,
    deliver: (body: string) => {
      let parsed: unknown
      try {
        parsed = JSON.parse(body)
      } catch {
        throw new Error('Malformed inbound logical frame JSON')
      }
      if (!parsed || typeof parsed !== 'object' || (parsed as { version?: unknown }).version !== 2) {
        throw new Error('Malformed inbound logical frame: expected version 2')
      }
      const frame = parsed as { type?: unknown; resource?: unknown }
      if (frame.type === 'subscribe') {
        const resource = frame.resource as AppEventsSubscriptionResource | undefined
        if (!resource || typeof resource !== 'object' || typeof resource.kind !== 'string') {
          throw new Error('Malformed subscribe frame: invalid resource')
        }
        options.onSubscribe?.(resource)
        if (autoAck) {
          sendLogicalFrame({
            version: 2,
            type: 'subscribed',
            resource,
            cursor: '0',
          })
        }
      }
    },
    onFailure: (recover: boolean) => {
      throw new Error(`FramedEventLink failure (recover=${recover})`)
    },
  })

  socket.onMessage((raw) => {
    if (typeof raw !== 'string') {
      throw new Error('Binary WebSocket frames are not supported on app-events carrier')
    }
    link.accept(raw)
  })

  socket.onClose(() => {
    link.close()
  })

  return {
    send: sendLogicalFrame,
  }
}
