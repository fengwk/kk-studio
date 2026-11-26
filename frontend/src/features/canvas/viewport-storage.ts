export interface StoredCanvasViewport {
  x: number
  y: number
  zoom: number
}

export const DEFAULT_CANVAS_VIEWPORT: StoredCanvasViewport = { x: 0, y: 0, zoom: 1 }
export const MIN_CANVAS_ZOOM = 0.25
export const MAX_CANVAS_ZOOM = 2

function canvasViewportStorageKey(viewKey: string): string {
  return `kk-studio.canvas.viewport:${viewKey}`
}

export function hasStoredCanvasViewport(
  viewKey: string,
  storage: Pick<Storage, 'getItem'> = localStorage,
): boolean {
  return readCanvasViewport(viewKey, storage) !== null
}

export function loadCanvasViewport(
  viewKey: string,
  storage: Pick<Storage, 'getItem'> = localStorage,
): StoredCanvasViewport {
  return readCanvasViewport(viewKey, storage) ?? { ...DEFAULT_CANVAS_VIEWPORT }
}

function readCanvasViewport(
  viewKey: string,
  storage: Pick<Storage, 'getItem'>,
): StoredCanvasViewport | null {
  try {
    const raw = storage.getItem(canvasViewportStorageKey(viewKey))
    if (!raw) {
      return null
    }
    const value = JSON.parse(raw) as Partial<StoredCanvasViewport>
    if (
      !Number.isFinite(value.x)
      || !Number.isFinite(value.y)
      || !Number.isFinite(value.zoom)
    ) {
      return null
    }
    return {
      x: value.x as number,
      y: value.y as number,
      zoom: clampCanvasZoom(value.zoom as number),
    }
  } catch {
    return null
  }
}

export function saveCanvasViewport(
  viewKey: string,
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
    canvasViewportStorageKey(viewKey),
    JSON.stringify({
      x: viewport.x,
      y: viewport.y,
      zoom: clampCanvasZoom(viewport.zoom),
    }),
  )
}

function clampCanvasZoom(zoom: number): number {
  return Math.min(MAX_CANVAS_ZOOM, Math.max(MIN_CANVAS_ZOOM, zoom))
}
