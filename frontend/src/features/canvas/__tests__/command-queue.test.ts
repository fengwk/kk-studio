import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

function snapshot(revision: string): CanvasSnapshotDTO {
  return {
    document: {
      id: '1',
      title: 'Board',
      graphRevision: revision,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    links: [],
  }
}

describe('CanvasCommandQueue', () => {
  it('serializes batches and uses the latest successful graphRevision', async () => {
    const calls: string[] = []
    const apply = vi.fn(async (_canvasId, request) => {
      calls.push(request.expectedRevision)
      return snapshot(String(Number(request.expectedRevision) + 1))
    })
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: snapshot('7'),
      apply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('command-a')
        .mockReturnValueOnce('command-b'),
    })

    const first = queue.enqueue([{ type: 'DELETE_NODE', nodeId: '10' }])
    const second = queue.enqueue([{ type: 'DELETE_NODE', nodeId: '11' }])

    await expect(first).resolves.toMatchObject({ document: { graphRevision: '8' } })
    await expect(second).resolves.toMatchObject({ document: { graphRevision: '9' } })
    expect(calls).toEqual(['7', '8'])
    expect(apply.mock.calls[0]?.[1].commandId).toBe('command-a')
    expect(apply.mock.calls[1]?.[1].commandId).toBe('command-b')
  })

  it('refetches on 409, adopts the snapshot, and never replays semantic commands', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('stale', 409, 'CONFLICT'))
      .mockResolvedValueOnce(snapshot('13'))
    const refetch = vi.fn().mockResolvedValue(snapshot('12'))
    const snapshots: string[] = []
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: snapshot('10'),
      apply,
      refetch,
      createCommandId: () => 'command',
      onSnapshot: (value) => snapshots.push(value.document.graphRevision),
    })

    await expect(queue.enqueue([
      { type: 'RENAME_NODE', nodeId: '2', name: 'Changed' },
    ])).rejects.toBeInstanceOf(CanvasCommandConflictError)

    expect(apply).toHaveBeenCalledTimes(1)
    expect(refetch).toHaveBeenCalledTimes(1)
    expect(queue.currentSnapshot().document.graphRevision).toBe('12')
    expect(snapshots).toEqual(['12'])

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: '2' }])
    expect(apply.mock.calls[1]?.[1].expectedRevision).toBe('12')
  })

  it('does not let a failed batch block later queued batches', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('bad request', 400))
      .mockResolvedValueOnce(snapshot('2'))
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: snapshot('1'),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'command',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: '2' }])).rejects.toThrow('bad request')
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: '3' }])).resolves.toMatchObject({
      document: { graphRevision: '2' },
    })
  })

  it('uses UUID command ids by default and forwards AbortSignal', async () => {
    const signal = new AbortController().signal
    const randomUUID = vi.spyOn(crypto, 'randomUUID')
      .mockReturnValue('00000000-0000-4000-8000-000000000001')
    const apply = vi.fn().mockResolvedValue(snapshot('2'))
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: snapshot('1'),
      apply,
      refetch: vi.fn(),
    })

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: '2' }], { signal })

    expect(apply).toHaveBeenCalledWith('1', {
      expectedRevision: '1',
      commandId: '00000000-0000-4000-8000-000000000001',
      commands: [{ type: 'DELETE_NODE', nodeId: '2' }],
    }, { signal })
    randomUUID.mockRestore()
  })

  it('returns the current snapshot without sending an empty batch', async () => {
    const apply = vi.fn()
    const initial = snapshot('5')
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: initial,
      apply,
      refetch: vi.fn(),
    })

    await expect(queue.enqueue([])).resolves.toBe(initial)
    expect(apply).not.toHaveBeenCalled()
  })

  it('does not regress to a stale query snapshot but accepts equal-revision runtime updates', () => {
    const queue = new CanvasCommandQueue('1', {
      initialSnapshot: snapshot('8'),
      apply: vi.fn(),
      refetch: vi.fn(),
    })
    const stale = snapshot('7')
    const equal = {
      ...snapshot('8'),
      document: {
        ...snapshot('8').document,
        title: 'runtime update',
      },
    }

    expect(queue.replaceSnapshot(stale)).toBe(queue.currentSnapshot())
    expect(queue.currentSnapshot().document.graphRevision).toBe('8')
    expect(queue.replaceSnapshot(equal)).toBe(equal)
    expect(queue.currentSnapshot().document.title).toBe('runtime update')
  })
})
