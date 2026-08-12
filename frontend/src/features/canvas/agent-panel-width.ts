export const DEFAULT_AGENT_PANEL_WIDTH = 480
export const AGENT_PANEL_WIDTH_STORAGE_KEY = 'kkstudio.canvas.chat-panel-width.v1'

const DESKTOP_BREAKPOINT = 900
const DESKTOP_MIN_WIDTH = 360
const DESKTOP_MAX_WIDTH = 720
const DESKTOP_CANVAS_RESERVE = 480
const NARROW_MIN_WIDTH = 280
const NARROW_GUTTER = 24

export function agentPanelWidthBounds(viewportWidth: number): {
  min: number
  max: number
} {
  const safeViewportWidth = Math.max(0, viewportWidth)
  const narrow = safeViewportWidth <= DESKTOP_BREAKPOINT
  const availableWidth = Math.max(
    0,
    safeViewportWidth - (narrow ? NARROW_GUTTER : DESKTOP_CANVAS_RESERVE),
  )
  const targetMin = narrow ? NARROW_MIN_WIDTH : DESKTOP_MIN_WIDTH
  const min = Math.min(targetMin, availableWidth)
  return {
    min,
    max: Math.max(min, Math.min(DESKTOP_MAX_WIDTH, availableWidth)),
  }
}

export function clampAgentPanelWidth(width: number, viewportWidth: number): number {
  const bounds = agentPanelWidthBounds(viewportWidth)
  return Math.min(bounds.max, Math.max(bounds.min, width))
}

export function loadAgentPanelWidth(
  viewportWidth: number,
  storage: Pick<Storage, 'getItem'> = localStorage,
): number {
  const stored = Number(storage.getItem(AGENT_PANEL_WIDTH_STORAGE_KEY))
  return clampAgentPanelWidth(
    Number.isFinite(stored) && stored > 0 ? stored : DEFAULT_AGENT_PANEL_WIDTH,
    viewportWidth,
  )
}

export function saveAgentPanelWidth(
  width: number,
  viewportWidth: number,
  storage: Pick<Storage, 'setItem'> = localStorage,
): void {
  if (!Number.isFinite(width)) {
    return
  }
  storage.setItem(
    AGENT_PANEL_WIDTH_STORAGE_KEY,
    String(clampAgentPanelWidth(width, viewportWidth)),
  )
}
