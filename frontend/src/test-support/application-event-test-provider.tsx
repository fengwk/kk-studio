import { useState, type PropsWithChildren } from 'react'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'

/** Supplies the real application-event manager while keeping tests off the network. */
export function ApplicationEventTestProvider({ children }: PropsWithChildren) {
  const [sockets] = useState(() => new FakeWebSocketHarness())
  return (
    <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
      {children}
    </ApplicationEventProvider>
  )
}
