import { act, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CloudFilesInvalidationBridge } from '@/features/files/extensions/files-extension'
import { useCloudFilesInvalidation } from '@/features/files/useCloudFilesInvalidation'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { useProjectsInvalidation } from '@/features/projects/useProjectsInvalidation'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const PROJECT_ID = '11111111-2222-4333-8444-555555555555'

function InvalidationConsumers({
  onProjectsChanged,
  onCloudFilesChanged,
}: {
  onProjectsChanged: () => void
  onCloudFilesChanged: () => void
}) {
  useProjectsInvalidation(onProjectsChanged)
  useCloudFilesInvalidation(onCloudFilesChanged)
  return null
}

describe('platform invalidation bridges', () => {
  it('connects global WebSocket resources to project and Cloud Files refetch hooks', () => {
    const sockets = new FakeWebSocketHarness()
    const onProjectsChanged = vi.fn()
    const onCloudFilesChanged = vi.fn()
    render(
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <ProjectsInvalidationBridge />
        <CloudFilesInvalidationBridge />
        <InvalidationConsumers
          onProjectsChanged={onProjectsChanged}
          onCloudFilesChanged={onCloudFilesChanged}
        />
      </ApplicationEventProvider>,
    )

    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'projects' } },
      { version: 1, type: 'subscribe', resource: { kind: 'cloud-files' } },
    ])

    act(() => {
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'projects' },
        cursor: '0',
      })
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'cloud-files' },
        cursor: '0',
      })
    })
    expect(onProjectsChanged).toHaveBeenCalledWith(undefined)
    expect(onCloudFilesChanged).toHaveBeenCalledWith(undefined)

    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'projects' },
        name: 'changed',
        data: { projectId: PROJECT_ID },
      })
      socket.emitServer({ type: 'resync', resource: { kind: 'cloud-files' } })
    })
    expect(onProjectsChanged).toHaveBeenCalledWith({ projectId: PROJECT_ID })
    expect(onCloudFilesChanged).toHaveBeenCalledWith(undefined)
    expect(onProjectsChanged).toHaveBeenCalledTimes(2)
    expect(onCloudFilesChanged).toHaveBeenCalledTimes(2)

    act(() => {
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'projects' },
        cursor: '0',
      })
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'cloud-files' },
        cursor: '0',
      })
    })
    expect(onProjectsChanged).toHaveBeenLastCalledWith(undefined)
    expect(onCloudFilesChanged).toHaveBeenLastCalledWith(undefined)
    expect(onProjectsChanged).toHaveBeenCalledTimes(3)
    expect(onCloudFilesChanged).toHaveBeenCalledTimes(3)

    act(() => {
      socket.emitServer({
        type: 'error',
        resource: { kind: 'projects' },
        code: 'RESOURCE_NOT_FOUND',
        message: 'Resource not found',
      })
      socket.emitServer({
        type: 'error',
        resource: { kind: 'cloud-files' },
        code: 'RESOURCE_NOT_FOUND',
        message: 'Resource not found',
      })
    })
    expect(onProjectsChanged).toHaveBeenCalledTimes(4)
    expect(onCloudFilesChanged).toHaveBeenCalledTimes(4)
  })
})
