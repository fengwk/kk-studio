import { act, renderHook, waitFor } from '@testing-library/react'
import type { MutableRefObject } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  CanvasSnapshot,
  Group,
  Resource,
  ResourceNode,
} from '@/features/canvas/domain'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import {
  useCanvasStageContextMenu,
} from '@/features/canvas/useCanvasStageContextMenu'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const GROUP_ID = 'c4d5e6f7-8a9b-4c0d-8e1f-2a3b4c5d6e7f'
const NODE_A = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const NODE_B = 'a2b3c4d5-6e7f-4a8b-9c0d-1e2f3a4b5c6d'

const transform = { x: 0, y: 0, width: 320, height: 260 }

function resourceNode(overrides: Partial<ResourceNode> = {}): ResourceNode {
  return {
    id: NODE_A,
    canvasId: CANVAS_ID,
    name: 'Image',
    transform,
    groupId: null,
    resources: [resource()],
    function: null,
    run: null,
    ...overrides,
  }
}

function resource(overrides: Partial<Resource> = {}): Resource {
  return {
    id: 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a',
    canvasId: CANVAS_ID,
    ownerNodeId: NODE_A,
    resourceIndex: 0,
    blobId: 'blob-asset',
    name: 'image.png',
    textContent: null,
    kind: 'IMAGE',
    mediaType: 'image/png',
    sizeBytes: 3,
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

function group(overrides: Partial<Group> = {}): Group {
  return {
    id: GROUP_ID,
    canvasId: CANVAS_ID,
    title: 'Frame',
    transform,
    ...overrides,
  }
}

function snapshot(overrides: Partial<CanvasSnapshot> = {}): CanvasSnapshot {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [resourceNode()],
    groups: [group()],
    links: [],
    ...overrides,
  }
}

function flowNode(id: string, dataKind: 'resource' | 'group' = 'resource'): CanvasFlowNode {
  return {
    id,
    type: dataKind,
    position: { x: 0, y: 0 },
    selected: false,
    data: dataKind === 'group'
      ? { kind: 'group', group: group() }
      : {
        kind: 'resource',
        node: resourceNode({ id }),
        model: null,
        callbacks: { editTextNode: vi.fn() },
      },
  } as CanvasFlowNode
}

function mouseEvent(clientX = 100, clientY = 120): MouseEvent {
  return {
    clientX,
    clientY,
    preventDefault: vi.fn(),
  } as unknown as MouseEvent
}

function setupHook(options: {
  snapshotValue?: CanvasSnapshot | null
  models?: readonly CanvasFunctionModelDTO[]
  selectedIds?: string[]
  setSelection?: (nodeIds: string[]) => void
}) {
  const setSelection = options.setSelection ?? vi.fn()
  const closeContextMenuRef: MutableRefObject<(() => void) | null> = {
    current: null,
  }
  const utils = renderHook(() => useCanvasStageContextMenu({
    snapshot: options.snapshotValue === undefined ? snapshot() : options.snapshotValue,
    models: options.models ?? [],
    selectedIds: options.selectedIds ?? [],
    setSelection,
    closeContextMenuRef,
  }))
  return { ...utils, setSelection, closeContextMenuRef }
}

describe('useCanvasStageContextMenu', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('registers the close callback into the controller ref and clears it on unmount', () => {
    const { closeContextMenuRef, unmount, result } = setupHook({})

    expect(closeContextMenuRef.current).not.toBeNull()
    const registered = closeContextMenuRef.current
    act(() => registered?.())
    expect(result.current.contextMenu).toBeNull()

    unmount()
    expect(closeContextMenuRef.current).toBeNull()
  })

  it('opens a resource menu from a node right-click, persists selection, and prevents default', () => {
    const setSelection = vi.fn()
    const { result } = setupHook({ setSelection, selectedIds: [] })
    const event = mouseEvent(40, 60)

    act(() => result.current.openNodeContextMenu(event, flowNode(NODE_A)))

    expect(event.preventDefault).toHaveBeenCalled()
    expect(setSelection).toHaveBeenCalledWith([NODE_A])
    expect(result.current.contextMenu).toEqual({
      x: 40,
      y: 60,
      target: expect.objectContaining({ kind: 'resource', node: expect.objectContaining({ id: NODE_A }) }),
    })
    expect(result.current.contextMenuKey).toBe(`resource:${NODE_A}`)
  })

  it('opens the full selection when right-clicking a selected resource node', () => {
    const { result } = setupHook({ selectedIds: [NODE_A, NODE_B] })
    const event = mouseEvent()

    act(() => result.current.openNodeContextMenu(event, { ...flowNode(NODE_A), selected: true }))

    expect(result.current.contextMenu?.target).toMatchObject({
      kind: 'multi',
      nodeIds: [NODE_A, NODE_B],
    })
  })

  it('opens a group menu for a group flow node and does not expand the selection', () => {
    const setSelection = vi.fn()
    const { result } = setupHook({ setSelection, selectedIds: [NODE_A] })

    act(() => result.current.openNodeContextMenu(mouseEvent(), flowNode(`group:${GROUP_ID}`, 'group')))

    expect(setSelection).toHaveBeenCalledWith([`group:${GROUP_ID}`])
    expect(result.current.contextMenu?.target).toEqual({
      kind: 'group',
      group: expect.objectContaining({ id: GROUP_ID }),
    })
  })

  it('opens a multi menu from the selection context menu', () => {
    const { result } = setupHook({})
    const event = mouseEvent()

    act(() => result.current.openSelectionContextMenu(
      event,
      [flowNode(NODE_A), flowNode(NODE_B)],
    ))

    expect(result.current.contextMenu?.target).toMatchObject({
      kind: 'multi',
      nodeIds: [NODE_A, NODE_B],
    })
  })

  it('closes any open menu when the selection context menu receives no nodes', () => {
    const { result } = setupHook({})
    act(() => result.current.openNodeContextMenu(mouseEvent(), flowNode(NODE_A)))
    expect(result.current.contextMenu).not.toBeNull()

    act(() => result.current.openSelectionContextMenu(mouseEvent(), []))

    expect(result.current.contextMenu).toBeNull()
  })

  it('does not open a menu when the target cannot be resolved', () => {
    const { result } = setupHook({})
    const event = mouseEvent()

    act(() => result.current.openNodeContextMenu(event, flowNode('ghost-id')))

    expect(event.preventDefault).toHaveBeenCalled()
    expect(result.current.contextMenu).toBeNull()
  })

  it('closes the menu on window Escape while open and removes the listener when closed', () => {
    const { result } = setupHook({})
    act(() => result.current.openNodeContextMenu(mouseEvent(), flowNode(NODE_A)))
    expect(result.current.contextMenu).not.toBeNull()

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(result.current.contextMenu).toBeNull()

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(result.current.contextMenu).toBeNull()
  })

  it('keeps the menu open when selection changes to the same id set and closes on a different set', () => {
    const { result } = setupHook({ selectedIds: [NODE_A] })
    act(() => result.current.openNodeContextMenu(mouseEvent(), flowNode(NODE_A)))
    expect(result.current.contextMenu).not.toBeNull()

    act(() => result.current.handleSelectionChange([NODE_A]))
    expect(result.current.contextMenu).not.toBeNull()

    act(() => result.current.handleSelectionChange([NODE_A, NODE_B]))
    expect(result.current.contextMenu).toBeNull()

    // 再次外部选区变化（菜单已关）不产生副作用。
    act(() => result.current.handleSelectionChange([NODE_B]))
    expect(result.current.contextMenu).toBeNull()
  })

  it('closes the menu through the registered close callback (surface Escape path)', async () => {
    const { closeContextMenuRef, result } = setupHook({})
    act(() => result.current.openNodeContextMenu(mouseEvent(), flowNode(NODE_A)))

    act(() => closeContextMenuRef.current?.())

    await waitFor(() => expect(result.current.contextMenu).toBeNull())
    expect(result.current.contextMenuKey).toBeNull()
  })
})
