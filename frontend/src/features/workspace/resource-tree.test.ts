import { describe, expect, it } from 'vitest'
import { groupThreads, indexRows, resolveTreeDrop, treeRows, visibleRows } from './resource-tree'

const roots = treeRows({ treeVersion: '1', folders: [
  { id: 'root', name: 'In Progress', children: [{ id: 'child', name: 'Child', children: [], resources: [] }], resources: [
    { id: 'session', title: 'Conversation', kind: 'SESSION', threads: [
      { id: 'thread', state: 'RUNNING', lastActivityAt: 1, title: 'Topic', version: '0' },
      { id: 'newer', state: 'RUNNING', lastActivityAt: 2, title: 'Another', version: '0' },
    ] },
    { id: 'canvas', title: 'Drawing', kind: 'CANVAS' },
  ] },
  { id: 'other', name: 'Other', children: [], resources: [] },
] })
const index = indexRows(roots)
const ids = (rows: { id: string }[]) => rows.map(row => row.id)

describe('workspace tree projection', () => {
  it('shows only name matches and their ancestry, without changing expansion', () => {
    const expanded = new Set(['root'])
    expect(ids(visibleRows(roots, expanded, ' topic '))).toEqual(['root', 'session', 'thread'])
    expect(ids(visibleRows(roots, expanded, 'running'))).toEqual([])
    expect(ids(visibleRows(roots, expanded, ''))).toEqual(['root', 'session', 'canvas', 'child', 'other'])
  })

  it('sorts activity within status groups without modifying tree order', () => {
    const groups = groupThreads(index.values())
    expect([...groups.keys()]).toEqual(['NEEDS_YOU', 'RUNNING', 'WAITING', 'FAILED', 'IDLE'])
    expect(ids(groups.get('RUNNING')!)).toEqual(['newer', 'thread'])
    expect(ids(index.get('session')!.children)).toEqual(['thread', 'newer'])
  })

  it('uses drop zones for folder nesting and reorder, rejecting cycles', () => {
    expect(resolveTreeDrop(roots, index, 'other', 'child', 0.5)).toEqual({ parentId: 'child', beforeId: null, position: 'into' })
    expect(resolveTreeDrop(roots, index, 'child', 'root', 0.9)).toEqual({ parentId: null, beforeId: 'other', position: 'after' })
    expect(resolveTreeDrop(roots, index, 'root', 'child', 0.5)).toBeUndefined()
    expect(resolveTreeDrop(roots.slice(0, 1), index, 'root', 'other', 0.5)).toBeUndefined()
  })

  it('inserts resources relative to other resources or at the top of a folder', () => {
    expect(resolveTreeDrop(roots, index, 'canvas', 'session', 0.2)).toEqual({ parentId: 'root', beforeId: 'session', position: 'before' })
    expect(resolveTreeDrop(roots, index, 'canvas', 'root', 0.5)).toEqual({ parentId: 'root', beforeId: 'session', position: 'into' })
    expect(resolveTreeDrop(roots, index, 'canvas', 'other', 0.5)).toEqual({ parentId: 'other', beforeId: null, position: 'into' })
    expect(resolveTreeDrop(roots, index, 'thread', 'other', 0.5)).toBeUndefined()
  })
})
