import { describe, expect, it } from 'vitest'
import {
  canvasViewportStorageKey,
  DEFAULT_CANVAS_VIEWPORT,
  hasStoredCanvasViewport,
  loadCanvasViewport,
  saveCanvasViewport,
} from '@/features/canvas/viewport-storage'

describe('canvas viewport storage', () => {
  it('isolates viewport by canvas id and clamps zoom', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    }

    saveCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', { x: 12, y: -9, zoom: 99 }, storage)
    saveCanvasViewport('bbbbbbbb-2222-4222-8222-222222222222', { x: 1, y: 2, zoom: 0.5 }, storage)

    expect(loadCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', storage)).toEqual({ x: 12, y: -9, zoom: 2 })
    expect(loadCanvasViewport('bbbbbbbb-2222-4222-8222-222222222222', storage)).toEqual({ x: 1, y: 2, zoom: 0.5 })
    expect(hasStoredCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', storage)).toBe(true)
    expect(values.has(canvasViewportStorageKey('aaaaaaaa-1111-4111-8111-111111111111'))).toBe(true)
    expect(canvasViewportStorageKey('aaaaaaaa-1111-4111-8111-111111111111')).toBe('kkstudio.canvas.viewport.v6:aaaaaaaa-1111-4111-8111-111111111111')
  })

  it('falls back for malformed or non-finite persisted values', () => {
    const nonFiniteStorage = {
      getItem: () => '{"x":0,"y":0,"zoom":"NaN"}',
    }
    const malformedStorage = { getItem: () => '{' }

    // Invalid JSON or value types must not suppress the editor's one-time initial fit.
    expect(hasStoredCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', nonFiniteStorage)).toBe(false)
    expect(hasStoredCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', malformedStorage)).toBe(false)
    expect(loadCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', nonFiniteStorage)).toEqual(DEFAULT_CANVAS_VIEWPORT)
    expect(loadCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', malformedStorage)).toEqual(DEFAULT_CANVAS_VIEWPORT)
  })

  it('ignores non-finite values instead of corrupting storage', () => {
    let writeCount = 0
    saveCanvasViewport('aaaaaaaa-1111-4111-8111-111111111111', { x: Number.NaN, y: 0, zoom: 1 }, {
      setItem: () => {
        writeCount += 1
      },
    })
    expect(writeCount).toBe(0)
  })
})
