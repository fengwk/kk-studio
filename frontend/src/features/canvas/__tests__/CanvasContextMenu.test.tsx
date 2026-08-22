import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CanvasContextMenu,
  type CanvasContextMenuState,
  type ContextMenuTarget,
} from '@/features/canvas/CanvasContextMenu'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { Group, Resource, ResourceNode } from '@/features/canvas/domain'
import { getCanvasResourceOriginalUrl } from '@/shared/api/studio-service'
import type { CanvasFunctionModelDTO } from '@/shared/api/contracts/studio'
import { setLocale } from '@/shared/i18n'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_ID = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const GROUP_ID = 'c4d5e6f7-8a9b-4c0d-8e1f-2a3b4c5d6e7f'
const RESOURCE_ID = 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(() => new Promise(() => undefined)),
}))

const model: CanvasFunctionModelDTO = {
  key: 'fake-image',
  label: 'Fake Image',
  outputKind: 'IMAGE',
  referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
  parameters: [],
  available: true,
  unavailableReason: null,
}

const transform = { x: 0, y: 0, width: 320, height: 260 }

function resource(overrides: Partial<Resource> = {}): Resource {
  return {
    id: RESOURCE_ID,
    canvasId: CANVAS_ID,
    ownerNodeId: NODE_ID,
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

function resourceNode(overrides: Partial<ResourceNode> = {}): ResourceNode {
  return {
    id: NODE_ID,
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

function group(overrides: Partial<Group> = {}): Group {
  return {
    id: GROUP_ID,
    canvasId: CANVAS_ID,
    title: 'Frame',
    transform,
    ...overrides,
  }
}

function makeController(overrides: Partial<CanvasController> = {}): CanvasController {
  return {
    renameNode: vi.fn(),
    deleteNode: vi.fn(),
    renameGroup: vi.fn(),
    deleteGroup: vi.fn(),
    ungroupGroup: vi.fn(),
    createGroup: vi.fn(),
    editTextNode: vi.fn(),
    startFunctionRun: vi.fn(),
    cancelFunctionRun: vi.fn(),
    ...overrides,
  } as unknown as CanvasController
}

/** 直接渲染 CanvasContextMenu，用 stub controller 提供全部动作（不经过 Stage）。 */
function renderMenu(
  target: ContextMenuTarget,
  onClose = vi.fn(),
  controller: CanvasController = makeController(),
) {
  const state: CanvasContextMenuState = { x: 100, y: 120, target }
  const view = render(
    <CanvasRuntimeContext.Provider value={controller}>
      <CanvasContextMenu state={state} onClose={onClose} />
    </CanvasRuntimeContext.Provider>,
  )
  return { view, onClose, controller }
}

async function openRename(menu: HTMLElement) {
  await userEvent.click(within(menu).getByRole('menuitem', { name: '重命名' }))
  return within(menu).getByRole('textbox', { name: '新名称' })
}

describe('CanvasContextMenu resource target', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
  })

  it('renames a resource through the in-menu editor and closes', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    const input = await openRename(menu)
    await userEvent.clear(input)
    await userEvent.type(input, '新名字')
    await userEvent.click(within(menu).getByRole('button', { name: '保存' }))

    expect(controller.renameNode).toHaveBeenCalledWith(NODE_ID, '新名字')
    expect(onClose).toHaveBeenCalled()
  })

  it('does not save an empty rename and keeps the menu open', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode({ name: 'Original' }),
      model: null,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    const input = await openRename(menu)
    await userEvent.clear(input)
    expect(within(menu).getByRole('button', { name: '保存' })).toBeDisabled()
    await userEvent.keyboard('{Enter}')

    expect(controller.renameNode).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('cancels rename back to the menu without dispatching', async () => {
    const controller = makeController()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, vi.fn(), controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await openRename(menu)
    await userEvent.click(within(menu).getByRole('button', { name: '取消' }))

    expect(within(menu).getByRole('menuitem', { name: '重命名' })).toBeInTheDocument()
    expect(controller.renameNode).not.toHaveBeenCalled()
  })

  it('deletes a resource only after the in-menu confirmation', async () => {
    const controller = makeController()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode({ name: 'Note' }),
      model: null,
    }, vi.fn(), controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '删除' }))
    const confirm = await within(menu).findByRole('alertdialog', { name: '删除「Note」？' })
    expect(controller.deleteNode).not.toHaveBeenCalled()

    await userEvent.click(within(confirm).getByRole('button', { name: '取消' }))
    expect(controller.deleteNode).not.toHaveBeenCalled()
    expect(within(menu).getByRole('menuitem', { name: '重命名' })).toBeInTheDocument()

    await userEvent.click(within(menu).getByRole('menuitem', { name: '删除' }))
    const confirmAgain = await within(menu).findByRole('alertdialog')
    await userEvent.click(within(confirmAgain).getByRole('button', { name: '确认删除' }))
    expect(controller.deleteNode).toHaveBeenCalledWith(NODE_ID)
  })

  it('runs a ready function node and closes the menu', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode({
        resources: [],
        function: { modelKey: 'fake-image', configJson: '{}' },
        run: null,
      }),
      model,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '运行' }))

    expect(controller.startFunctionRun).toHaveBeenCalledWith(NODE_ID)
    expect(onClose).toHaveBeenCalled()
  })

  it('cancels a running function and closes the menu', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const requestId = 'f1f2f3f4-0000-4000-8000-000000000001'
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode({
        resources: [],
        function: { modelKey: 'fake-image', configJson: '{}' },
        run: {
          nodeId: NODE_ID,
          requestId,
          status: 'RUNNING',
          stage: 'RUNNING',
          error: null,
          updatedAt: '2026-08-10T00:00:00Z',
        },
      }),
      model,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    expect(within(menu).queryByRole('menuitem', { name: '运行' })).not.toBeInTheDocument()
    await userEvent.click(within(menu).getByRole('menuitem', { name: '取消生成' }))

    expect(controller.cancelFunctionRun).toHaveBeenCalledWith(NODE_ID, requestId)
    expect(onClose).toHaveBeenCalled()
  })

  it('groups an ungrouped resource and edits a TEXT resource', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const textNode = resourceNode({
      resources: [resource({
        id: 'eeeeeeee-0000-4000-8000-0000000000cc',
        kind: 'TEXT',
        name: 'note.md',
        blobId: null,
        textContent: 'hello',
        mediaType: 'text/markdown',
      })],
    })
    const { view } = renderMenu({
      kind: 'resource',
      node: textNode,
      model: null,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '打组' }))
    expect(controller.createGroup).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()

    onClose.mockClear()
    await userEvent.click(within(menu).getByRole('menuitem', { name: '编辑文本' }))
    expect(controller.editTextNode).toHaveBeenCalledWith(textNode)
    expect(onClose).toHaveBeenCalled()
  })
})

describe('CanvasContextMenu group and multi targets', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
  })

  it('renames, ungroups, and deletes a group with confirmation', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const { view } = renderMenu({ kind: 'group', group: group() }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '解组' }))
    expect(controller.ungroupGroup).toHaveBeenCalledWith(GROUP_ID)
    expect(onClose).toHaveBeenCalled()

    onClose.mockClear()
    const input = await openRename(menu)
    await userEvent.clear(input)
    await userEvent.type(input, '新组名')
    await userEvent.keyboard('{Enter}')
    expect(controller.renameGroup).toHaveBeenCalledWith(GROUP_ID, '新组名')
    expect(onClose).toHaveBeenCalled()

    // 重新打开菜单（重命名/删除确认后菜单已关闭）。
    onClose.mockClear()
    const { view: freshView } = renderMenu({ kind: 'group', group: group() }, onClose, controller)
    const freshMenu = freshView.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(freshMenu).getByRole('menuitem', { name: '删除' }))
    const confirm = await within(freshMenu).findByRole('alertdialog', { name: '删除分组「Frame」？' })
    expect(controller.deleteGroup).not.toHaveBeenCalled()
    await userEvent.click(within(confirm).getByRole('button', { name: '确认删除' }))
    expect(controller.deleteGroup).toHaveBeenCalledWith(GROUP_ID)
    expect(onClose).toHaveBeenCalled()
  })

  it('groups from a multi target only when it contains ungrouped resources', async () => {
    const controller = makeController()
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'multi',
      nodeIds: [NODE_ID],
      hasUngroupedResource: true,
    }, onClose, controller)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '打组' }))
    expect(controller.createGroup).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('renders no items for a multi target without ungrouped resources', () => {
    const { view } = renderMenu({
      kind: 'multi',
      nodeIds: [NODE_ID],
      hasUngroupedResource: false,
    })

    expect(view.container.querySelector('[role="menuitem"]')).toBeNull()
  })
})

describe('CanvasContextMenu keyboard and dismissal', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
  })

  it('navigates items with Arrow/Home/End and closes on Escape', async () => {
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    })

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    const items = [...menu.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')]
    expect(items.length).toBeGreaterThanOrEqual(4)

    menu.focus()
    fireEvent.keyDown(menu, { key: 'Home' })
    expect(document.activeElement).toBe(items[0])
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[1])
    fireEvent.keyDown(menu, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(items[0])
    fireEvent.keyDown(menu, { key: 'End' })
    expect(document.activeElement).toBe(items[items.length - 1])
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[0])
  })

  it('closes on Escape from the menu mode', async () => {
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('returns from rename/confirm modes to the menu on Escape', async () => {
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await openRename(menu)
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    expect(within(menu).getByRole('menuitem', { name: '重命名' })).toBeInTheDocument()

    await userEvent.click(within(menu).getByRole('menuitem', { name: '删除' }))
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    expect(within(menu).getByRole('menuitem', { name: '重命名' })).toBeInTheDocument()
  })

  it('closes on any pointer down outside the menu and ignores inside clicks', async () => {
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose)

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    fireEvent.pointerDown(menu)
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.pointerDown(document.body)
    expect(onClose).toHaveBeenCalled()
  })
})

describe('CanvasContextMenu resource original actions', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    // 默认签名请求挂起：需要失败的 case 单独覆盖拒绝。
    vi.mocked(getCanvasResourceOriginalUrl).mockReset()
    vi.mocked(getCanvasResourceOriginalUrl).mockImplementation(
      () => new Promise(() => undefined),
    )
  })

  it('requests open and download with the shared hook and disables while loading', async () => {
    const onClose = vi.fn()
    const binaryNode = resourceNode({
      resources: [resource()],
    })
    const { view } = renderMenu({
      kind: 'resource',
      node: binaryNode,
      model: null,
    }, onClose, makeController())

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    const open = within(menu).getByRole('menuitem', { name: '打开原件' })
    const download = within(menu).getByRole('menuitem', { name: '下载' })
    expect(open).not.toBeDisabled()
    expect(download).not.toBeDisabled()

    await userEvent.click(open)
    await waitFor(() => expect(open).toBeDisabled())
    expect(download).toBeDisabled()
  })

  it('closes the menu after a fulfilled original open', async () => {
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValueOnce({
      method: 'GET',
      url: 'https://s3.example/original',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose, makeController())

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    const open = within(menu).getByRole('menuitem', { name: '打开原件' })
    await userEvent.click(open)

    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('shows the signing error when the original request fails', async () => {
    vi.mocked(getCanvasResourceOriginalUrl).mockRejectedValueOnce(new Error('sign failed'))
    const onClose = vi.fn()
    const { view } = renderMenu({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    }, onClose, makeController())

    const menu = view.container.querySelector('.canvas-context-menu') as HTMLElement
    await userEvent.click(within(menu).getByRole('menuitem', { name: '打开原件' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('原件签名失败')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('hides open/download for TEXT-only and empty resources', () => {
    const textNode = resourceNode({
      resources: [resource({ kind: 'TEXT', blobId: null, textContent: 'note' })],
    })
    const { view } = renderMenu({ kind: 'resource', node: textNode, model: null })

    expect(view.container.querySelector('[role="menuitem"]')).not.toBeNull()
    expect(screen.queryByRole('menuitem', { name: '打开原件' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '下载' })).not.toBeInTheDocument()
  })
})
