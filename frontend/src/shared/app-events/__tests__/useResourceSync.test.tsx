import { act, render, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { useResourceSync } from '../useResourceSync'

const TREE = { kind: 'thread-tree', id: '11111111-2222-4333-8444-555555555555' } as const

function ResourceSyncHarness({ options }: { options: Parameters<typeof useResourceSync>[1] }) {
  useResourceSync(TREE, options)
  return null
}

describe('useResourceSync', () => {
  it('keeps subscribed sync by default and consumes rejected async sync promises', async () => {
    const rejected = new Error('read failed')
    const sync = vi.fn().mockRejectedValue(rejected)
    const onSyncError = vi.fn()
    const sockets = new FakeWebSocketHarness()
    const view = render(
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <ResourceSyncHarness options={{ sync, onSyncError }} />
      </ApplicationEventProvider>,
    )
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: TREE }]))

    act(() => socket.emitServer({ type: 'subscribed', resource: TREE, cursor: '0' }))
    await waitFor(() => expect(onSyncError).toHaveBeenCalledWith(rejected))
    expect(sync).toHaveBeenCalledTimes(1)

    view.unmount()
  })

  it('can skip sync on subscribed and handle resource errors without automatic retries', async () => {
    const sync = vi.fn()
    const onError = vi.fn()
    const sockets = new FakeWebSocketHarness()
    const view = render(
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <ResourceSyncHarness options={{ sync, syncOnSubscribed: false, onError }} />
      </ApplicationEventProvider>,
    )
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: TREE }]))

    act(() => socket.emitServer({ type: 'subscribed', resource: TREE, cursor: '0' }))
    act(() => socket.emitServer({
      type: 'error',
      resource: TREE,
      code: 'TREE_SNAPSHOT_FAILED',
      message: 'snapshot failed',
    }))

    expect(sync).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledWith('TREE_SNAPSHOT_FAILED', 'snapshot failed')
    view.unmount()
  })

  it('forwards snapshot-ref to the caller hook', async () => {
    const sync = vi.fn()
    const onSnapshotRef = vi.fn()
    const sockets = new FakeWebSocketHarness()
    const view = render(
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <ResourceSyncHarness options={{ sync, syncOnSubscribed: false, onSnapshotRef }} />
      </ApplicationEventProvider>,
    )
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: TREE }]))

    // 完整消息原样转发（执行树不带 version）。
    act(() => socket.emitServer({ type: 'snapshot-ref', resource: TREE }))
    expect(onSnapshotRef).toHaveBeenCalledWith({ type: 'snapshot-ref', resource: TREE })
    expect(sync).not.toHaveBeenCalled()

    view.unmount()
  })

  it('falls back to an authoritative sync when the caller has no snapshot-ref handler', async () => {
    const sync = vi.fn()
    const sockets = new FakeWebSocketHarness()
    const view = render(
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <ResourceSyncHarness options={{ sync, syncOnSubscribed: false }} />
      </ApplicationEventProvider>,
    )
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: TREE }]))

    act(() => socket.emitServer({ type: 'snapshot-ref', resource: TREE }))
    expect(sync).toHaveBeenCalledTimes(1)

    view.unmount()
  })
})
