import { describe, expect, it } from 'vitest'
import {
  AGENT_PANEL_WIDTH_STORAGE_KEY,
  agentPanelWidthBounds,
  clampAgentPanelWidth,
  loadAgentPanelWidth,
  saveAgentPanelWidth,
} from '@/features/canvas/agent-panel-width'

describe('Canvas Chat panel width', () => {
  it('keeps a desktop canvas reserve while allowing a wider panel', () => {
    // Boundary viewports prove both the preferred 360-720px range and the canvas reserve.
    expect(agentPanelWidthBounds(1_266)).toEqual({ min: 360, max: 720 })
    expect(agentPanelWidthBounds(1_024)).toEqual({ min: 360, max: 544 })
    expect(clampAgentPanelWidth(900, 1_024)).toBe(544)
    expect(clampAgentPanelWidth(200, 1_266)).toBe(360)
  })

  it('uses the available narrow-screen overlay width without overflowing', () => {
    expect(agentPanelWidthBounds(700)).toEqual({ min: 280, max: 676 })
    expect(agentPanelWidthBounds(300)).toEqual({ min: 276, max: 276 })
    expect(clampAgentPanelWidth(480, 300)).toBe(276)
  })

  it('loads, validates, clamps, and persists the local preference', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    }

    expect(loadAgentPanelWidth(1_266, storage)).toBe(480)
    values.set(AGENT_PANEL_WIDTH_STORAGE_KEY, '680')
    expect(loadAgentPanelWidth(1_266, storage)).toBe(680)
    values.set(AGENT_PANEL_WIDTH_STORAGE_KEY, 'invalid')
    expect(loadAgentPanelWidth(1_266, storage)).toBe(480)

    saveAgentPanelWidth(900, 1_024, storage)
    expect(values.get(AGENT_PANEL_WIDTH_STORAGE_KEY)).toBe('544')
    saveAgentPanelWidth(Number.NaN, 1_024, storage)
    expect(values.get(AGENT_PANEL_WIDTH_STORAGE_KEY)).toBe('544')
  })
})
