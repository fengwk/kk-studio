import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFunctionConfigSync } from '@/features/canvas/function-config'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { CanvasCommandDTO, CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

describe('useFunctionConfigSync', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('debounces network sync by 320ms while immediately invoking onImmediateDraft', async () => {
    const executeCommands = vi.fn(async () => undefined)
    let gen = 0
    const onImmediateDraft = vi.fn((_nodeId: string) => ++gen)
    const nodeId = 'node-1'

    const { result } = renderHook(() =>
      useFunctionConfigSync({
        executeCommands,
        onImmediateDraft,
      }),
    )

    act(() => {
      result.current.scheduleFunctionConfig(nodeId, 'gen-image', {
        prompt: 'a cat',
        references: [],
        parameters: {},
      })
    })

    expect(onImmediateDraft).toHaveBeenCalledTimes(1)
    expect(executeCommands).not.toHaveBeenCalled()

    // 319ms - not flushed yet
    await act(async () => {
      await vi.advanceTimersByTimeAsync(319)
    })
    expect(executeCommands).not.toHaveBeenCalled()

    // 320ms - flushed
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
    expect(executeCommands).toHaveBeenCalledWith(
      [
        {
          type: 'SET_NODE_FUNCTION',
          nodeId,
          expectedFunction: null,
          function: {
            name: 'gen-image',
            args: { prompt: 'a cat' },
          },
        },
      ],
      [
        {
          nodeId,
          field: 'function',
          generation: 1,
        },
      ],
    )
  })

  it('resets debounce timer on rapid edits and only flushes latest draft', async () => {
    const executeCommands = vi.fn(async (_commands: CanvasCommandDTO[], _acks?: CanvasDraftAck[]) => undefined)
    let gen = 0
    const onImmediateDraft = vi.fn(() => ++gen)
    const nodeId = '00000000-0000-4000-8000-000000000002'

    const { result } = renderHook(() =>
      useFunctionConfigSync({
        executeCommands,
        onImmediateDraft,
      }),
    )

    act(() => {
      result.current.scheduleFunctionConfig(nodeId, 'gen-image', {
        prompt: 'version 1',
        references: [],
        parameters: {},
      })
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(200)
    })
    expect(executeCommands).not.toHaveBeenCalled()

    // Second edit within 200ms
    act(() => {
      result.current.scheduleFunctionConfig(nodeId, 'gen-image', {
        prompt: 'version 2',
        references: [],
        parameters: {},
      })
    })

    // Advance 200ms (total 400ms from start, but only 200ms from second edit)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200)
    })
    expect(executeCommands).not.toHaveBeenCalled()

    // Advance remaining 120ms
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120)
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
    const cmd = executeCommands.mock.calls[0]?.[0]?.[0]
    expect(cmd?.type).toBe('SET_NODE_FUNCTION')
    if (cmd?.type !== 'SET_NODE_FUNCTION') {
      throw new Error('Expected SET_NODE_FUNCTION')
    }
    expect(cmd.function?.args.prompt).toBe('version 2')
  })

  it('flushFunctionConfig flushes immediately and cancels pending timer', async () => {
    const executeCommands = vi.fn(async (_commands: CanvasCommandDTO[], _acks?: CanvasDraftAck[]) => undefined)
    const nodeId = '00000000-0000-4000-8000-000000000003'

    const mockSnapshot: CanvasSnapshotDTO = {
      document: {
        id: '11111111-1111-4000-8000-000000000001',
        title: 'test',
        revision: '1',
        createdAt: '2026-08-10T00:00:00Z',
        updatedAt: '2026-08-10T00:00:00Z',
      },
      nodes: [
        {
          id: nodeId,
          canvasId: '11111111-1111-4000-8000-000000000001',
          name: 'node-3',
          transform: { x: 0, y: 0, width: 100, height: 100 },
          groupId: null,
          resources: [],
          function: { name: 'old-func', args: { old: true } },
          run: null,
        },
      ],
      groups: [],
      references: [],
    }

    const { result } = renderHook(() =>
      useFunctionConfigSync({
        executeCommands,
        getSnapshot: () => mockSnapshot,
      }),
    )

    act(() => {
      result.current.scheduleFunctionConfig(nodeId, 'new-func', {
        prompt: 'immediate',
        references: [],
        parameters: {},
      })
    })

    // Explicit flush without waiting 320ms
    await act(async () => {
      await result.current.flushFunctionConfig(nodeId)
    })

    expect(executeCommands).toHaveBeenCalledTimes(1)
    const cmd = executeCommands.mock.calls[0]?.[0]?.[0]
    expect(cmd?.type).toBe('SET_NODE_FUNCTION')
    if (cmd?.type !== 'SET_NODE_FUNCTION') {
      throw new Error('Expected SET_NODE_FUNCTION')
    }
    expect(cmd.expectedFunction).toEqual({
      name: 'old-func',
      args: { old: true },
    })

    // Advance past 320ms to verify no second flush
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
  })

  it('resetPending clears pending timers and pending drafts without executing commands', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const nodeId = 'node-4'

    const { result } = renderHook(() =>
      useFunctionConfigSync({
        executeCommands,
      }),
    )

    act(() => {
      result.current.scheduleFunctionConfig(nodeId, 'gen-image', {
        prompt: 'should-be-cleared',
        references: [],
        parameters: {},
      })
    })

    // Reset pending before timer fires
    act(() => {
      result.current.resetPending()
    })

    // Advance timers well beyond 320ms
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(executeCommands).not.toHaveBeenCalled()
  })
})
