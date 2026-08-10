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

    saveCanvasViewport('1', { x: 12, y: -9, zoom: 99 }, storage)
    saveCanvasViewport('2', { x: 1, y: 2, zoom: 0.5 }, storage)

    expect(loadCanvasViewport('1', storage)).toEqual({ x: 12, y: -9, zoom: 2 })
    expect(loadCanvasViewport('2', storage)).toEqual({ x: 1, y: 2, zoom: 0.5 })
    expect(hasStoredCanvasViewport('1', storage)).toBe(true)
    expect(values.has(canvasViewportStorageKey('1'))).toBe(true)
    expect(canvasViewportStorageKey('1')).toBe('kkstudio.canvas.viewport.v3:1')
  })

  it('falls back for malformed or non-finite persisted values', () => {
    const nonFiniteStorage = {
      getItem: () => '{"x":0,"y":0,"zoom":"NaN"}',
    }
    const malformedStorage = { getItem: () => '{' }

    // Invalid JSON or value types must not suppress the editor's one-time initial fit.
    expect(hasStoredCanvasViewport('1', nonFiniteStorage)).toBe(false)
    expect(hasStoredCanvasViewport('1', malformedStorage)).toBe(false)
    expect(loadCanvasViewport('1', nonFiniteStorage)).toEqual(DEFAULT_CANVAS_VIEWPORT)
    expect(loadCanvasViewport('1', malformedStorage)).toEqual(DEFAULT_CANVAS_VIEWPORT)
  })

  it('ignores non-finite values instead of corrupting storage', () => {
    let writeCount = 0
    saveCanvasViewport('1', { x: Number.NaN, y: 0, zoom: 1 }, {
      setItem: () => {
        writeCount += 1
      },
    })
    expect(writeCount).toBe(0)
  })
})
