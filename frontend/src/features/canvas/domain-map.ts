/**
 * Maps the Canvas presentation model to Studio domain vocabulary.
 *
 * Presentation types stay UI-oriented (web/image/generator/...).
 * Studio kinds are the durable backend vocabulary (RESOURCE/FUNCTION/GROUP).
 *
 * @see docs/technical-solution/domain-map.md
 */

import type { CanvasNodeType, StudioNodeKind } from '@/features/canvas/types'

/** Presentation node type → Studio CanvasNodeKind. */
export function studioKindOf(type: CanvasNodeType): StudioNodeKind {
  switch (type) {
    case 'frame':
      return 'GROUP'
    case 'generator':
    case 'run':
      return 'FUNCTION'
    case 'web':
    case 'image':
    case 'file':
    case 'text':
    case 'matrix':
    case 'result':
      return 'RESOURCE'
    default: {
      const exhaustive: never = type
      return exhaustive
    }
  }
}

/** FunctionRef for presentation generator modes (catalog stubs on backend). */
export function functionRefForGenerator(mode: 'text' | 'image' | 'video'): {
  functionId: string
  version: string
} {
  return {
    functionId: `system.generate-${mode}`,
    version: '1',
  }
}

/** Agent Function on the canvas is always the single system entry. */
export const AGENT_FUNCTION_REF = {
  functionId: 'system.agent.execute',
  version: '1',
} as const
