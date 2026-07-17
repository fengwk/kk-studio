import { describe, expect, it } from 'vitest'
import {
  AGENT_FUNCTION_REF,
  functionRefForGenerator,
  studioKindOf,
} from '@/features/canvas/domain-map'
import { createInitialNodes } from '@/features/canvas/data'

describe('canvas domain-map', () => {
  // Locks presentation → Studio kind mapping so UI demo types cannot drift silently.
  it('maps every presentation node type to a Studio kind', () => {
    expect(studioKindOf('frame')).toBe('GROUP')
    expect(studioKindOf('text')).toBe('RESOURCE')
    expect(studioKindOf('generator')).toBe('FUNCTION')
    expect(studioKindOf('run')).toBe('FUNCTION')
  })

  it('seeds demo nodes with consistent domainKind', () => {
    for (const node of createInitialNodes()) {
      expect(node.domainKind).toBe(studioKindOf(node.type))
    }
  })

  it('uses stable system Function refs for generation and agent', () => {
    expect(functionRefForGenerator('image')).toEqual({
      functionId: 'system.generate-image',
      version: '1',
    })
    expect(AGENT_FUNCTION_REF.functionId).toBe('system.agent.execute')
  })
})
