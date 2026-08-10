export interface StoredCanvasViewport {
  x: number
  y: number
  zoom: number
}

export const DEFAULT_CANVAS_VIEWPORT: StoredCanvasViewport = { x: 80, y: 20, zoom: 0.6 }
export const MIN_CANVAS_ZOOM = 0.25
export const MAX_CANVAS_ZOOM = 1.45

export function canvasViewportStorageKey(canvasId: string): string {
  return `kkstudio.canvas.viewport.v1:${canvasId}`
}

export function loadCanvasViewport(
  canvasId: string,
  storage: Pick<Storage, 'getItem'> = localStorage,
): StoredCanvasViewport {
  const raw = storage.getItem(canvasViewportStorageKey(canvasId))
  if (!raw) {
    return { ...DEFAULT_CANVAS_VIEWPORT }
  }
  try {
    const value = JSON.parse(raw) as Partial<StoredCanvasViewport>
    if (
      !Number.isFinite(value.x)
      || !Number.isFinite(value.y)
      || !Number.isFinite(value.zoom)
    ) {
      return { ...DEFAULT_CANVAS_VIEWPORT }
    }
    return {
      x: value.x as number,
      y: value.y as number,
      zoom: clampCanvasZoom(value.zoom as number),
    }
  } catch {
    return { ...DEFAULT_CANVAS_VIEWPORT }
  }
}

export function saveCanvasViewport(
  canvasId: string,
  viewport: StoredCanvasViewport,
  storage: Pick<Storage, 'setItem'> = localStorage,
): void {
  if (
    !Number.isFinite(viewport.x)
    || !Number.isFinite(viewport.y)
    || !Number.isFinite(viewport.zoom)
  ) {
    return
  }
  storage.setItem(
    canvasViewportStorageKey(canvasId),
    JSON.stringify({
      x: viewport.x,
      y: viewport.y,
      zoom: clampCanvasZoom(viewport.zoom),
    }),
  )
}

export function clampCanvasZoom(zoom: number): number {
  return Math.min(MAX_CANVAS_ZOOM, Math.max(MIN_CANVAS_ZOOM, zoom))
}
