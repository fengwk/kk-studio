import { act, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { useProjectsInvalidation } from '@/features/projects/useProjectsInvalidation'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const PROJECT_ID = '11111111-2222-4333-8444-555555555555'

function InvalidationConsumer({ onProjectsChanged }: { onProjectsChanged: () => void }) {
  useProjectsInvalidation(onProjectsChanged)
  return null
}

describe('platform invalidation bridges', () => {
  it('connects the global WebSocket resource to project refetch hooks', () => {
    const sockets = new FakeWebSocketHarness()
    const onProjectsChanged = vi.fn()
    render(
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <ProjectsInvalidationBridge />
        <InvalidationConsumer onProjectsChanged={onProjectsChanged} />
      </ApplicationEventProvider>,
    )

    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'projects' } },
    ])

    act(() => {
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'projects' },
        cursor: '0',
      })
    })
    expect(onProjectsChanged).toHaveBeenCalledWith(undefined)

    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'projects' },
        name: 'changed',
        data: { projectId: PROJECT_ID },
      })
    })
    expect(onProjectsChanged).toHaveBeenCalledWith({ projectId: PROJECT_ID })
    expect(onProjectsChanged).toHaveBeenCalledTimes(2)

    act(() => {
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'projects' },
        cursor: '0',
      })
    })
    expect(onProjectsChanged).toHaveBeenLastCalledWith(undefined)
    expect(onProjectsChanged).toHaveBeenCalledTimes(3)

    act(() => {
      socket.emitServer({
        type: 'error',
        resource: { kind: 'projects' },
        code: 'RESOURCE_NOT_FOUND',
        message: 'Resource not found',
      })
    })
    expect(onProjectsChanged).toHaveBeenCalledTimes(4)
  })
})
