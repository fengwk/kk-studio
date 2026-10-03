import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFunctionConfigSync } from '@/features/canvas/function-config'
import type { CanvasSnapshotDTO, UUIDString } from '@/shared/api/contracts/studio'

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
    const onImmediateDraft = vi.fn((_nodeId: UUIDString) => ++gen)
    const nodeId = 'node-1' as UUIDString

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
    const executeCommands = vi.fn(async () => undefined)
    let gen = 0
    const onImmediateDraft = vi.fn(() => ++gen)
    const nodeId = 'node-2' as UUIDString

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
    expect(executeCommands.mock.calls[0]?.[0]?.[0]?.function?.args?.prompt).toBe('version 2')
  })

  it('flushFunctionConfig flushes immediately and cancels pending timer', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const nodeId = 'node-3' as UUIDString

    const mockSnapshot: CanvasSnapshotDTO = {
      document: { id: 'canvas-1', title: 'test', version: '1', createdAt: '', updatedAt: '' },
      nodes: [
        {
          id: nodeId,
          kind: 'resource',
          transform: { x: 0, y: 0, width: 100, height: 100 },
          function: { name: 'old-func', args: { old: true } },
        },
      ],
      links: [],
      runs: [],
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
    expect(executeCommands.mock.calls[0]?.[0]?.[0]?.expectedFunction).toEqual({
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
    const nodeId = 'node-4' as UUIDString

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
