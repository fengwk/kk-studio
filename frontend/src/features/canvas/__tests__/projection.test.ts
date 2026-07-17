import { describe, expect, it } from 'vitest'
import { createInitialLinks, createInitialNodes } from '@/features/canvas/data'
import { projectEdges, projectNodes } from '@/features/canvas/projection'

describe('canvas projection', () => {
  it('maps domain nodes/links to React Flow view models without mutating domain ids', () => {
    const nodes = projectNodes(createInitialNodes(), ['run', 'matrix'])
    const edges = projectEdges(createInitialLinks())
    expect(nodes).toHaveLength(createInitialNodes().length)
    expect(nodes.find((node) => node.id === 'run')?.selected).toBe(true)
    expect(nodes.find((node) => node.id === 'frame')?.zIndex).toBe(0)
    expect(nodes[0]).not.toHaveProperty('draggable')
    expect(nodes[0]?.measured).toEqual({ width: 610, height: 430 })
    expect(edges[0]).toMatchObject({
      source: 'web',
      target: 'run',
      sourceHandle: 'out',
      targetHandle: 'in',
    })
    expect(nodes[0]?.data.domain.id).toBe('frame')
    expect(edges).toHaveLength(8)
  })
})
